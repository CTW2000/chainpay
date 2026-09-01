package com.chainpay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.api.admin.AdminAuthFilter;
import com.chainpay.api.admin.AdminService.IssuedCredential;
import com.chainpay.api.auth.ApiCredentialService;
import com.chainpay.support.AbstractPostgresTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 发放凭证的守卫。
 *
 * <p><b>这一组测试守的是「控制面 / 数据面分离」这条分界线：</b>
 *
 * <pre>
 *   数据面  /api/...    商户日常调用   HMAC 签名认证
 *   控制面  /admin/...  开户、发钥匙   管理员令牌 + 回环地址
 * </pre>
 *
 * <p>为什么必须分开：如果商户能用自己的凭证去发新凭证，
 * 那么一把泄露的钥匙就能自我繁殖 —— 商户吊销了泄露的那把，
 * 攻击者手上新配的那把还活着。<b>能配钥匙的钥匙，吊销不掉。</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("M1 · 发放凭证契约")
class AdminCredentialTest extends AbstractPostgresTest {

    /** 与 src/test/resources/application.yml 里的测试令牌一致。 */
    private static final String ADMIN_TOKEN = "chainpay-test-admin-token-not-for-prod";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void clearMerchants() {
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();
    }

    // ==================================================================
    // 谁能调这个接口
    // ==================================================================

    @Test
    @DisplayName("★ 没有管理员令牌 —— 401，且什么都没创建")
    void withoutAdminTokenNothingIsCreated() {
        var response = post("/admin/v1/merchants", """
                {"code":"acme","name":"Acme"}""", null);

        assertThat(response.statusCode()).isEqualTo(401);
        // 关键：不只看状态码，还要确认真的没写进去。
        // 「返回了 401 但其实已经创建了」是最坏的一种失败。
        assertThat(merchantCount()).isZero();
    }

