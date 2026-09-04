package com.chainpay.chain.erc20;

/**
 * 这笔金额装不进账本的 NUMERIC(38,18)：整数位超过 20 位，或代币的小数位超过 18。
 *
 * <p>两种坏法里静默截断更糟——记少了，商户少收，没有人知道。所以在写账本之前自己检查、
 * 明确拒绝，让调用方把这笔标成「无法入账、等人看」，而不是让一笔坏转账卡住整个入账循环。
 */
public class AmountOverflowException extends RuntimeException {

    public AmountOverflowException(String message) {
        super(message);
    }
}
