package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RLP 是以太坊的序列化：哈希是对字节算的，同一笔交易必须在任何实现里得到同一串字节。
 * 向量逐字来自 ethereum/tests 的 RLPTests/rlptest.json（见 src/test/resources/vectors/README.md）。
 * 向量里以 # 开头的字符串表示大整数，其余字符串按 UTF-8 字节；数字按整数；数组按列表。
 */
@DisplayName("M4-① · RLP 规范向量（28 例）")
class RlpVectorsTest {

    static Stream<Arguments> cases() throws Exception {
        try (InputStream in = RlpVectorsTest.class.getResourceAsStream("/vectors/rlptest.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<Arguments> out = new ArrayList<>();
            root.properties().forEach(e -> out.add(Arguments.of(e.getKey(), e.getValue().get("in"), e.getValue().get("out").asString())));
            assertThat(out).as("向量文件不能是空的").hasSizeGreaterThanOrEqualTo(20);
            return out.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("★ 编码与规范逐字节相同")
    void encodesExactlyAsTheSpecSays(String name, JsonNode in, String out) {
        assertThat(hex(Rlp.encode(toItem(in)))).as(name).isEqualTo(out);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("解码再编码回到同一串字节")
    void decodeRoundTrips(String name, JsonNode in, String out) {
        byte[] bytes = HexFormat.of().parseHex(out.substring(2));
        assertThat(Rlp.encode(Rlp.decode(bytes))).as(name).isEqualTo(bytes);
    }

    @Test
    @DisplayName("★ 整数按去前导零的大端：12 → 0c，0 → 80，255 → 81ff，256 → 820100；负数拒绝")
    void integersAreCanonical() {
        assertThat(hex(Rlp.encode(Rlp.integer(12)))).isEqualTo("0x0c");
        assertThat(hex(Rlp.encode(Rlp.integer(0)))).isEqualTo("0x80");
        assertThat(hex(Rlp.encode(Rlp.integer(255)))).isEqualTo("0x81ff");
        assertThat(hex(Rlp.encode(Rlp.integer(256)))).isEqualTo("0x820100");
        assertThatThrownBy(() -> Rlp.integer(BigInteger.valueOf(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    // 解码委托给 web3j 之后不再单独验「拒绝非规范形式」：库的解码器是宽松的，而我们只解码自己签出的字节。
    // 规范性由交易层守：Eip1559Transaction.decode 拆回再编回必须与原文逐字节相同（Eip1559VectorTest 的官方反例）。

    static Rlp.Item toItem(JsonNode node) {
        if (node.isString()) {
            String text = node.asString();
            if (text.startsWith("#")) {
                return Rlp.integer(new BigInteger(text.substring(1)));
            }
            return Rlp.bytes(text.getBytes(StandardCharsets.UTF_8));
        }
        if (node.isNumber()) {
            return Rlp.integer(node.asLong());
        }
        if (node.isArray()) {
            List<Rlp.Item> items = new ArrayList<>();
            for (JsonNode child : node) {
                items.add(toItem(child));
            }
            return Rlp.list(items);
        }
        throw new IllegalArgumentException("向量里出现了不认识的形状：" + node);
    }

    static String hex(byte[] bytes) {
        return "0x" + HexFormat.of().formatHex(bytes);
    }
}
