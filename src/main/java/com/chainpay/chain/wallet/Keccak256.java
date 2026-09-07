package com.chainpay.chain.wallet;

import org.bouncycastle.jcajce.provider.digest.Keccak;

/**
 * Keccak-256。以太坊用的是标准化之前的 Keccak，和 NIST 定稿的 SHA3-256 只差一个填充字节，结果完全不同——
 * JDK 自带的 {@code MessageDigest.getInstance("SHA3-256")} 算出来的<b>不是</b>以太坊地址。
 */
public final class Keccak256 {

    private Keccak256() {}

    public static byte[] hash(byte[] input) {
        return new Keccak.Digest256().digest(input);
    }
}
