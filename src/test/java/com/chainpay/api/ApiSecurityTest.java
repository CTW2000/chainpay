package com.chainpay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.api.auth.SecretCipher;
import com.chainpay.support.AbstractPostgresTest;
import com.chainpay.support.SignedRequests;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * M1 六层防护的守卫。
 *
 * <p><b>为什么必须有这个类：</b>
 *
 * <p>M1 的四步（认证、授权、错误码、签名）全部是用 curl 手工演示验证的。
 * 那些演示很有说服力，<b>但它们不会在将来自动重跑</b> ——
 * 只要有人改坏了签名逻辑、或者新加一个接口忘了走授权，没有任何东西会发现。
 *
 * <p>这与本项目已经付过两次学费的形状完全一致：
 * <b>测试的价值 = 抓 bug 的能力 × 被运行的频率。后者为 0 时前者再高也是 0。</b>
 *
 * <p>这里用真实 HTTP 请求（{@code @SpringBootTest} 起真 Tomcat +
 * {@link HttpClient}），而不是 MockMvc。原因：<b>签名覆盖 method、path、body，
 * 而 MockMvc 绕过了真实的 Servlet 容器和过滤器链</b>，
 * 用它测出来的「通过」不能证明真实请求也通过。
 * 这和我们坚持用真 Postgres 而不是 H2 是同一条理由。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("M1 · API 安全契约")
