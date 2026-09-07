package com.chainpay.chain.wallet;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.bouncycastle.math.ec.ECPoint;

/**
 * BIP-32 扩展公钥（xpub）：公钥 + 链码 + 位置信息（深度、父指纹、序号）。
 *
 * <p>它只能做<b>普通派生</b>（序号 &lt; 2^31）：
 * <pre>
 *   I   = HMAC-SHA512(key = 父链码, data = ser_P(父公钥) ‖ ser_32(i))
 *   K_i = point(parse_256(I_L)) + K_par          ← 只用到父公钥，这就是「服务器不需要私钥」的数学
 *   c_i = I_R
 * </pre>
 * 硬化派生（序号 ≥ 2^31）的 data 里放的是父私钥，xpub 做不了，这是设计使然：账户层以上的层都硬化，
 * 泄露 xpub 只能枚举地址，推不出账户层之上的任何密钥。
 *
 * <p>序列化 78 字节：版本 4（xpub 0x0488B21E）‖ 深度 1 ‖ 父指纹 4 ‖ 序号 4 ‖ 链码 32 ‖ 压缩公钥 33，再 Base58Check。
 */
public final class ExtendedPublicKey {

    /** 硬化派生的分界：序号 ≥ 2^31 需要父私钥。 */
    public static final long HARDENED = 0x80000000L;

    static final int VERSION_XPUB = 0x0488B21E;
    static final int VERSION_XPRV = 0x0488ADE4;

    private final int depth;
    private final byte[] parentFingerprint;
    private final long childNumber;
    private final byte[] chainCode;
    private final ECPoint publicKey;

    ExtendedPublicKey(int depth, byte[] parentFingerprint, long childNumber, byte[] chainCode, ECPoint publicKey) {
        this.depth = depth;
        this.parentFingerprint = parentFingerprint.clone();
        this.childNumber = childNumber;
        this.chainCode = chainCode.clone();
        this.publicKey = publicKey.normalize();
    }

    public static ExtendedPublicKey parse(String xpub) {
        byte[] raw;
        try {
            raw = Base58Check.decode(xpub == null ? "" : xpub.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不是合法的 xpub：" + e.getMessage(), e);
        }
        if (raw.length != 78) {
            throw new IllegalArgumentException("扩展密钥应为 78 字节，收到 " + raw.length + " 字节");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int version = buf.getInt();
        if (version != VERSION_XPUB) {
            throw new IllegalArgumentException(String.format("版本字节不是 xpub 的 %08X，收到 %08X%s", VERSION_XPUB, version,
                    version == VERSION_XPRV ? "：这是一把 xprv，私钥不该出现在这里" : ""));
        }
        int depth = buf.get() & 0xFF;
        byte[] fingerprint = new byte[4];
        buf.get(fingerprint);
        long child = buf.getInt() & 0xFFFFFFFFL;
        byte[] chain = new byte[32];
        buf.get(chain);
        byte[] key = new byte[33];
        buf.get(key);
        ECPoint point;
        try {
            point = Secp256k1.curve().decodePoint(key);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("xpub 里的公钥不在曲线上", e);
        }
        return new ExtendedPublicKey(depth, fingerprint, child, chain, point);
    }

    /** CKDpub：只用父公钥与链码派生子公钥。 */
    public ExtendedPublicKey deriveChild(long index) {
        if (index < 0 || index >= HARDENED) {
            throw new IllegalArgumentException("xpub 只能做普通派生（序号 0 到 2^31−1），序号 " + index
                    + " 是硬化派生，需要父私钥");
        }
        if (depth >= 255) {
            throw new IllegalStateException("派生深度已达上限 255");
        }
        byte[] i = Bip32Math.hmacSha512(chainCode, Bip32Math.concat(publicKey.getEncoded(true), Bip32Math.ser32(index)));
        BigInteger il = new BigInteger(1, Arrays.copyOfRange(i, 0, 32));
        if (il.compareTo(Secp256k1.n()) >= 0) {
            throw new IllegalStateException("BIP-32：I_L ≥ n，序号 " + index + " 不可用（概率约 2^-127），换下一个序号");
        }
        ECPoint child = Secp256k1.g().multiply(il).add(publicKey).normalize();
        if (child.isInfinity()) {
            throw new IllegalStateException("BIP-32：子公钥是无穷远点，序号 " + index + " 不可用，换下一个序号");
        }
        return new ExtendedPublicKey(depth + 1, fingerprint(), index, Arrays.copyOfRange(i, 32, 64), child);
    }

    public String serialize() {
        ByteBuffer buf = ByteBuffer.allocate(78);
        buf.putInt(VERSION_XPUB).put((byte) depth).put(parentFingerprint).putInt((int) childNumber)
                .put(chainCode).put(publicKey.getEncoded(true));
        return Base58Check.encode(buf.array());
    }

    /** 这把公钥对应的以太坊地址（EIP-55 写法）。 */
    public String ethAddress() {
        return EthAddress.fromPublicKey(publicKey);
    }

    /** 指纹 = HASH160(压缩公钥) 的前 4 字节，写进子密钥的「父指纹」字段。 */
    public byte[] fingerprint() {
        return Arrays.copyOf(Bip32Math.hash160(publicKey.getEncoded(true)), 4);
    }

    public int depth() {
        return depth;
    }

    public ECPoint publicKey() {
        return publicKey;
    }
}
