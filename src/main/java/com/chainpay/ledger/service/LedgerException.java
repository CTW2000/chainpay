package com.chainpay.ledger.service;

/**
 * 账本操作被拒绝，原因见 {@link Reason}。
 *
 * <p>用一个带原因枚举的异常，而不是裸的 {@code RuntimeException}：失败模式必须可枚举（看一眼 {@code Reason}
 * 就知道账本会以几种方式拒绝你），调用方要能按原因分流（{@code ApiExceptionHandler} 按它映射错误码，
 * 而不是去匹配消息字符串）。也不用一个原因一个类：处理方式都一样（拒绝并说明原因），拆开只增加文件数。
 */
public class LedgerException extends RuntimeException {

    /** 账本拒绝一笔转账的全部理由。新增拒绝路径时必须在这里加一项，不要复用不相干的。 */
    public enum Reason {
        /** 幂等键缺失。调用方必须提供，服务端不替它生成——见 LedgerService 的 javadoc。 */
        MISSING_IDEMPOTENCY_KEY,
        /**
         * 业务类型缺失。每笔转账都必须说明「为什么」——
         * 事后从借贷双方猜不出这是充值、提现还是冲正。
         */
        MISSING_TRANSFER_CODE,
        /** 金额非正，或装不下 NUMERIC(38,18)（小数超过 18 位、整数超过 20 位，见 {@link LedgerAmounts#requireFits}）。 */
        INVALID_AMOUNT,
        /** 借贷方是同一个账户。余额不变却产生两条分录，会让统计翻倍。 */
        SAME_ACCOUNT,
        /** 账户不存在。 */
        ACCOUNT_NOT_FOUND,
        /** 账户币种与转账币种不符。跨币种数值不可直接搬运。 */
        CURRENCY_MISMATCH,
        /** 借方余额不足，且该账户不允许为负。 */
        INSUFFICIENT_BALANCE,
        /**
         * 幂等键已被<b>另一笔不同的</b>请求使用。
         *
         * <p>与「同键同体」严格区分：后者是超时重发，必须幂等返回；
         * 前者是调用方复用了键（最常见：把订单号当幂等键，退款时又用同一个订单号），
         * 必须拒绝——否则第二笔会被静默吞掉并回 200。
         */
        IDEMPOTENCY_CONFLICT
    }

    private final transient Reason reason;

    public LedgerException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