class ApiSecurityTest extends AbstractPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SecretCipher cipher;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String acmeSecret;
    private String evilSecret;
    private long acmeAccountA;
    private long acmeAccountB;
    private long evilAccount;

    @BeforeEach
    void seedMerchantsAndCredentials() {
        // 基类的 @BeforeEach 已 TRUNCATE 账本三表；商户相关的表要单独清。
        // 顺序：先删引用方（凭证），再删被引用方（商户），否则外键会拦。
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();

        long acme = createMerchant("acme", "Acme 商贸");
        long evil = createMerchant("evilco", "Evil 有限公司");

        acmeSecret = cipher.generateSecret();
        evilSecret = cipher.generateSecret();
        createCredential(acme, "ak_acme", acmeSecret);
        createCredential(evil, "ak_evilco", evilSecret);

        acmeAccountA = createOwnedAccount("user:acme-a:USDT", acme);
        acmeAccountB = createOwnedAccount("user:acme-b:USDT", acme);
        evilAccount = createOwnedAccount("user:evil:USDT", evil);

        // 注资是管理操作，走 SQL 而不是商户接口 ——
        // 商户接口做不到从平台账户注资，这本身就是「默认拒绝」的体现。
        long mint = createAccount("house:mint:USDT", "USDT", "EQUITY", true);
        seedBalance(mint, acmeAccountA, new BigDecimal("1000"));
    }

    // ==================================================================
    // 第 ① 层 · 签名验证
    // ==================================================================

    @Test
    @DisplayName("正确签名的请求放行")
    void validSignatureIsAccepted() {
        var response = signedGet(acmeSecret, "ak_acme", balancePath(acmeAccountA));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("1000");
    }

    @Test
    @DisplayName("完全没有凭证 —— 401")
    void requestWithoutCredentialsIsRejected() {
        var response = send(HttpRequest.newBuilder().uri(url(balancePath(acmeAccountA))).GET());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("★ 改请求体（金额 1 → 9999），签名不变 —— 401")
    void tamperedBodyIsRejected() {
        // 签名机制存在的头号理由：攻击者截获一个合法请求，只改金额。
        // 若签名不覆盖 body，这一步会成功。
        String honestBody = transferBody("t-1", "1", acmeAccountA, acmeAccountB);
        String tamperedBody = transferBody("t-1", "9999", acmeAccountA, acmeAccountB);

        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        String signature = sign(acmeSecret, ts, nonce, "POST", "/api/v1/transfers", honestBody);

        var response = send(HttpRequest.newBuilder()
                .uri(url("/api/v1/transfers"))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", "ak_acme")
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-SIGN", signature)                          // 对 honestBody 算的
                .POST(HttpRequest.BodyPublishers.ofString(tamperedBody)));   // 实际发 tamperedBody

        assertThat(response.statusCode()).as("签名必须覆盖 body").isEqualTo(401);
        assertThat(transferCount()).as("被拒的请求不能留下任何记录").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 拿查余额的签名去打转账接口（换 path）—— 401")
    void signatureFromAnotherPathIsRejected() {
        long ts = System.currentTimeMillis();
        String otherNonce = SignedRequests.newNonce();
        String otherPathSignature =
                sign(acmeSecret, ts, otherNonce, "GET", balancePath(acmeAccountA), "");
        String body = transferBody("t-2", "1", acmeAccountA, acmeAccountB);

        var response = send(HttpRequest.newBuilder()
                .uri(url("/api/v1/transfers"))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", "ak_acme")
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-SIGN", otherPathSignature)
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertThat(response.statusCode()).as("签名必须覆盖 path").isEqualTo(401);
    }

    @Test
    @DisplayName("★ 6 秒前的签名（超出时间窗）—— 401")
    void expiredTimestampIsRejected() {
        var response = signedGetAtTime(acmeSecret, "ak_acme", balancePath(acmeAccountA),
                System.currentTimeMillis() - 6_000);

        assertThat(response.statusCode()).as("时间窗必须挡住过期签名").isEqualTo(401);
    }

    @Test
    @DisplayName("★ 未来 6 秒的签名（预先签好等会儿用）—— 401")
    void futureTimestampIsRejected() {
        // 时间窗必须双向。只卡「过期」不卡「未来」的话，
        // 攻击者可以预先用未来时间戳签好请求，等到那一刻再发。
        var response = signedGetAtTime(acmeSecret, "ak_acme", balancePath(acmeAccountA),
                System.currentTimeMillis() + 6_000);

        assertThat(response.statusCode()).as("时间窗必须双向都卡").isEqualTo(401);
    }

    @Test
    @DisplayName("用别人的 secret 签名 —— 401")
    void signatureWithWrongSecretIsRejected() {
        var response = signedGet(evilSecret, "ak_acme", balancePath(acmeAccountA));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("不存在的 key 与签名错误，回答必须完全一致")
    void authFailuresAreIndistinguishable() {
        // 若两者回答不同，攻击者就能据此枚举出哪些 api_key 是真实存在的。
        var unknownKey = signedGet(acmeSecret, "ak_does_not_exist", balancePath(acmeAccountA));
        var wrongSecret = signedGet(evilSecret, "ak_acme", balancePath(acmeAccountA));

        assertThat(unknownKey.statusCode()).isEqualTo(wrongSecret.statusCode());
        assertThat(unknownKey.body())
                .as("响应体也必须一致，不能泄露是哪一步失败的")
                .isEqualTo(wrongSecret.body());
    }

    // ==================================================================
    // 第 ② 层 · 归属授权
    // ==================================================================

    @Test
    @DisplayName("★ evilco 用自己合法的凭证转走 acme 的钱 —— 403")
    void crossMerchantTransferIsForbidden() {
        // 这是 M1 第二步实测成功过的攻击（当时 HTTP 200，钱真的到手了）。
        // 认证这关 evilco 是光明正大过的 —— 它确实是 evilco。
        String body = transferBody("steal-1", "100", acmeAccountA, evilAccount);
        var response = signedPost(evilSecret, "ak_evilco", "/api/v1/transfers", body);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"3001\"");
        assertThat(balanceOf(acmeAccountA)).as("acme 的余额一分不能少")
                .isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("evilco 偷看 acme 的余额 —— 403")
    void crossMerchantBalanceReadIsForbidden() {
        var response = signedGet(evilSecret, "ak_evilco", balancePath(acmeAccountA));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("★ 无权访问 与 账户不存在，回答必须完全一致")
    void accessDeniedAndNotFoundAreIndistinguishable() {
        // 若两者回答不同，攻击者拿 id 挨个试就能画出系统的账户分布：
        // 回「无权访问」的是真实账户，回「不存在」的是空号。
        var othersAccount = signedGet(evilSecret, "ak_evilco", balancePath(acmeAccountA));
        var nonexistent = signedGet(evilSecret, "ak_evilco", "/api/v1/accounts/999999/balance");

        assertThat(othersAccount.statusCode()).isEqualTo(nonexistent.statusCode());
        assertThat(digitsMasked(othersAccount.body())).isEqualTo(digitsMasked(nonexistent.body()));
    }

    @Test
    @DisplayName("贷方账户也必须是自己的（防止把接口当账户探测器）")
    void creditAccountMustAlsoBeOwned() {
        // 放开贷方看似无害（你的钱爱给谁给谁），但那样一来，
        // 给任意 id 转一个极小金额、成功即说明该账户存在。
        String body = transferBody("probe-1", "1", acmeAccountA, evilAccount);
        var response = signedPost(acmeSecret, "ak_acme", "/api/v1/transfers", body);

        assertThat(response.statusCode()).isEqualTo(403);
    }

    // ==================================================================
    // 第 ③ 层 · 业务错误必须用正确的状态码
    // ==================================================================

    @Test
    @DisplayName("★ 余额不足 —— 400 而不是 500")
    void insufficientBalanceIsBadRequestNotServerError() {
        // 500 对调用方的含义是「我坏了，请重试」，实际含义是「你余额不够，别再试」。
        // 回 500 会让商户客户端无限重试一个永远不会成功的请求。
        String body = transferBody("too-much", "99999", acmeAccountA, acmeAccountB);
        var response = signedPost(acmeSecret, "ak_acme", "/api/v1/transfers", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"4001\"");
    }

    @Test
    @DisplayName("金额格式非法 —— 400，且不回显内部细节")
    void malformedAmountIsBadRequest() {
        String body = transferBody("bad-amount", "abc", acmeAccountA, acmeAccountB);
        var response = signedPost(acmeSecret, "ak_acme", "/api/v1/transfers", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .as("不能把异常消息回显给调用方")
                .doesNotContain("NumberFormatException")
                .doesNotContain("java.");
    }

    @Test
    @DisplayName("业务类型非法 —— 400，且不泄露枚举的合法值")
    void unknownTransferCodeIsBadRequest() {
        String body = transferBody("bad-code", "1", acmeAccountA, acmeAccountB)
                .replace("\"INTERNAL\"", "\"HAHA\"");
        var response = signedPost(acmeSecret, "ak_acme", "/api/v1/transfers", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .as("异常消息里含全部枚举值，回显等于免费送情报")
                .doesNotContain("WITHDRAWAL")
                .doesNotContain("DEPOSIT");
    }

    // ==================================================================
    // 正常路径 —— 防护不能把合法请求也挡住
    // ==================================================================

    @Test
    @DisplayName("acme 在自己两个账户之间转账 —— 成功且余额正确")
    void ownTransferSucceeds() {
        String body = transferBody("ok-1", "250", acmeAccountA, acmeAccountB);
        var response = signedPost(acmeSecret, "ak_acme", "/api/v1/transfers", body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(balanceOf(acmeAccountA)).isEqualByComparingTo("750");
        assertThat(balanceOf(acmeAccountB)).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("★ 原样重放 —— 被 nonce 挡住；换新 nonce 的重试 —— 被幂等键兜住")
    void replayIsBlockedAndLegitimateRetryStillWorks() {
        // ★ 这条测试在 M1.5 变了语义，值得记下来为什么 ★
        //
        // 原来的版本断言「重放返回 200，靠幂等键不重复扣款」。
        // 那时挡住损失的只有账本层，认证层是放行的 ——
        // 意味着任何一个**不经过账本**的接口（发通知、触发结算）都会中招。
        //
        // M1.5 加了签名唯一性之后，原样重放在认证层就被拒了（1002）。
        // 但这带来一个新问题：客户端超时后重试，发的就是一模一样的请求。
        // 如果它被永久拒绝，客户端就卡住了 —— 它不知道原来那笔到底成没成功。
        //
        // 答案是两个机制各管一件事：
        //   nonce  —— 防「别人」截获你的请求原样重发
        //   幂等键 —— 防「你自己」超时后重发
        // 所以客户端重试的正确姿势是：**换一个新 nonce 重新签名，保持 clientTransferId 不变**。
        String body = transferBody("replay-1", "10", acmeAccountA, acmeAccountB);
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        String signature = sign(acmeSecret, ts, nonce, "POST", "/api/v1/transfers", body);

        var first = sendSigned(ts, nonce, signature, "ak_acme", body);
        var identicalReplay = sendSigned(ts, nonce, signature, "ak_acme", body);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(identicalReplay.statusCode())
                .as("nonce 重复的重放必须在认证层就被拒，不进业务逻辑")
                .isEqualTo(401);
        assertThat(identicalReplay.body()).contains("\"code\":\"1002\"");

        // 合法重试：新 nonce、新签名，但 clientTransferId 不变
        String retryNonce = SignedRequests.newNonce();
        var legitimateRetry = sendSigned(ts, retryNonce,
                sign(acmeSecret, ts, retryNonce, "POST", "/api/v1/transfers", body),
                "ak_acme", body);

        assertThat(legitimateRetry.statusCode())
                .as("换新 nonce 的重试必须放行 —— 否则超时的客户端会永远卡住")
                .isEqualTo(200);
        assertThat(balanceOf(acmeAccountB))
                .as("而且只能到账一次 —— 这是幂等键的活")
                .isEqualByComparingTo("10");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private String balancePath(long accountId) {
        return "/api/v1/accounts/" + accountId + "/balance";
    }

    /** 两条响应里只有账户 id 不同，比较时把数字抹掉。 */
    private String digitsMasked(String body) {
        return body.replaceAll("\\d+", "N");
    }

    private String transferBody(String key, String amount, long debit, long credit) {
        return ("{\"clientTransferId\":\"%s\",\"currency\":\"USDT\",\"amount\":\"%s\","
                + "\"debitAccountId\":%d,\"creditAccountId\":%d,\"code\":\"INTERNAL\"}")
                .formatted(key, amount, debit, credit);
    }

    /**
     * 算签名。nonce 由调用方显式传入 —— <b>不在这里自动生成</b>，
     * 因为有的测试需要「同一个 nonce 发两次」来验证重放防护。
     * 自动生成会让那种测试永远绿，因为每次都是新 nonce。
     */
    private String sign(String secret, long timestamp, String nonce,
                        String method, String path, String body) {
        return SignedRequests.sign(secret, timestamp, nonce, method, path, body);
    }

    private HttpResponse<String> signedGet(String secret, String apiKey, String path) {
        return signedGetAtTime(secret, apiKey, path, System.currentTimeMillis());
    }

    private HttpResponse<String> signedGetAtTime(String secret, String apiKey, String path, long ts) {
        String nonce = SignedRequests.newNonce();
        return send(HttpRequest.newBuilder()
                .uri(url(path))
                .header("X-CP-API-KEY", apiKey)
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-NONCE", nonce)
                .header("X-CP-API-SIGN", sign(secret, ts, nonce, "GET", path, ""))
                .GET());
    }

    private HttpResponse<String> signedPost(String secret, String apiKey, String path, String body) {
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        return send(HttpRequest.newBuilder()
                .uri(url(path))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", apiKey)
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-NONCE", nonce)
                .header("X-CP-API-SIGN", sign(secret, ts, nonce, "POST", path, body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    /** nonce 显式传入：重放测试要用同一个 nonce 发两次。 */
    private HttpResponse<String> sendSigned(long ts, String nonce, String signature,
                                            String apiKey, String body) {
        return send(HttpRequest.newBuilder()
                .uri(url("/api/v1/transfers"))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", apiKey)
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-NONCE", nonce)
                .header("X-CP-API-SIGN", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 请求失败", e);
        }
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    // ---- 测试数据构造 ----

    private long createMerchant(String code, String name) {
        return jdbc.sql("INSERT INTO merchant(code,name) VALUES (:code,:name) RETURNING id")
                .param("code", code).param("name", name)
                .query(Long.class).single();
    }

    private void createCredential(long merchantId, String apiKey, String secret) {
        jdbc.sql("""
                        INSERT INTO api_credential(merchant_id, api_key, secret_encrypted)
                        VALUES (:merchantId, :apiKey, :secret)
                        """)
                .param("merchantId", merchantId)
                .param("apiKey", apiKey)
                .param("secret", cipher.encrypt(secret))
                .update();
    }

    private long createOwnedAccount(String code, long merchantId) {
        return jdbc.sql("""
                        INSERT INTO account(code, currency, kind, merchant_id)
                        VALUES (:code, 'USDT', 'LIABILITY', :merchantId)
                        RETURNING id
                        """)
                .param("code", code).param("merchantId", merchantId)
                .query(Long.class).single();
    }

    /** 直接用 SQL 注资：注资是管理操作，商户接口做不到，这本身就是「默认拒绝」的体现。 */
    private void seedBalance(long from, long to, BigDecimal amount) {
        long transferId = jdbc.sql("""
                        INSERT INTO transfer(idempotency_key, currency, amount,
                                             debit_account_id, credit_account_id, code)
                        VALUES ('seed-' || :to, 'USDT', :amount, :from, :to, 'SEED')
                        RETURNING id
                        """)
                .param("from", from).param("to", to).param("amount", amount)
                .query(Long.class).single();

        jdbc.sql("""
                        INSERT INTO entry(transfer_id, account_id, currency, amount) VALUES
                            (:t, :from, 'USDT', :negative),
                            (:t, :to,   'USDT', :positive)
                        """)
                .param("t", transferId).param("from", from).param("to", to)
                .param("negative", amount.negate()).param("positive", amount)
                .update();

        jdbc.sql("UPDATE account SET balance = balance - :amount WHERE id = :id")
                .param("amount", amount).param("id", from).update();
        jdbc.sql("UPDATE account SET balance = balance + :amount WHERE id = :id")
                .param("amount", amount).param("id", to).update();
    }

    private BigDecimal balanceOf(long accountId) {
        return jdbc.sql("SELECT balance FROM account_balance WHERE account_id = :id")
                .param("id", accountId)
                .query(BigDecimal.class).single();
    }
}
