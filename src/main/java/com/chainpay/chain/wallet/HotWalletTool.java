package com.chainpay.chain.wallet;

import java.io.IOException;

/**
 * 离线小工具：读一行助记词，打印热钱包（m/44'/60'/1'/0/0）的私钥与地址。
 *
 * <p>私钥只打印这一次：粘进 {@code env/local.env} 的 {@code CHAINPAY_PAYOUT_HOT_WALLET_KEY} 之后清屏、别进 shell 历史。
 * 请<b>断网</b>运行：{@code tools/hotwallet.sh}。助记词不进参数、不进环境变量、不落盘，读法同 {@link XpubTool}。
 */
public final class HotWalletTool {

    private HotWalletTool() {}

    public static void main(String[] args) throws IOException {
        System.err.println("chainpay · 离线热钱包工具：读一行助记词，输出 m/44'/60'/1'/0/0 的私钥与地址。请断网运行。");
        System.err.println("用另一句助记词，或与收款树同一句——账户 1' 是硬化派生，和收款树（账户 0'）互相推不出。");
        String mnemonic = XpubTool.readMnemonic(System.console());
        HotWalletDerivation.Derived derived = HotWalletDerivation.fromMnemonic(mnemonic);
        System.out.println("CHAINPAY_PAYOUT_HOT_WALLET_KEY=" + derived.privateKeyHex());
        System.out.println("热钱包地址 = " + derived.address() + "   ← 给它领 Sepolia ETH（付 gas）并转入 LINK");
        System.err.println("上面那行私钥只显示这一次：粘进 env/local.env 后清屏。");
    }
}
