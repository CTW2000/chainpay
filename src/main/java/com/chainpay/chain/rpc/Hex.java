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
        String digits = hex.substring(2);
        // 以太坊的 Quantity 永远是非负十六进制；Long.parseLong 与 BigInteger 都接受前导 -/+，只查前缀会把 0x-1 放成 -1（2026-09-09 扫描补丁）
        if (!digits.matches("[0-9a-fA-F]*")) {
            throw new IllegalArgumentException("不是十六进制数量（只允许 0-9a-f）：" + hex);
        }
        return digits;
    }
}
