package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 热钱包签名器：进程里唯一持有私钥的对象。只暴露两个动作——地址是什么、签这笔——文字表示不含密钥。
 * 已知答案：Hardhat 公开的测试私钥 #0，其地址 0xf39F…2266（M3 用同一助记词的 xpub 做过 KAT）。
 */
@DisplayName("M4-① · 热钱包签名器")
class HotWalletSignerTest {

    static final String HARDHAT_KEY_0 = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    static final String HARDHAT_ADDRESS_0 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";

    @Test
    @DisplayName("★ 从十六进制私钥装入，地址与已知答案一致；带不带 0x 都行")
    void addressMatchesTheKnownAnswer() {
        assertThat(HotWalletSigner.fromHex(HARDHAT_KEY_0).address()).isEqualTo(HARDHAT_ADDRESS_0);
        assertThat(HotWalletSigner.fromHex(HARDHAT_KEY_0.substring(2)).address()).isEqualTo(HARDHAT_ADDRESS_0);
    }

    @Test
    @DisplayName("★ 签一笔：从原文恢复出的签名者就是它的地址")
    void signsSoThatTheSignerCanBeRecovered() {
        Eip1559Transaction tx = new Eip1559Transaction(11_155_111L, 3, BigInteger.valueOf(1_500_000_000L),
                BigInteger.valueOf(30_000_000_000L), 65_000, LINK, BigInteger.ZERO, new byte[] {(byte) 0xa9, 0x05, (byte) 0x9c, (byte) 0xbb});
        Eip1559Transaction.Signed signed = HotWalletSigner.fromHex(HARDHAT_KEY_0).sign(tx);

        Eip1559Transaction.Decoded decoded = Eip1559Transaction.decode(signed.raw());
        assertThat(Ecdsa.recoverAddress(decoded.transaction().signingHash(), decoded.signature())).isEqualTo(HARDHAT_ADDRESS_0);
        assertThat(signed.hash()).isEqualTo(Keccak256.hash(signed.raw()));
    }

    @Test
    @DisplayName("★ 文字表示只有地址，没有密钥的任何一段")
    void toStringNeverContainsTheKey() {
        String text = HotWalletSigner.fromHex(HARDHAT_KEY_0).toString();

        assertThat(text).contains(HARDHAT_ADDRESS_0);
        assertThat(text.toLowerCase()).doesNotContain(HARDHAT_KEY_0.substring(2, 18));
    }

    @Test
    @DisplayName("★ 形状不对的私钥一律拒绝：长度不对、不是十六进制、0、等于曲线的阶")
    void rejectsMalformedKeys() {
        assertThatThrownBy(() -> HotWalletSigner.fromHex("0x1234")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HotWalletSigner.fromHex("0x" + "zz".repeat(32))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HotWalletSigner.fromHex("0x" + "00".repeat(32))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HotWalletSigner.fromHex("0x" + Secp256k1.n().toString(16))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HotWalletSigner.fromHex(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
