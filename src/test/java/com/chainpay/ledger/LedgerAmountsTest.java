package com.chainpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.ledger.service.LedgerAmounts;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 金额的家（2026-09-22 收口）：装不装得下只在 {@code requireFits} 判，写成字符串只经 {@code text}。
 * 纯计算、不起容器；接上它的几处由各自的测试守——账本入口 {@code LedgerAmountBoundsTest}、链上换算 {@code TokenAmountsTest}、
 * 响应里的写法 {@code DepositApiTest} / {@code WithdrawalApiTest}（钉着 {@code "10.000000000000000000"} 这样的原文）。
 */
@DisplayName("金额的家 · 装得下与写成字符串")
class LedgerAmountsTest {

    @Test
    @DisplayName("★ 写成字符串：普通写法、小数位原样保留，负数与零照写，null 仍是 null")
    void textIsThePlainFormOfAnAmountThatFits() {
        assertThat(LedgerAmounts.text(new BigDecimal("18.000000000000000000"))).isEqualTo("18.000000000000000000");
        assertThat(LedgerAmounts.text(new BigDecimal("-18.000000000000000000"))).isEqualTo("-18.000000000000000000");
        assertThat(LedgerAmounts.text(new BigDecimal("0E-18"))).isEqualTo("0.000000000000000000");
        assertThat(LedgerAmounts.text(new BigDecimal("99999999999999999999.999999999999999999")))
                .isEqualTo("99999999999999999999.999999999999999999");
        assertThat(LedgerAmounts.text(null)).isNull();
        // 1 wei：String.valueOf 写成科学计数法，text 写成普通写法——收口前对账里两种写法并存
        assertThat(String.valueOf(new BigDecimal("1E-18"))).isEqualTo("1E-18");
        assertThat(LedgerAmounts.text(new BigDecimal("1E-18"))).isEqualTo("0.000000000000000001");
    }

    /**
     * 装不下的数走到输出这一步，说明有一个没经过校验的数混了进来——那是程序错。
     * 不放 {@code 1E-999999999}：万一有人拆掉这道检查，它会真的去吃测试 JVM 的堆；下面几个在拆掉之后也只会快速失败。
     */
    @ParameterizedTest(name = "{0} → 程序错，不展开")
    @ValueSource(strings = {"1E-19", "100000000000000000000", "1E+2147483647", "100E+2147483647"})
    @DisplayName("★ 装不下的数不许写成字符串 —— 抛 IllegalStateException，报错不写金额")
    void textRefusesAnAmountThatDoesNotFit(String amount) {
        assertThatThrownBy(() -> LedgerAmounts.text(new BigDecimal(amount)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).as("报错的长度").isLessThan(200));
    }

    @Test
    @DisplayName("★ requireFits：装得下原样返回同一个对象；装不下抛调用方给的异常，原因说清几位")
    void requireFitsReturnsTheSameAmountOrTheCallersException() {
        BigDecimal ok = new BigDecimal("1.500");
        assertThat(LedgerAmounts.requireFits(ok, IllegalArgumentException::new)).isSameAs(ok);

        assertThatThrownBy(() -> LedgerAmounts.requireFits(new BigDecimal("0.1234567890123456789"), IllegalArgumentException::new))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("19 位").hasMessageContaining("18 位");
        assertThatThrownBy(() -> LedgerAmounts.requireFits(new BigDecimal("100000000000000000000"), IllegalArgumentException::new))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("21 位").hasMessageContaining("20 位");
    }
}
