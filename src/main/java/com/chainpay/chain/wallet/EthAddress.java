package com.chainpay.chain.wallet;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.bouncycastle.math.ec.ECPoint;

/**
 * 以太坊地址 = Keccak-256(公钥的 64 字节 x‖y) 的最后 20 字节。大小写是 EIP-55 校验和：
 * 把小写十六进制地址当 ASCII 再做一次 Keccak-256，第 i 个字符对应哈希的第 i 个半字节，半字节 ≥ 8 就大写。
 * 抄错一位，校验和大概率对不上，钱包会拒绝——所以对外一律给带校验和的写法，存库一律小写。
 *
 * <p>web3j 的 {@code Keys.getAddress / toChecksumAddress} 是同一算法的另一份实现。有意保留自写版、不委托库：
 * {@link Ecdsa#recoverAddress} 走库、这里走自写，测试（Eip155VectorTest、HotWalletSignerTest）让两条路互相对拍，
 * 合成一份就失去这层对拍。证据是 EIP-55 正文 8 例、ethereum/tests 的 keyaddrtest 与两条路的一致性断言。
 */
public final class EthAddress {

    /**
     * 地址的形状：0x 加 40 位十六进制，十六进制部分大小写不限（大小写就是 EIP-55 的校验和）。
     * 全项目只在这里写这一份（{@code SingleHomeGuardTest} 守着）：
     * 请求记录与配置的 {@code @Pattern} 用这个字符串，代码里用 {@link #isWellFormed}。
     * 存库的写法是另一件事——一律小写，由库里的函数 is_eth_address（V28）经各表的 CHECK 守。
     */
    public static final String SHAPE = "0x[0-9a-fA-F]{40}";

    /** 预编译一次：{@code String.matches} 每调一次都要现编一次正则，而索引器每条日志都要问。 */
    private static final Pattern SHAPE_PATTERN = Pattern.compile(SHAPE);

    private EthAddress() {}

    /** 形状对不对；不校验 EIP-55 校验和（十六进制部分全小写、全大写也是合法地址）。null 算不对。 */
    public static boolean isWellFormed(String address) {
        return address != null && SHAPE_PATTERN.matcher(address).matches();
    }

    /** 从公钥算地址，返回 EIP-55 带校验和的写法。 */
    public static String fromPublicKey(ECPoint publicKey) {
        byte[] uncompressed = publicKey.normalize().getEncoded(false);          // 0x04 ‖ x ‖ y，65 字节
        byte[] hash = Keccak256.hash(Arrays.copyOfRange(uncompressed, 1, 65));  // 哈希的是 x‖y，不含 0x04
        return checksummed("0x" + HexFormat.of().formatHex(Arrays.copyOfRange(hash, 12, 32)));
    }

    /** 校验形状并转成小写：存库一律小写（CHECK 约束要求），对外展示才用 {@link #checksummed}。 */
    public static String lowercase(String address) {
        if (!isWellFormed(address)) {
            throw new IllegalArgumentException("不是地址：" + address);
        }
        return address.toLowerCase(Locale.ROOT);
    }

    /** 给一个地址加上 EIP-55 校验和（输入大小写不限）。 */
    public static String checksummed(String address) {
        if (!isWellFormed(address)) {
            throw new IllegalArgumentException("不是地址：" + address);
        }
        String lower = address.substring(2).toLowerCase(Locale.ROOT);
        byte[] hash = Keccak256.hash(lower.getBytes(StandardCharsets.US_ASCII));
        StringBuilder out = new StringBuilder("0x");
        for (int i = 0; i < 40; i++) {
            int nibble = i % 2 == 0 ? (hash[i / 2] >> 4) & 0xF : hash[i / 2] & 0xF;
            char c = lower.charAt(i);
            out.append(nibble >= 8 ? Character.toUpperCase(c) : c);
        }
        return out.toString();
    }
}
