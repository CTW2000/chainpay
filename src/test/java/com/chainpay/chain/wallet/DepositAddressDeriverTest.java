package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 端到端的已知答案：Hardhat 的公开默认助记词，路径 m/44'/60'/0'/0/i（v2.hardhat.org 参考文档，2026-09-07 取得）。
 * 它是公开的、一文不值的助记词，可以进测试；它证明的是「派生算法对」，证明不了「配进服务器的 xpub 是你的」（M3-before 第 30 问）。
 *
 * <p>链路：助记词 → BIP-39 种子（无 passphrase）→ 主密钥 → 硬化派生到账户层 m/44'/60'/0' → neuter 成 xpub →
 * <b>服务端只拿 xpub</b> → 普通派生 0/i → Keccak 地址 → EIP-55。
 */
@DisplayName("M3-① · 收款地址派生（零私钥）")
class DepositAddressDeriverTest {

    static final String HARDHAT_MNEMONIC = "test test test test test test test test test test test junk";
    static final String ACCOUNT_0 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String ACCOUNT_1 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
    static final String ACCOUNT_2 = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC";

    @Test
    @DisplayName("★ 只拿账户层 xpub，派生出的前三个地址等于 Hardhat 公布的三个")
    void derivesHardhatAddressesFromTheAccountXpubOnly() {
        String accountXpub = ExtendedPrivateKey.fromSeed(Bip39.seed(HARDHAT_MNEMONIC, ""))
                .derivePath("m/44'/60'/0'").neuter().serialize();

        DepositAddressDeriver deriver = new DepositAddressDeriver(accountXpub);

        assertThat(deriver.addressAt(0)).isEqualTo(ACCOUNT_0);
        assertThat(deriver.addressAt(1)).isEqualTo(ACCOUNT_1);
        assertThat(deriver.addressAt(2)).isEqualTo(ACCOUNT_2);
    }

    @Test
    @DisplayName("★ 不是账户层的 xpub（深度不是 3）拒绝：配了根 xpub 或找零层 xpub，派出来的地址没人有私钥")
    void rejectsAnXpubAtTheWrongDepth() {
        ExtendedPrivateKey master = ExtendedPrivateKey.fromSeed(Bip39.seed(HARDHAT_MNEMONIC, ""));
        String rootXpub = master.neuter().serialize();
        String changeLevelXpub = master.derivePath("m/44'/60'/0'/0").neuter().serialize();

        assertThatThrownBy(() -> new DepositAddressDeriver(rootXpub))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("深度");
        assertThatThrownBy(() -> new DepositAddressDeriver(changeLevelXpub))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("深度");
    }

    @Test
    @DisplayName("序号超出普通派生范围（≥ 2^31）拒绝")
    void rejectsHardenedIndex() {
        String accountXpub = ExtendedPrivateKey.fromSeed(Bip39.seed(HARDHAT_MNEMONIC, ""))
                .derivePath("m/44'/60'/0'").neuter().serialize();

        assertThatThrownBy(() -> new DepositAddressDeriver(accountXpub).addressAt(ExtendedPublicKey.HARDENED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
