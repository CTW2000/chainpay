package com.chainpay.chain.erc20;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ABI 编解码的已知答案测试。期望值从 ABI 规范和公开的函数选择器手写，不是 Java 自己算自己比。
 *
 * <p>string 的编码规则（ABI 规范「动态类型」）：第一个字是数据的偏移量（0x20 = 32），
 * 第二个字是字节长度，然后是内容，右补零到 32 字节的整数倍。
 *
 * <p>偏移量和长度这两个字是<b>对方给的</b>：一个 32 字节的字能表示的数远大于机器整数，
 * 先收窄再检查等于没检查。所有形状不对的返回值都必须是 {@link IllegalArgumentException}，调用方只接这一种。
 */
@DisplayName("M2-⑥ · ABI 编解码")
class AbiTest {

    static final String ALICE = "0x4281ecf07378ee595c564a59048801330f3084ee";
    static final String WORD_32 = "0000000000000000000000000000000000000000000000000000000000000020";
    static final String WORD_4 = "0000000000000000000000000000000000000000000000000000000000000004";
    static final String WORD_100 = "0000000000000000000000000000000000000000000000000000000000000064";
    static final String WORD_2_POW_30 = "0000000000000000000000000000000000000000000000000000000040000000";   // int 能装，×2 就溢出
    static final String WORD_2_POW_31 = "0000000000000000000000000000000000000000000000000000000080000000";   // int 装不下
    static final String WORD_2_POW_64 = "0000000000000000000000000000000000000000000000010000000000000000";   // long 装不下
    static final String LINK_PADDED = "4c494e4b00000000000000000000000000000000000000000000000000000000";

    @Test
    @DisplayName("★ 四个选择器是公开常量：keccak256 签名的前 4 字节")
    void selectorsAreTheWellKnownConstants() {
        assertThat(Abi.DECIMALS).isEqualTo("0x313ce567");
        assertThat(Abi.SYMBOL).isEqualTo("0x95d89b41");
        assertThat(Abi.NAME).isEqualTo("0x06fdde03");
        assertThat(Abi.BALANCE_OF).isEqualTo("0x70a08231");
    }

    @Test
    @DisplayName("★ balanceOf(address)：选择器 + 地址左补 12 字节的零")
    void encodesAnAddressArgument() {
        assertThat(Abi.encodeCall(Abi.BALANCE_OF, ALICE))
                .isEqualTo("0x70a08231" + "000000000000000000000000" + "4281ecf07378ee595c564a59048801330f3084ee");
        assertThat(Abi.encodeCall(Abi.DECIMALS)).isEqualTo("0x313ce567");
    }

    @Test
    @DisplayName("encodeCall 拒绝不成形的地址：少一位、多一位、非十六进制、没有 0x、null")
    void encodeCallRejectsMalformedAddresses() {
        for (String bad : List.of(ALICE.substring(0, 41), ALICE + "0", ALICE.substring(0, 40) + "zz", ALICE.substring(2))) {
            assertThatThrownBy(() -> Abi.encodeCall(Abi.BALANCE_OF, bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Abi.encodeCall(Abi.BALANCE_OF, (String) null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("uint 解码：一个 32 字节的字；18 → decimals")
    void decodesAUint() {
        assertThat(Abi.decodeUint("0x0000000000000000000000000000000000000000000000000000000000000012"))
                .isEqualTo(BigInteger.valueOf(18));
        assertThat(Abi.decodeUint("0x" + "f".repeat(64))).isEqualTo(BigInteger.TWO.pow(256).subtract(BigInteger.ONE));
    }

    @Test
    @DisplayName("★ uint 解码拒绝空返回和长度不对的返回：不猜")
    void rejectsMalformedUint() {
        assertThatThrownBy(() -> Abi.decodeUint("0x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Abi.decodeUint("0x12")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("★ string 解码：偏移 32、长度 4、内容 LINK 右补零 → \"LINK\"")
    void decodesADynamicString() {
        assertThat(Abi.decodeString("0x" + WORD_32 + WORD_4 + LINK_PADDED)).isEqualTo("LINK");
        assertThat(Abi.decodeString("0x" + WORD_32 + "0".repeat(64))).isEmpty();
    }

    @Test
    @DisplayName("string 解码拒绝形状不对的返回（比如 MKR 那种 bytes32 的 symbol）")
    void rejectsMalformedString() {
        assertThatThrownBy(() -> Abi.decodeString("0x" + "4d4b5200" + "0".repeat(56)))    // 单字 bytes32
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Abi.decodeString("0x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("string 解码：声称的长度比实际给的内容长，拒绝")
    void rejectsAStringThatClaimsMoreBytesThanPresent() {
        assertThatThrownBy(() -> Abi.decodeString("0x" + WORD_32 + WORD_100 + LINK_PADDED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不够长");
    }

    @Test
    @DisplayName("★ 长度字或偏移字大到机器整数装不下：仍是 IllegalArgumentException，不是溢出漏出来的别的异常")
    void rejectsOversizedLengthAndOffsetWordsAsMalformed() {
        assertThatThrownBy(() -> Abi.decodeString("0x" + WORD_32 + WORD_2_POW_31 + LINK_PADDED))
                .as("长度 2^31：int 装不下").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Abi.decodeString("0x" + WORD_32 + WORD_2_POW_30 + LINK_PADDED))
                .as("长度 2^30：×2 溢出成负数，会绕过边界检查").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Abi.decodeString("0x" + WORD_2_POW_64 + WORD_4 + LINK_PADDED))
                .as("偏移 2^64：long 装不下").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("编码与解码互逆（假节点用编码造答案）")
    void encodeAndDecodeRoundTrip() {
        assertThat(Abi.encodeUint(BigInteger.valueOf(18)))
                .isEqualTo("0x0000000000000000000000000000000000000000000000000000000000000012");
        assertThat(Abi.encodeString("LINK")).isEqualTo("0x" + WORD_32 + WORD_4 + LINK_PADDED);
        assertThat(Abi.decodeString(Abi.encodeString("Wrapped Ether"))).isEqualTo("Wrapped Ether");
    }
}
