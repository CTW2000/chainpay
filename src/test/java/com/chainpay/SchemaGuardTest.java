package com.chainpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.ledger.service.LedgerAmounts;
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
 * 模式规矩由系统表守着，不只写在注释里。匹配集合都要求非空——守卫扫到 0 个对象等于没守。
 */
@SpringBootTest
@DisplayName("模式守卫")
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
    @DisplayName("★ 账本三表只追加：两个角色对 transfer / entry 都没有 UPDATE / DELETE，account 只能改 balance 一列（V29）")
    void theLedgerIsAppendOnlyForBothRoles() {
        // 「账本只追加」由权限守，不靠「没人写那种 SQL」的纪律——纪律会被新接口、手工 SQL、将来的自己绕过。
        // 这个测试守的是「V29 撤掉的权限不许被加回去」。
        for (String role : List.of("chainpay_app", "chainpay_system")) {
            for (String table : List.of("transfer", "entry")) {
                assertThat(can(role, table, "INSERT")).as("%s 必须能往 %s 追加", role, table).isTrue();
                assertThat(can(role, table, "SELECT")).as("%s 必须能读 %s", role, table).isTrue();
                assertThat(can(role, table, "UPDATE")).as("%s 不该能改写 %s 的历史", role, table).isFalse();
                assertThat(can(role, table, "DELETE")).as("%s 不该能删 %s 的行", role, table).isFalse();
            }
            // 余额要改，但「能不能透支」「这是谁的钱」不该被应用连接改——所以是列级授权
            assertThat(can(role, "account", "UPDATE")).as("%s 不该有 account 的整行 UPDATE", role).isFalse();
            assertThat(canUpdateColumn(role, "account", "balance")).as("%s 必须能改余额", role).isTrue();
            for (String column : List.of("allow_negative", "merchant_id", "currency", "kind", "code")) {
                assertThat(canUpdateColumn(role, "account", column))
                        .as("%s 不该能改 account.%s", role, column).isFalse();
            }
            assertThat(can(role, "account", "DELETE")).as("%s 不该能删账户", role).isFalse();
        }
    }

    private boolean can(String role, String table, String privilege) {
        return jdbc.sql("SELECT has_table_privilege(:r, :t, :p)")
                .param("r", role).param("t", table).param("p", privilege)
                .query(Boolean.class).single();
    }

    private boolean canUpdateColumn(String role, String table, String column) {
        return jdbc.sql("SELECT has_column_privilege(:r, :t, :c, 'UPDATE')")
                .param("r", role).param("t", table).param("c", column)
                .query(Boolean.class).single();
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
    @DisplayName("★ 每个视图都按调用者身份执行（security_invoker）：视图默认用主人的身份读表——主人是超级用户时静默绕过行级安全，不是超级用户时又静默看不见")
    void everyViewRunsAsItsCaller() {
        List<String> views = jdbc.sql("""
                        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'v' ORDER BY 1
                        """).query(String.class).list();
        assertThat(views).as("守卫的匹配集合不能为空").hasSizeGreaterThanOrEqualTo(3);

        List<String> runAsOwner = jdbc.sql("""
                        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'v'
                          AND coalesce(array_to_string(c.reloptions, ','), '') !~ 'security_invoker=(true|on|1)'
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(runAsOwner).as("没开 security_invoker、以主人身份读表的视图").isEmpty();
    }

    @Test
    @DisplayName("★ 每个 SECURITY DEFINER 函数：属主是 chainpay_system、search_path 钉死且 pg_temp 在最后、PUBLIC 没有执行权")
    void everySecurityDefinerFunctionIsLockedDown() {
        // 按属主身份执行的函数是一扇侧门。search_path 没钉死（或 pg_temp 不在最后），调用方能在自己的临时 schema 里建同名表喂给它；
        // PUBLIC 能执行，谁都能从这扇门进；属主是迁移账号时，开发库、测试库（超级用户）照绿，托管库（非超级用户）一行都看不到。
        List<String> functions = jdbc.sql("""
                        SELECT p.oid::regprocedure::text FROM pg_proc p
                        WHERE p.pronamespace = 'public'::regnamespace AND p.prosecdef ORDER BY 1
                        """).query(String.class).list();
        assertThat(functions).as("守卫的匹配集合不能为空").isNotEmpty();

        List<String> violations = jdbc.sql("""
                        SELECT p.oid::regprocedure::text || '：' || concat_ws('、',
                                 CASE WHEN pg_get_userbyid(p.proowner) <> 'chainpay_system' THEN '属主是 ' || pg_get_userbyid(p.proowner) END,
                                 CASE WHEN NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c ~ '^search_path=.*pg_temp$')
                                      THEN 'search_path 没钉死或 pg_temp 不在最后' END,
                                 CASE WHEN has_function_privilege('public', p.oid, 'EXECUTE') THEN 'PUBLIC 能执行' END)
                        FROM pg_proc p
                        WHERE p.pronamespace = 'public'::regnamespace AND p.prosecdef
                          AND (pg_get_userbyid(p.proowner) <> 'chainpay_system'
                               OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c ~ '^search_path=.*pg_temp$')
                               OR has_function_privilege('public', p.oid, 'EXECUTE'))
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(violations).as("没锁好的 SECURITY DEFINER 函数").isEmpty();
    }

    @Test
    @DisplayName("★ 每个带小数位的列都正好是账本自己的上限：常量改了或列改了都红")
    void everyAmountColumnIsExactlyTheLedgersCapacity() {
        // 带小数位的 numeric 一律当金额列（链上原始单位是 NUMERIC(78,0)，小数位 0，不在其中）。
        // 哪天真要一个不是金额的小数列（比如百分比），在这里显式放行，而不是悄悄放宽规则。
        String expected = "(%d,%d)".formatted(LedgerAmounts.INTEGER_DIGITS + LedgerAmounts.SCALE, LedgerAmounts.SCALE);
        List<String> columns = jdbc.sql("""
                        SELECT c.table_name || '.' || c.column_name || ' (' || c.numeric_precision || ',' || c.numeric_scale || ')'
                        FROM information_schema.columns c
                        JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                        WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                          AND c.data_type = 'numeric' AND c.numeric_scale > 0
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(columns).as("守卫的匹配集合不能为空：账本的三列必须在里面")
                .anyMatch(c -> c.startsWith("transfer.amount "))
                .anyMatch(c -> c.startsWith("entry.amount "))
                .anyMatch(c -> c.startsWith("account.balance "));
        assertThat(columns.stream().filter(c -> !c.endsWith(" " + expected)).toList())
                .as("精度与小数位不是 LedgerAmounts 的 %s 的金额列", expected)
                .isEmpty();
    }

    @Test
    @DisplayName("★ 地址的形状在库里只写一份：只有 is_eth_address 里有这个正则，约束都调它")
    void addressShapeLivesOnlyInIsEthAddress() {
        List<String> constraintsWithRegex = jdbc.sql("""
                        SELECT conrelid::regclass || '.' || conname FROM pg_constraint
                        WHERE contype = 'c' AND connamespace = 'public'::regnamespace AND pg_get_constraintdef(oid) LIKE '%{40}%'
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(constraintsWithRegex).as("自己写着地址正则的约束（应改成调用 is_eth_address）").isEmpty();

        List<String> functionsWithRegex = jdbc.sql("""
                        SELECT proname FROM pg_proc
                        WHERE pronamespace = 'public'::regnamespace AND prokind = 'f' AND pg_get_functiondef(oid) LIKE '%{40}%'
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(functionsWithRegex).as("写着地址正则的函数只许有一个").containsExactly("is_eth_address");

        // 存库的写法：一律小写（大写、短一位都不算）；NULL 放行、交给 NOT NULL 管
        assertThat(jdbc.sql("SELECT is_eth_address('0x' || repeat('a', 40))").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT is_eth_address('0x' || repeat('A', 40))").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT is_eth_address('0x' || repeat('a', 39))").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT is_eth_address(NULL) IS NULL").query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("★ 每个地址类的列都有守：自带调用 is_eth_address 的 CHECK，或外键指向这样的列")
    void everyAddressColumnIsGuarded() {
        // 「地址类的列」按名字认：address、*_address、token、hot_wallet 的 text 列。起了别的名字的地址列这里认不出，要靠评审
        String addressColumns = """
                SELECT a.attrelid AS rel, a.attnum AS num, c.relname || '.' || a.attname AS name
                FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
                  AND a.atttypid = 'text'::regtype AND (a.attname ~ '(^|_)address$' OR a.attname IN ('token', 'hot_wallet'))
                """;
        assertThat(jdbc.sql("SELECT count(*) FROM (" + addressColumns + ") cols").query(Long.class).single())
                .as("守卫的匹配集合不能为空").isGreaterThanOrEqualTo(15L);

        List<String> unguarded = jdbc.sql("WITH cols AS (" + addressColumns + """
                        ), checked AS (
                          SELECT conrelid AS rel, conkey[1] AS num FROM pg_constraint
                          WHERE contype = 'c' AND cardinality(conkey) = 1 AND pg_get_constraintdef(oid) LIKE '%is_eth_address(%'
                        ), referencing AS (
                          SELECT f.conrelid AS rel, f.conkey[1] AS num FROM pg_constraint f
                          JOIN checked ch ON ch.rel = f.confrelid AND ch.num = f.confkey[1]
                          WHERE f.contype = 'f' AND cardinality(f.conkey) = 1
                        )
                        SELECT name FROM cols
                        WHERE NOT EXISTS (SELECT 1 FROM checked WHERE checked.rel = cols.rel AND checked.num = cols.num)
                          AND NOT EXISTS (SELECT 1 FROM referencing r WHERE r.rel = cols.rel AND r.num = cols.num)
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(unguarded).as("既没有调用 is_eth_address 的 CHECK、也没有外键指向这样的列").isEmpty();
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
