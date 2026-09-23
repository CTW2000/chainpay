package com.chainpay.security.crypto;

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
 * 对称加解密 API secret（AES-256-GCM）。
 *
 * <p><b>为什么存密文而不是哈希：</b>签名认证要求服务端<b>用 secret 重算一遍签名</b>，哈希算不回明文，
 * 所以 secret 必须可被服务端还原（币安、OKX 也一样）。存密文的全部安全性押在<b>加密密钥和数据分开存放</b>上：
 * 密钥从环境变量注入，绝不进数据库、代码、git——只拖走数据库备份的攻击者，拿到的是一堆解不开的 Base64。
 *
 * <p><b>算法依据</b> OWASP Cryptographic_Storage Cheat Sheet：对称加密用 AES（最好 256 位），
 * 优先用认证加密模式（GCM / CCM），不用 ECB。GCM 还能<b>发现密文被篡改</b>：CBC / CTR 被改一个字节只会解出乱码而不报错，
 * GCM 直接抛异常——对存钥匙的地方，「悄悄解出错的东西」比「解不开」危险得多。
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
        // 长度不对就在启动时炸掉：配置错误应该让应用「起不来」，而不是「起来了但加密是弱的」——后者没有人会发现。
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
     * 同一个密钥配同一个 IV 加密两条不同的消息，在 GCM 下会<b>直接暴露明文的异或值</b>。
     * IV 不是秘密，但必须唯一，所以每次用 SecureRandom 现生成、拼在密文前面。
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
            // 消息里不带异常内容：加密失败的细节对调用方没用，对攻击者有用。
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
     * <p>必须用 {@link SecureRandom} 而不是 {@code java.util.Random}：后者的种子被推算出来之后，
     * 它此前和此后生成的<b>所有</b>值都能被算出来（OWASP：PRNG <i>"must not be used for anything security critical"</i>）。
     */
    public String generateSecret() {
        return randomToken(32);
    }

    /**
     * 生成一段十六进制随机串，用于 api_key 这类<b>公开但不该被猜到</b>的标识。
     *
     * <p>api_key 不是秘密（明文出现在请求头里、存在库里），但<b>可猜的标识本身就是信息泄露</b>：
     * 递增的 {@code ak_1}、{@code ak_2} 会暴露商户数量与增长，也让暴力破解有了立足点（知道 key 一定存在，只需要猜签名）。
     *
     * @param byteCount 随机字节数；返回的十六进制字符串长度是它的两倍
     */
    public String randomToken(int byteCount) {
        byte[] raw = new byte[byteCount];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }
}
