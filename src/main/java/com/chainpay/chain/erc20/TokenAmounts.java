package com.chainpay.chain.erc20;

import com.chainpay.ledger.service.LedgerAmounts;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 链上原始单位 → 账本金额，只经这里。
 *
 * <p>链上没有小数点，decimals 只是「显示时把小数点往左挪几位」。换算是精确除法，永远不四舍五入；
 * 装不下就抛 {@link AmountOverflowException}——在写账本之前，而不是让数据库报「numeric field overflow」，
 * 更不是静默截断。「装得下」是多大只由 {@link LedgerAmounts} 定，这里不留别名。
 */
public final class TokenAmounts {

    private TokenAmounts() {}

    public static BigDecimal toLedger(BigInteger raw, int decimals) {
        if (raw == null || raw.signum() < 0) {
            throw new IllegalArgumentException("金额不能为负：" + raw);
        }
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals 不能为负：" + decimals);
        }
        if (decimals > LedgerAmounts.SCALE) {
            throw new AmountOverflowException("代币 decimals=" + decimals + " 超过账本的 " + LedgerAmounts.SCALE
                    + " 位小数，装不下，而钱不能四舍五入");
        }
        BigDecimal amount = new BigDecimal(raw).movePointLeft(decimals);
        // 小数位已由上面 decimals 的检查保证，requireFits 拦的是整数位
        return LedgerAmounts.requireFits(amount, AmountOverflowException::new).setScale(LedgerAmounts.SCALE);
    }
}
