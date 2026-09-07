package com.chainpay.chain.wallet;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Base58Check：比特币字母表（去掉 0 O I l 四个易混字符）+ 双 SHA-256 的前 4 字节校验和。
 * xpub / xprv 都用它包装，所以任何一位算错，整串都会变。
 */
public final class Base58Check {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger FIFTY_EIGHT = BigInteger.valueOf(58);

    private Base58Check() {}

    public static String encode(byte[] payload) {
        byte[] data = Bip32Math.concat(payload, checksumOf(payload));
        int zeros = 0;
        while (zeros < data.length && data[zeros] == 0) {
            zeros++;
        }
        BigInteger n = new BigInteger(1, data);
        StringBuilder out = new StringBuilder();
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(FIFTY_EIGHT);
            out.append(ALPHABET.charAt(qr[1].intValue()));
            n = qr[0];
        }
        for (int i = 0; i < zeros; i++) {
            out.append('1');                                    // 前导零字节各对应一个 '1'
        }
        return out.reverse().toString();
    }

    /** 解码并核对校验和，返回不含校验和的载荷。 */
    public static byte[] decode(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("空的 Base58 字符串");
        }
        int zeros = 0;
        while (zeros < text.length() && text.charAt(zeros) == '1') {
            zeros++;
        }
        BigInteger n = BigInteger.ZERO;
        for (char c : text.toCharArray()) {
            int digit = ALPHABET.indexOf(c);
            if (digit < 0) {
                throw new IllegalArgumentException("不是 Base58 字符：" + c);
            }
            n = n.multiply(FIFTY_EIGHT).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = n.toByteArray();
        if (magnitude.length > 1 && magnitude[0] == 0) {
            magnitude = Arrays.copyOfRange(magnitude, 1, magnitude.length);   // 去掉 BigInteger 的符号字节
        }
        if (n.signum() == 0) {
            magnitude = new byte[0];
        }
        byte[] data = Bip32Math.concat(new byte[zeros], magnitude);
        if (data.length < 4) {
            throw new IllegalArgumentException("Base58Check 太短，连校验和都装不下");
        }
        byte[] payload = Arrays.copyOf(data, data.length - 4);
        byte[] checksum = Arrays.copyOfRange(data, data.length - 4, data.length);
        if (!Arrays.equals(checksum, checksumOf(payload))) {
            throw new IllegalArgumentException("Base58Check 校验和不对：抄错了一位，或者根本不是扩展密钥");
        }
        return payload;
    }

    private static byte[] checksumOf(byte[] payload) {
        return Arrays.copyOf(Bip32Math.sha256(Bip32Math.sha256(payload)), 4);
    }
}
