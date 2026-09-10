package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 私钥 → 公钥 → 地址：向量逐字来自 ethereum/tests 的 BasicTests/keyaddrtest.json。 */
@DisplayName("M4-① · 私钥到地址的规范向量")
class KeyAddressVectorsTest {

    static Stream<Arguments> cases() throws Exception {
        try (InputStream in = KeyAddressVectorsTest.class.getResourceAsStream("/vectors/keyaddrtest.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<Arguments> out = new ArrayList<>();
            for (JsonNode c : root) {
                out.add(Arguments.of(c.get("seed").asString(), c.get("key").asString(), c.get("addr").asString()));
            }
            assertThat(out).isNotEmpty();
            return out.stream();
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("cases")
    @DisplayName("★ 地址 = Keccak(公钥 x‖y) 的后 20 字节")
    void addressFollowsFromThePrivateKey(String seed, String key, String addr) {
        String address = EthAddress.fromPublicKey(Secp256k1.point(new BigInteger(key, 16)));

        assertThat(EthAddress.lowercase(address)).isEqualTo("0x" + addr);
    }
}
