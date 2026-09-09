package com.chainpay.chain.wallet;

import java.io.Console;
import java.io.IOException;
import java.util.Arrays;

/**
 * 离线小工具：读一行助记词，只打印 m/44'/60'/0' 的账户层 xpub 与前三个地址。
 *
 * <p>不回显（有终端时用 readPassword）、不落盘、不记日志；助记词只在这个进程的内存里活几百毫秒。
 * 请<b>断网</b>运行：{@code tools/xpub.sh}。前三个地址要和 MetaMask 里同一助记词的前三个账户一致，
 * 一致才说明配进服务器的 xpub 是你钱包的那一支。
 */
public final class XpubTool {

    private XpubTool() {}

    public static void main(String[] args) throws IOException {
        System.err.println("chainpay · 离线 xpub 工具：读一行助记词，输出 m/44'/60'/0' 的 xpub 与前三个地址。请断网运行。");
        String mnemonic = readMnemonic(System.console());
        byte[] seed = Bip39.seed(mnemonic, "");
        ExtendedPrivateKey account;
        try {
            account = ExtendedPrivateKey.fromSeed(seed).derivePath("m/44'/60'/0'");
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
        String xpub = account.neuter().serialize();
        System.out.println("CHAINPAY_DEPOSIT_XPUB=" + xpub);
        DepositAddressDeriver deriver = new DepositAddressDeriver(xpub);
        for (int i = 0; i < 3; i++) {
            System.out.println("m/44'/60'/0'/0/" + i + " = " + deriver.addressAt(i) + "   ← 应等于 MetaMask 第 " + (i + 1) + " 个账户");
        }
    }

    /**
     * 只从真实终端读，不回显。没有 Console（管道、IDE 控制台、CI）就拒绝——此前退回明文 readLine，
     * 把「不回显」这条硬要求悄悄丢掉了（2026-09-09 扫描补丁）。
     *
     * <p>诚实说明：readPassword 给的 char[] 在这里被转成了 String，NFKD 归一化也只对 String 做，
     * 于是助记词以不可清零的 String 在堆里多活到 GC 为止。这是离线短命进程里可接受的残余，不是被忽略的。
     */
    static String readMnemonic(Console console) {
        if (console == null) {
            throw new IllegalStateException("助记词只在真实终端里交互读取（不回显）：管道、IDE 控制台、CI 拿不到 Console，拒绝运行");
        }
        char[] chars = console.readPassword("助记词（输入时不显示）: ");
        if (chars == null || chars.length == 0) {
            throw new IllegalArgumentException("没有读到助记词");
        }
        try {
            return new String(chars);
        } finally {
            Arrays.fill(chars, ' ');
        }
    }
}
