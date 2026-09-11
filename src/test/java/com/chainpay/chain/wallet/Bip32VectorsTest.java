package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BIP-32 的官方测试向量（bip-0032.mediawiki「Test Vectors」，2026-09-07 用 curl 取 bitcoin/bips 原文逐字核对；
 * 第一次经概括模型转述时 m/0'/1/2' 的 xprv 被抄错一个字母，Base58Check 校验和立刻不成立——已知答案必须来自原始文本）。
 * 期望值一个字符都不改。xpub 里任何一位算错，Base58Check 的校验和就对不上，整串就不同。
 */
@DisplayName("M3-① · BIP-32 规范向量")
class Bip32VectorsTest {

    // ---- Test vector 1 ----
    static final byte[] SEED_1 = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f");
    static final String V1_M_XPUB = "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8";
    static final String V1_M_XPRV = "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi";
    static final String V1_M0H_XPUB = "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw";
    static final String V1_M0H_XPRV = "xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7";
    static final String V1_M0H_1_XPUB = "xpub6ASuArnXKPbfEwhqN6e3mwBcDTgzisQN1wXN9BJcM47sSikHjJf3UFHKkNAWbWMiGj7Wf5uMash7SyYq527Hqck2AxYysAA7xmALppuCkwQ";
    static final String V1_M0H_1_XPRV = "xprv9wTYmMFdV23N2TdNG573QoEsfRrWKQgWeibmLntzniatZvR9BmLnvSxqu53Kw1UmYPxLgboyZQaXwTCg8MSY3H2EU4pWcQDnRnrVA1xe8fs";
    static final String V1_M0H_1_2H_XPUB = "xpub6D4BDPcP2GT577Vvch3R8wDkScZWzQzMMUm3PWbmWvVJrZwQY4VUNgqFJPMM3No2dFDFGTsxxpG5uJh7n7epu4trkrX7x7DogT5Uv6fcLW5";
    static final String V1_M0H_1_2H_XPRV = "xprv9z4pot5VBttmtdRTWfWQmoH1taj2axGVzFqSb8C9xaxKymcFzXBDptWmT7FwuEzG3ryjH4ktypQSAewRiNMjANTtpgP4mLTj34bhnZX7UiM";

