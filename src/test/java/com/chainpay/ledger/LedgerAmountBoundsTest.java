package com.chainpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.ledger.service.LedgerAmounts;
import com.chainpay.ledger.service.LedgerException;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 账本入口对金额「装不装得下」的判断（2026-09-21）。
 *
 * <p>金额列是 NUMERIC(38,18)：小数 18 位、整数 20 位。多出来的小数数据库会静默四舍五入，多出来的整数位数据库报
 * numeric field overflow——前者改人家的钱，后者在 HTTP 上落成 500 + 9001（按段位约定是「可以重试」）。
 * 两者都必须在写库之前拒绝。
 *
 * <p>拒绝时的报错本身也是一处输出：此前它用 toPlainString 把金额逐位写全，{@code 1E-999999999} 只有 12 个字符，
 * 写全是 10 亿个字符——同镜像、同内存上限的实例实测一个请求就内存耗尽退出（退出码 3）。
 * 所以这里除了「拒没拒」，还钉住「报错有多长」。
 */
@DisplayName("账本 · 金额装得下才写")
class LedgerAmountBoundsTest extends AbstractPostgresTest {

    private static final String USDT = "USDT";

    private long mint;
    private long alice;

    @BeforeEach
    void seedAccounts() {
        mint = createAccount("house:mint:USDT", USDT, "EQUITY", true);
        alice = createAccount("user:alice:USDT", USDT, "LIABILITY");
    }

    @Test
    @DisplayName("★ 小数超过 18 位被拒，而且报错不把金额写全 —— 1E-100000000 只有 12 个字符")
    void tooManyDecimalsIsRefusedWithoutSpellingTheAmountOut() {
        // 此前的报错是一亿个字符（拼它要约 500 MB 堆）。1E-999999999 那个量级在旧代码上会把测试 JVM 一起打挂，
        // 所以红灯只用这一亿位的：旧代码拼得出来、失败得干净。
        assertRefused("1E-100000000");
    }

    @Test
    @DisplayName("★ 整数部分超过 20 位被拒在写库之前 —— 而不是由数据库报 numeric field overflow")
    void integerPartBeyondTheColumnIsRefusedBeforeTheDatabase() {
        assertRefused("100000000000000000000");   // 21 位
        assertRefused("1E+25");
    }

    /**
     * 修好之后才加得进来的几个：旧代码上 {@code 1E-2147483647} 在 JDK 里直接抛 OutOfMemoryError（长度算溢出），
     * 整数那一侧会落到数据库。{@code 1E-999999999} 不放在这里——万一有人把展开改回去，它会真的吃光测试 JVM 的堆，
     * 把同一进程里的别的测试一起拖垮；它由真跑（同镜像实例）验证，上面那条一亿位的负责在测试里干净地红。
     */
    @ParameterizedTest(name = "金额 {0} → 拒绝，报错不到 200 个字符")
    @ValueSource(strings = {
            "1E-2147483647",                      // scale 的上界
            "1E+999999999", "1E+2147483647",      // 整数位用 int 算会溢出成负数，从「超过 20 位」底下钻过去
            "1E+2147483648", "10E+2147483647",    // scale 正好是 int 的下界
            "100E+2147483647"})                   // 去零时 scale 越过 int 下界：stripTrailingZeros 抛 ArithmeticException
    @DisplayName("★ 写法再极端也只读 scale 与 precision 就拒绝 —— 指数写到 int 两头、int 溢出、去零溢出")
    void extremeExponentsAreRefusedWithoutExpandingTheNumber(String amount) {
        assertRefused(amount);
    }

    @Test
    @DisplayName("★ 整数位按 long 算 —— scale 是负二十亿时，int 相减会溢出成负数")
    void integerDigitsAreCountedInLong() {
        assertThat(LedgerAmounts.integerDigits(new BigDecimal("1E+2147483647"))).isEqualTo(2_147_483_648L);
        assertThat(LedgerAmounts.integerDigits(new BigDecimal("12.34"))).isEqualTo(2);
        assertThat(LedgerAmounts.integerDigits(new BigDecimal("0.05"))).isEqualTo(-1);
    }

    @Test
    @DisplayName("★ 列能装下的最大值照常写进去，一分不差")
    void theLargestAmountTheColumnHoldsGoesThrough() {
        BigDecimal max = new BigDecimal("99999999999999999999.999999999999999999");

        ledger.transfer(command("max-1", max));

        assertThat(ledger.balanceOf(alice)).isEqualByComparingTo(max);
    }

    @Test
    @DisplayName("★ 18 位小数精确到最后一位 —— 账本不能比列还严（收紧到 17 位时，全套测试原本一条都不红）")
    void eighteenDecimalsAreKeptToTheLastDigit() {
        BigDecimal wei = new BigDecimal("0.000000000000000001");

        ledger.transfer(command("wei-1", wei));

        assertThat(ledger.balanceOf(alice)).isEqualByComparingTo(wei);
    }

    private void assertRefused(String amount) {
        assertThatThrownBy(() -> ledger.transfer(command("refused-" + amount, new BigDecimal(amount))))
                .as("金额 %s", amount)
                .isInstanceOfSatisfying(LedgerException.class, e -> {
                    assertThat(e.reason()).isEqualTo(LedgerException.Reason.INVALID_AMOUNT);
                    // 断言长度这个数，不断言字符串本身：失败时 AssertJ 要打印实际值，一亿个字符会淹掉报告
                    assertThat(e.getMessage().length()).as("金额 %s 的报错长度", amount).isLessThan(200);
                });
    }

    private TransferCommand command(String key, BigDecimal amount) {
        return new TransferCommand(key, USDT, amount, mint, alice, TransferCode.INTERNAL, null);
    }
}
