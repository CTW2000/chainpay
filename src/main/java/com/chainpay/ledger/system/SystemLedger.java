package com.chainpay.ledger.system;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerServiceImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
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

    /** 一次系统事务里能用的两样东西：系统连接上的 SQL 客户端，和绑在同一连接上的账本。 */
    public record Session(JdbcClient jdbc, LedgerService ledger) {}

    private final HikariDataSource pool;
    private final TransactionTemplate tx;
    private final Session session;

    private SystemLedger(HikariDataSource pool) {
        this.pool = pool;
        this.tx = new TransactionTemplate(new JdbcTransactionManager(pool));
        JdbcClient jdbc = JdbcClient.create(pool);
        this.session = new Session(jdbc, new LedgerServiceImpl(jdbc));
    }

    /** 建池、连一次、核对身份。任何一步不满足都抛出，池随之关闭。 */
    public static SystemLedger connect(String jdbcUrl, String username, String password, int maximumPoolSize) {
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
        HikariDataSource pool = new HikariDataSource(config);        // 建池即连一次：连不上、密码错，这里就抛
        SystemLedger ledger = new SystemLedger(pool);
        try {
            ledger.requireSystemIdentity();
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

    @Override
    public void close() {
        pool.close();
    }
}
