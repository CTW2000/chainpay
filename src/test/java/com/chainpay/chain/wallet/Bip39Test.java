package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BIP-39 官方向量（trezor/python-mnemonic 的 vectors.json，2026-09-07 浅克隆取得）。
 * 这组向量的 passphrase 是 "TREZOR"（tests/test_mnemonic.py 第 37 行）。
 */
@DisplayName("M3-① · BIP-39 规范向量")
class Bip39Test {

    @Test
    @DisplayName("★ 全零熵的助记词 → 种子与根 xprv 逐字相同")
    void allZeroEntropy() {
        byte[] seed = Bip39.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "TREZOR");

        assertThat(HexFormat.of().formatHex(seed)).isEqualTo(
                "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04");
        assertThat(ExtendedPrivateKey.fromSeed(seed).serialize()).isEqualTo(
                "xprv9s21ZrQH143K3h3fDYiay8mocZ3afhfULfb5GX8kCBdno77K4HiA15Tg23wpbeF1pLfs1c5SPmYHrEpTuuRhxMwvKDwqdKiGJS9XFKzUsAF");
    }

    @Test
    @DisplayName("★ 第二条向量：legal winner thank …")
    void secondVector() {
        byte[] seed = Bip39.seed("legal winner thank year wave sausage worth useful legal winner thank yellow", "TREZOR");

        assertThat(HexFormat.of().formatHex(seed)).isEqualTo(
                "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607");
        assertThat(ExtendedPrivateKey.fromSeed(seed).serialize()).isEqualTo(
                "xprv9s21ZrQH143K2gA81bYFHqU68xz1cX2APaSq5tt6MFSLeXnCKV1RVUJt9FWNTbrrryem4ZckN8k4Ls1H6nwdvDTvnV7zEXs2HgPezuVccsq");
    }
}
