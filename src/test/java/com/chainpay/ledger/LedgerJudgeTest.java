package com.chainpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.support.AbstractPostgresTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 扫描补丁（2026-09-09）：两个判官视图此前没授权给任何角色、没有 security_invoker，而 entry 是 FORCE RLS。
 * 它们「能查通」只因为开发库与测试库的属主恰好是超级用户；换成托管数据库的非超级用户属主，
 * 同一条语句静默返回 0 行、把坏账当平账。修法：判官以系统身份（BYPASSRLS）跑，且判官函数拒绝在看不全的身份下给结论。
 */
@SpringBootTest
@DisplayName("扫描补丁 · 账本判官必须以能看到全部行的身份运行")
class LedgerJudgeTest extends AbstractPostgresTest {

    private final JdbcClient appJdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl(), "chainpay_app", "chainpay_app_dev"));
    private final JdbcClient systemJdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl(), "chainpay_system", "chainpay_system_dev"));

    @Test
    @DisplayName("★ 应用角色调判官：拒绝并说明原因，不是静默返回 0 行")
    void refusesUnderAnIdentityThatCannotSeeEveryRow() {
        assertThatThrownBy(() -> appJdbc.sql("SELECT * FROM ledger_judge()").query(String.class).list())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("判官");
    }

    @Test
    @DisplayName("★ 系统身份调判官：平账 0 行；属主绕过账本服务塞一条单边分录，判官必须报出来")
    void seesAnImbalanceThatARowLimitedIdentityWouldHide() {
        assertThat(judge()).isEmpty();
        long source = createAccount("judge:source", "USDT", "ASSET", true);
        long target = createAccount("judge:target", "USDT", "LIABILITY");
        long transferId = jdbc.sql("""
                        INSERT INTO transfer (idempotency_key, currency, amount, debit_account_id, credit_account_id, code)
                        VALUES ('judge:lopsided', 'USDT', 7, :a, :b, 'INTERNAL') RETURNING id
                        """).param("a", source).param("b", target).query(Long.class).single();
        try {
            jdbc.sql("INSERT INTO entry (transfer_id, account_id, currency, amount) VALUES (:t, :a, 'USDT', 7)")
                    .param("t", transferId).param("a", source).update();

            assertThat(judge()).anySatisfy(row -> assertThat(row).contains("ledger_invariant"));
        } finally {
            jdbc.sql("DELETE FROM entry WHERE transfer_id = :t").param("t", transferId).update();
            jdbc.sql("DELETE FROM transfer WHERE id = :t").param("t", transferId).update();
            jdbc.sql("DELETE FROM account WHERE id IN (:a, :b)").param("a", source).param("b", target).update();
        }
    }

    private List<String> judge() {
        return systemJdbc.sql("SELECT check_name || ' ' || subject || ' ' || detail FROM ledger_judge()")
                .query(String.class).list();
    }
}
