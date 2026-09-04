package com.chainpay.chain.erc20;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 链上原始单位 → 账本金额。M3 入账、M4 出金都只经这里。
 *
 * <p>链上没有小数点，decimals 只是「显示时把小数点往左挪几位」。换算是精确除法，永远不四舍五入；
 * 装不下就抛 {@link AmountOverflowException}——在写账本之前，而不是让数据库报「numeric field overflow」，
 * 更不是静默截断。
 */
public final class TokenAmounts {

    /** 账本列 NUMERIC(38,18)：整数最多 20 位，小数 18 位。 */
    public static final int LEDGER_INTEGER_DIGITS = 20;
    public static final int LEDGER_SCALE = 18;

    private TokenAmounts() {}

    public static BigDecimal toLedger(BigInteger raw, int decimals) {
        if (raw == null || raw.signum() < 0) {
            throw new IllegalArgumentException("金额不能为负：" + raw);
        }
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals 不能为负：" + decimals);
        }
        if (decimals > LEDGER_SCALE) {
            throw new AmountOverflowException("代币 decimals=" + decimals + " 超过账本的 " + LEDGER_SCALE
                    + " 位小数，装不下，而钱不能四舍五入");
        }
        BigDecimal amount = new BigDecimal(raw).movePointLeft(decimals);
        int integerDigits = amount.precision() - amount.scale();
        if (integerDigits > LEDGER_INTEGER_DIGITS) {
            throw new AmountOverflowException("整数部分 " + integerDigits + " 位，超过账本的 "
                    + LEDGER_INTEGER_DIGITS + " 位：" + amount.toPlainString());
        }
        return amount.setScale(LEDGER_SCALE);
    }
}
