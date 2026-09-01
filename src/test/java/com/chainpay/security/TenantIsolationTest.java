package com.chainpay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.security.service.TenantScope;
import com.chainpay.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 租户隔离下沉到数据库之后的守卫。
 *
 * <p><b>这一组测试和 {@code ApiSecurityTest} 守的不是同一件事：</b>
 *
 * <pre>
 *   ApiSecurityTest      走 HTTP，证明**接口**不让你碰别人的账户
 *   TenantIsolationTest  绕过整个 Java 层直接写 SQL，
 *                        证明**数据库自己**就不让你查到别人的行
 * </pre>
 *
 * <p>后者才是 RLS 的意义所在：它保护的是那些<b>还没写出来的接口</b>、
 * 直连数据库的报表服务、和忘了调授权检查的将来的自己。
 */
@SpringBootTest
@DisplayName("M1 · 租户隔离契约（数据库层）")
class TenantIsolationTest extends AbstractPostgresTest {

    @Autowired
    private TenantScope tenantScope;

    private long acmeId;
    private long evilcoId;
    private long acmeAccount;
    private long evilcoAccount;
    private long houseAccount;

    @BeforeEach
    void seedTwoMerchants() {
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();

        acmeId = jdbc.sql("INSERT INTO merchant(code,name) VALUES ('acme','Acme') RETURNING id")
                .query(Long.class).single();
        evilcoId = jdbc.sql("INSERT INTO merchant(code,name) VALUES ('evilco','Evil') RETURNING id")
                .query(Long.class).single();

        acmeAccount = account("user:acme:USDT", acmeId);
        evilcoAccount = account("user:evilco:USDT", evilcoId);
        // merchant_id 为 NULL = 平台自有账户，不属于任何商户
        houseAccount = account("house:mint:USDT", null);
    }

    // ==================================================================
    // 读：查不到别人的行
    // ==================================================================

    @Test
    @DisplayName("★ 手写 SQL 直查全表 —— 也只看得到自己的账户")
    void rawSqlStillOnlySeesOwnAccounts() {
        // 注意这里**完全没有**经过 AccountAccessService。
        // 这条 SQL 就是「将来某个忘了做授权的新接口」会写出来的样子。
        var visible = tenantScope.asMerchant(acmeId, () ->
                jdbc.sql("SELECT id FROM account ORDER BY id").query(Long.class).list());

        assertThat(visible)
                .as("只能看到自己的账户；别人的和平台自有的都不该出现")
                .containsExactly(acmeAccount);
    }

