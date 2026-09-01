package com.chainpay.support;

import com.chainpay.api.auth.ApiCredentialService;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 测试里构造已签名请求的小工具。
 *
 * <p><b>为什么值得单独抽出来：</b>签名串的拼法是<b>协议契约</b>，
 * 而它同时出现在四个测试类里。散着写的话，改一次协议要在四处改对，
 * 漏一处就是一堆莫名其妙的 401 —— 而且每处的错法可能还不一样。
 *
 * <p>加 nonce 这次改动就是证据：如果没有这个类，
 * 四个测试类里十几个签名点都要手工改一遍。
 */
public final class SignedRequests {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SignedRequests() {}

    /**
     * 生成一个 nonce：32 个十六进制字符（16 字节随机）。
     *
     * <p>长度必须和 {@code ApiKeyAuthFilter.NONCE_HEX_LENGTH} 一致 ——
     * 服务端会校验，长度不对直接 401。
     */
    public static String newNonce() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    /** 按协议拼出被签名的字符串：时间戳 + nonce + 方法 + 路径 + body。 */
    public static String prehash(long timestampMillis, String nonce, String method,
                                 String path, String body) {
        return timestampMillis + nonce + method + path + (body == null ? "" : body);
    }

    /** 算签名。 */
    public static String sign(String secret, long timestampMillis, String nonce,
                              String method, String path, String body) {
        return ApiCredentialService.sign(prehash(timestampMillis, nonce, method, path, body),
                secret);
    }
}
