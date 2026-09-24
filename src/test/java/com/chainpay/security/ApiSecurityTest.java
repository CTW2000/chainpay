package com.chainpay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.security.crypto.SecretCipher;
import com.chainpay.support.AbstractPostgresTest;
import com.chainpay.support.SignedRequests;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * API 安全的守卫：签名验证、拿着别人的对象 id 来碰、重放与幂等。手工演示不会在将来自动重跑，这些性质要由测试钉住。
 *
 * <p>这里用真实 HTTP 请求（{@code @SpringBootTest} 起真 Tomcat + {@link HttpClient}），而不是 MockMvc：
 * <b>签名覆盖 method、path、body，而 MockMvc 绕过了真实的 Servlet 容器和过滤器链</b>，
 * 用它测出来的「通过」不能证明真实请求也通过——和坚持用真 Postgres 而不是 H2 是同一条理由。
 *
 * <p><b>靶子必须是真实存在的路由</b>：过滤器在路由之前跑，打一条不存在的路径照样回 401，
 * 签名测试会「绿着但什么都不证明」。删接口时，打它的测试要逐条看过，不能只看红的那些。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API 安全契约")
class ApiSecurityTest extends AbstractPostgresTest {

    /** 签名层的靶子：登记幂等、无副作用、不依赖链上配置。GET 空列表也回 200。 */
    private static final String TARGET = "/api/v1/withdrawal-addresses";

    /** acme 预先登记的白名单地址（② 层里「别人的对象」）。 */
    private static final String ACME_ADDRESS = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    /** 测试自己登记用的地址。 */
    private static final String NEW_ADDRESS = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    /** 篡改请求体时换成的地址。 */
    private static final String OTHER_ADDRESS = "0xcccccccccccccccccccccccccccccccccccccccc";

    @LocalServerPort
    private int port;

    @Autowired
    private SecretCipher cipher;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String acmeSecret;
    private String evilSecret;
    private long acmeAddressId;

    @BeforeEach
    void seedMerchantsAndCredentials() {
        // 基类的 @BeforeEach 已 TRUNCATE 账本三表；商户相关的表要单独清。
        // 顺序：先删引用方（凭证、白名单），再删被引用方（商户），否则外键会拦。
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM payout_address").update();
        jdbc.sql("DELETE FROM merchant").update();

        long acme = createMerchant("acme", "Acme 商贸");
        long evil = createMerchant("evilco", "Evil 有限公司");

        acmeSecret = cipher.generateSecret();
        evilSecret = cipher.generateSecret();
        createCredential(acme, "ak_acme", acmeSecret);
        createCredential(evil, "ak_evilco", evilSecret);

        // 白名单那一行用属主连接写：这张表有 RLS，商户接口只能登记自己的，
        // 而这里要的正是「一条属于 acme 的行，供 evilco 去碰」。
        acmeAddressId = jdbc.sql("""
                        INSERT INTO payout_address(merchant_id, address, label)
                        VALUES (:m, :a, 'acme 的提现地址') RETURNING id
                        """)
                .param("m", acme).param("a", ACME_ADDRESS)
                .query(Long.class).single();
    }

    /**
     * 谁创建谁清理：白名单表不在基类的 TRUNCATE 里，而它指向 merchant——留着它，
     * 别的测试类 DELETE FROM merchant 时会撞外键，那边的断言跟着红。
     */
    @AfterEach
    void removeWhitelistRows() {
        jdbc.sql("DELETE FROM payout_address").update();
    }

    // ==================================================================
    // 第 ① 层 · 签名验证
    // ==================================================================

    @Test
    @DisplayName("正确签名的请求放行")
    void validSignatureIsAccepted() {
        var response = signedGet(acmeSecret, "ak_acme", TARGET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).as("看到的是自己那一条").contains("\"status\":\"ACTIVE\"");
    }

