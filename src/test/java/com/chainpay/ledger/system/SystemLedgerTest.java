package com.chainpay.ledger.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 系统权限是连接身份，不是一个开关。
 *
 * <p>M0 到 M2 的系统操作靠 {@code TenantScope.asSystem()} 在事务里设一个会话变量放行 RLS——
 * 一个 public 方法，任何代码都能调，靠「控制器不得调它」的纪律守着。
 * M3 的入账是第一段无人值守、按时刻表跨所有商户动钱的代码，它的特权应当来自<b>它连库用的角色</b>：
 * 独立的 {@code chainpay_system}（BYPASSRLS，非超级用户，非属主），独立的连接池，
 * 拿不到这个池就拿不到这份权限。
 */
@SpringBootTest
@DisplayName("M3-⓪ · 系统账本：权限来自连接身份")
class SystemLedgerTest extends AbstractPostgresTest {

    @Autowired
    private SystemLedger systemLedger;

    /** 应用自己的账本 bean 与连接：商户视角只能拿它们来问。 */
    @Autowired
    private LedgerService appLedger;

    @Autowired
    private JdbcClient appJdbc;

    private long acmeId;
    private long acmeAccount;
    private long evilcoAccount;
    private long custodyAccount;

    @BeforeEach
    void seedTwoMerchantsAndTheCustodyMirror() {
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();
        acmeId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('acme', 'Acme') RETURNING id").query(Long.class).single();
        long evilcoId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('evilco', 'Evil') RETURNING id").query(Long.class).single();
        acmeAccount = account("user:acme:USDT", "LIABILITY", false, acmeId);
        evilcoAccount = account("user:evilco:USDT", "LIABILITY", false, evilcoId);
        // 链上托管余额在账本里的镜像：入账时它是变负的对手方，绝对值 = 托管地址链上应有的余额之和
        custodyAccount = account("chain:custody:USDT", "ASSET", true, null);
    }

    @Test
    @DisplayName("★ 系统连接就是系统身份：current_user 是 chainpay_system，带 BYPASSRLS，不是超级用户")
    void connectsAsTheSystemRole() {
        record Identity(String user, boolean bypassRls, boolean superuser) {}

        Identity id = systemLedger.inTransaction(s -> s.jdbc()
                .sql("SELECT current_user, rolbypassrls, rolsuper FROM pg_roles WHERE rolname = current_user")
                .query((rs, i) -> new Identity(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)))
                .single());

        assertThat(id.user()).isEqualTo("chainpay_system");
        assertThat(id.bypassRls()).as("权限来自角色属性，不来自会话变量").isTrue();
        assertThat(id.superuser()).as("BYPASSRLS 不等于超级用户：不能建表、不能改策略").isFalse();
    }

    @Test
    @DisplayName("★ 不设任何会话变量也看得到全部账户；应用连接在同样的条件下一行都看不到")
    void seesEveryRowWithoutAnySessionVariable() {
        long system = systemLedger.inTransaction(s -> s.jdbc().sql("SELECT count(*) FROM account").query(Long.class).single());
        long app = appJdbc.sql("SELECT count(*) FROM account").query(Long.class).single();

        assertThat(system).as("两个商户的账户 + 平台镜像账户").isEqualTo(3);
        assertThat(app).as("应用角色没进作用域：fail-closed").isZero();
    }

    @Test
    @DisplayName("★ 以系统身份记一笔 DEPOSIT：镜像账户变负、商户账户变正，商户在自己的作用域里看到余额")
    void postsADepositAcrossTheTenantBoundary() {
        long transferId = systemLedger.inTransaction(s -> s.ledger().transfer(new TransferCommand(
                "deposit:0xblock:7", "USDT", new BigDecimal("10"), custodyAccount, acmeAccount,
                TransferCode.DEPOSIT, Instant.parse("2026-09-06T00:00:00Z"))));

        assertThat(transferId).isPositive();
        BigDecimal seenByAcme = tenantScope.asMerchant(acmeId, () -> appLedger.balanceOf(acmeAccount));
        assertThat(seenByAcme).isEqualByComparingTo("10");
        assertThat(jdbc.sql("SELECT balance FROM account WHERE id = :id").param("id", custodyAccount)
                .query(BigDecimal.class).single()).isEqualByComparingTo("-10");
        assertThat(jdbc.sql("SELECT balance FROM account WHERE id = :id").param("id", evilcoAccount)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("★ 事务边界在系统池上真的成立：回调里抛异常，转账一条不留")
    void rollsBackTheWholeCallback() {
        assertThatThrownBy(() -> systemLedger.inTransaction(s -> {
            s.ledger().transfer(new TransferCommand("deposit:0xblock:8", "USDT", new BigDecimal("3"),
                    custodyAccount, acmeAccount, TransferCode.DEPOSIT, null));
            throw new IllegalStateException("模拟：账本已记、后半段失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(transferCount()).as("手工 new 的 LedgerServiceImpl 没有 @Transactional 代理，边界只能由 SystemLedger 给").isZero();
        assertThat(jdbc.sql("SELECT balance FROM account WHERE id = :id").param("id", acmeAccount)
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("★ 系统身份也删不了账本：DELETE 被数据库拒绝，账本对谁都是只追加的")
    void cannotDeleteLedgerRows() {
        systemLedger.inTransaction(s -> s.ledger().transfer(new TransferCommand("deposit:0xblock:9", "USDT",
                new BigDecimal("1"), custodyAccount, acmeAccount, TransferCode.DEPOSIT, null)));

        assertThatThrownBy(() -> systemLedger.inTransaction(s -> s.jdbc().sql("DELETE FROM entry").update()))
                .isInstanceOf(DataAccessException.class)
                // PG 的 SQLSTATE 42501（权限不足）被 Spring 归到 42 这一类 = BadSqlGrammarException，原文只在根因里
                .hasStackTraceContaining("permission denied");
        assertThat(jdbc.sql("SELECT count(*) FROM entry").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 配错身份就起不来：应用角色没有 BYPASSRLS 被拒；属主是超级用户也被拒")
    void refusesTheWrongIdentityAtStartup() {
        assertThatThrownBy(() -> SystemLedger.connect(jdbcUrl(), "chainpay_app", "chainpay_app_dev", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BYPASSRLS");
        assertThatThrownBy(() -> SystemLedger.connect(jdbcUrl(), ownerUsername(), ownerPassword(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超级用户");
    }

    // ------------------------------------------------------------------ 脚手架

    private long account(String code, String kind, boolean allowNegative, Long merchantId) {
        return jdbc.sql("""
                        INSERT INTO account(code, currency, kind, allow_negative, merchant_id)
                        VALUES (:c, 'USDT', :k, :n, :m) RETURNING id
                        """)
                .param("c", code).param("k", kind).param("n", allowNegative).param("m", merchantId)
                .query(Long.class).single();
    }
}
