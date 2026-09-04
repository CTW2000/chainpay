package com.chainpay.chain.erc20;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ABI 编解码的已知答案测试。期望值从 ABI 规范和公开的函数选择器手写，不是 Java 自己算自己比。
 *
 * <p>string 的编码规则（ABI 规范「动态类型」）：第一个字是数据的偏移量（0x20 = 32），
 * 第二个字是字节长度，然后是内容，右补零到 32 字节的整数倍。
 */
@DisplayName("M2-⑥ · ABI 编解码")
class AbiTest {

    static final String ALICE = "0x4281ecf07378ee595c564a59048801330f3084ee";
    static final String WORD_32 = "0000000000000000000000000000000000000000000000000000000000000020";
    static final String WORD_4 = "0000000000000000000000000000000000000000000000000000000000000004";
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
    @DisplayName("编码与解码互逆（假节点用编码造答案）")
    void encodeAndDecodeRoundTrip() {
        assertThat(Abi.encodeUint(BigInteger.valueOf(18)))
                .isEqualTo("0x0000000000000000000000000000000000000000000000000000000000000012");
        assertThat(Abi.encodeString("LINK")).isEqualTo("0x" + WORD_32 + WORD_4 + LINK_PADDED);
        assertThat(Abi.decodeString(Abi.encodeString("Wrapped Ether"))).isEqualTo("Wrapped Ether");
    }
}
