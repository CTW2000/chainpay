package com.chainpay.chain.wallet;

/**
 * 从账户层 xpub（m/44'/60'/0'，深度 3）派生第 i 个收款地址 m/44'/60'/0'/0/i。服务端唯一需要的入口。
 *
 * <p>深度不是 3 就拒绝：配了根 xpub 或找零层 xpub，派出来的地址没有任何钱包会持有它的私钥，打进去的钱永远丢失。
 * 深度只能证明「是第三层」，证明不了「是 44'/60'/0' 这一支」（硬化路径从 xpub 推不回来），
 * 那一环靠工具打印的前三个地址与 MetaMask 对照（M3-before 第 30 问）。
 */
public final class DepositAddressDeriver {

    private static final int ACCOUNT_DEPTH = 3;
    private static final long EXTERNAL_CHAIN = 0;     // BIP-44 的「找零」层：0 = 对外收款，1 = 找零

    private final ExtendedPublicKey externalChain;

    public DepositAddressDeriver(String accountXpub) {
        ExtendedPublicKey account = ExtendedPublicKey.parse(accountXpub);
        if (account.depth() != ACCOUNT_DEPTH) {
            throw new IllegalArgumentException("收款地址要从账户层 xpub 派生（m/44'/60'/0'，深度 " + ACCOUNT_DEPTH
                    + "），收到深度 " + account.depth() + "：配错层派出来的地址没有人持有私钥，钱会永远丢失");
        }
        this.externalChain = account.deriveChild(EXTERNAL_CHAIN);
    }

    /** m/44'/60'/0'/0/index 的地址，EIP-55 写法。 */
    public String addressAt(long index) {
        return externalChain.deriveChild(index).ethAddress();
    }
}
