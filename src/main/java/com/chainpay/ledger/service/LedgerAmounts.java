package com.chainpay.ledger.service;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * 金额的规矩只在这里写一份：<b>账本装得下多大的数，以及这个数对外怎么写</b>。
 *
 * <p>这是同一个概念的两面，所以放在一起：写法里的「20 位整数、18 位小数」就是容量本身，改了一个另一个必须跟着改。
 * 链上原始单位按 decimals 换成账本金额是另一个概念，留在 {@code TokenAmounts}：它依赖这里，反过来不行。
 *
 * <p><b>容量。</b>金额列一律 {@code NUMERIC(38,18)}：小数 {@link #SCALE} 位，整数 {@link #INTEGER_DIGITS} 位。
 * 多出来的小数，数据库会静默四舍五入；多出来的整数位，数据库报 numeric field overflow——两者都必须在写库之前拒绝
 * （{@link #requireFits}）。{@code SchemaGuardTest} 把这两个数和库里每一个带小数位的列绑在一起：列改了或这里改了都红。
 *
 * <p><b>写法。</b>对外的金额在 JSON 里一律是字符串（不丢精度），解析成 BigDecimal 是我们自己做的，
 * 所以「什么样的字符串才准解析」只能由我们定。BigDecimal 认的写法太多了：{@code 1E-999999999}（12 个字符，
 * 逐位写全是 10 亿个字符，一个请求就能打挂进程）、一百万位的普通写法（光解析就要 11 秒）、{@code +1}、{@code 1.}、
 * 别的文字的数字（阿拉伯-印度数字它也认；Java 正则的 {@code \d} 只认 ASCII 的 0–9）。
 * 正则（{@link #FITTING_DECIMAL}）给请求记录的 {@code @Pattern} 用，{@code ControllerBoundaryTest} 守着：控制器里每个
 * {@code new BigDecimal(…)} 解析的字段都带它。
 */
public final class LedgerAmounts {

    /** 小数位。多出来的，数据库会静默四舍五入——所以必须在写库之前拒绝。 */
    public static final int SCALE = 18;

    /** 整数位（38 − 18）。多出来的，数据库报 numeric field overflow。 */
    public static final int INTEGER_DIGITS = 20;

    /**
     * 普通小数写法，且装得下：数字，可选一个小数点后跟数字；不收正负号、指数、空格、千分位；
     * 整数不超过 {@link #INTEGER_DIGITS} 位、小数不超过 {@link #SCALE} 位。
     * 提现与限额接口用它：超出容量在边界就回 2001，到不了账本。
     */
    public static final String FITTING_DECIMAL =
            "\\d{1," + INTEGER_DIGITS + "}(\\.\\d{1," + SCALE + "})?";

    private LedgerAmounts() {}

    /**
     * 整数部分有几位：12.34 是 2，0.5 是 0，0.05 是 −1（≤ 0 就是小于 1）。只读 precision 与 scale，不展开数字。
     *
     * <p><b>必须用 long 算</b>：scale 可以是负二十亿（{@code 1E+2147483647} 这种写法是合法的），
     * int 相减 1 − (−2147483647) 溢出成 −2147483648，「超过 20 位」的检查就被一个负数绕过去了。
     */
    public static long integerDigits(BigDecimal value) {
        return (long) value.precision() - value.scale();
    }

    /**
     * 装得下就原样返回；装不下就用调用方给的异常报出原因。全项目「装不装得下」只在这里判：
     * 账本入口抛 {@code LedgerException}、链上换算抛 {@code AmountOverflowException}、提现申请回 400、
     * 写成字符串（{@link #text}）装不下是程序错——各自给各自的异常，判断只有这一份。
     *
     * <p>原因只说几位，不写金额本身：金额可以写成 {@code 1E-999999999}——12 个字符，逐位写全是 10 亿个字符，
     * 写进报错一个请求就能耗尽内存。这里只读 scale 与 precision，代价与数有多大无关。
     */
    public static BigDecimal requireFits(BigDecimal amount, Function<String, ? extends RuntimeException> refusal) {
        BigDecimal value;
        try {
            value = amount.stripTrailingZeros();   // 几位小数按值算、不按写法算：1.500 是 1 位
        } catch (ArithmeticException e) {
            // 只有「去零后 scale 越过 int 的下界」会抛（100E+2147483647 这种写法）：那是一个至少二十亿位的整数
            throw refusal.apply("金额整数部分超过 %d 位，账本装不下".formatted(INTEGER_DIGITS));
        }
        if (value.scale() > SCALE) {
            throw refusal.apply("金额小数位 %d 位，超过 %d 位，拒绝四舍五入".formatted(value.scale(), SCALE));
        }
        long digits = integerDigits(value);
        if (digits > INTEGER_DIGITS) {
            throw refusal.apply("金额整数部分 %d 位，超过 %d 位，账本装不下".formatted(digits, INTEGER_DIGITS));
        }
        return amount;
    }

    /**
     * 金额写成对外的字符串：普通写法、不用科学计数法、小数位原样保留（库里读出来的是 18 位：{@code 18.000000000000000000}）；
     * null 仍是 null。全项目金额变成字符串只经这里（{@code SingleHomeGuardTest} 守着）。
     *
     * <p>先判装不装得下再写：装得下的数最多 20 位整数、18 位小数加一个负号，写全不过 40 个字符；
     * 装不下的数（比如一个没经过校验的 {@code 1E-999999999}）根本不该走到输出这一步，那是程序错，抛 {@link IllegalStateException}。
     */
    public static String text(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return requireFits(amount, IllegalStateException::new).toPlainString();
    }
}
