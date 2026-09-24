package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EIP-55 的测试用例（eips.ethereum.org/EIPS/eip-55「Test Cases」），期望值逐字。 */
@DisplayName("EIP-55 校验和")
class EthAddressTest {

    static final List<String> VECTORS = List.of(
            "0x52908400098527886E0F7030069857D2E4169EE7",   // all caps
            "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
            "0xde709f2102306220921060314715629080e2fb77",   // all lower
            "0x27b1fdb04752bbc536007a920d24acb045561c26",
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",   // normal
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb");

    @Test
    @DisplayName("★ 八个规范地址：从全小写算出的校验和写法逐字相同")
    void checksumMatchesTheSpecVectors() {
        for (String expected : VECTORS) {
            assertThat(EthAddress.checksummed(expected.toLowerCase())).as(expected).isEqualTo(expected);
            assertThat(EthAddress.checksummed(expected.toUpperCase().replace("0X", "0x"))).as(expected).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("lowercase：校验形状并转小写；不成形的拒绝")
    void lowercaseValidatesAndNormalizes() {
        assertThat(EthAddress.lowercase("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")).isEqualTo("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed");
        for (String bad : List.of("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed", "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beae", "0xzz")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> EthAddress.lowercase(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 地址的形状只在 EthAddress 写一份，注解用 {@code SHAPE}、代码用 {@code isWellFormed}。
     * 两者必须对每个样本给同一个答案——否则就是「请求里放行、代码里拒绝」或者反过来，边界从中间裂开。
     */
    @Test
    @DisplayName("★ 地址的形状：SHAPE（给注解）与 isWellFormed（给代码）对每个样本答案一致")
    void shapeAndIsWellFormedAgree() {
        String hex = "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
        List<String> good = List.of("0x" + hex, "0x" + hex.toUpperCase(Locale.ROOT), "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed");
        List<String> bad = Arrays.asList(null, "", "0x", hex, "0X" + hex, "0x" + hex.substring(1), "0x" + hex + "0",
                "0x" + hex.substring(1) + "g", " 0x" + hex, "0x" + hex + " ", "0x" + "٣".repeat(40));
        for (String s : good) {
            assertThat(EthAddress.isWellFormed(s)).as(s).isTrue();
            assertThat(s.matches(EthAddress.SHAPE)).as(s).isTrue();
        }
        for (String s : bad) {
            assertThat(EthAddress.isWellFormed(s)).as(String.valueOf(s)).isFalse();
            if (s != null) {
                assertThat(s.matches(EthAddress.SHAPE)).as(s).isFalse();
            }
        }
    }
}
