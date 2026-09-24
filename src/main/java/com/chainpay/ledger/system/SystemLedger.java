package com.chainpay.ledger.system;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * 系统身份的入口：以 {@code chainpay_system}（BYPASSRLS、非超级用户）连库的<b>独立连接池</b>怎么建、池上的 SQL 客户端怎么建，以及启动自检。
 *
 * <p>池、事务管理器、SQL 客户端、绑在系统连接上的账本都是容器里限定名 {@value #QUALIFIER} 的非默认候选 bean（见 {@link SystemLedgerConfig}）。
 * 系统侧的写法：注入限定名 system 的 {@code JdbcClient} 与 {@code LedgerService}；几条写要一起成败的，放进 {@code @Transactional("system")} 的方法里，
 * 账本的转账必须已经在这样的事务里（{@link SystemLedgerService}）。控制器不得碰本类与这些 bean（{@code ControllerBoundaryTest} 扫源码）。
 *
 * <p><b>启动即自检：</b>第一次借连接时问一次 {@code pg_roles}，不是 BYPASSRLS（RLS 会让它一行都看不到，
 * 入账任务将静默地无事可做）或者是超级用户（权限没有边界），直接拒绝启动。
 * 配错了就起不来，远好过看起来正常。
 */
public final class SystemLedger {

    private static final Logger log = LoggerFactory.getLogger(SystemLedger.class);

    /** 系统身份那几个 bean 的限定名：{@code @Qualifier("system")}、{@code @Transactional("system")}。 */
    public static final String QUALIFIER = "system";

    private final JdbcClient jdbc;

    private SystemLedger(HikariDataSource pool, JdbcTransactionManager transactionManager) {
        if (transactionManager.getDataSource() != pool) {
            throw new IllegalArgumentException("系统事务管理器必须管着系统池：否则 @Transactional(\"system\") 开的事务管不住系统连接上的 SQL，每条各自提交");
        }
        this.jdbc = jdbcClient(pool);
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

    /** 系统连接上的 SQL 客户端：等锁超时（55P03）翻成瞬时异常。容器里限定名 system 的 JdbcClient 与启动自检用同一个做法。 */
    public static JdbcClient jdbcClient(HikariDataSource pool) {
        JdbcTemplate template = new JdbcTemplate(pool);
        template.setExceptionTranslator(lockTimeoutAsTransient(template.getExceptionTranslator()));
        return JdbcClient.create(template);
    }

    /** 用容器里的系统池与系统事务管理器做启动自检（身份、判官）。任何一步不满足都抛出；池是容器的 bean，由容器关。 */
    public static SystemLedger start(HikariDataSource pool, JdbcTransactionManager transactionManager) {
        SystemLedger ledger = new SystemLedger(pool, transactionManager);
        ledger.requireSystemIdentity();
        ledger.judgeAtBoot();
        return ledger;
    }

    private void requireSystemIdentity() {
        record Identity(String user, boolean bypassRls, boolean superuser) {}
        Identity id = jdbc
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
        List<String> violations = jdbc
                .sql("SELECT check_name || ' ' || subject || ' ' || detail FROM ledger_judge()")
                .query(String.class).list();
        long entries = jdbc.sql("SELECT count(*) FROM entry").query(Long.class).single();
        if (violations.isEmpty()) {
            log.info("账本判官（系统身份）：0 处违规，可见分录 {} 条", entries);
        } else {
            violations.forEach(v -> log.error("账本判官：{}", v));
            log.error("账本判官（系统身份）：{} 处违规，可见分录 {} 条——不变量被破坏，先查再动", violations.size(), entries);
        }
    }
}