    @Test
    @DisplayName("完全没有凭证 —— 401")
    void requestWithoutCredentialsIsRejected() {
        var response = send(HttpRequest.newBuilder().uri(url(TARGET)).GET());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("★ 改请求体（换一个地址），签名不变 —— 401")
    void tamperedBodyIsRejected() {
        // 签名机制存在的头号理由：攻击者截获一个合法请求，只改内容。
        // 若签名不覆盖 body，这一步会成功——进白名单的就成了攻击者的地址。
        String honestBody = addressBody(NEW_ADDRESS, "honest");
        String tamperedBody = addressBody(OTHER_ADDRESS, "honest");

        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        String signature = sign(acmeSecret, ts, nonce, "POST", TARGET, honestBody);

        var response = send(HttpRequest.newBuilder()
                .uri(url(TARGET))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", "ak_acme")
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-NONCE", nonce)
                .header("X-CP-API-SIGN", signature)                          // 对 honestBody 算的
                .POST(HttpRequest.BodyPublishers.ofString(tamperedBody)));   // 实际发 tamperedBody

        // ★ 小心空转：除了 body，其余一切（包括 X-CP-API-NONCE 头）都必须和签名时一致。
        // 漏发一个头，签名会因为别的原因对不上，把 body 改回原样也照样 401——摘掉 body 的覆盖这条也绿。
        assertThat(response.statusCode()).as("签名必须覆盖 body").isEqualTo(401);
        assertThat(addressCount()).as("被拒的请求不能留下任何记录（只剩 seed 那一条）").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 拿另一个路径的签名来打这个接口 —— 401")
    void signatureFromAnotherPathIsRejected() {
        long ts = System.currentTimeMillis();
        String otherNonce = SignedRequests.newNonce();
        String body = addressBody(NEW_ADDRESS, "x");
        // 方法与请求体都一样，只有 path 不同
        String otherPathSignature = sign(acmeSecret, ts, otherNonce, "POST", "/api/v1/withdrawals", body);

        var response = send(HttpRequest.newBuilder()
                .uri(url(TARGET))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", "ak_acme")
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-NONCE", otherNonce)
                .header("X-CP-API-SIGN", otherPathSignature)
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertThat(response.statusCode()).as("签名必须覆盖 path").isEqualTo(401);
        assertThat(addressCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 6 秒前的签名（超出时间窗）—— 401")
    void expiredTimestampIsRejected() {
        var response = signedGetAtTime(acmeSecret, "ak_acme", TARGET,
                System.currentTimeMillis() - 6_000);

        assertThat(response.statusCode()).as("时间窗必须挡住过期签名").isEqualTo(401);
    }

    @Test
    @DisplayName("★ 未来 6 秒的签名（预先签好等会儿用）—— 401")
    void futureTimestampIsRejected() {
        // 时间窗必须双向。只卡「过期」不卡「未来」的话，
        // 攻击者可以预先用未来时间戳签好请求，等到那一刻再发。
        var response = signedGetAtTime(acmeSecret, "ak_acme", TARGET,
                System.currentTimeMillis() + 6_000);

        assertThat(response.statusCode()).as("时间窗必须双向都卡").isEqualTo(401);
    }

    @Test
    @DisplayName("用别人的 secret 签名 —— 401")
    void signatureWithWrongSecretIsRejected() {
        var response = signedGet(evilSecret, "ak_acme", TARGET);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("不存在的 key 与签名错误，回答必须完全一致")
    void authFailuresAreIndistinguishable() {
        // 若两者回答不同，攻击者就能据此枚举出哪些 api_key 是真实存在的。
        var unknownKey = signedGet(acmeSecret, "ak_does_not_exist", TARGET);
        var wrongSecret = signedGet(evilSecret, "ak_acme", TARGET);

        assertThat(unknownKey.statusCode()).isEqualTo(wrongSecret.statusCode());
        assertThat(unknownKey.body())
                .as("响应体也必须一致，不能泄露是哪一步失败的")
                .isEqualTo(wrongSecret.body());
    }

    // ==================================================================
    // 第 ② 层 · 拿着别人的对象 id 来碰（商户接口唯一还收 id 的地方）
    // ==================================================================

    @Test
    @DisplayName("★ evilco 用自己合法的凭证停用 acme 的白名单地址 —— 拒绝，且那一行没动")
    void evilcoCannotDisableAcmesWhitelistAddress() {
        // 认证这关 evilco 是光明正大过的，它确实是 evilco；要挡住的是「合法身份 + 别人的对象 id」。
        // 挡住它的是 RLS：UPDATE payout_address … WHERE id = :id 跑在商户连接上，别人的行不在可见范围里，
        // 改了 0 行 → 服务判为「白名单里没有这个地址」。
        var response = signedPost(evilSecret, "ak_evilco", TARGET + "/" + acmeAddressId + "/disable", "");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"2009\"");
        assertThat(statusOf(acmeAddressId)).as("acme 的白名单地址必须一点没动").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("★ 别人的 id 与不存在的 id，回答必须完全一致")
    void foreignIdAndNonexistentIdAnswerTheSame() {
        // 若两者回答不同，攻击者拿 id 挨个试就能画出系统里有哪些对象：
        // 回「不是你的」的是真实存在的行，回「不存在」的是空号。
        var othersRow = signedPost(evilSecret, "ak_evilco", TARGET + "/" + acmeAddressId + "/disable", "");
        var nonexistent = signedPost(evilSecret, "ak_evilco", TARGET + "/999999/disable", "");

        assertThat(othersRow.statusCode()).isEqualTo(nonexistent.statusCode());
        assertThat(digitsMasked(othersRow.body())).isEqualTo(digitsMasked(nonexistent.body()));
    }

    // ==================================================================
    // 第 ③ 层 · 重放与幂等
    // ==================================================================

    @Test
    @DisplayName("★ 原样重放 —— 被 nonce 挡住；换新 nonce 的重试 —— 被接口的幂等兜住")
    void replayIsBlockedAndLegitimateRetryStillWorks() {
        // 两个机制各管一件事：
        //   nonce —— 防「别人」截获你的请求原样重发：在认证层就拒（1002）。只靠业务幂等的话，
        //            任何不经过账本的接口（发通知、触发结算）都会中招
        //   幂等  —— 防「你自己」超时后重发：客户端不知道原来那笔成没成功，重试必须能过
        // 所以客户端重试的正确姿势是：换一个新 nonce 重新签名，请求体保持不变。
        String body = addressBody(NEW_ADDRESS, "replay");
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        String signature = sign(acmeSecret, ts, nonce, "POST", TARGET, body);

        var first = sendSignedPost(TARGET, ts, nonce, signature, "ak_acme", body);
        var identicalReplay = sendSignedPost(TARGET, ts, nonce, signature, "ak_acme", body);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(identicalReplay.statusCode())
                .as("nonce 重复的重放必须在认证层就被拒，不进业务逻辑")
                .isEqualTo(401);
        assertThat(identicalReplay.body()).contains("\"code\":\"1002\"");

        // 合法重试：新 nonce、新签名，请求体不变
        String retryNonce = SignedRequests.newNonce();
        var legitimateRetry = sendSignedPost(TARGET, ts, retryNonce,
                sign(acmeSecret, ts, retryNonce, "POST", TARGET, body),
                "ak_acme", body);

        assertThat(legitimateRetry.statusCode())
                .as("换新 nonce 的重试必须放行 —— 否则超时的客户端会永远卡住")
                .isEqualTo(200);
        assertThat(addressCount())
                .as("而且只登记一条 —— 这是接口幂等的活（seed 那条 + 这条）")
                .isEqualTo(2);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private String addressBody(String address, String label) {
        return "{\"address\":\"%s\",\"label\":\"%s\"}".formatted(address, label);
    }

    /** 两条响应里只有 id 不同，比较时把数字抹掉。 */
    private String digitsMasked(String body) {
        return body.replaceAll("\\d+", "N");
    }

    private long addressCount() {
        return jdbc.sql("SELECT count(*) FROM payout_address").query(Long.class).single();
    }

    private String statusOf(long addressId) {
        return jdbc.sql("SELECT status FROM payout_address WHERE id = :id")
                .param("id", addressId).query(String.class).single();
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
        return sendSignedPost(path, ts, nonce, sign(secret, ts, nonce, "POST", path, body), apiKey, body);
    }

    /** nonce 与签名显式传入：重放测试要用同一个 nonce 发两次。 */
    private HttpResponse<String> sendSignedPost(String path, long ts, String nonce, String signature,
                                                String apiKey, String body) {
        return send(HttpRequest.newBuilder()
                .uri(url(path))
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
}
