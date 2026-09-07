package com.chainpay.chain.wallet;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;

/** BIP-32 用到的几个原语，规范里的名字：HMAC-SHA512、ser32、ser256、HASH160。 */
final class Bip32Math {

    private Bip32Math() {}

    static byte[] hmacSha512(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 缺少 HmacSHA512", e);
        }
    }

    /** 32 位无符号整数，大端 4 字节。 */
    static byte[] ser32(long i) {
        return new byte[] {(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i};
    }

    /** 256 位整数，大端 32 字节，左补零。BigInteger.toByteArray 可能带一个符号字节或少几个字节，这里都抹平。 */
    static byte[] ser256(BigInteger k) {
        byte[] raw = k.toByteArray();
        if (raw.length == 33 && raw[0] == 0) {
            raw = Arrays.copyOfRange(raw, 1, 33);
        }
        if (raw.length > 32) {
            throw new IllegalStateException("整数超过 256 位");
        }
        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /** HASH160 = RIPEMD160(SHA256(x))，指纹用它的前 4 字节。 */
    static byte[] hash160(byte[] input) {
        byte[] sha = sha256(input);
        RIPEMD160Digest ripemd = new RIPEMD160Digest();
        ripemd.update(sha, 0, sha.length);
        byte[] out = new byte[20];
        ripemd.doFinal(out, 0);
        return out;
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
