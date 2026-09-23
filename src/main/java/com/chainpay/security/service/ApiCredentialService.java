package com.chainpay.security.service;

import com.chainpay.security.crypto.SecretCipher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 验证 API 请求的签名 —— 回答「你是谁」，而且<b>不需要你把钥匙发过来</b>。
 *
 * <p>双方各自用 secret 对「请求内容」算一个签名，只发签名；服务端用自己存的 secret 重算一遍，
 * 对得上就说明对方确实知道 secret。<b>钥匙从不离开双方</b>：secret 若放在请求头里，反向代理的访问日志、
 * 中间任何一跳、一次不小心的 {@code log.debug(headers)}，谁看到一次就永久拥有它。
 *
 * <p>签名串是 CP2 规范串（{@link #prehash}）：
 *
 * <pre>
 *   canonical = "CP2" ‖ LF ‖ timestamp ‖ LF ‖ nonce ‖ LF ‖ method ‖ LF ‖ requestPath ‖ LF ‖ SHA256hex(body)
 *   signature = Base64( HMAC-SHA256(canonical, secret) )
 * </pre>
 *
 * <p><b>规范串必须无歧义：</b>HMAC 只认字节，「请求 → 字节串」这个映射必须是单射，否则两个不同的请求共享一个签名。
 * 各段直接拼接时，把路径末尾的字符挪进请求体开头，拼出的字节一模一样。CP2 让每段要么定长（时间戳、nonce、请求体的哈希）
 * 要么不含换行（方法、路径），边界因此唯一；版本标签在串里受签名保护，下次改协议可以新旧并存一个过渡期。
 *
 * <p>各段各防一件事：{@code timestamp} 限定有效期（配合时间窗校验）；{@code nonce} 保证每个请求各不相同（见下）；
 * {@code method} 防止把 GET 改成 DELETE 重放；{@code path}（含查询串）防止打到另一个接口或改参数；
 * {@code body} 防止改金额、改收款地址。
 *
 * <p><b>为什么要 nonce：</b>HMAC 是确定性的，同样的输入永远得到同样的签名，所以<b>签名本身没法回答「这是不是第一次收到」</b>。
 * 时间戳不够：它只到毫秒，一毫秒里能发出去几十个请求——没有 nonce，两个内容相同的并发请求会算出同一个签名，
 * 合法请求被当成重放拒掉。所以把两个职责拆开：<b>签名只管「是不是你签的」，nonce 只管「是不是第一次」</b>
 * （登记见 {@code ReplayGuard}）。
 */
@Service
public class ApiCredentialService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 允许的时间偏差（同币安 {@code recvWindow} 的默认值），<b>双向</b>生效（见 {@link #withinClockSkew}）。
     *
     * <p>对应 OWASP Transaction_Authorization 2.9：授权凭据只在有限时间窗内有效，
     * 挡住「凭据被送到攻击者机器上，稍后再用」。窗口大小是个取舍：太小，客户端和服务器时钟差几秒就全部失败；
     * 太大，攻击者重放的窗口就有那么长。
     */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(5);

    private final JdbcClient jdbcClient;
    private final SecretCipher cipher;
    /**
     * 诱饵密文：key 不存在时也拿它解密一次并算一次 HMAC，让「不存在」和「签名错」两条失败路径等耗时。
     * 响应早已做到不可区分（同一个 401 / 1001），耗时也要——否则计时能确认「这个 api_key 是活的」。
     */
    private final String decoyCiphertext;

    public ApiCredentialService(JdbcClient jdbcClient, SecretCipher cipher) {
        this.jdbcClient = jdbcClient;
        this.cipher = cipher;
        this.decoyCiphertext = cipher.encrypt(cipher.generateSecret());
    }

    /** 认证成功后我们知道的全部信息。 */
    public record AuthenticatedMerchant(long merchantId, String merchantCode) {}

    /**
     * 一个待验证的已签名请求。
     *
     * @param timestampMillis 客户端声称的发起时刻（毫秒纪元）
     * @param nonce           客户端每次生成的一次性随机串。参与签名，
     *                        因此攻击者改不了它——改了签名就对不上
     * @param method          HTTP 方法，大写
     * @param path            请求路径，含查询串
     * @param body            请求体原文；GET 请求为空字符串
     * @param signature       Base64 的 HMAC-SHA256
     */
    public record SignedRequest(
            String apiKey,
            long timestampMillis,
            String nonce,
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

        // 先查时间窗（纯内存，几乎零成本），再查数据库：攻击者用过期时间戳刷请求时打不到数据库上。
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
            // 未命中也走一遍解密 + HMAC：两条失败路径同样贵，结果丢弃
            sign(prehash(request), cipher.decrypt(decoyCiphertext));
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
     * 构造被签名的 CP2 规范串。三处副本必须一字不差：这里、测试助手 {@code SignedRequests}、{@code tools/api.py}。
     *
     * <p>换行能当分隔符，是因为任何一段都不可能含有它：时间戳是数字，nonce 是十六进制，方法是字母，
     * 路径里的换行在 HTTP 中只能以 {@code %0A} 三个普通字符出现，请求体换成了 64 个十六进制字符的哈希。
     * 路径连查询串<b>原样</b>签，不排序、不解码——规范化本身就是新的歧义来源，签双方都拿得到的原样字节最不容易错。
     * 空请求体的哈希是固定值 {@code e3b0c442…}，GET 与 POST 走同一条规则。
     */
    public static String prehash(SignedRequest request) {
        return "CP2\n" + request.timestampMillis() + "\n" + request.nonce() + "\n" + request.method() + "\n"
                + request.path() + "\n" + sha256Hex(request.body() == null ? "" : request.body());
    }

    /** 请求体的 SHA-256，十六进制小写：把变长的最后一段变成定长。 */
    static String sha256Hex(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 没有 SHA-256", e);
        }
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
