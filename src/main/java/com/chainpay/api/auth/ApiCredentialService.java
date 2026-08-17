package com.chainpay.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 验证 API 请求的签名 —— 回答「你是谁」，而且<b>不需要你把钥匙发过来</b>。
 *
 * <p><b>上一版的问题：</b>请求头里带着 secret 明文。
 * 这等于每次开门都把钥匙复印一份留在门口 —— 反向代理的访问日志、
 * 中间任何一跳、一次不小心的 {@code log.debug(headers)}，
 * 谁看到一次就永久拥有这把钥匙。而且截获一个请求原样重发，服务端分辨不出来。
 *
 * <p><b>这一版：</b>双方各自用 secret 对「请求内容」算一个签名，只发签名。
 * 服务端用自己存的 secret 重算一遍，对得上就说明对方确实知道 secret。
 * <b>钥匙从不离开双方。</b>
 *
 * <p>签名串的构造照 OKX v5，比币安更严：
 *
 * <pre>
 *   prehash   = timestamp + method + requestPath + body
 *   signature = Base64( HMAC-SHA256(prehash, secret) )
 * </pre>
 *
 * <p>四个部分各自防一件事：
 * <ul>
 *   <li>{@code timestamp} —— 防重放（配合服务端的时间窗校验）</li>
 *   <li>{@code method}    —— 防止把 GET 改成 DELETE 重放（币安只签 query string，挡不住这个）</li>
 *   <li>{@code path}      —— 防止把请求打到另一个接口上</li>
 *   <li>{@code body}      —— 防止改金额、改收款账户</li>
 * </ul>
 */
@Service
public class ApiCredentialService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 允许的时间偏差。
     *
     * <p>对应币安的 {@code recvWindow}（默认 5 秒）。它同时满足 OWASP
     * Transaction_Authorization 的两条要求：
     * <ul>
     *   <li><b>2.9</b> 授权凭据只在有限时间窗内有效 ——
     *       挡住「凭据被恶意软件送到攻击者机器上，稍后再用」</li>
     *   <li><b>2.10</b> 每次操作的凭据必须唯一 ——
     *       timestamp 参与签名，换一秒签名就完全不同</li>
     * </ul>
     *
     * <p><b>窗口大小是个取舍</b>：
     * 太小，客户端和服务器时钟差几秒就全部失败；
     * 太大，攻击者重放的窗口就有那么长。5 秒是业界常见值。
     *
     * <p>注意它是<b>双向</b>的：也要挡住「时间戳在未来」的请求，
     * 否则攻击者可以预先用未来时间戳签好，等到那一刻再发。
     */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(5);

    private final JdbcClient jdbcClient;
    private final SecretCipher cipher;

    public ApiCredentialService(JdbcClient jdbcClient, SecretCipher cipher) {
        this.jdbcClient = jdbcClient;
        this.cipher = cipher;
    }

    /** 认证成功后我们知道的全部信息。 */
    public record AuthenticatedMerchant(long merchantId, String merchantCode) {}

    /**
     * 一个待验证的已签名请求。
     *
     * @param timestampMillis 客户端声称的发起时刻（毫秒纪元）
     * @param method          HTTP 方法，大写
     * @param path            请求路径，含查询串
     * @param body            请求体原文；GET 请求为空字符串
     * @param signature       Base64 的 HMAC-SHA256
     */
    public record SignedRequest(
            String apiKey,
            long timestampMillis,
            String method,
            String path,
            String body,
            String signature
    ) {}

    /**
     * 验证签名并返回商户身份。
     *
     * <p>失败一律返回空 {@link Optional}，<b>不区分原因</b>：
     * 「key 不存在」「签名不对」「时间戳过期」如果给出不同回答，
     * 攻击者就能据此枚举出哪些 key 是真的、以及服务器的时钟。
     */
    public Optional<AuthenticatedMerchant> authenticate(SignedRequest request) {
        if (request.apiKey() == null || request.apiKey().isBlank()
                || request.signature() == null || request.signature().isBlank()) {
            return Optional.empty();
        }

        // ★ 先查时间窗，再查数据库 ★
        //
        // 顺序是有意的：时间窗校验是纯内存计算，几乎零成本；查数据库要一次 IO。
        // 把便宜的检查放前面，攻击者用过期时间戳刷请求时打不到数据库上。
        if (!withinClockSkew(request.timestampMillis())) {
            return Optional.empty();
        }

        var row = jdbcClient.sql("""
                        SELECT c.secret_encrypted, m.id AS merchant_id, m.code AS merchant_code
                        FROM api_credential c
                                 JOIN merchant m ON m.id = c.merchant_id
                        WHERE c.api_key = :apiKey
                          AND c.status  = 'ACTIVE'
                          AND m.status  = 'ACTIVE'
                        """)
                .param("apiKey", request.apiKey())
                .query((rs, rowNum) -> new CredentialRow(
                        rs.getString("secret_encrypted"),
                        rs.getLong("merchant_id"),
                        rs.getString("merchant_code")))
                .optional();

        if (row.isEmpty()) {
            return Optional.empty();
        }

        String secret = cipher.decrypt(row.get().secretEncrypted());
        String expected = sign(prehash(request), secret);

        // 常量时间比较：String.equals 一发现字符不同就返回，
        // 「前 1 个字符对」和「前 20 个字符对」耗时不同，理论上可被逐字符试探。
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                request.signature().getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        touchLastUsed(request.apiKey());
        return Optional.of(new AuthenticatedMerchant(
                row.get().merchantId(), row.get().merchantCode()));
    }

    /**
     * 构造被签名的字符串。
     *
     * <p><b>拼接顺序和分隔方式必须双方完全一致</b>，差一个字符签名就对不上。
     * 这里不加分隔符，与 OKX v5 一致 —— 因为 timestamp 是定长数字、
     * method 是大写字母、path 以 {@code /} 开头，天然不会有歧义。
     *
     * <p>（如果各段长度可变且字符集重叠，就必须加分隔符，
     * 否则 {@code "ab"+"c"} 和 {@code "a"+"bc"} 会算出同一个签名 ——
     * 这是拼接式签名的经典漏洞。）
     */
    public static String prehash(SignedRequest request) {
        return request.timestampMillis() + request.method() + request.path() + request.body();
    }

    /** 用 secret 对内容算 HMAC-SHA256，返回 Base64。 */
    public static String sign(String content, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /**
     * 时间戳必须落在 [现在 - 窗口, 现在 + 窗口] 之内。
     *
     * <p>两端都要卡：只卡「过期」不卡「未来」的话，
     * 攻击者可以预先用未来时间戳签好请求，等到那一刻再发。
     */
    private boolean withinClockSkew(long timestampMillis) {
        long skew = Math.abs(System.currentTimeMillis() - timestampMillis);
        return skew <= MAX_CLOCK_SKEW.toMillis();
    }

    /**
     * 记录凭证最近一次被使用的时间，用来回收长期闲置的僵尸凭证。
     *
     * <p><b>已知代价</b>：每个 API 请求一次数据库写，量大后会成为写入热点。
     * 常见优化是「距上次更新超过 N 分钟才写」，等真有量了再改。
     */
    private void touchLastUsed(String apiKey) {
        jdbcClient.sql("UPDATE api_credential SET last_used_at = now() WHERE api_key = :apiKey")
                .param("apiKey", apiKey)
                .update();
    }

    private record CredentialRow(String secretEncrypted, long merchantId, String merchantCode) {}
}