    // ---- Test vector 2 ----
    static final byte[] SEED_2 = HexFormat.of().parseHex(
            "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542");
    static final String V2_M_XPUB = "xpub661MyMwAqRbcFW31YEwpkMuc5THy2PSt5bDMsktWQcFF8syAmRUapSCGu8ED9W6oDMSgv6Zz8idoc4a6mr8BDzTJY47LJhkJ8UB7WEGuduB";
    static final String V2_M_XPRV = "xprv9s21ZrQH143K31xYSDQpPDxsXRTUcvj2iNHm5NUtrGiGG5e2DtALGdso3pGz6ssrdK4PFmM8NSpSBHNqPqm55Qn3LqFtT2emdEXVYsCzC2U";
    // 向量 3：专门测 ser256 的前导零——I_L 算出的私钥高位是 0 时，序列化必须补满 32 字节，否则 xprv 整串就错
    static final byte[] SEED_3 = HexFormat.of().parseHex(
            "4b381541583be4423346c643850da4b320e46a87ae3d2a4e6da11eba819cd4acba45d239319ac14f863b8d5ab5a0d0c64d2e8a1e7d1457df2e5a3c51c73235be");
    static final String V3_M_XPUB = "xpub661MyMwAqRbcEZVB4dScxMAdx6d4nFc9nvyvH3v4gJL378CSRZiYmhRoP7mBy6gSPSCYk6SzXPTf3ND1cZAceL7SfJ1Z3GC8vBgp2epUt13";
    static final String V3_M_XPRV = "xprv9s21ZrQH143K25QhxbucbDDuQ4naNntJRi4KUfWT7xo4EKsHt2QJDu7KXp1A3u7Bi1j8ph3EGsZ9Xvz9dGuVrtHHs7pXeTzjuxBrCmmhgC6";
    static final String V3_M0H_XPUB = "xpub68NZiKmJWnxxS6aaHmn81bvJeTESw724CRDs6HbuccFQN9Ku14VQrADWgqbhhTHBaohPX4CjNLf9fq9MYo6oDaPPLPxSb7gwQN3ih19Zm4Y";
    static final String V3_M0H_XPRV = "xprv9uPDJpEQgRQfDcW7BkF7eTya6RPxXeJCqCJGHuCJ4GiRVLzkTXBAJMu2qaMWPrS7AANYqdq6vcBcBUdJCVVFceUvJFjaPdGZ2y9WACViL4L";
    // 向量 4：同样是前导零，多一级硬化
    static final byte[] SEED_4 = HexFormat.of().parseHex("3ddd5602285899a946114506157c7997e5444528f3003f6134712147db19b678");
    static final String V4_M_XPUB = "xpub661MyMwAqRbcGczjuMoRm6dXaLDEhW1u34gKenbeYqAix21mdUKJyuyu5F1rzYGVxyL6tmgBUAEPrEz92mBXjByMRiJdba9wpnN37RLLAXa";
    static final String V4_M_XPRV = "xprv9s21ZrQH143K48vGoLGRPxgo2JNkJ3J3fqkirQC2zVdk5Dgd5w14S7fRDyHH4dWNHUgkvsvNDCkvAwcSHNAQwhwgNMgZhLtQC63zxwhQmRv";
    static final String V4_M0H_1H_XPUB = "xpub6BJA1jSqiukeaesWfxe6sNK9CCGaujFFSJLomWHprUL9DePQ4JDkM5d88n49sMGJxrhpjazuXYWdMf17C9T5XnxkopaeS7jGk1GyyVziaMt";
    static final String V4_M0H_1H_XPRV = "xprv9xJocDuwtYCMNAo3Zw76WENQeAS6WGXQ55RCy7tDJ8oALr4FWkuVoHJeHVAcAqiZLE7Je3vZJHxspZdFHfnBEjHqU5hG1Jaj32dVoS6XLT1";
    static final String V2_M0_XPUB = "xpub69H7F5d8KSRgmmdJg2KhpAK8SR3DjMwAdkxj3ZuxV27CprR9LgpeyGmXUbC6wb7ERfvrnKZjXoUmmDznezpbZb7ap6r1D3tgFxHmwMkQTPH";
    static final String V2_M0_XPRV = "xprv9vHkqa6EV4sPZHYqZznhT2NPtPCjKuDKGY38FBWLvgaDx45zo9WQRUT3dKYnjwih2yJD9mkrocEZXo1ex8G81dwSM1fwqWpWkeS3v86pgKt";

    @Test
    @DisplayName("★ 向量 1：种子 → 主密钥，xprv / xpub 与规范逐字相同")
    void masterKeyFromSeed() {
        ExtendedPrivateKey m = ExtendedPrivateKey.fromSeed(SEED_1);

        assertThat(m.serialize()).isEqualTo(V1_M_XPRV);
        assertThat(m.neuter().serialize()).isEqualTo(V1_M_XPUB);
    }

    @Test
    @DisplayName("★ 向量 1：硬化派生 m/0'（只有私钥能做）")
    void hardenedChild() {
        ExtendedPrivateKey m0h = ExtendedPrivateKey.fromSeed(SEED_1).deriveChild(ExtendedPrivateKey.HARDENED);

        assertThat(m0h.serialize()).isEqualTo(V1_M0H_XPRV);
        assertThat(m0h.neuter().serialize()).isEqualTo(V1_M0H_XPUB);
    }

    @Test
    @DisplayName("★ 向量 1：m/0'/1 用 xpub 做普通派生（CKDpub），结果等于用私钥派生再 neuter")
    void publicDerivationMatchesPrivateDerivation() {
        ExtendedPublicKey fromXpub = ExtendedPublicKey.parse(V1_M0H_XPUB).deriveChild(1);
        ExtendedPrivateKey viaPrivate = ExtendedPrivateKey.fromSeed(SEED_1).derivePath("m/0'/1");

        assertThat(fromXpub.serialize()).isEqualTo(V1_M0H_1_XPUB);
        assertThat(viaPrivate.serialize()).isEqualTo(V1_M0H_1_XPRV);
        assertThat(viaPrivate.neuter().serialize()).isEqualTo(V1_M0H_1_XPUB);
    }

