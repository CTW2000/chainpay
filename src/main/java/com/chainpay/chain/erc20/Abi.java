package com.chainpay.chain.erc20;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 我们用到的那一点 ABI：函数选择器、地址参数编码、uint 与 string 的返回值解码。
 *
 * <p>选择器 = keccak256(函数签名) 的前 4 个字节。四个值是 ERC-20 世界里人人可查的常量
 * （4byte.directory / EIP-20），测试里当已知答案钉住。
 *
 * <p>编码规则（ABI 规范）：每个静态参数占 32 字节，地址左补零；动态类型 string 先给一个字的偏移量（32），
 * 再给一个字的字节长度，然后是内容，右补零到 32 字节的整数倍。形状不对一律拒绝，不猜。
 */
public final class Abi {

    public static final String DECIMALS = "0x313ce567";      // decimals()
    public static final String SYMBOL = "0x95d89b41";        // symbol()
    public static final String NAME = "0x06fdde03";          // name()
    public static final String BALANCE_OF = "0x70a08231";    // balanceOf(address)

    private static final int WORD_HEX = 64;                  // 32 字节 = 64 个十六进制字符
    private static final String ADDRESS_PADDING = "000000000000000000000000";   // 12 字节的零

    private Abi() {}

    /** 选择器后面接每个地址参数，各左补零到 32 字节。 */
    public static String encodeCall(String selector, String... addressArgs) {
        StringBuilder data = new StringBuilder(selector);
        for (String address : addressArgs) {
            if (address == null || !address.matches("0x[0-9a-fA-F]{40}")) {
                throw new IllegalArgumentException("不是地址：" + address);
            }
            data.append(ADDRESS_PADDING).append(address.substring(2).toLowerCase());
        }
        return data.toString();
    }

    /** 恰好一个 32 字节的字：uint256 / uint8 都这么回。空返回、长度不对都拒绝。 */
    public static BigInteger decodeUint(String hex) {
        String body = body(hex);
        if (body.length() != WORD_HEX) {
            throw new IllegalArgumentException("uint 返回值应恰好 32 字节，收到 " + body.length() / 2 + " 字节：" + preview(hex));
        }
        return new BigInteger(body, 16);
    }

    /** 动态 string：偏移量一个字、长度一个字、内容按 32 字节补齐。 */
    public static String decodeString(String hex) {
        String body = body(hex);
        if (body.length() < 2 * WORD_HEX || body.length() % WORD_HEX != 0) {
            throw new IllegalArgumentException("不是 ABI 动态 string（至少两个字，且按 32 字节对齐）：" + preview(hex));
        }
        long offset = new BigInteger(body.substring(0, WORD_HEX), 16).longValueExact();
        if (offset != 32) {
            throw new IllegalArgumentException("动态 string 的偏移量应为 32，收到 " + offset + "：" + preview(hex));
        }
        int length = new BigInteger(body.substring(WORD_HEX, 2 * WORD_HEX), 16).intValueExact();
        int contentEnd = 2 * WORD_HEX + length * 2;
        if (contentEnd > body.length()) {
            throw new IllegalArgumentException("动态 string 声称 " + length + " 字节，返回值不够长：" + preview(hex));
        }
        byte[] bytes = HexFormat.of().parseHex(body.substring(2 * WORD_HEX, contentEnd));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String encodeUint(BigInteger value) {
        if (value == null || value.signum() < 0 || value.bitLength() > 256) {
            throw new IllegalArgumentException("不是 uint256：" + value);
        }
        return "0x" + String.format("%064x", value);
    }

    public static String encodeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String content = HexFormat.of().formatHex(bytes);
        int padded = (content.length() + WORD_HEX - 1) / WORD_HEX * WORD_HEX;
        return "0x" + word(32) + word(bytes.length) + content + "0".repeat(padded - content.length());
    }

    private static String word(long value) {
        return String.format("%064x", value);
    }

    private static String body(String hex) {
        if (hex == null || !hex.startsWith("0x")) {
            throw new IllegalArgumentException("不是 0x 开头的返回值：" + hex);
        }
        return hex.substring(2);
    }

    private static String preview(String hex) {
        return hex == null ? "null" : hex.length() <= 20 ? hex : hex.substring(0, 20) + "…";
    }
}