    @Test
    @DisplayName("★ 令牌错误 —— 401，且什么都没创建")
    void wrongAdminTokenIsRejected() {
        var response = post("/admin/v1/merchants", """
                {"code":"acme","name":"Acme"}""", "wrong-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(merchantCount()).isZero();
    }

    @Test
    @DisplayName("★ 带 X-Forwarded-For —— 拒绝，因为它说明请求经过了代理")
    void forwardedRequestsAreRejectedEvenWithCorrectToken() {
        // ★ 这条防的是一个很隐蔽的洞 ★
        // 管理接口靠「只能从本机调用」保护。但 nginx 通常和应用跑在同一台机器上，
        // 于是**所有**经过 nginx 的公网请求，getRemoteAddr() 都是 127.0.0.1。
        // 回环检查在这种部署下形同虚设。
        //
        // X-Forwarded-For 的存在恰好证明「这个请求被转发过」，
        // 所以在管理接口上它不是身份信息，而是**拒绝的理由**。
        var response = send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/admin/v1/merchants"))
                .header("Content-Type", "application/json")
                .header(AdminAuthFilter.HEADER_ADMIN_TOKEN, ADMIN_TOKEN)
                .header("X-Forwarded-For", "203.0.113.7")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"code":"acme","name":"Acme"}""")));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(merchantCount()).isZero();
    }

    @Test
    @DisplayName("★ 商户凭证不能用来调管理接口 —— 防止一把钥匙配出另一把钥匙")
    void merchantCredentialsCannotReachAdminApi() {
        long merchantId = createMerchant("acme", "Acme");
        var issued = issueCredential(merchantId, "primary");

        // 拿商户自己合法的凭证，去调管理接口发新凭证
        String path = "/admin/v1/merchants/" + merchantId + "/credentials";
        String body = "{\"label\":\"x\"}";
        long ts = System.currentTimeMillis();
        var response = send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", issued.apiKey())
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-SIGN",
                        ApiCredentialService.sign(ts + "POST" + path + body, issued.secret()))
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertThat(response.statusCode())
                .as("合法的商户凭证在控制面上必须无效")
                .isEqualTo(401);
        assertThat(credentialCount(merchantId))
                .as("必须还是只有最初那一把")
                .isEqualTo(1);
    }

    // ==================================================================
    // 创建商户
    // ==================================================================

    @Test
    @DisplayName("重复的商户 code —— 409，不是 500")
    void duplicateMerchantCodeReturns409() {
        assertThat(post("/admin/v1/merchants", """
                {"code":"acme","name":"Acme"}""", ADMIN_TOKEN).statusCode()).isEqualTo(201);

        var duplicate = post("/admin/v1/merchants", """
                {"code":"acme","name":"Acme Again"}""", ADMIN_TOKEN);

        // 500 会把 Postgres 的原始报错(含表名、约束名)吐给调用方。
        // 409 是「你要建的东西已经存在」的标准答案。
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body())
                .as("响应体里不能出现数据库内部细节")
                .doesNotContain("merchant_code_uk")
                .doesNotContain("constraint");
        assertThat(merchantCount()).isEqualTo(1);
    }

    // ==================================================================
    // 发放凭证
    // ==================================================================

    @Test
    @DisplayName("★ 发放的凭证真的能用来调数据面 API")
    void issuedCredentialActuallyWorks() {
        // 端到端：这一条如果绿，说明「发凭证」和「用凭证」两端对得上。
        // 分开测两边各自都对、合起来不通，是集成层最常见的失败。
        long merchantId = createMerchant("acme", "Acme");
        var issued = issueCredential(merchantId, "primary");
        long accountId = createMerchantAccount(merchantId, "user:acme:USDT");

        assertThat(signedBalanceGet(accountId, issued).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("★ secret 明文只在发放的那一次响应里出现，之后再也查不到")
    void secretIsReturnedExactlyOnce() {
        long merchantId = createMerchant("acme", "Acme");
        var issued = issueCredential(merchantId, "primary");

        assertThat(issued.secret()).as("发放时必须返回明文").isNotBlank();

        var listed = get("/admin/v1/merchants/" + merchantId + "/credentials", ADMIN_TOKEN);

        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body())
                .as("列表里必须能看到这把 key（否则幽灵凭证无法被发现）")
                .contains(issued.apiKey());
        assertThat(listed.body())
                .as("★ 但绝不能再出现明文 secret ★")
                .doesNotContain(issued.secret());
    }

    @Test
    @DisplayName("★ 一个商户可以同时持有多把有效凭证 —— 零停机轮换的前提")
    void aMerchantCanHoldSeveralActiveCredentials() {
        // 如果表结构限制「一个商户一把钥匙」，轮换就只能是
        // 「先作废旧的、再发新的」—— 中间那段时间商户的线上业务全挂。
        long merchantId = createMerchant("acme", "Acme");
        long accountId = createMerchantAccount(merchantId, "user:acme:USDT");

        var oldKey = issueCredential(merchantId, "old");
        var newKey = issueCredential(merchantId, "new");

        assertThat(signedBalanceGet(accountId, oldKey).statusCode()).isEqualTo(200);
        assertThat(signedBalanceGet(accountId, newKey).statusCode()).isEqualTo(200);
    }

    // ==================================================================
    // 关掉
    // ==================================================================

    @Test
    @DisplayName("★ 吊销一把凭证 —— 那把立刻失效，同商户的另一把不受影响")
    void revokingOneCredentialLeavesTheOthersAlive() {
        long merchantId = createMerchant("acme", "Acme");
        long accountId = createMerchantAccount(merchantId, "user:acme:USDT");
        var oldKey = issueCredential(merchantId, "old");
        var newKey = issueCredential(merchantId, "new");

        var revoke = post("/admin/v1/credentials/" + oldKey.credentialId() + "/revoke",
                "", ADMIN_TOKEN);
        assertThat(revoke.statusCode()).isEqualTo(204);

        assertThat(signedBalanceGet(accountId, oldKey).statusCode())
                .as("被吊销的那把必须立刻失效")
                .isEqualTo(401);
        assertThat(signedBalanceGet(accountId, newKey).statusCode())
                .as("同商户的另一把不受影响 —— 这正是零停机轮换")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("★ 停用商户 —— 他名下所有凭证同时失效")
    void suspendingAMerchantKillsEveryCredentialAtOnce() {
        long merchantId = createMerchant("acme", "Acme");
        long accountId = createMerchantAccount(merchantId, "user:acme:USDT");
        var first = issueCredential(merchantId, "first");
        var second = issueCredential(merchantId, "second");

        var suspend = post("/admin/v1/merchants/" + merchantId + "/suspend", "", ADMIN_TOKEN);
        assertThat(suspend.statusCode()).isEqualTo(204);

        assertThat(signedBalanceGet(accountId, first).statusCode()).isEqualTo(401);
        assertThat(signedBalanceGet(accountId, second).statusCode()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM api_credential WHERE merchant_id = :m")
                .param("m", merchantId).query(Long.class).single())
                .as("停用不能靠删凭证实现 —— 删了就查不出这把 key 曾属于谁，审计断链")
                .isEqualTo(2);
    }

    // ==================================================================
    // 明文别泄进日志
    // ==================================================================

    @Test
    @DisplayName("★ 凭证对象的 toString() 不含明文 —— 防 log.info(result) 把密钥打进日志")
    void issuedCredentialToStringHidesTheSecret() {
        // Java 的 record 会**自动生成包含全部字段的 toString()**。
        // 于是一句看起来人畜无害的 log.info("发放成功: {}", result)
        // 就把密钥永久写进了日志文件 —— 写这行的人根本没意识到。
        //
        // 让「误用的默认行为」是安全的，比要求每个人都记得别打日志可靠得多。
        var record = new IssuedCredential(7L, "ak_visible", "SUPER-SECRET-PLAINTEXT");

        assertThat(record.toString())
                .contains("ak_visible")
                .doesNotContain("SUPER-SECRET-PLAINTEXT");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private long createMerchant(String code, String name) {
        var response = post("/admin/v1/merchants",
                "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}", ADMIN_TOKEN);
        assertThat(response.statusCode()).isEqualTo(201);
        return jdbc.sql("SELECT id FROM merchant WHERE code = :c")
                .param("c", code).query(Long.class).single();
    }

    private IssuedCredential issueCredential(long merchantId, String label) {
        var response = post("/admin/v1/merchants/" + merchantId + "/credentials",
                "{\"label\":\"" + label + "\"}", ADMIN_TOKEN);
        assertThat(response.statusCode()).isEqualTo(201);
        return new IssuedCredential(
                Long.parseLong(jsonField(response.body(), "credentialId")),
                jsonField(response.body(), "apiKey"),
                jsonField(response.body(), "secret"));
    }

    private long createMerchantAccount(long merchantId, String code) {
        return jdbc.sql("""
                        INSERT INTO account(code, currency, kind, merchant_id)
                        VALUES (:c, 'USDT', 'LIABILITY', :m) RETURNING id
                        """)
                .param("c", code).param("m", merchantId).query(Long.class).single();
    }

    private HttpResponse<String> signedBalanceGet(long accountId, IssuedCredential credential) {
        String path = "/api/v1/accounts/" + accountId + "/balance";
        long ts = System.currentTimeMillis();
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-CP-API-KEY", credential.apiKey())
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-SIGN",
                        ApiCredentialService.sign(ts + "GET" + path, credential.secret()))
                .GET());
    }

    private long merchantCount() {
        return jdbc.sql("SELECT COUNT(*) FROM merchant").query(Long.class).single();
    }

    private long credentialCount(long merchantId) {
        return jdbc.sql("SELECT COUNT(*) FROM api_credential WHERE merchant_id = :m")
                .param("m", merchantId).query(Long.class).single();
    }

    /** 极简 JSON 取值。测试里够用，不值得为此引入解析库。 */
    private static String jsonField(String json, String field) {
        String needle = "\"" + field + "\":";
        int start = json.indexOf(needle) + needle.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) {
            start++;
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != ','
                && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    private HttpResponse<String> post(String path, String body, String adminToken) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (adminToken != null) {
            builder.header(AdminAuthFilter.HEADER_ADMIN_TOKEN, adminToken);
        }
        return send(builder);
    }

    private HttpResponse<String> get(String path, String adminToken) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (adminToken != null) {
            builder.header(AdminAuthFilter.HEADER_ADMIN_TOKEN, adminToken);
        }
        return send(builder);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 请求失败", e);
        }
    }
}
