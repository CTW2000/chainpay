package com.chainpay.chain.wallet;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * BIP-39：助记词 → 512 位种子。只给 {@link XpubTool} 与测试用，服务端不碰助记词。
 *
 * <p>种子 = PBKDF2-HMAC-SHA512(密码 = NFKD(助记词), 盐 = "mnemonic" + NFKD(口令), 2048 轮, 64 字节)。
 * 词是给人抄的，种子才是密钥树的根。这里不做词表校验：算法由规范向量钉住，
 * 抄错词的后果由工具打印的前三个地址与钱包对照来发现。
 */
public final class Bip39 {

    private static final int ROUNDS = 2048;
    private static final int SEED_BITS = 512;

    private Bip39() {}

    public static byte[] seed(String mnemonic, String passphrase) {
        if (mnemonic == null || mnemonic.isBlank()) {
            throw new IllegalArgumentException("助记词为空");
        }
        String words = Normalizer.normalize(mnemonic.trim().replaceAll("\\s+", " "), Normalizer.Form.NFKD);
        String salt = Normalizer.normalize("mnemonic" + (passphrase == null ? "" : passphrase), Normalizer.Form.NFKD);
        PBEKeySpec spec = new PBEKeySpec(words.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), ROUNDS, SEED_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 缺少 PBKDF2WithHmacSHA512", e);
        } finally {
            spec.clearPassword();
        }
    }
}
