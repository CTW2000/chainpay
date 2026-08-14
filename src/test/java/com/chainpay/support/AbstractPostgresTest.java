package com.chainpay.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 所有账本测试的基类：跑<b>真的 PostgreSQL 18</b>，不用 H2。
 *
 * <p>为什么不用 H2：H2 的 {@code NUMERIC} 语义、事务隔离级别、行锁行为
 * 和 Postgres 都不一样 —— 而这三处正好是本项目最要命的地方。
 * 用 H2 测试通过，只能证明「在 H2 上没错」。
 *
 * <p>容器是 {@code static} 的，整个测试类共享一个，不会每个方法起一次。
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    @Autowired
    protected JdbcClient jdbc;

    /** 每个测试方法前清空数据，保证测试之间互不影响。 */
    @BeforeEach
    void resetLedger() {
        jdbc.sql("TRUNCATE entry, transfer, account RESTART IDENTITY CASCADE").update();
    }

    /** 建一个普通账户（余额不得为负），返回它的 id。 */
    protected long createAccount(String code, String currency, String kind) {
        return createAccount(code, currency, kind, false);
    }

    /**
     * 建账户。
     *
     * @param allowNegative 是否允许余额为负。只有「资金来源」账户该是 true ——
     *                      复式记账里钱不能凭空出现，注资时它是那个变负的对手方
     */
    protected long createAccount(String code, String currency, String kind, boolean allowNegative) {
        return jdbc.sql("""
                        INSERT INTO account (code, currency, kind, allow_negative)
                        VALUES (:code, :currency, :kind, :allowNegative)
                        RETURNING id
                        """)
                .param("code", code)
                .param("currency", currency)
                .param("kind", kind)
                .param("allowNegative", allowNegative)
                .query(Long.class)
                .single();
    }

    /**
     * 核心不变量：每种币的分录金额之和必须恒为 0。
     *
     * <p>返回违反不变量的币种数。任何时刻调用都必须是 0。
     */
    protected long invariantViolations() {
        return jdbc.sql("SELECT COUNT(*) FROM ledger_invariant WHERE total <> 0")
                .query(Long.class)
                .single();
    }

    /** 有多少个「不该为负」的账户余额为负。任何时刻都必须是 0。 */
    protected long illegalNegativeBalances() {
        return jdbc.sql("SELECT COUNT(*) FROM account_balance WHERE balance < 0 AND NOT allow_negative")
                .query(Long.class)
                .single();
    }

    /** transfer 表里的记录条数。 */
    protected long transferCount() {
        return jdbc.sql("SELECT COUNT(*) FROM transfer").query(Long.class).single();
    }
}
