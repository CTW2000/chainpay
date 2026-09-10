package com.chainpay.chain.wallet;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * 热钱包私钥的离线派生：助记词 → 种子 → {@code m/44'/60'/1'/0/0}。
 *
 * <p>账户层用 <b>1'</b>（硬化）而不是收款树的 0'：普通派生的子私钥 = 父私钥 + 一个由 xpub 就能算出的偏移量，
 * 所以「收款树的 xpub + 任意一个普通派生的子私钥」能反推出父私钥、进而所有收款地址的私钥；
 * 硬化派生的偏移量用父私钥算，拿着 xpub 算不出，链在 44'/60' 下面断成两支。
 * 用另一句助记词当然也行，隔离更彻底；两种都被 {@code HotWalletDerivationTest} 证明不在收款树里。
 */
public final class HotWalletDerivation {

    public static final String PATH = "m/44'/60'/1'/0/0";

    private HotWalletDerivation() {}

    public record Derived(String privateKeyHex, String address) {}

    public static Derived fromMnemonic(String mnemonic) {
        byte[] seed = Bip39.seed(mnemonic, "");
        try {
            BigInteger key = ExtendedPrivateKey.fromSeed(seed).derivePath(PATH).key();
            return new Derived("0x" + String.format("%064x", key), EthAddress.fromPublicKey(Secp256k1.point(key)));
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
    }
}
