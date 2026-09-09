package com.chainpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.support.AbstractPostgresTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 扫描补丁（2026-09-09）：几条只存在于注释里的模式规矩，从此由系统表守着。
 * 匹配集合都要求非空——守卫扫到 0 个对象等于没守（质询模板 5.10）。
 */
@SpringBootTest
@DisplayName("扫描补丁 · 模式守卫")
class SchemaGuardTest extends AbstractPostgresTest {

    @Test
    @DisplayName("★ 开了 RLS 的表必须同时 FORCE：属主跑运维脚本也逃不掉策略（V6 写下的理由，V18/V19 漏了）")
    void everyRowSecuredTableIsAlsoForced() {
        List<String> secured = jdbc.sql("""
                        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relrowsecurity ORDER BY 1
                        """).query(String.class).list();
        assertThat(secured).as("守卫的匹配集合不能为空").hasSizeGreaterThanOrEqualTo(5);

        List<String> notForced = jdbc.sql("""
                        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relrowsecurity AND NOT c.relforcerowsecurity
                        """).query(String.class).list();
        assertThat(notForced).as("有 ENABLE 没 FORCE 的表").isEmpty();
    }

    @Test
    @DisplayName("★ 判官视图（*_invariant / *_consistency）必须授权给系统角色：它是唯一被声明能看全部行的身份")
    void judgeViewsAreReadableByTheSystemRole() {
        List<String> judges = jdbc.sql("""
                        SELECT viewname FROM pg_views
                        WHERE schemaname = 'public' AND (viewname LIKE '%\\_invariant' OR viewname LIKE '%\\_consistency') ORDER BY 1
                        """).query(String.class).list();
        assertThat(judges).as("守卫的匹配集合不能为空").hasSizeGreaterThanOrEqualTo(2);
        for (String view : judges) {
            assertThat(jdbc.sql("SELECT has_table_privilege('chainpay_system', :v, 'SELECT')").param("v", view)
                    .query(Boolean.class).single()).as(view).isTrue();
        }
    }

    @Test
    @DisplayName("租户策略里的会话函数包成 (SELECT …)：每条语句求值一次，不是每行一次")
    void tenantPoliciesEvaluateSessionFunctionsOncePerStatement() {
        List<String> quals = jdbc.sql("""
                        SELECT policyname || ': ' || qual FROM pg_policies
                        WHERE schemaname = 'public' AND policyname LIKE '%\\_tenant' ORDER BY 1
                        """).query(String.class).list();
        assertThat(quals).as("守卫的匹配集合不能为空").hasSizeGreaterThanOrEqualTo(5);
        assertThat(quals).allSatisfy(q -> assertThat(q).contains("SELECT"));
    }

    @Test
    @DisplayName("★ 按地址查链上日志走索引：入账候选、余额核对、商户两个接口都按 to_address / from_address 连接")
    void addressLookupsOnTheTransferLogUseAnIndex() throws Exception {
        // 结构：两个部分索引的列与谓词。谓词耦合——查询里少了 status = 'CANONICAL'，索引就静默失效
        for (String column : List.of("to", "from")) {
            String def = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = :n").param("n", "chain_transfer_log_" + column + "_idx")
                    .query(String.class).optional().orElse("(缺失)");
            assertThat(def).as(column).contains("(" + column + "_address, token)").contains("WHERE (status = 'CANONICAL'::text)");
        }

        // 行为：空表上规划器随便挑，所以先灌 60 行、ANALYZE，让统计信息决定——按 to 查走 to_idx，按 from 查走 from_idx
        try (Connection c = DriverManager.getConnection(jdbcUrl(), ownerUsername(), ownerPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO chain_token (address, symbol, decimals) VALUES ('" + LINK + "', 'LINK', 18) ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO chain_transfer_log (token, from_address, to_address, value, block_number, block_hash, tx_hash, log_index)"
                    + " SELECT '" + LINK + "', '0x' || lpad(to_hex(i), 40, '0'), '0x' || lpad(to_hex(1000 + i), 40, '0'), 1, i,"
                    + " '0x' || lpad(to_hex(i), 64, '0'), '0x' || lpad(to_hex(5000 + i), 64, '0'), 0 FROM generate_series(1, 60) i");
            st.execute("ANALYZE chain_transfer_log");
            st.execute("SET enable_seqscan = off");                       // 60 行只占一页，不关掉顺扫它永远赢；关掉后比的是两个索引谁更省
            try {
                String byTo = plan(st, "EXPLAIN SELECT 1 FROM chain_transfer_log WHERE to_address = '0x' || lpad(to_hex(1007), 40, '0')"
                        + " AND token = '" + LINK + "' AND status = 'CANONICAL'");
                assertThat(byTo).as("按收款地址：%n%s", byTo).contains("chain_transfer_log_to_idx");
                String byFrom = plan(st, "EXPLAIN SELECT 1 FROM chain_transfer_log WHERE from_address = '0x' || lpad(to_hex(7), 40, '0')"
                        + " AND token = '" + LINK + "' AND status = 'CANONICAL'");
                assertThat(byFrom).as("按付款地址：%n%s", byFrom).contains("chain_transfer_log_from_idx");
            } finally {
                st.execute("TRUNCATE chain_transfer_log CASCADE");
            }
        }
    }

    private static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";

    private static String plan(Statement st, String explain) throws Exception {
        StringBuilder out = new StringBuilder();
        try (ResultSet rs = st.executeQuery(explain)) {
            while (rs.next()) {
                out.append(rs.getString(1)).append('\n');
            }
        }
        return out.toString();
    }
}