    @Test
    @DisplayName("★ 向量 1：路径 m/0'/1/2' 三级混合派生")
    void mixedPath() {
        ExtendedPrivateKey key = ExtendedPrivateKey.fromSeed(SEED_1).derivePath("m/0'/1/2'");

        assertThat(key.serialize()).isEqualTo(V1_M0H_1_2H_XPRV);
        assertThat(key.neuter().serialize()).isEqualTo(V1_M0H_1_2H_XPUB);
    }

    @Test
    @DisplayName("★ 向量 2：64 字节种子；m/0 用 xpub 普通派生")
    void vectorTwo() {
        ExtendedPrivateKey m = ExtendedPrivateKey.fromSeed(SEED_2);

        assertThat(m.serialize()).isEqualTo(V2_M_XPRV);
        assertThat(m.neuter().serialize()).isEqualTo(V2_M_XPUB);
        assertThat(ExtendedPublicKey.parse(V2_M_XPUB).deriveChild(0).serialize()).isEqualTo(V2_M0_XPUB);
        assertThat(m.deriveChild(0).serialize()).isEqualTo(V2_M0_XPRV);
    }

    @Test
    @DisplayName("xpub 解析与序列化互逆；深度读得出来")
    void parseRoundTrip() {
        assertThat(ExtendedPublicKey.parse(V1_M0H_1_XPUB).serialize()).isEqualTo(V1_M0H_1_XPUB);
        assertThat(ExtendedPublicKey.parse(V1_M_XPUB).depth()).isZero();
        assertThat(ExtendedPublicKey.parse(V1_M0H_1_2H_XPUB).depth()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 拒绝：校验和错一位、把 xprv 当 xpub、长度不对、硬化序号")
    void rejectsMalformedInput() {
        String corrupted = V1_M_XPUB.substring(0, V1_M_XPUB.length() - 1) + "9";
        assertThatThrownBy(() -> ExtendedPublicKey.parse(corrupted))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("校验和");
        assertThatThrownBy(() -> ExtendedPublicKey.parse(V1_M_XPRV))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("xpub");
        assertThatThrownBy(() -> ExtendedPublicKey.parse("xpub"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExtendedPublicKey.parse(V1_M_XPUB).deriveChild(ExtendedPublicKey.HARDENED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("硬化");
    }

    @Test
    @DisplayName("★ 向量 3 / 4：私钥高位为零的种子，xprv 与 xpub 仍与规范逐字相同（ser256 必须补满 32 字节）")
    void leadingZeroVectorsSerializeCorrectly() {
        ExtendedPrivateKey m3 = ExtendedPrivateKey.fromSeed(SEED_3);
        assertThat(m3.serialize()).isEqualTo(V3_M_XPRV);
        assertThat(m3.neuter().serialize()).isEqualTo(V3_M_XPUB);
        ExtendedPrivateKey m3h = m3.deriveChild(ExtendedPrivateKey.HARDENED);
        assertThat(m3h.serialize()).isEqualTo(V3_M0H_XPRV);
        assertThat(m3h.neuter().serialize()).isEqualTo(V3_M0H_XPUB);

        ExtendedPrivateKey m4 = ExtendedPrivateKey.fromSeed(SEED_4);
        assertThat(m4.serialize()).isEqualTo(V4_M_XPRV);
        assertThat(m4.neuter().serialize()).isEqualTo(V4_M_XPUB);
        ExtendedPrivateKey m4h1h = m4.derivePath("m/0'/1'");
        assertThat(m4h1h.serialize()).isEqualTo(V4_M0H_1H_XPRV);
        assertThat(m4h1h.neuter().serialize()).isEqualTo(V4_M0H_1H_XPUB);
    }
}