    @Test
    @DisplayName("★ 点名查别人的账户 —— 返回 0 行，不是报错")
    void namingSomeoneElsesAccountReturnsNothing() {
        var found = tenantScope.asMerchant(acmeId, () ->
                jdbc.sql("SELECT id FROM account WHERE id = :id")
                        .param("id", evilcoAccount).query(Long.class).optional());

        // 「不存在」和「无权访问」给同一个回答，攻击者无法用它枚举账户 ——
        // 这正是 AccountAccessService 刻意做到的「不可区分响应」，
        // 而 RLS 直接把它变成了数据库的天然行为，不需要谁记得实现它。
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("★ 透过 account_balance 视图查 —— 视图也挡得住")
    void theBalanceViewIsAlsoFiltered() {
        // 这条防的是一个静默绕过：PostgreSQL 的视图默认以**视图所有者**身份执行，
        // 而所有者是超级用户 —— 于是直接查表被拦，透过视图查却畅通无阻。
        // 而 LedgerServiceImpl.balanceOf() 读的正是这个视图，
        // 也就是说不加 security_invoker，整套隔离在最常用的路径上是个大洞。
        var visible = tenantScope.asMerchant(acmeId, () ->
                jdbc.sql("SELECT account_id FROM account_balance ORDER BY account_id")
                        .query(Long.class).list());

        assertThat(visible).containsExactly(acmeAccount);
    }

    @Test
    @DisplayName("★ 忘了设租户 —— 什么都查不到，而不是什么都查得到")
    void forgettingTheTenantContextSeesNothing() {
        // 这是本次设计里最关键的一条不对称：
        //   没有 RLS 时，漏掉一次授权检查 → 数据泄露，且没人会发现
        //   有 RLS 时，漏掉一次设置租户   → 一行都查不到，立刻炸给你看
        // 让失误的方向指向「立刻暴露」，而不是「悄悄地错」。
        var visible = tenantScope.asMerchant(acmeId, () -> {
            // 手动把租户变量清成空 —— 模拟「谁忘了设」
            jdbc.sql("SELECT set_config('chainpay.merchant_id', '', true)")
                    .query(String.class).single();
            return jdbc.sql("SELECT id FROM account").query(Long.class).list();
        });

        assertThat(visible).isEmpty();
    }

    // ==================================================================
    // 写：往别人账户上挂分录，数据库直接拒绝
    // ==================================================================

    @Test
    @DisplayName("★ 往别人的账户写一条分录 —— 被数据库拒绝，不是被 Java 拒绝")
    void cannotWriteAnEntryAgainstSomeoneElsesAccount() {
        // 这是 RLS 的 WITH CHECK 那一半。
        // 哪怕有人手写 SQL 完全绕开 Java 层，也挂不上别人账户的分录。
        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> {
            long transferId = jdbc.sql("""
                            INSERT INTO transfer(idempotency_key, currency, amount,
                                                 debit_account_id, credit_account_id, code)
                            VALUES ('attack-1','USDT',1,:d,:c,'ADJUSTMENT') RETURNING id
                            """)
                    .param("d", acmeAccount).param("c", evilcoAccount)
                    .query(Long.class).single();

            return jdbc.sql("""
                            INSERT INTO entry(transfer_id, account_id, currency, amount)
                            VALUES (:t, :a, 'USDT', 1)
                            """)
                    .param("t", transferId).param("a", evilcoAccount)   // ← 别人的账户
                    .update();
        })).hasStackTraceContaining("row-level security policy");
    }

    @Test
    @DisplayName("★ 把自己的账户改成别人的 —— 也被拒绝")
    void cannotGiveAwayAnAccountByChangingItsOwner() {
        // 只写 USING 不写 WITH CHECK 的话，这条 UPDATE 会成功 ——
        // 商户可以一次性把账户「送」给别人（或者把别人的账户认领过来）。
        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () ->
                jdbc.sql("UPDATE account SET merchant_id = :other WHERE id = :id")
                        .param("other", evilcoId).param("id", acmeAccount)
                        .update()))
                .hasStackTraceContaining("row-level security policy");
    }

    // ==================================================================
    // 别把正常路径挡死了
    // ==================================================================

    @Test
    @DisplayName("租户作用域内，自己的账户照常读写")
    void ownAccountsRemainFullyUsable() {
        // 加一道保险很容易把正常路径也一起挡死。这条证明没有。
        //
        // 注意这里**不**直接改 balance 列：第一版我写了 UPDATE account SET balance = 5，
        // 结果被 @AfterEach 里的 balanceDrift() 抓住 ——
        // 物化余额和分录求和对不上了。判官连我自己的测试都一起管，这是对的。
        var result = tenantScope.asMerchant(acmeId, () -> {
            jdbc.sql("UPDATE account SET code = 'user:acme:USDT:renamed' WHERE id = :id")
                    .param("id", acmeAccount).update();
            return jdbc.sql("SELECT code FROM account WHERE id = :id")
                    .param("id", acmeAccount).query(String.class).single();
        });

        assertThat(result).isEqualTo("user:acme:USDT:renamed");
    }

    @Test
    @DisplayName("★ 事务结束后角色和租户变量都必须复位 —— 否则连接池会串号")
    void theTenantContextDoesNotLeakBackIntoThePool() {
        tenantScope.asMerchant(acmeId, () -> jdbc.sql("SELECT 1").query(Integer.class).single());

        // 出了作用域之后，同一个池子里的连接必须已经变回原来的身份。
        // 用 SET 而不是 SET LOCAL 的话，这里会读到 chainpay_app 和 acme 的 id ——
        // 下一个借到这条连接的请求就继承了 acme 的租户上下文。
        // 这种 bug 只在并发下出现，单跑测试永远是绿的。
        String role = jdbc.sql("SELECT current_user").query(String.class).single();
        String tenant = jdbc.sql("SELECT coalesce(current_setting('chainpay.merchant_id', true),'')")
                .query(String.class).single();

        assertThat(role).isNotEqualTo("chainpay_app");
        assertThat(tenant).isEmpty();
        // 顺带证明脱离作用域后又能看到全部账户
        assertThat(jdbc.sql("SELECT count(*) FROM account").query(Long.class).single())
                .isEqualTo(3);
    }

    // ==================================================================

    private long account(String code, Long merchantId) {
        return jdbc.sql("""
                        INSERT INTO account(code, currency, kind, merchant_id)
                        VALUES (:c, 'USDT', 'LIABILITY', :m) RETURNING id
                        """)
                .param("c", code).param("m", merchantId).query(Long.class).single();
    }
}
