package com.chainpay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.security.service.TenantScope;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
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

    /** 应用侧的账本 bean（普通角色 chainpay_app）：幂等键那条测试要以商户身份真的记一笔。 */
    @Autowired
    private LedgerService ledger;

    /**
     * <b>应用自己的</b>连接——和基类里做 seed 的 {@code jdbc}（属主）不是同一个。
     * 「以应用的身份跑 SQL 会看到什么」只能拿这一个来问。
     */
    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient appJdbc;

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
                appJdbc.sql("SELECT id FROM account ORDER BY id").query(Long.class).list());

        assertThat(visible)
                .as("只能看到自己的账户；别人的和平台自有的都不该出现")
                .containsExactly(acmeAccount);
    }

    @Test
    @DisplayName("★ 点名查别人的账户 —— 返回 0 行，不是报错")
    void namingSomeoneElsesAccountReturnsNothing() {
        var found = tenantScope.asMerchant(acmeId, () ->
                appJdbc.sql("SELECT id FROM account WHERE id = :id")
                        .param("id", evilcoAccount).query(Long.class).optional());

        // 「不存在」和「无权访问」给同一个回答，攻击者无法用它枚举账户 ——
        // 这正是 AccountAccessService 刻意做到的「不可区分响应」，
        // 而 RLS 直接把它变成了数据库的天然行为，不需要谁记得实现它。
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("★ 根本没调 asMerchant —— 什么都查不到，而不是什么都查得到")
    void forgettingTheTenantScopeEntirelySeesNothing() {
        // ★ 这条测试在 2026-08-31 的质询扫描后重写，值得记下来为什么 ★
        //
        // 第一版在 asMerchant **里面**清掉租户变量，模拟的是「降了权但没设变量」。
        // 那个状态在生产里构造不出来：enterTenantScope 的两行同生同死。
        // 真实的失误形态是「整个 asMerchant 都没调」——而第一版一次都没测过它。
        //
        // 更糟的是，当时应用以超级用户连库，「没调 asMerchant」的真实后果是
        // **看到全库**（超级用户无条件绕过 RLS），和 TenantScope 的 javadoc
        // 写的「一行都查不到，立刻炸」恰好相反。实测：acme 拿到了 evilco 的账户。
        //
        // 现在应用以普通角色 chainpay_app 连库，RLS 对它无条件生效。
        // 没调 asMerchant → 租户变量没设 → current_merchant_id() 为 NULL →
        // merchant_id = NULL 恒为假 → 一行都看不到。**失误方向终于朝着立刻暴露。**
        //
        // 这是清单 5.4 的原型：唯一能区分对错的输入是「根本不调」，第一版不在集合里。
        var visible = appJdbc.sql("SELECT id FROM account").query(Long.class).list();

        assertThat(visible)
                .as("没有租户作用域的查询必须一无所获——这是 RLS 存在的全部意义")
                .isEmpty();
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
            long transferId = appJdbc.sql("""
                            INSERT INTO transfer(idempotency_key, currency, amount,
                                                 debit_account_id, credit_account_id, code)
                            VALUES ('attack-1','USDT',1,:d,:c,'ADJUSTMENT') RETURNING id
                            """)
                    .param("d", acmeAccount).param("c", evilcoAccount)
                    .query(Long.class).single();

            return appJdbc.sql("""
                            INSERT INTO entry(transfer_id, account_id, currency, amount)
                            VALUES (:t, :a, 'USDT', 1)
                            """)
                    .param("t", transferId).param("a", evilcoAccount)   // ← 别人的账户
                    .update();
        })).hasStackTraceContaining("row-level security policy");
    }

    @Test
    @DisplayName("★ 把自己的账户改成别人的 —— 两道都拦：先是列权限（V29），把权限给回去还有 RLS 的 WITH CHECK")
    void cannotGiveAwayAnAccountByChangingItsOwner() {
        // 只写 USING 不写 WITH CHECK 的话，这条 UPDATE 会成功 ——
        // 商户可以一次性把账户「送」给别人（或者把别人的账户认领过来）。
        //
        // 2026-09-22（V29）之后它连跑都跑不起来：应用角色对 account 只剩 UPDATE(balance)，
        // 而权限检查在行级安全之前，所以报的是 permission denied，不再是 row-level security policy。
        //
        // **两层都要断言**：只断言权限的话，V6 那道 WITH CHECK 就没人守了——哪天有人给 account
        // 重新 GRANT 整行 UPDATE（比如为了让某个新功能能改别的列），这条测试照样绿，
        // 而「把账户送给别人」又成了可能。这是删接口那天踩到的空转的另一种形态。
        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, this::giveAwayTheAccount))
                .as("V29：应用角色没有 account.merchant_id 的 UPDATE 权限")
                .hasStackTraceContaining("permission denied");

        jdbc.sql("GRANT UPDATE (merchant_id) ON account TO chainpay_app").update();
        try {
            assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, this::giveAwayTheAccount))
                    .as("就算把列权限给回来，行级安全的 WITH CHECK 仍然拦住")
                    .hasStackTraceContaining("row-level security policy");
        } finally {
            jdbc.sql("REVOKE UPDATE (merchant_id) ON account FROM chainpay_app").update();
        }
    }

    private int giveAwayTheAccount() {
        return appJdbc.sql("UPDATE account SET merchant_id = :other WHERE id = :id")
                .param("other", evilcoId).param("id", acmeAccount)
                .update();
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
        //
        // 2026-09-22（V29）也不再改 code：应用角色对 account 只剩 UPDATE(balance)，
        // 改别的列要属主身份。所以「自己的账户照常读写」里那个「写」，
        // 换成应用角色真正会做的那一种——在自己名下插一个账户（RLS 的 WITH CHECK 只允许挂在自己名下），
        // 再读回来。余额那条路由账本的测试覆盖：它改 balance 时同时写分录，判官才平。
        var result = tenantScope.asMerchant(acmeId, () -> {
            appJdbc.sql("""
                            INSERT INTO account(code, currency, kind, merchant_id)
                            VALUES ('user:acme:USDT:2', 'USDT', 'LIABILITY', :m)
                            """)
                    .param("m", acmeId).update();
            return appJdbc.sql("SELECT code FROM account WHERE code = 'user:acme:USDT:2'")
                    .query(String.class).single();
        });

        assertThat(result).isEqualTo("user:acme:USDT:2");
    }

    @Test
    @DisplayName("★ 事务结束后租户变量必须复位 —— 否则连接池会串号")
    void theTenantContextDoesNotLeakBackIntoThePool() {
        tenantScope.asMerchant(acmeId, () -> appJdbc.sql("SELECT 1").query(Integer.class).single());

        // 出了作用域之后，同一个池子里的连接必须已经清掉租户变量。
        // 用 SET 而不是 SET LOCAL 的话，这里会读到 acme 的 id——
        // 下一个借到这条连接的请求就继承了 acme 的租户上下文。
        String tenant = appJdbc.sql(
                "SELECT coalesce(current_setting('chainpay.merchant_id', true),'')")
                .query(String.class).single();
        assertThat(tenant).isEmpty();

        // ★ 第一版这里断言「脱离作用域后又能看到全部账户 = 3」★
        // 那是把真实的失效形态（超级用户绕过 RLS）当成正常行为钉了下来。
        // 现在的正确断言：脱离作用域后**一行都看不到**，和上一条测试同一个方向。
        assertThat(appJdbc.sql("SELECT count(*) FROM account").query(Long.class).single())
                .as("脱离作用域后不该看到任何账户——看得到就说明连接是特权角色")
                .isZero();
    }

    // ==================================================================
    // 幂等键的作用域（2026-09-22 从 ApiSecurityTest 下移到这里）
    // ==================================================================

    @Test
    @DisplayName("★ 两个商户各用同一个幂等键 —— 各自成一笔，互不干扰")
    void theIdempotencyKeyNamespaceIsPerMerchant() {
        // 历史（M1.5 质询扫描 9.8）：V1 那行 UNIQUE (idempotency_key) 的作用域是**全表**。
        // evilco 用了 acme 用过的键 → UNIQUE 冲突 → ON CONFLICT DO NOTHING 返回空 →
        // 回读那一笔又被 RLS 藏住 → 0 行 → .single() 炸 → 500，而 9001 还标着「可重试」。
        // 两个后果：① 跨租户的存在性预言机（看 200 还是 500 就能探出别人用过哪些键）；
        //          ② 可抢占——预先占掉受害者要用的键，让他永久拿 500。
        // 修法是 V7：UNIQUE NULLS NOT DISTINCT (submitter_merchant_id, idempotency_key)，
        // 而 submitter_merchant_id 由 SQL 里的 current_merchant_id() 填——「谁提交的」由租户变量说，
        // 不由调用方的参数说。NULLS NOT DISTINCT 是为了系统身份（商户为 NULL）之间也不许撞。
        //
        // 这条性质原先由 ApiSecurityTest 经通用转账接口守着；接口 2026-09-22 删了，
        // 而约束本身在数据库，所以测试下移到这里，夹具也便宜（不必起 HTTP、不必发签名请求）。
        long acmeTo = account("user:acme-2:USDT", acmeId);
        long evilcoTo = account("user:evilco-2:USDT", evilcoId);
        // 借方允许为负：这条测试只关心幂等键的作用域，不关心余额，免得再搭一套注资夹具
        long acmeFrom = overdraftAccount("user:acme-src:USDT", acmeId);
        long evilcoFrom = overdraftAccount("user:evilco-src:USDT", evilcoId);

        long acmeTransfer = tenantScope.asMerchant(acmeId, () ->
                ledger.transfer(new TransferCommand("order-1", "USDT", new BigDecimal("1"),
                        acmeFrom, acmeTo, TransferCode.INTERNAL, null)));
        long evilcoTransfer = tenantScope.asMerchant(evilcoId, () ->
                ledger.transfer(new TransferCommand("order-1", "USDT", new BigDecimal("1"),
                        evilcoFrom, evilcoTo, TransferCode.INTERNAL, null)));

        assertThat(evilcoTransfer)
                .as("同一个键在另一个商户名下必须是全新的一笔，不能撞到 acme 那笔")
                .isNotEqualTo(acmeTransfer);
        assertThat(transferCount()).as("两笔都真的落库了").isEqualTo(2);
    }

    // ==================================================================

    private long overdraftAccount(String code, long merchantId) {
        return jdbc.sql("""
                        INSERT INTO account(code, currency, kind, merchant_id, allow_negative)
                        VALUES (:c, 'USDT', 'LIABILITY', :m, TRUE) RETURNING id
                        """)
                .param("c", code).param("m", merchantId).query(Long.class).single();
    }

    private long account(String code, Long merchantId) {
        return jdbc.sql("""
                        INSERT INTO account(code, currency, kind, merchant_id)
                        VALUES (:c, 'USDT', 'LIABILITY', :m) RETURNING id
                        """)
                .param("c", code).param("m", merchantId).query(Long.class).single();
    }
}
