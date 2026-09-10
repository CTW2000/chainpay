package com.chainpay.chain.wallet;

import java.math.BigInteger;

/**
 * 热钱包签名器：进程里<b>唯一</b>持有付款私钥的对象。只暴露两个动作——地址是什么、签这笔——文字表示只含地址。
 *
 * <p>私钥从环境变量 {@code CHAINPAY_PAYOUT_HOT_WALLET_KEY} 装入一次（{@code chain.payout.config.PayoutConfig}），
 * 之后只以 {@link BigInteger} 存在于内存。诚实说明：BigInteger 不可变、无法擦除，它会在堆里活到进程结束；
 * 这是 Java 里能做到的边界，比「以字符串到处传」好，但不是硬件钱包那种「密钥永不出芯片」。
 *
 * <p>形状不对的私钥在装入时就拒绝，错误消息只说长度与范围，永不回显内容。
 */
public final class HotWalletSigner {

    private final BigInteger privateKey;
    private final String address;

    private HotWalletSigner(BigInteger privateKey) {
        this.privateKey = privateKey;
        this.address = EthAddress.fromPublicKey(Secp256k1.point(privateKey));
    }

    /** 0x + 64 位十六进制（0x 可省）；必须在 [1, n−1] 内。 */
    public static HotWalletSigner fromHex(String privateKeyHex) {
        if (privateKeyHex == null || privateKeyHex.isBlank()) {
            throw new IllegalArgumentException("热钱包私钥为空");
        }
        String hex = privateKeyHex.strip();
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (!hex.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("热钱包私钥必须是 64 位十六进制（可带 0x），收到 " + hex.length() + " 个字符");
        }
        BigInteger d = new BigInteger(hex, 16);
        if (d.signum() <= 0 || d.compareTo(Secp256k1.n()) >= 0) {
            throw new IllegalArgumentException("热钱包私钥必须在 [1, n−1] 内");
        }
        return new HotWalletSigner(d);
    }

    /** EIP-55 写法的地址。 */
    public String address() {
        return address;
    }

    /** 签一笔类型 2 交易：算签名哈希、RFC 6979 签名、接上签名得到原文与交易哈希。 */
    public Eip1559Transaction.Signed sign(Eip1559Transaction transaction) {
        return transaction.sign(Ecdsa.sign(transaction.signingHash(), privateKey));
    }

    @Override
    public String toString() {
        return "HotWalletSigner[" + address + "]";
    }
}
