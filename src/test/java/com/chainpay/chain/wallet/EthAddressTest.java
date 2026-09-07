package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EIP-55 的测试用例（eips.ethereum.org/EIPS/eip-55「Test Cases」，2026-09-07 取得），期望值逐字。 */
@DisplayName("M3-① · EIP-55 校验和")
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
}
