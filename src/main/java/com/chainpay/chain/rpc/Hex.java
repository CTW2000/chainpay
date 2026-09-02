package com.chainpay.chain.rpc;

import java.math.BigInteger;

/**
 * 以太坊 JSON-RPC 里所有数字都是 {@code 0x} 开头的十六进制<b>字符串</b>。
 *
 * <p>为什么不是 JSON number：JS 的 number 是 double，装不下 uint256。
 * 和我们 M1 把金额做成字符串是同一个理由——链在 2015 年就做了这个选择。
 */
public final class Hex {

    private Hex() {}

    public static long toLong(String hex) {
        return Long.parseLong(strip(hex), 16);
    }

    /** uint256 用 {@link BigInteger}——long 只有 64 位，装不下。 */
    public static BigInteger toBigInteger(String hex) {
        String digits = strip(hex);
        return digits.isEmpty() ? BigInteger.ZERO : new BigInteger(digits, 16);
    }

    public static String fromLong(long value) {
        return "0x" + Long.toHexString(value);
    }

    private static String strip(String hex) {
        if (hex == null || !hex.startsWith("0x")) {
            throw new IllegalArgumentException("不是 0x 开头的十六进制：" + hex);
        }
        return hex.substring(2);
    }
}
