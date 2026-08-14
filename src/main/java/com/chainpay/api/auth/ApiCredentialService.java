package com.chainpay.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 验证 API 凭证 —— 回答「你是谁」。
 *
 * <p>注意它<b>不</b>回答「你能动谁的钱」。那是授权，是下一步的事。
 * 把这两件事分成两个类，是为了让「只做认证不做授权会怎样」看得见。
 */
@Service
public class ApiCredentialService {

    private final JdbcClient jdbcClient;

    public ApiCredentialService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 认证成功后我们知道的全部信息。 */
    public record AuthenticatedMerchant(long merchantId, String merchantCode) {}

    /**
     * 用 api key + secret 换取商户身份。
     *
     * <p>失败时返回空 {@link Optional}，<b>不区分失败原因</b>：
     * 「这个 key 不存在」和「key 存在但 secret 错了」如果给出不同的回答，
     * 攻击者就能用它来枚举出哪些 key 是真的。
     */
    public Optional<AuthenticatedMerchant> authenticate(String apiKey, String secret) {
        if (apiKey == null || apiKey.isBlank() || secret == null || secret.isBlank()) {
            return Optional.empty();
        }

        // 一次查询同时校验三件事：凭证有效、商户存在、商户未被停用。
        // 分成三次查询也能做，但那样会出现「凭证有效但商户已停用」这种中间状态，
        // 而中间状态是要被处理的分支，分支就是出错的机会。
        var row = jdbcClient.sql("""
                        SELECT c.secret_hash, m.id AS merchant_id, m.code AS merchant_code
                        FROM api_credential c
                                 JOIN merchant m ON m.id = c.merchant_id
                        WHERE c.api_key = :apiKey
                          AND c.status  = 'ACTIVE'
                          AND m.status  = 'ACTIVE'
                        """)
                .param("apiKey", apiKey)
                .query((rs, rowNum) -> new CredentialRow(
                        rs.getString("secret_hash"),
                        rs.getLong("merchant_id"),
                        rs.getString("merchant_code")))
                .optional();

        if (row.isEmpty()) {
            return Optional.empty();
        }

        // ★ 用常量时间比较，不用 String.equals ★
        //
        // String.equals 一发现某个字符不同就立刻返回，所以「前 1 个字符对」
        // 和「前 20 个字符对」耗时不同。攻击者反复请求、测量响应时间差，
        // 理论上可以一个字符一个字符地把正确值试出来 —— 这叫时序攻击。
        //
        // 对我们这种 32 字节随机 secret，时序攻击实际上很难成功（网络抖动远大于
        // 那点时间差）。但 MessageDigest.isEqual 是免费的，
        // 而「这里可以偷懒」的判断一旦形成习惯，会被带到真正要命的地方去。
        if (!MessageDigest.isEqual(
                sha256Hex(secret).getBytes(StandardCharsets.UTF_8),
                row.get().secretHash().getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        touchLastUsed(apiKey);
        return Optional.of(new AuthenticatedMerchant(row.get().merchantId(), row.get().merchantCode()));
    }

    /**
     * 计算 secret 的 SHA-256 十六进制。
     *
     * <p><b>为什么用快哈希 SHA-256，而不是存密码常用的 bcrypt：</b>
     *
     * <ul>
     *   <li>用户密码是人记的，熵低（"abc123"），必须用<b>故意很慢</b>的算法，
     *       让暴力破解每次尝试都付出代价。</li>
     *   <li>API secret 是服务端生成的 32 字节随机串，猜不出来，慢哈希没有意义；
     *       而且<b>每个请求都要验一次</b>，bcrypt 会让每个请求多花几百毫秒。</li>
     * </ul>
     *
     * <p>这个取舍成立的前提是：<b>secret 必须由服务端随机生成，绝不允许商户自选</b>。
     * 一旦允许自选，商户会填 "123456"，快哈希就守不住了。
     */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 Java 平台规范要求必须提供的算法，走不到这里。
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 记录凭证最近一次被使用的时间。
     *
     * <p>用途：找出「三个月没用过却一直有效」的凭证并回收 ——
     * 长期闲置又有效的凭证是最容易被忘掉、也最容易被滥用的那种。
     *
     * <p><b>已知代价</b>：这是<b>每个 API 请求一次数据库写</b>。
     * 请求量上来之后它会成为热点（每次写都产生 WAL、都要拿行锁）。
     * 常见优化是「距上次更新超过 N 分钟才写」，等真的有量了再改，
     * 现在先用最简单的写法，把代价写在这里。
     */
    private void touchLastUsed(String apiKey) {
        jdbcClient.sql("UPDATE api_credential SET last_used_at = now() WHERE api_key = :apiKey")
                .param("apiKey", apiKey)
                .update();
    }

    private record CredentialRow(String secretHash, long merchantId, String merchantCode) {}
}
