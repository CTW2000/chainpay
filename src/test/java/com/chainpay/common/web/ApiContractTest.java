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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    /**
     * 信封、重放、请求校验这几组的靶子（2026-09-22 换：原来是通用转账接口与按 id 查余额，两个都删了）。
     * 挑白名单接口的理由：登记幂等（重发不会多出一行）、不依赖链上配置、空列表也回 200。
     */
    private static final String TARGET = "/api/v1/withdrawal-addresses";

    /**
     * 金额契约的靶子。请求记录上的格式正则由 {@code @Valid} 在进业务逻辑<b>之前</b>跑，
     * 所以这一组不需要余额、限额、白名单任何夹具。
     */
    private static final String WITHDRAWALS = "/api/v1/withdrawals";

    private static final String ADDRESS = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /**
     * 用 V13 种下的那一行（Sepolia 的 LINK），<b>不自己插一行新代币</b>。
     * 基类每个测试只 TRUNCATE entry / transfer / account，{@code chain_token} 留着——
     * 往它插一行假代币会跨测试类泄漏：对账那组拿着这个地址去问假节点的余额，
     * 当场「execution reverted」，一口气红 28 条（2026-09-22 实测踩过）。
     */
    private static final String TOKEN = "0x779877a7b0d9e8603169ddbd7836e478b4624789";

    private String secret;
    private long merchantId;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM payout_address").update();
        jdbc.sql("DELETE FROM merchant").update();

        merchantId = jdbc.sql("INSERT INTO merchant(code,name) VALUES ('acme','Acme') RETURNING id")
                .query(Long.class).single();
        secret = cipher.generateSecret();
        jdbc.sql("""
                        INSERT INTO api_credential(merchant_id, api_key, secret_encrypted)
                        VALUES (:m,'ak_acme',:s)
                        """)
                .param("m", merchantId).param("s", cipher.encrypt(secret)).update();

        // 白名单里先放一条：信封那条要断言 data 里装的确实是业务数据
        jdbc.sql("""
                        INSERT INTO payout_address(merchant_id, address, label)
                        VALUES (:m, :a, '收款方')
                        """)
                .param("m", merchantId).param("a", ADDRESS).update();
    }

    /**
     * 谁创建谁清理。基类每个测试只 TRUNCATE entry / transfer / account，白名单表留着，
     * 而它指向 merchant——别的测试类做 `DELETE FROM merchant` 时会撞外键，
     * 于是那边的商户删不掉、断言「被拦住就什么都没建」当场红（2026-09-22 实测：一口气连累 14 条）。
     */
    @AfterEach
    void removeWhitelistRows() {
        jdbc.sql("DELETE FROM payout_address").update();
    }

    // ==================================================================
    // 信封
    // ==================================================================

    @Test
    @DisplayName("★ 成功响应是信封：code=0 / msg 空 / data 带业务数据")
    void successIsWrappedInTheEnvelope() {
        var response = signedGet(TARGET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"code\":\"0\"")
                .contains("\"msg\":\"\"")
                .contains("\"data\":");
        // data 里才是业务数据，不是平铺在顶层
        assertThat(response.body()).contains("\"address\"");
    }

    @Test
    @DisplayName("★ 失败响应是同一个信封形状，data 为 null")
    void failureUsesTheSameEnvelope() {
        // 停用一个不存在的白名单行：商户接口唯一还收对象 id 的地方
        var response = signedPost(TARGET + "/999999/disable", "");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .as("客户端必须能用同一套逻辑解析成功和失败")
                .contains("\"code\":\"2009\"")
                .contains("\"msg\":")
                .contains("\"data\":null");
    }

    @Test
    @DisplayName("★ 过滤器写的响应也必须是信封 —— 否则形状会在两个地方分叉")
    void filterWrittenResponsesUseTheEnvelopeToo() {
        // 过滤器在 Spring 的消息转换器之前执行，拿不到 Jackson，只能手写 JSON。
        // 最容易出现"控制器一种形状、过滤器另一种形状"的地方就是这里。
        var response = send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + TARGET))
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
        // 余额不足：请求本身没错，是账户状态不允许 → 4xxx。
        // 走真实的提现申请：代币是 V13 种的那一行、地址在白名单里（seed 放的），
        // 最后倒在冻结那一步——商户一分钱都没有。不必注资：要的就是「钱不够」。
        var insufficient = signedPost(WITHDRAWALS, withdrawalBody("1", "biz-1"));
        assertThat(insufficient.statusCode()).as(insufficient.body()).isEqualTo(400);
        assertThat(insufficient.body()).contains("\"code\":\"4001\"");

        // 金额格式非法：请求本身就错了 → 2xxx（@Valid 在业务逻辑之前就拦下）
        var badAmount = signedPost(WITHDRAWALS, withdrawalBody("abc", "req-1"));
        assertThat(badAmount.statusCode()).isEqualTo(400);
        assertThat(badAmount.body()).contains("\"code\":\"2001\"");
    }

    // 「不存在 与 无权访问 回答必须一致」2026-09-22 搬到 ApiSecurityTest：
    // 按 id 查余额的接口删了之后，这条测试两边都拿到 404「路径不存在」——**照样绿，但什么都不证明**。
    // 新家是商户接口唯一还收对象 id 的地方（停用白名单地址），那里 RLS 让「不是你的」和「不存在」
    // 都是「改了 0 行」，是结构性成立的。

    // ==================================================================
    // 重放防护
    // ==================================================================

    @Test
    @DisplayName("★ 原样重放一次 POST —— 第二次被拒（1002），不再进入业务逻辑")
    void replayingAPostIsRejected() {
        String body = addressBody("0xcccccccccccccccccccccccccccccccccccccccc");
        String path = TARGET;
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        String sign = SignedRequests.sign(secret, ts, nonce, "POST", path, body);

        var first = sendSigned(path, "POST", body, ts, nonce, sign);
        var second = sendSigned(path, "POST", body, ts, nonce, sign);

        // 第一次成功与否不重要 —— 重要的是它**到达了业务逻辑**，而第二次没有。
        assertThat(first.body()).doesNotContain("\"code\":\"1002\"");
        assertThat(second.statusCode()).isEqualTo(401);
        assertThat(second.body()).contains("\"code\":\"1002\"");
    }

    @Test
    @DisplayName("★ 签名必须真的进了 Redis，且 TTL 大于验签的时间窗")
    void usedSignaturesAreStoredInRedisWithASafeTtl() {
        // TTL 必须严格大于时钟容差（5 秒），否则会出现一段真空：
        // 签名记录已过期，而时间窗还没关上 —— 那几毫秒里重放是通的。
        String body = addressBody("0xdddddddddddddddddddddddddddddddddddddddd");
        String path = TARGET;
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
        String path = TARGET;
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
        String path = TARGET;
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
        String path = TARGET;
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
    @DisplayName("★ nonce 必须是 32 个十六进制字符，且在验签之前就检查：字符集不对，签名算对了也 401")
    void nonceMustBeHexadecimalBeforeSignatureVerification() {
        String path = TARGET;
        long ts = System.currentTimeMillis();
        String notHex = "z".repeat(32);                                        // 长度对，字符集不对

        var response = sendSigned(path, "GET", null, ts, notHex,
                SignedRequests.sign(secret, ts, notHex, "GET", path, ""));

        assertThat(response.statusCode())
                .as("过滤器 javadoc 用「nonce 定长」论证拼接无歧义，这个前提必须在验签之前被强制，而不是验签之后")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("★ 请求体字段为 null（amount / code）—— 400 / 2001，不是 500：500 会被客户端当成「稍后重试」")
    void nullFieldsAreBadRequestsNotServerErrors() {
        var nullAddress = signedPost(TARGET, "{\"address\":null,\"label\":\"x\"}");
        assertThat(nullAddress.statusCode()).as(nullAddress.body()).isEqualTo(400);
        assertThat(nullAddress.body()).contains("\"code\":\"2001\"");

        // 字段整个缺席 —— 和显式 null 是两条不同的路（Jackson 一个给 null、一个不调 setter）
        var missingAddress = signedPost(TARGET, "{\"label\":\"x\"}");
        assertThat(missingAddress.statusCode()).as(missingAddress.body()).isEqualTo(400);
        assertThat(missingAddress.body()).contains("\"code\":\"2001\"");
    }

    @Test
    @DisplayName("★ 请求体不是 JSON —— 400 / 2001，不是 500")
    void malformedJsonIsABadRequest() {
        var garbage = signedPost(TARGET, "{not json");

        assertThat(garbage.statusCode()).as(garbage.body()).isEqualTo(400);
        assertThat(garbage.body()).contains("\"code\":\"2001\"");
    }

    @Test
    @DisplayName("★ nonce 长度必须固定 —— 变长会让签名拼接产生歧义，超长会被用来打 Redis")
    void nonceLengthIsEnforced() {
        // 两个理由都是承重的：
        //   上限 —— 不限长的话，每个请求塞 1MB nonce，Redis 内存几分钟被吃光
        //   定长 —— prehash 各段直接拼接不加分隔符，nonce 可变长会让
        //           "ab"+"c" 和 "a"+"bc" 拼出同一个串、算出同一个签名
        String path = TARGET;
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

    @Test
    @DisplayName("★ 超过 1 MB 的请求体 —— 413，且和其他错误一样走信封")
    void oversizedBodyIsRejectedWithEnvelope() throws Exception {
        // 质询扫描 6.5：过滤器里其他每一处拒绝都走 errors.write，唯独 413 是裸
        // setStatus + return，空 body。客户端按信封解析会拿到解析异常，多半当传输失败重试。
        // 这条不需要签名：体积检查必须发生在验签之前（读 body 是验签的前提）。
        String oversized = "x".repeat(1024 * 1024 + 1);
        var response = send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + TARGET))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(oversized)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body())
                .contains("\"code\":\"2007\"")
                .contains("\"data\":null");
    }

    // ==================================================================
    // 账本拒绝路径（质询扫描 5.1a：这些 throw 之前没有任何断言守着）
    // ==================================================================

    /**
     * 2026-09-21：金额此前只有 {@code @NotBlank}，控制器拿到什么就 {@code new BigDecimal} 什么。
     * {@code 1E-999999999} 一个请求打挂进程、一百万位的普通写法光解析就要 11 秒，都是从这里进来的。
     * 其中 {@code ٣} 是阿拉伯-印度数字的 3：BigDecimal 认所有文字的数字，正则的 {@code \d} 只认 ASCII 的 0–9。
     *
     * <p><b>2026-09-22 换靶子后多了两条</b>（19 位小数、21 位整数）。此前它们各自是一条测试、断言 2004：
     * 转账接口用的是 {@code PLAIN_DECIMAL}，只管写法、范围交给账本，所以回 2004。那个接口删了，
     * 而提现与限额用的是 {@code FITTING_DECIMAL}（范围也写进正则），装不下的金额在边界就回 2001、到不了账本。
     * 账本那一侧「装不下 → 2004、报错只说几位不写金额」由 {@code LedgerAmountBoundsTest} 在服务层守着。
     */
    @ParameterizedTest(name = "金额 [{0}] → 400 / 2001")
    @ValueSource(strings = {"1E-5", "1e2", "1E+3", "+1", "-1", "1.", ".5", " 1", "1,000", "0x10", "NaN", "٣",
            "10000000000000000000000000000000000000000000000000000000000000000",   // 65 位
            "0.1234567890123456789",           // 19 位小数：多出的那位会被 NUMERIC(38,18) 静默四舍五入
            "100000000000000000000",           // 21 位整数：到了数据库是 numeric field overflow → 500 + 9001
            "1E-100000000", "1E-999999999"})   // 修好之前前者回一个 100 MB 的报错，后者打挂进程
    @DisplayName("★ 金额只收装得下的普通小数写法 —— 指数、正负号、空格、别的文字的数字、超长、超范围都在边界 400 / 2001")
    void amountMustBeAPlainDecimal(String amount) {
        var r = signedPost(WITHDRAWALS, withdrawalBody(amount, "pf-1"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"2001\"");
    }

    @Test
    @DisplayName("★ 一百万位的普通写法 —— 在解析之前就 400 / 2001（此前光 new BigDecimal 就要 11 秒）")
    void aMillionDigitAmountIsRejectedBeforeParsing() {
        // 请求体上限是 1 MB，这个金额刚好塞得进去。它是合法的普通写法，只加「写法」规则挡不住它，
        // 挡住它的是正则里的长度上限——正则在解析之前跑，匹配到上限之后就失败，不看剩下的。
        var r = signedPost(WITHDRAWALS, withdrawalBody("7".repeat(1_000_000), "md-1"));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"2001\"");
    }

    // 「转账币种与账户不符 → 2006」2026-09-22 下移到 LedgerModelingTest：
    // 商户接口不再收账户 id，币种也由 token 决定（提现）或由收款地址决定（入账），HTTP 上已经造不出这个组合。
    // 而账本自己那道 requireCurrencyMatches 还在，测试跟着它走到服务层——被守的东西在哪，测试就该在哪。

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

    /** 白名单登记的请求体：结构最简单的一个商户 POST（地址 + 标签）。 */
    private String addressBody(String address) {
        return "{\"address\":\"%s\",\"label\":\"x\"}".formatted(address);
    }

    /**
     * 提现申请的请求体。金额那条正则由 {@code @Valid} 在业务逻辑之前跑，
     * 所以格式不合法的那些用例走不到代币、白名单、余额任何一道门，不必为它们准备夹具。
     */
    private String withdrawalBody(String amount, String key) {
        return """
                {"token":"%s","toAddress":"%s","amount":"%s","idempotencyKey":"%s"}"""
                .formatted(TOKEN, ADDRESS, amount, key);
    }

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

    @Test
    @DisplayName("★ 签过名却用错方法（PUT）：405 + 2001，不是 500 + 9001「可重试」")
    void wrongMethodIsMethodNotAllowedNotServerError() {
        String path = TARGET;
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        var response = sendSignedWithMethod(path, "PUT", "{}", ts, nonce, SignedRequests.sign(secret, ts, nonce, "PUT", path, "{}"));

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).contains("\"code\":\"2001\"").doesNotContain("9001");
    }

    @Test
    @DisplayName("★ 签过名的未知路径：404 + 2001，不是 500 + 9001")
    void unknownPathIsNotFoundNotServerError() {
        String path = "/api/v1/no-such-thing";
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        var response = sendSignedWithMethod(path, "GET", "", ts, nonce, SignedRequests.sign(secret, ts, nonce, "GET", path, ""));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"2001\"").doesNotContain("9001");
    }

    private HttpResponse<String> sendSignedWithMethod(String path, String method, String body,
                                                      long ts, String nonce, String sign) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", "ak_acme")
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-NONCE", nonce)
                .header("X-CP-API-SIGN", sign)
                .method(method, "GET".equals(method) ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return send(builder);
    }
}
