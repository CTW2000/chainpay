package com.chainpay.ledger.system;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerServiceImpl;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 系统身份的账本入口：以 {@code chainpay_system}（BYPASSRLS、非超级用户）连库的<b>独立连接池</b>，
 * 加一份绑在这条连接上的账本实现。系统侧的读写（入账、出账、对账、控制面）都从这里走。
 *
 * <p><b>池与事务管理器是容器里的 bean，账本不是：</b>前两者是限定名 {@value #QUALIFIER} 的非默认候选 bean
 * （见 {@link SystemLedgerConfig}）；绑在系统连接上的 {@code JdbcClient} 与账本只在 {@link #inTransaction} 的回调里可见，
 * 「拿到 {@link Session} 才拿得到账本」这条边界由类型守着。控制器不得碰本类与那两个 bean（{@code ControllerBoundaryTest} 扫源码）。
 *
 * <p><b>为什么账本只在回调里可见：</b>
 * {@link LedgerServiceImpl#transfer} 上的 {@code @Transactional} 是代理魔法，只对容器创建的 bean 生效。
 * 这里的账本是手工 {@code new} 出来的，注解形同虚设——若把它直接暴露出去，调用方在事务外调 transfer，
 * 一条 transfer、两条 entry、两次余额更新各自提交，账本的原子性契约就悄悄没了。
 * 所以事务边界由本类的模板给（模板用的就是容器里的系统事务管理器），账本的 SQL 走同一个数据源，自动加入这个事务；
 * 外层若已经有限定名 system 的注解事务，回调直接加入它（SystemPoolBeansTest 钉住）。
 *
 * <p><b>启动即自检：</b>第一次借连接时问一次 {@code pg_roles}，不是 BYPASSRLS（RLS 会让它一行都看不到，
 * 入账任务将静默地无事可做）或者是超级用户（权限没有边界），直接拒绝启动。
 * 配错了就起不来，远好过看起来正常。
 */
public final class SystemLedger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SystemLedger.class);

    /** 系统池与系统事务管理器的限定名：{@code @Qualifier("system")}、{@code @Transactional("system")}。 */
    public static final String QUALIFIER = "system";

    /** 一次系统事务里能用的两样东西：系统连接上的 SQL 客户端，和绑在同一连接上的账本。 */
    public record Session(JdbcClient jdbc, LedgerService ledger) {}

    private final HikariDataSource pool;
    private final TransactionTemplate tx;
    private final Session session;

    /** 池是不是本实例自己建的（只有 {@link #connect} 建的才是）；不是自己的池，{@link #close} 不能碰。 */
    private final boolean ownsPool;

    private SystemLedger(HikariDataSource pool, JdbcTransactionManager transactionManager, boolean ownsPool) {
        this.ownsPool = ownsPool;
        if (transactionManager.getDataSource() != pool) {
            throw new IllegalArgumentException("系统事务管理器必须管着系统池：否则 inTransaction 开的事务管不住账本的 SQL，每条各自提交");
        }
        this.pool = pool;
        this.tx = new TransactionTemplate(transactionManager);
        JdbcTemplate template = new JdbcTemplate(pool);
        template.setExceptionTranslator(lockTimeoutAsTransient(template.getExceptionTranslator()));
        JdbcClient jdbc = JdbcClient.create(template);
        this.session = new Session(jdbc, new LedgerServiceImpl(jdbc));
    }

    /**
     * 系统池的配置：池名 chainpay-system、借连接最多等 3 秒、每条物理连接带 lock_timeout。
     * 只建对象、不连库，和 Boot 自己建的数据源一样：第一次借连接（启动自检）时才起池。密码没设、lockTimeout 不合法，这里就抛。
     */
    public static HikariDataSource pool(String jdbcUrl, String username, String password, int maximumPoolSize, Duration lockTimeout) {
        if (lockTimeout == null || lockTimeout.isZero() || lockTimeout.isNegative()) {
            throw new IllegalArgumentException("lockTimeout 必须大于 0：PostgreSQL 里 0 表示无限等待，那正是要去掉的行为");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("系统连接没有密码：设 CHAINPAY_SYSTEM_DB_PASSWORD"
                    + "（角色 chainpay_system 由 db/init/01-roles.sql 创建）");
        }
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(jdbcUrl);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(maximumPoolSize);
        pool.setPoolName("chainpay-system");
        pool.setConnectionTimeout(3_000);
        // 等锁的上限：没有它，一个被别的事务握着的账本表能让入账事务无限期等下去，
        // 而且拖住的是调度线程。超时时 PostgreSQL 抛 SQLSTATE 55P03，被翻译成瞬时异常，入账任务下一轮再来。
        // 会话级 SET：池里每条物理连接建立时执行一次、之后一直带着；值来自 Duration，不是外部字符串。
        pool.setConnectionInitSql("SET lock_timeout = '" + lockTimeout.toMillis() + "ms'");
        return pool;
    }

    /** 用容器里的系统池与系统事务管理器建账本，并做启动自检（身份、判官）。任何一步不满足都抛出；池是容器的 bean，由容器关。 */
    public static SystemLedger start(HikariDataSource pool, JdbcTransactionManager transactionManager) {
        SystemLedger ledger = new SystemLedger(pool, transactionManager, false);
        ledger.requireSystemIdentity();
        ledger.judgeAtBoot();
        return ledger;
    }

    /** 不经容器、自己建池（测试用：拿错误的身份验证自检会拒绝）。自检失败时池随之关闭；成功时用完要 {@link #close}。 */
    public static SystemLedger connect(String jdbcUrl, String username, String password, int maximumPoolSize,
                                       Duration lockTimeout) {
        HikariDataSource pool = pool(jdbcUrl, username, password, maximumPoolSize, lockTimeout);
        try {
            SystemLedger ledger = new SystemLedger(pool, new JdbcTransactionManager(pool), true);
            ledger.requireSystemIdentity();
            ledger.judgeAtBoot();
            return ledger;
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
    }

    /** 在系统身份的一个事务里做一件事。回调抛出 = 整体回滚。外层已有 system 事务时加入它。 */
    public <T> T inTransaction(Function<Session, T> work) {
        return tx.execute(status -> work.apply(session));
    }

    private void requireSystemIdentity() {
        record Identity(String user, boolean bypassRls, boolean superuser) {}
        Identity id = session.jdbc()
                .sql("SELECT current_user, rolbypassrls, rolsuper FROM pg_roles WHERE rolname = current_user")
                .query((rs, i) -> new Identity(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)))
                .single();
        if (id.superuser()) {
            throw new IllegalStateException("系统连接不能用超级用户 " + id.user()
                    + "：它能改策略、建表、删行，权限没有边界。用 db/init/01-roles.sql 里的 chainpay_system");
        }
        if (!id.bypassRls()) {
            throw new IllegalStateException("系统连接的角色 " + id.user() + " 没有 BYPASSRLS："
                    + "RLS 会让它一行都看不到，入账任务将静默地无事可做。用 db/init/01-roles.sql 里的 chainpay_system");
        }
    }

    /**
     * 等锁超时（SQLSTATE 55P03）是瞬时的：换个时刻再来多半就成了。Spring 7 的 SQLSTATE 翻译器不认识 55 这一类，
     * 会给 {@code UncategorizedSQLException}（非瞬时）——入账任务据此把那笔记成 HELD_ERROR、等人来看，
     * 每一次等锁超时都变成一张工单。所以在系统连接上把它翻成 {@link CannotAcquireLockException}（{@code TransientDataAccessException} 的子类），其余交回默认翻译器。
     */
    private static SQLExceptionTranslator lockTimeoutAsTransient(SQLExceptionTranslator defaults) {
        return (task, sql, ex) -> {
            if ("55P03".equals(ex.getSQLState())) {
                return new CannotAcquireLockException(task + "；等锁超时（lock_timeout）：" + ex.getMessage(), ex);
            }
            return defaults.translate(task, sql, ex);
        };
    }

    /**
     * 启动时以系统身份跑一次判官并把可见分录数打进日志：判官只在能看到全部行的身份下给结论，
     * 0 处违规才算平账。有违规打 ERROR 但不拒绝启动：失衡要人进来查，起不来反而挡路。
     */
    private void judgeAtBoot() {
        List<String> violations = session.jdbc()
                .sql("SELECT check_name || ' ' || subject || ' ' || detail FROM ledger_judge()")
                .query(String.class).list();
        long entries = session.jdbc().sql("SELECT count(*) FROM entry").query(Long.class).single();
        if (violations.isEmpty()) {
            log.info("账本判官（系统身份）：0 处违规，可见分录 {} 条", entries);
        } else {
            violations.forEach(v -> log.error("账本判官：{}", v));
            log.error("账本判官（系统身份）：{} 处违规，可见分录 {} 条——不变量被破坏，先查再动", violations.size(), entries);
        }
    }

    /** 关掉自己建的池（{@link #connect} 建的）。容器装配的实例池归容器：这里关掉它，之后每次借连接都失败。 */
    @Override
    public void close() {
        if (ownsPool) {
            pool.close();
        }
    }
}
