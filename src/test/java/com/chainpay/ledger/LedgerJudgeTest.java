package com.chainpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.support.AbstractPostgresTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 判官只能以看得到全部行的身份给结论：entry 是 FORCE RLS，看不全的身份会静默返回 0 行、把坏账当平账。
 * 所以判官以系统身份（BYPASSRLS）跑，判官函数在看不全的身份下直接拒绝。
 *
 * <p>只查调用者还不够：视图默认用<b>主人</b>的身份读表，主人受行级安全约束时同样报 0 行（托管数据库的属主就不是超级用户）。
 * 视图开了 {@code security_invoker}，主人是谁才不影响结论。
 */
@SpringBootTest
@DisplayName("账本判官必须以能看到全部行的身份运行")
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

    @Test
    @DisplayName("★ 判官视图的主人换成一个受行级安全约束的角色（托管数据库的样子）：判官照样看得见坏账——主人是谁不能影响结论")
    void theJudgeStaysSightedWhenItsViewsAreOwnedByARowLimitedRole() throws SQLException {
        // 整个实验在属主连接的一个事务里，最后回滚：建角色、改属主、坏账一起撤掉（PostgreSQL 的 DDL 也在事务里）
        try (Connection c = DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                long source = insertReturningId(s, "INSERT INTO account (code, currency, kind, allow_negative) VALUES ('judge-owner:source', 'USDT', 'ASSET', true) RETURNING id");
                long target = insertReturningId(s, "INSERT INTO account (code, currency, kind, allow_negative) VALUES ('judge-owner:target', 'USDT', 'LIABILITY', false) RETURNING id");
                long transfer = insertReturningId(s, "INSERT INTO transfer (idempotency_key, currency, amount, debit_account_id, credit_account_id, code) "
                        + "VALUES ('judge-owner:lopsided', 'USDT', 7, " + source + ", " + target + ", 'INTERNAL') RETURNING id");
                s.executeUpdate("INSERT INTO entry (transfer_id, account_id, currency, amount) VALUES (" + transfer + ", " + source + ", 'USDT', 7)");
                assertThat(checksFoundAsSystem(s)).as("基线：视图的主人是超级用户，两个视图各自报得出这条单边分录")
                        .contains("ledger_invariant", "balance_consistency");

                s.execute("CREATE ROLE judge_view_owner NOLOGIN");                 // 不是超级用户、没有 BYPASSRLS：行级安全对它生效
                s.execute("GRANT SELECT ON account, entry TO judge_view_owner");
                s.execute("ALTER VIEW ledger_invariant OWNER TO judge_view_owner");
                s.execute("ALTER VIEW balance_consistency OWNER TO judge_view_owner");

                assertThat(checksFoundAsSystem(s)).as("换了主人之后判官不能变瞎：一行都没有正是「平账」的样子")
                        .contains("ledger_invariant", "balance_consistency");
            } finally {
                c.rollback();
            }
        }
    }

    /** 以系统身份（BYPASSRLS）调判官，返回报出违规的检查名——逐个视图看，只修好一个也会被抓住。SET LOCAL 只活在当前事务里。 */
    private static List<String> checksFoundAsSystem(Statement s) throws SQLException {
        s.execute("SET LOCAL ROLE chainpay_system");
        List<String> checks = new ArrayList<>();
        try (ResultSet r = s.executeQuery("SELECT DISTINCT check_name FROM ledger_judge() ORDER BY 1")) {
            while (r.next()) {
                checks.add(r.getString(1));
            }
            return checks;
        } finally {
            s.execute("RESET ROLE");
        }
    }

    private static long insertReturningId(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    private List<String> judge() {
        return systemJdbc.sql("SELECT check_name || ' ' || subject || ' ' || detail FROM ledger_judge()")
                .query(String.class).list();
    }
}
