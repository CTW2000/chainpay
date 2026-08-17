package com.chainpay.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 对称加解密 API secret。
 *
 * <p><b>为什么从「只存哈希」改成「存密文」—— 这是一个被迫的取舍：</b>
 *
 * <p>上一步我们只存 SHA-256 哈希，因为哈希是单向的，数据库泄露也算不回去。
 * 但签名认证要求服务端<b>用 secret 重算一遍签名</b>，而重算需要明文 ——
 * 哈希做不到。币安、OKX 面对同样的问题，选择也一样：secret 必须可被服务端还原。
 *
 * <pre>
 *   存哈希：数据库泄露 = 安全，但无法做签名认证
 *   存密文：数据库泄露 = 仍然安全（前提是加密密钥没跟着泄露），可以做签名认证
 * </pre>
 *
 * <p>所以「存密文」的全部安全性，都押在<b>加密密钥和数据分开存放</b>上。
 * 密钥从环境变量注入，绝不进数据库、绝不进代码、绝不进 git。
 * 只拖走一个数据库备份的攻击者，拿到的是一堆解不开的 Base64。
 *
 * <p><b>算法选择的依据</b>（OWASP Cryptographic_Storage Cheat Sheet 原文）：
 * <ul>
 *   <li><i>"For symmetric encryption AES with a key that's at least 128 bits
 *       (ideally 256 bits)"</i> → 我们用 AES-256</li>
 *   <li><i>"authenticated modes should always be used... GCM and CCM should be used
 *       as a first preference"</i> → 我们用 GCM</li>
 *   <li><i>"ECB should not be used outside of very specific circumstances"</i></li>
 * </ul>
 *
 * <p><b>GCM 是「认证加密」</b>：它除了保密，还能<b>发现密文被篡改</b>。
 * 普通模式（CBC/CTR）被改一个字节，解出来是乱码但不报错，程序会拿着垃圾继续跑；
 * GCM 会直接抛异常。对存钥匙的地方，「悄悄解出错的东西」比「解不开」危险得多。
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** GCM 推荐 12 字节 IV：这是规范推荐值，用其他长度会走较慢的兼容路径。 */
    private static final int IV_LENGTH = 12;
    /** GCM 认证标签 128 位 —— 篡改检测的强度。 */
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param base64Key Base64 编码的 32 字节（256 位）密钥，从环境变量注入。
     *                  生成方式：{@code openssl rand -base64 32}
     */
    public SecretCipher(@Value("${chainpay.secret-key}") String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        // 长度不对就在启动时炸掉，不要等第一个请求进来才发现。
        // 配置错误应该让应用「起不来」，而不是「起来了但加密是弱的」——
        // 后者没有人会发现。
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "chainpay.secret-key 必须是 32 字节（AES-256），当前 " + raw.length + " 字节");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /**
     * 加密，返回 Base64(IV ‖ 密文 ‖ 认证标签)。
     *
     * <p><b>IV（初始化向量）每次必须不同，而且必须随密文一起存。</b>
     * 同一个密钥配同一个 IV 加密两条不同的消息，在 GCM 下会<b>直接暴露明文的异或值</b>，
     * 是灾难性的。所以这里每次用 SecureRandom 现生成，并把它拼在密文前面 ——
     * IV 不是秘密，不需要保护，但必须唯一。
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 不把异常内容带出去：加密失败的细节对调用方没用，对攻击者有用。
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密。密文被改过一个字节都会在这里抛异常，不会悄悄解出垃圾。 */
    public String decrypt(String base64Combined) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Combined);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }

    /**
     * 生成一个新的 API secret（32 字节随机 → 64 位十六进制）。
     *
     * <p>必须用 {@link SecureRandom} 而不是 {@code java.util.Random}：
     * 后者是可预测的伪随机数生成器，种子被推算出来之后，
     * 它此前和此后生成的<b>所有</b>值都能被算出来。
     * OWASP 的原话是 PRNG <i>"must not be used for anything security critical"</i>。
     */
    public String generateSecret() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }
}
