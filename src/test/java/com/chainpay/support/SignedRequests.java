package com.chainpay.support;

import com.chainpay.security.service.ApiCredentialService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 测试里构造已签名请求的小工具。签名串的拼法是<b>协议契约</b>，被多个测试类共用：
 * 散着写的话，改一次协议要处处改对，漏一处就是一堆莫名其妙的 401。
 */
public final class SignedRequests {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SignedRequests() {}

    /** 生成一个 nonce：32 个十六进制字符（16 字节随机），长度必须和 {@code ApiKeyAuthFilter.NONCE_HEX_LENGTH} 一致，不对直接 401。 */
    public static String newNonce() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    /** CP2 规范串：版本标签、五段各占一行、请求体换成 SHA-256（与 ApiCredentialService.prehash、tools/api.py 一字不差）。 */
    public static String prehash(long timestampMillis, String nonce, String method,
                                 String path, String body) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return "CP2\n" + timestampMillis + "\n" + nonce + "\n" + method + "\n" + path + "\n" + HexFormat.of().formatHex(digest);
    }

    public static String sign(String secret, long timestampMillis, String nonce,
                              String method, String path, String body) {
        return ApiCredentialService.sign(prehash(timestampMillis, nonce, method, path, body),
                secret);
    }
}
