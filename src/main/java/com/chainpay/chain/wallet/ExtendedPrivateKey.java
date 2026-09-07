package com.chainpay.chain.wallet;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.math.ec.ECPoint;

/**
 * BIP-32 扩展私钥（xprv）。<b>服务端永远不构造它</b>：只有 {@link XpubTool} 和测试用它，WalletBoundaryTest 扫源码守着。
 *
 * <pre>
 *   主密钥：I = HMAC-SHA512(key = "Bitcoin seed", data = 种子)，I_L 是主私钥，I_R 是主链码
 *   硬化派生：data = 0x00 ‖ ser_256(k_par) ‖ ser_32(i)      ← 用的是父私钥，所以 xpub 做不了
 *   普通派生：data = ser_P(point(k_par)) ‖ ser_32(i)
 *   k_i = parse_256(I_L) + k_par (mod n)，c_i = I_R
 * </pre>
 */
public final class ExtendedPrivateKey {

    public static final long HARDENED = 0x80000000L;

    private final int depth;
    private final byte[] parentFingerprint;
    private final long childNumber;
    private final byte[] chainCode;
    private final BigInteger key;

    private ExtendedPrivateKey(int depth, byte[] parentFingerprint, long childNumber, byte[] chainCode, BigInteger key) {
        this.depth = depth;
        this.parentFingerprint = parentFingerprint.clone();
        this.childNumber = childNumber;
        this.chainCode = chainCode.clone();
        this.key = key;
    }

    public static ExtendedPrivateKey fromSeed(byte[] seed) {
        if (seed == null || seed.length < 16 || seed.length > 64) {
            throw new IllegalArgumentException("BIP-32 种子应为 16 到 64 字节");
        }
        byte[] i = Bip32Math.hmacSha512("Bitcoin seed".getBytes(StandardCharsets.US_ASCII), seed);
        BigInteger il = new BigInteger(1, Arrays.copyOfRange(i, 0, 32));
        if (il.signum() == 0 || il.compareTo(Secp256k1.n()) >= 0) {
            throw new IllegalStateException("这个种子产生了无效的主密钥（概率约 2^-127），换一个种子");
        }
        return new ExtendedPrivateKey(0, new byte[4], 0, Arrays.copyOfRange(i, 32, 64), il);
    }

    public ExtendedPrivateKey deriveChild(long index) {
        if (index < 0 || index > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("序号超出 32 位：" + index);
        }
        if (depth >= 255) {
            throw new IllegalStateException("派生深度已达上限 255");
        }
        byte[] data = index >= HARDENED
                ? Bip32Math.concat(new byte[] {0}, Bip32Math.ser256(key), Bip32Math.ser32(index))
                : Bip32Math.concat(publicPoint().getEncoded(true), Bip32Math.ser32(index));
        byte[] i = Bip32Math.hmacSha512(chainCode, data);
        BigInteger il = new BigInteger(1, Arrays.copyOfRange(i, 0, 32));
        if (il.compareTo(Secp256k1.n()) >= 0) {
            throw new IllegalStateException("BIP-32：I_L ≥ n，序号 " + index + " 不可用，换下一个序号");
        }
        BigInteger childKey = il.add(key).mod(Secp256k1.n());
        if (childKey.signum() == 0) {
            throw new IllegalStateException("BIP-32：子私钥为 0，序号 " + index + " 不可用，换下一个序号");
        }
        return new ExtendedPrivateKey(depth + 1, fingerprint(), index, Arrays.copyOfRange(i, 32, 64), childKey);
    }

    /** 按 "m/44'/60'/0'" 这样的路径逐级派生；' 表示硬化。 */
    public ExtendedPrivateKey derivePath(String path) {
        if (path == null || !path.matches("m(/\\d+'?)*")) {
            throw new IllegalArgumentException("路径格式应为 m/44'/60'/0'，收到：" + path);
        }
        ExtendedPrivateKey current = this;
        for (String segment : path.substring(1).split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            boolean hardened = segment.endsWith("'");
            long index = Long.parseLong(hardened ? segment.substring(0, segment.length() - 1) : segment);
            if (index >= HARDENED) {
                throw new IllegalArgumentException("序号超出范围：" + segment);
            }
            current = current.deriveChild(hardened ? index + HARDENED : index);
        }
        return current;
    }

    /** 去掉私钥，得到同一位置的 xpub。这是交给服务器的那份。 */
    public ExtendedPublicKey neuter() {
        return new ExtendedPublicKey(depth, parentFingerprint, childNumber, chainCode, publicPoint());
    }

    public String serialize() {
        ByteBuffer buf = ByteBuffer.allocate(78);
        buf.putInt(ExtendedPublicKey.VERSION_XPRV).put((byte) depth).put(parentFingerprint).putInt((int) childNumber)
                .put(chainCode).put((byte) 0).put(Bip32Math.ser256(key));
        return Base58Check.encode(buf.array());
    }

    private ECPoint publicPoint() {
        return Secp256k1.point(key);
    }

    private byte[] fingerprint() {
        return Arrays.copyOf(Bip32Math.hash160(publicPoint().getEncoded(true)), 4);
    }
}
