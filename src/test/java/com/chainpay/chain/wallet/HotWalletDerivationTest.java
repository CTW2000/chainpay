package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.support.AbstractPostgresTest;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 热钱包私钥从助记词的<b>硬化</b>账户 1' 派生：拿着收款树（账户 0'）的 xpub 算不出它，它也算不出收款树的任何私钥。
 * 用公开的 Hardhat 助记词做自洽检查；账户 1' 的地址没有公开的已知答案，所以只证明结构：路径硬化、与签名器一致、不在收款树里。
 */
@DisplayName("M4-① · 热钱包离线派生")
class HotWalletDerivationTest {

    static final String HARDHAT_MNEMONIC = "test test test test test test test test test test test junk";

    @Test
    @DisplayName("★ 路径在账户层是硬化的（1'），不是收款树的账户 0'")
    void thePathIsAHardenedSiblingOfTheDepositAccount() {
        assertThat(HotWalletDerivation.PATH).isEqualTo("m/44'/60'/1'/0/0");
    }

    @Test
    @DisplayName("★ 派生出的私钥装进签名器，地址一致；私钥是 0x + 64 位十六进制")
    void derivedKeyAndAddressAgree() {
        HotWalletDerivation.Derived derived = HotWalletDerivation.fromMnemonic(HARDHAT_MNEMONIC);

        assertThat(derived.privateKeyHex()).matches("0x[0-9a-f]{64}");
        assertThat(HotWalletSigner.fromHex(derived.privateKeyHex()).address()).isEqualTo(derived.address());
        assertThat(derived.address()).matches("0x[0-9a-fA-F]{40}");
    }

    @Test
    @DisplayName("★ 热钱包地址不在收款树的前一百个地址里：同一助记词，但隔着一层硬化派生")
    void theHotWalletIsNotADepositAddress() {
        String hotWallet = EthAddress.lowercase(HotWalletDerivation.fromMnemonic(HARDHAT_MNEMONIC).address());
        DepositAddressDeriver deposits = new DepositAddressDeriver(AbstractPostgresTest.HARDHAT_ACCOUNT_XPUB);

        assertThat(IntStream.range(0, 100).mapToObj(i -> EthAddress.lowercase(deposits.addressAt(i))))
                .doesNotContain(hotWallet);
    }
}
