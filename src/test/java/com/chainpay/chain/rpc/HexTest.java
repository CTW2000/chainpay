package com.chainpay.chain.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 翻译层是外部 JSON 的唯一入口：以太坊的 Quantity 永远是非负的十六进制，带符号或非法字符的都不是节点该给的东西。
 * {@code Long.parseLong} 与 {@code new BigInteger} 都接受前导减号，所以「只查 0x 前缀」会把 {@code 0x-1} 放成 -1。
 */
@DisplayName("扫描补丁 · 十六进制解析只接受 [0-9a-fA-F]")
class HexTest {

    @Test
    @DisplayName("★ 0x-1 不是数量：拒绝，不是 -1")
    void rejectsASignAfterThePrefix() {
        assertThatThrownBy(() -> Hex.toLong("0x-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hex.toBigInteger("0x-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hex.toLong("0x+1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("合法值照常：0x0、0x1c、大写、空串（BigInteger 视为 0）")
    void acceptsPlainHexadecimal() {
        assertThat(Hex.toLong("0x1c")).isEqualTo(28);
        assertThat(Hex.toLong("0xFF")).isEqualTo(255);
        assertThat(Hex.toBigInteger("0x")).isEqualTo(BigInteger.ZERO);
        assertThat(Hex.toBigInteger("0x0de0b6b3a7640000")).isEqualTo(new BigInteger("1000000000000000000"));
    }
}
