package com.chainpay.chain.erc20;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 原始单位换算成账本金额：精确、不四舍五入、装不下就明确拒绝。
 *
 * <p>M2-before 第 17 问：uint256 有 78 位，账本整数部分只有 20 位，大额转账是报错还是静默截断？
 * 答案：在写账本之前自己检查、明确拒绝。
 */
@DisplayName("M2-⑥ · 金额换算")
class TokenAmountsTest {

    static final BigInteger TEN_POW_18 = BigInteger.TEN.pow(18);

    @Test
    @DisplayName("decimals 18：25 × 10^18 原始单位 = 25.000000000000000000")
    void convertsEighteenDecimalsExactly() {
        assertThat(TokenAmounts.toLedger(BigInteger.valueOf(25).multiply(TEN_POW_18), 18))
                .isEqualByComparingTo(new BigDecimal("25"))
                .hasScaleOf(18);
        assertThat(TokenAmounts.toLedger(BigInteger.ONE, 18)).isEqualByComparingTo(new BigDecimal("0.000000000000000001"));
    }

    @Test
    @DisplayName("decimals 6（USDC 那种）：1500000 = 1.5，后 12 位小数是零，精确")
    void convertsSixDecimalsExactly() {
        assertThat(TokenAmounts.toLedger(BigInteger.valueOf(1_500_000), 6))
                .isEqualByComparingTo(new BigDecimal("1.5"))
                .hasScaleOf(18);
    }

    @Test
    @DisplayName("decimals 0：7 就是 7")
    void convertsZeroDecimals() {
        assertThat(TokenAmounts.toLedger(BigInteger.valueOf(7), 0)).isEqualByComparingTo(new BigDecimal("7"));
    }

    @Test
    @DisplayName("★ uint256 最大值装不进账本：明确拒绝，不截断")
    void rejectsUint256Max() {
        assertThatThrownBy(() -> TokenAmounts.toLedger(BigInteger.TWO.pow(256).subtract(BigInteger.ONE), 18))
                .isInstanceOf(AmountOverflowException.class)
                .hasMessageContaining("20");
    }

    @Test
    @DisplayName("★ 边界：10^20 个币拒绝，10^20 − 1 个币通过")
    void integerDigitsBoundary() {
        BigInteger justTooBig = BigInteger.TEN.pow(20).multiply(TEN_POW_18);
        BigInteger largestOk = BigInteger.TEN.pow(20).subtract(BigInteger.ONE).multiply(TEN_POW_18);

        assertThatThrownBy(() -> TokenAmounts.toLedger(justTooBig, 18)).isInstanceOf(AmountOverflowException.class);
        assertThat(TokenAmounts.toLedger(largestOk, 18)).isEqualByComparingTo(new BigDecimal("99999999999999999999"));
    }

    @Test
    @DisplayName("★ decimals 超过 18：账本装不下这种代币的小数位，拒绝")
    void rejectsMoreThanEighteenDecimals() {
        assertThatThrownBy(() -> TokenAmounts.toLedger(BigInteger.TEN.pow(24), 24))
                .isInstanceOf(AmountOverflowException.class)
                .hasMessageContaining("18");
    }

    @Test
    @DisplayName("负数和负 decimals 都不合法")
    void rejectsNegatives() {
        assertThatThrownBy(() -> TokenAmounts.toLedger(BigInteger.valueOf(-1), 18)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenAmounts.toLedger(BigInteger.ONE, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
