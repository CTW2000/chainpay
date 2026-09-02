package com.chainpay.common.web;

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
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 对外契约的守卫：响应信封、分段错误码、重放防护。
 *
 * <p>这三样都是<b>契约</b>——一旦有客户端接了就很难再改。
 * 所以它们的形状必须被测试钉死，而不是靠"大家记得照着写"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("M1.5 · 对外契约")
class ApiContractTest extends AbstractPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SecretCipher cipher;

    @Autowired
    private ErrorResponseWriter errorWriter;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private String secret;
    private long accountId;
    private long otherAccountId;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();

        long acme = jdbc.sql("INSERT INTO merchant(code,name) VALUES ('acme','Acme') RETURNING id")
                .query(Long.class).single();
        secret = cipher.generateSecret();
        jdbc.sql("""
                        INSERT INTO api_credential(merchant_id, api_key, secret_encrypted)
                        VALUES (:m,'ak_acme',:s)
                        """)
                .param("m", acme).param("s", cipher.encrypt(secret)).update();

        accountId = jdbc.sql("""
                        INSERT INTO account(code,currency,kind,merchant_id)
                        VALUES ('user:acme:USDT','USDT','LIABILITY',:m) RETURNING id
                        """).param("m", acme).query(Long.class).single();
        otherAccountId = jdbc.sql("""
                        INSERT INTO account(code,currency,kind,merchant_id)
                        VALUES ('fee:acme:USDT','USDT','LIABILITY',:m) RETURNING id
                        """).param("m", acme).query(Long.class).single();
    }

    // ==================================================================
    // 信封
    // ==================================================================

    @Test
    @DisplayName("★ 成功响应是信封：code=0 / msg 空 / data 带业务数据")
    void successIsWrappedInTheEnvelope() {
        var response = signedGet("/api/v1/accounts/" + accountId + "/balance");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"code\":\"0\"")
                .contains("\"msg\":\"\"")
                .contains("\"data\":");
        // data 里才是业务数据，不是平铺在顶层
        assertThat(response.body()).contains("\"balance\"");
    }

    @Test
    @DisplayName("★ 失败响应是同一个信封形状，data 为 null")
    void failureUsesTheSameEnvelope() {
        // 未授权的账户
        long foreign = jdbc.sql("""
                        INSERT INTO account(code,currency,kind) VALUES ('house:x','USDT','ASSET')
                        RETURNING id""").query(Long.class).single();

        var response = signedGet("/api/v1/accounts/" + foreign + "/balance");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .as("客户端必须能用同一套逻辑解析成功和失败")
                .contains("\"code\":\"3001\"")
                .contains("\"msg\":")
                .contains("\"data\":null");
    }

    @Test
    @DisplayName("★ 过滤器写的响应也必须是信封 —— 否则形状会在两个地方分叉")
    void filterWrittenResponsesUseTheEnvelopeToo() {
        // 过滤器在 Spring 的消息转换器之前执行，拿不到 Jackson，只能手写 JSON。
        // 最容易出现"控制器一种形状、过滤器另一种形状"的地方就是这里。
        var response = send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/accounts/1/balance"))
                .GET());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body())
                .contains("\"code\":\"1001\"")
                .contains("\"data\":null");
    }

    // ==================================================================
    // 分段错误码
    // ==================================================================

    @Test
    @DisplayName("★ 段位与可重试性必须一致 —— 只有 5xxx 可以原样重试")
    void onlyTheRateLimitSegmentIsRetryable() {
        // 这条把「第一位数字就是重试策略」从注释里的约定，
        // 变成一个加错码就会失败的检查。
        // 客户端靠这条约定处理它**没见过**的新错误码；
        // 约定一旦被破坏，客户端会以错误的方式重试，而我们不会收到任何信号。
        for (ErrorCode code : EnumSet.allOf(ErrorCode.class)) {
            boolean segmentSaysRetryable = code.segment() == '5' || code.segment() == '9';
            assertThat(code.retryable())
                    .as("%s(%s) 的段位说%s可重试，但标注是 %s",
                            code, code.code(), segmentSaysRetryable ? "" : "不", code.retryable())
                    .isEqualTo(segmentSaysRetryable);
        }
    }

    @Test
    @DisplayName("★ 错误码不能重复 —— 两个原因用同一个码，客户端就分不开了")
    void everyErrorCodeIsUnique() {
        var codes = EnumSet.allOf(ErrorCode.class).stream().map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("★ 业务拒绝落在 4xxx，参数错落在 2xxx —— 客户端据此决定改什么")
    void businessAndRequestErrorsLandInDifferentSegments() {
        // 余额不足：请求本身没错，是账户状态不允许 → 4xxx
        var insufficient = signedPost("/api/v1/transfers", """
                {"clientTransferId":"t-1","currency":"USDT","amount":"99999",
                 "debitAccountId":%d,"creditAccountId":%d,"code":"INTERNAL"}"""
                .formatted(accountId, otherAccountId));
        assertThat(insufficient.statusCode()).isEqualTo(400);
        assertThat(insufficient.body()).contains("\"code\":\"4001\"");

        // 金额格式非法：请求本身就错了 → 2xxx
        var badAmount = signedPost("/api/v1/transfers", """
                {"clientTransferId":"t-2","currency":"USDT","amount":"abc",
                 "debitAccountId":%d,"creditAccountId":%d,"code":"INTERNAL"}"""
                .formatted(accountId, otherAccountId));
        assertThat(badAmount.statusCode()).isEqualTo(400);
        assertThat(badAmount.body()).contains("\"code\":\"2001\"");
    }

    @Test
    @DisplayName("★ 账户不存在 与 无权访问 —— 码、消息、状态码三者必须完全一致")
    void notFoundAndForbiddenAreIndistinguishable() {
        long foreign = jdbc.sql("""
                        INSERT INTO account(code,currency,kind) VALUES ('house:y','USDT','ASSET')
                        RETURNING id""").query(Long.class).single();

        var forbidden = signedGet("/api/v1/accounts/" + foreign + "/balance");
        var missing = signedGet("/api/v1/accounts/999999/balance");

        // 只统一其中一个维度是不够的：码一样但状态码不同，照样能枚举。
        assertThat(missing.statusCode()).isEqualTo(forbidden.statusCode());
        assertThat(digitsMasked(missing.body())).isEqualTo(digitsMasked(forbidden.body()));
    }

    // ==================================================================
    // 重放防护
    // ==================================================================

    @Test
    @DisplayName("★ 原样重放一次 POST —— 第二次被拒（1002），不再进入业务逻辑")
    void replayingAPostIsRejected() {
        String body = """
                {"clientTransferId":"replay-1","currency":"USDT","amount":"0",
                 "debitAccountId":%d,"creditAccountId":%d,"code":"INTERNAL"}"""
                .formatted(accountId, otherAccountId);
        String path = "/api/v1/transfers";
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        String sign = SignedRequests.sign(secret, ts, nonce, "POST", path, body);

        var first = sendSigned(path, "POST", body, ts, nonce, sign);
        var second = sendSigned(path, "POST", body, ts, nonce, sign);

        // 第一次会因为金额为 0 被账本拒（400/2004），这不重要 ——
        // 重要的是它**到达了业务逻辑**，而第二次没有。
        assertThat(first.body()).doesNotContain("\"code\":\"1002\"");
        assertThat(second.statusCode()).isEqualTo(401);
        assertThat(second.body()).contains("\"code\":\"1002\"");
    }

    @Test
    @DisplayName("★ 签名必须真的进了 Redis，且 TTL 大于验签的时间窗")
    void usedSignaturesAreStoredInRedisWithASafeTtl() {
        // TTL 必须严格大于时钟容差（5 秒），否则会出现一段真空：
        // 签名记录已过期，而时间窗还没关上 —— 那几毫秒里重放是通的。
        String body = """
                {"clientTransferId":"ttl-1","currency":"USDT","amount":"0",
                 "debitAccountId":%d,"creditAccountId":%d,"code":"INTERNAL"}"""
                .formatted(accountId, otherAccountId);
        String path = "/api/v1/transfers";
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        sendSigned(path, "POST", body, ts, nonce,
                SignedRequests.sign(secret, ts, nonce, "POST", path, body));

        String key = "cp:nonce:ak_acme:" + nonce;
        assertThat(redisTemplate.hasKey(key))
                .as("用过的 nonce 必须被记下来，否则重放防护不存在")
                .isTrue();
        assertThat(redisTemplate.getExpire(key))
                .as("TTL 必须大于 5 秒的时钟容差，否则记录先于时间窗过期")
                .isGreaterThan(5L);
    }

    @Test
    @DisplayName("★ 内容完全相同的并发 GET —— 因为 nonce 不同，不会被误判成重放")
    void identicalConcurrentGetsAreNotTreatedAsReplays() {
        // ★ 这条测试记录了一次设计返工 ★
        //
        // 加 nonce 之前，签名 = f(时间戳, 方法, 路径, body)，是决定性函数。
        // 同一毫秒里两个内容相同的 GET 会算出**同一个签名** ——
        // 如果拿签名当「请求身份证」，第二个就被当成攻击拒掉了。
        // 实测：并发发起 160 次只用掉 5 个不同的毫秒值，最挤的一毫秒挤了 39 个。
        //
        // 当时的权宜之计是「GET 不做重放检查」，但那给未来所有
        // body 为空的 POST 接口埋了一颗看不见的雷 —— 而且没写在任何地方。
        //
        // 加 nonce 之后，两个职责彻底分开：签名只管「是不是你签的」，
        // nonce 只管「是不是第一次」。GET 不再需要任何特例。
        String path = "/api/v1/accounts/" + accountId + "/balance";
        long ts = System.currentTimeMillis();   // 故意用同一个时间戳

        String n1 = SignedRequests.newNonce();
        String n2 = SignedRequests.newNonce();

        assertThat(sendSigned(path, "GET", null, ts, n1,
                SignedRequests.sign(secret, ts, n1, "GET", path, "")).statusCode())
                .isEqualTo(200);
        assertThat(sendSigned(path, "GET", null, ts, n2,
                SignedRequests.sign(secret, ts, n2, "GET", path, "")).statusCode())
                .as("同一毫秒、同一路径，只有 nonce 不同 —— 必须放行")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("★ GET 也受重放保护 —— nonce 重复必被拒")
    void repeatingANonceOnAGetIsAlsoRejected() {
        // 加 nonce 之前 GET 完全没有重放保护（攻击者能重放一次查询，
        // 拿到余额这类他本来看不到的数据）。现在补上了。
        String path = "/api/v1/accounts/" + accountId + "/balance";
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        String sign = SignedRequests.sign(secret, ts, nonce, "GET", path, "");

        assertThat(sendSigned(path, "GET", null, ts, nonce, sign).statusCode()).isEqualTo(200);
        var replay = sendSigned(path, "GET", null, ts, nonce, sign);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(replay.body()).contains("\"code\":\"1002\"");
    }

    @Test
    @DisplayName("★ 攻击者改掉 nonce 想绕过重放检查 —— 签名立刻对不上")
    void changingTheNonceInvalidatesTheSignature() {
        // ★ 这条才是 nonce 机制真正成立的原因 ★
        //
        // 如果 nonce 只是一个「旁路的键」、不参与签名计算，
        // 那么攻击者截获请求后只要换个 nonce 就能重放 ——
        // 签名还是那个签名，依然验得过，而重放检查查不到这个新 nonce。
        // 整套防护会变成一行摆设。
        //
        // nonce 参与签名，意味着改它就等于改请求内容，签名必然失效。
        String path = "/api/v1/accounts/" + accountId + "/balance";
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        String sign = SignedRequests.sign(secret, ts, nonce, "GET", path, "");

        assertThat(sendSigned(path, "GET", null, ts, nonce, sign).statusCode()).isEqualTo(200);

        // 原样重放会被 nonce 挡住；那就换个 nonce 试试 —— 签名不变
        var tampered = sendSigned(path, "GET", null, ts, SignedRequests.newNonce(), sign);

        assertThat(tampered.statusCode())
                .as("换了 nonce 就是换了签名内容，必须验签失败")
                .isEqualTo(401);
        assertThat(tampered.body())
                .as("是验签失败(1001)，不是重放(1002) —— 它压根没走到重放检查那一步")
                .contains("\"code\":\"1001\"");
    }

    @Test
    @DisplayName("★ nonce 长度必须固定 —— 变长会让签名拼接产生歧义，超长会被用来打 Redis")
    void nonceLengthIsEnforced() {
        // 两个理由都是承重的：
        //   上限 —— 不限长的话，每个请求塞 1MB nonce，Redis 内存几分钟被吃光
        //   定长 —— prehash 各段直接拼接不加分隔符，nonce 可变长会让
        //           "ab"+"c" 和 "a"+"bc" 拼出同一个串、算出同一个签名
        String path = "/api/v1/accounts/" + accountId + "/balance";
        long ts = System.currentTimeMillis();
        String tooShort = "abcd";

        var response = sendSigned(path, "GET", null, ts, tooShort,
                SignedRequests.sign(secret, ts, tooShort, "GET", path, ""));

        assertThat(response.statusCode())
                .as("签名算对了也不行 —— 长度不合规就是不合规")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("★ 每一个 LedgerException.Reason 都必须映射到一个 ErrorCode")
    void everyLedgerReasonIsMapped() {
        // 质询扫描 6.1 指出：ApiExceptionHandler 的注释声称「新增 Reason 忘了映射会让测试当场变红」，
        // 但全仓没有任何测试遍历 Reason.values()——那句话是空头支票。
        // 漏映射的真实后果是 Map.get() 返回 null，然后在 ApiResponse.error() 里 NPE，
        // 一个业务拒绝被伪装成 500。这条把支票兑现。
        for (var reason : com.chainpay.ledger.service.LedgerException.Reason.values()) {
            assertThat(ApiExceptionHandler.errorCodeFor(reason))
                    .as("Reason.%s 没有对应的 ErrorCode", reason)
                    .isNotNull();
        }
    }

    // ==================================================================
    // 错误信息的转义
    // ==================================================================

    @Test
    @DisplayName("★ msg 里带引号/反斜杠/换行 —— 响应仍是合法 JSON，内容原样保留")
    void errorMessagesAreEscapedNotConcatenated() throws Exception {
        // ★ 这条守的是「JSON 注入」，和 SQL 注入是同一个家族 ★
        //
        // 第一版的实现是手拼字符串：
        //     "{\"code\":\"" + code + "\",\"msg\":\"" + msg + "\",\"data\":null}"
        // 它当时是对的，但对得很脆弱 —— 前提是「msg 永远只来自代码里的常量」。
        // 而这个前提没有任何东西在守。打破它不需要有人写恶意代码，
        // 只需要有人做一件完全合理的事：让错误信息更有帮助一点，比如
        //     "API key " + apiKey + " 无效"      ← apiKey 是客户端传来的请求头
        // 攻击者传一个带引号的 key，就能往响应体里注入自己的字段。
        //
        // 交给 Jackson 之后，这个前提不再是前提。这条测试钉住那件事：
        // 任何人把这里改回字符串拼接，它立刻变红。
        String hostile = "他说\"不行\"，路径 C:\\tmp\n换行\t制表";

        var response = new org.springframework.mock.web.MockHttpServletResponse();
        errorWriter.write(response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                ErrorCode.UNAUTHORIZED, hostile);

        String json = response.getContentAsString();

        // ① 必须还是能解析的 JSON —— 手拼的话这里就炸了
        var parsed = objectMapper.readTree(json);

        // ② 结构没有被 msg 的内容篡改
        assertThat(parsed.get("code").asString()).isEqualTo("1001");
        assertThat(parsed.has("data")).isTrue();
        assertThat(parsed.get("data").isNull()).isTrue();

        // ③ 内容原样保留 —— 转义不是「把危险字符删掉」，是「标记它是内容」
        assertThat(parsed.get("msg").asString()).isEqualTo(hostile);

        // ④ 引号在**原文里**必须是被转义过的形态，证明确实做了转义
        assertThat(json).contains("\\\"");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private HttpResponse<String> signedGet(String path) {
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        return sendSigned(path, "GET", null, ts, nonce,
                SignedRequests.sign(secret, ts, nonce, "GET", path, ""));
    }

    private HttpResponse<String> signedPost(String path, String body) {
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        return sendSigned(path, "POST", body, ts, nonce,
                SignedRequests.sign(secret, ts, nonce, "POST", path, body));
    }

    private HttpResponse<String> sendSigned(String path, String method, String body,
                                            long ts, String nonce, String sign) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", "ak_acme")
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-NONCE", nonce)
                .header("X-CP-API-SIGN", sign);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return send(builder);
    }

    /**
     * 把每一<b>段</b>连续数字换成一个 #，用来比较两个响应「除了调用方自己传的 id
     * 之外是否完全一样」。
     *
     * <p>注意是按「段」而不是按「位」替换：按位替换的话，
     * id 5 变成 "#"、id 999999 变成 "######"，长度不同会误判成两种响应。
     * 而<b>回显调用方自己传来的 id 不构成泄露</b> —— 他本来就知道自己传了什么。
     */
    private static String digitsMasked(String text) {
        return text.replaceAll("\\d+", "#");
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
