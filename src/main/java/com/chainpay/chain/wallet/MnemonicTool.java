package com.chainpay.chain.wallet;

import java.io.Console;

/**
 * 离线小工具：生成一句 12 词的 BIP-39 助记词并打印一次，同时打印它派生出的热钱包地址（m/44'/60'/1'/0/0，不是秘密）。
 * 助记词只在这个 JVM 的内存里活几百毫秒：不落盘、不进日志、不进任何参数。只在真实终端里运行（没有 Console 就拒绝），请断网。
 */
public final class MnemonicTool {

    private MnemonicTool() {}

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("助记词只在真实终端里显示：管道、IDE 控制台、CI 拿不到 Console，拒绝运行");
        }
        System.err.println("chainpay · 离线助记词工具：生成 12 个词，只显示这一次。请断网、确认身后没人、抄在纸上并核对顺序。");
        String mnemonic = Mnemonic.generate12();
        String address = HotWalletDerivation.fromMnemonic(mnemonic).address();
        System.out.println();
        System.out.println("助记词（12 词，按顺序抄）：");
        System.out.println("    " + mnemonic);
        System.out.println();
        System.out.println("它派生的热钱包地址（m/44'/60'/1'/0/0，可以记下来对照）：" + address);
        System.out.println();
        System.out.println("抄完之后：清屏；断网跑 tools/hotwallet.sh 输入这 12 个词得到私钥；私钥进 env/local.env；纸收好。");
    }
}
