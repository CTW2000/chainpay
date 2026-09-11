package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 词表与已知答案都逐字取自原文：词表 bitcoin/bips 620871a 的 bip-0039/english.txt（sha256 2f5eed53…24dbda）；
 * 熵 → 助记词的向量取自 trezor/python-mnemonic b57a5ad 的 vectors.json（2026-09-11 浅克隆）。
 */
@DisplayName("M4-⑤ 前置 · BIP-39 助记词：词表、熵到词、校验位")
class MnemonicTest {

    static final String WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda";

    @Test
    @DisplayName("★ 词表：2048 个词、已排序、不重复、abandon 开头 zoo 结尾、sha256 与原文一致")
    void wordlistIsTheOfficialOne() throws Exception {
        List<String> words = Mnemonic.words();
        assertThat(words).hasSize(2048).isSorted().doesNotHaveDuplicates();
        assertThat(words.get(0)).isEqualTo("abandon");
        assertThat(words.get(2047)).isEqualTo("zoo");
        byte[] raw = MnemonicTest.class.getResourceAsStream("/bip39/english.txt").readAllBytes();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))).isEqualTo(WORDLIST_SHA256);
        assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain("\r");
    }

    @Test
    @DisplayName("★ 已知答案：四条 128 位熵与一条 256 位熵，助记词与规范向量逐字相同")
    void entropyToMnemonicMatchesTheVectors() {
        assertThat(Mnemonic.fromEntropy(hex("00000000000000000000000000000000")))
                .isEqualTo("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about");
        assertThat(Mnemonic.fromEntropy(hex("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f")))
                .isEqualTo("legal winner thank year wave sausage worth useful legal winner thank yellow");
        assertThat(Mnemonic.fromEntropy(hex("80808080808080808080808080808080")))
                .isEqualTo("letter advice cage absurd amount doctor acoustic avoid letter advice cage above");
        assertThat(Mnemonic.fromEntropy(hex("ffffffffffffffffffffffffffffffff")))
                .isEqualTo("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong");
        assertThat(Mnemonic.fromEntropy(hex("0000000000000000000000000000000000000000000000000000000000000000")))
                .isEqualTo("abandon ".repeat(23) + "art");
    }

    @Test
    @DisplayName("★ 校验位：规范向量都通过；最后一个词换掉、词序调换、不在词表里的词，都不通过")
    void checksumCatchesTampering() {
        assertThat(Mnemonic.checksumValid("legal winner thank year wave sausage worth useful legal winner thank yellow")).isTrue();
        assertThat(Mnemonic.checksumValid("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong")).isTrue();
        assertThat(Mnemonic.checksumValid("legal winner thank year wave sausage worth useful legal winner thank zoo")).isFalse();
        assertThat(Mnemonic.checksumValid("winner legal thank year wave sausage worth useful legal winner thank yellow")).isFalse();
        assertThat(Mnemonic.checksumValid("legal winner thank year wave sausage worth useful legal winner thank notaword")).isFalse();
        assertThat(Mnemonic.checksumValid("legal winner thank")).isFalse();
    }

    @Test
    @DisplayName("★ 生成：12 个词都在词表里、校验位通过、两次不同、算得出种子")
    void generatedMnemonicIsValidAndFresh() {
        String a = Mnemonic.generate12();
        String b = Mnemonic.generate12();
        assertThat(a.split(" ")).hasSize(12).allMatch(w -> Mnemonic.words().contains(w));
        assertThat(Mnemonic.checksumValid(a)).isTrue();
        assertThat(a).isNotEqualTo(b);
        assertThat(Bip39.seed(a, "")).hasSize(64);
    }

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s);
    }
}
