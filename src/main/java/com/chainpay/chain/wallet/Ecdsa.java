package com.chainpay.chain.wallet;

import java.math.BigInteger;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

/**
 * secp256k1 上的 ECDSA：签 32 字节的哈希，签名是 (r, s, yParity)；从签名与哈希恢复地址。
 *
 * <p>实现委托给 web3j（2026-09-09 改判）：{@code Sign.signMessage} 用 RFC 6979 的确定性 k 并把 s 归一到 ≤ n/2，
 * {@code Sign.recoverFromSignature} 做节点做的那件事——交易没有 from 字段，from 是从签名恢复出来的。
 * 两条会丢钱的规矩（k 不能重复、s 取小的那个）现在由官方向量与自洽测试对库验收：EIP-155 算例的 (r, s) 逐位相同，
 * 24 条消息里 s 永远 ≤ n/2。本类只保留输入校验与不依赖 web3j 类型的小接口。
 */
public final class Ecdsa {

    private Ecdsa() {}

    /** 签名。r、s 在 [1, n−1]，yParity 是 0 或 1。 */
    public record Signature(BigInteger r, BigInteger s, int yParity) {
        public Signature {
            requireScalar(r, "r");
            requireScalar(s, "s");
            if (yParity != 0 && yParity != 1) {
                throw new IllegalArgumentException("yParity 只能是 0 或 1：" + yParity);
            }
        }
    }

    public static Signature sign(byte[] hash32, BigInteger privateKey) {
        requireHash(hash32);
        requireScalar(privateKey, "私钥");
        Sign.SignatureData data = Sign.signMessage(hash32, ECKeyPair.create(privateKey), false);
        int v = data.getV()[0] & 0xff;                                     // web3j 用 27 / 28 表示奇偶
        return new Signature(new BigInteger(1, data.getR()), new BigInteger(1, data.getS()), v - 27);
    }

    /**
     * 恢复出的地址，EIP-55 写法。公钥 → 地址这一步走 web3j 的 {@code Keys.getAddress}，而 {@link EthAddress#fromPublicKey}
     * 是我们自己的实现：两套并存是有意的——测试（Eip155VectorTest、HotWalletSignerTest）把两条路的结果互相比对，
     * 一份实现错了另一份会揭发它。合成一份就失去这层对拍。
     */
    public static String recoverAddress(byte[] hash32, Signature signature) {
        requireHash(hash32);
        BigInteger publicKey = Sign.recoverFromSignature(signature.yParity(),
                new ECDSASignature(signature.r(), signature.s()), hash32);
        if (publicKey == null) {
            throw new IllegalArgumentException("签名无法恢复出公钥");
        }
        return EthAddress.checksummed("0x" + Keys.getAddress(publicKey));
    }

    private static void requireHash(byte[] hash32) {
        if (hash32 == null || hash32.length != 32) {
            throw new IllegalArgumentException("签的必须是 32 字节的哈希，收到 " + (hash32 == null ? "null" : hash32.length + " 字节"));
        }
    }

    private static void requireScalar(BigInteger value, String name) {
        if (value == null || value.signum() <= 0 || value.compareTo(Secp256k1.n()) >= 0) {
            throw new IllegalArgumentException(name + " 必须在 [1, n−1] 内");
        }
    }

    /** 32 字节定长的大端写法：web3j 的 SignatureData 要 r、s 各 32 字节。 */
    static byte[] toFixed32(BigInteger value) {
        return Bip32Math.ser256(value);      // 与 BIP-32 的 ser256 是同一件事，只写一遍
    }
}
