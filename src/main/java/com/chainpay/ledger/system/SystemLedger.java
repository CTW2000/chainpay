package com.chainpay.ledger.system;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerServiceImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 系统身份的账本入口：以 {@code chainpay_system}（BYPASSRLS、非超级用户）连库的<b>独立连接池</b>，
 * 加一份绑在这条连接上的账本实现。入账、结算、M4 出账都从这里走。
 *
 * <p><b>为什么是一个对象，而不是第二个 {@code DataSource} / {@code JdbcClient} bean：</b>
 * Spring Boot 的自动配置是「容器里没有这个类型的 bean 我才配一个」。多注册一个同类型的 bean，
 * 主连接的自动配置整体退让，全项目的 {@code JdbcClient} 注入要么二义、要么拿到错的那个。
 * 把池、事务模板、账本三样东西装进一个自定义类型里，主连接的一切原样不动，
 * 而「拿到 {@code SystemLedger} 就拿到了全库」这条边界由 {@code ControllerBoundaryTest} 守着。
 *
 * <p><b>为什么只有 {@link #inTransaction} 一个入口：</b>
 * {@link LedgerServiceImpl#transfer} 上的 {@code @Transactional} 是代理魔法，只对容器创建的 bean 生效。
 * 这里的账本是手工 {@code new} 出来的，注解形同虚设——若把它直接暴露出去，调用方在事务外调 transfer，
 * 一条 transfer、两条 entry、两次余额更新各自提交，账本的原子性契约就悄悄没了。
 * 所以事务边界由本类的模板给，账本只在回调里可见；账本的 SQL 走同一个数据源，自动加入这个事务。
 *
 * <p><b>建池即自检：</b>连上后问一次 {@code pg_roles}，不是 BYPASSRLS（RLS 会让它一行都看不到，
 * 入账任务将静默地无事可做）或者是超级用户（权限没有边界），直接拒绝启动。
 * 配错了就起不来，远好过看起来正常。
 */
public final class SystemLedger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SystemLedger.class);

    /** 一次系统事务里能用的两样东西：系统连接上的 SQL 客户端，和绑在同一连接上的账本。 */
    public record Session(JdbcClient jdbc, LedgerService ledger) {}

    private final HikariDataSource pool;
    private final TransactionTemplate tx;
    private final Session session;

    private SystemLedger(HikariDataSource pool) {
        this.pool = pool;
        this.tx = new TransactionTemplate(new JdbcTransactionManager(pool));
        JdbcTemplate template = new JdbcTemplate(pool);
        template.setExceptionTranslator(lockTimeoutAsTransient(template.getExceptionTranslator()));
        JdbcClient jdbc = JdbcClient.create(template);
        this.session = new Session(jdbc, new LedgerServiceImpl(jdbc));
    }

    /** 建池、连一次、核对身份。任何一步不满足都抛出，池随之关闭。 */
    public static SystemLedger connect(String jdbcUrl, String username, String password, int maximumPoolSize,
                                       Duration lockTimeout) {
        if (lockTimeout == null || lockTimeout.isZero() || lockTimeout.isNegative()) {
            throw new IllegalArgumentException("lockTimeout 必须大于 0：PostgreSQL 里 0 表示无限等待，那正是要去掉的行为");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("系统连接没有密码：设 CHAINPAY_SYSTEM_DB_PASSWORD"
                    + "（角色 chainpay_system 由 db/init/01-roles.sql 创建）");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setPoolName("chainpay-system");
        config.setConnectionTimeout(3_000);
        // 等锁的上限（M3-⑤ 演练实测）：没有它，一个被别的事务握着的账本表能让入账事务无限期等下去，
        // 而且拖住的是调度线程。超时时 PostgreSQL 抛 SQLSTATE 55P03，被翻译成瞬时异常，入账任务下一轮再来。
        // 会话级 SET：池里每条物理连接建立时执行一次、之后一直带着；值来自 Duration，不是外部字符串。
        config.setConnectionInitSql("SET lock_timeout = '" + lockTimeout.toMillis() + "ms'");
        HikariDataSource pool = new HikariDataSource(config);        // 建池即连一次：连不上、密码错，这里就抛
        SystemLedger ledger = new SystemLedger(pool);
        try {
            ledger.requireSystemIdentity();
            ledger.judgeAtBoot();
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
        return ledger;
    }

    /** 在系统身份的一个事务里做一件事。回调抛出 = 整体回滚。 */
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
     * 每一次等锁超时都变成一张工单。红灯测试实测就是这样（2026-09-09）。
     * 所以在系统连接上把它翻成 {@link CannotAcquireLockException}（{@code TransientDataAccessException} 的子类），其余交回默认翻译器。
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
     * 启动时以系统身份跑一次判官并把可见分录数打进日志（2026-09-09 扫描补丁）。
     * 判官「能查通」曾经只是超级用户绕过 RLS 的副作用；现在它在一个声明过能看全部行的身份下跑，
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

    @Override
    public void close() {
        pool.close();
    }
}
