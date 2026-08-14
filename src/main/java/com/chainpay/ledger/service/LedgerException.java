package com.chainpay.ledger.service;

/**
 * 账本操作被拒绝。
 *
 * <p><b>为什么用一个带 {@link Reason} 枚举的异常，而不是一堆异常类，也不是裸的
 * {@code RuntimeException}：</b>
 *
 * <ul>
 *   <li><b>失败模式必须是可枚举的。</b>看一眼 {@code Reason} 就知道这个账本一共会以几种方式
 *       拒绝你。裸的 {@code RuntimeException} 做不到这一点——调用方只能靠读消息字符串猜。</li>
 *   <li><b>调用方要能按原因分流。</b>M1 的 API 层需要把 {@code INSUFFICIENT_BALANCE}
 *       映射成一个业务错误码返给商户，把 {@code ACCOUNT_NOT_FOUND} 映射成另一个。
 *       靠 {@code getMessage().contains("余额")} 来判断是灾难。</li>
 *   <li><b>不用一个原因一个类</b>，是因为它们的处理方式一样（都是"拒绝并告诉调用方为什么"），
 *       拆成 6 个类只是增加文件数，不增加表达力。</li>
 * </ul>
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
        /** 金额非正，或小数位超过 18 位（超了会被数据库静默四舍五入，必须提前拒绝）。 */
        INVALID_AMOUNT,
        /** 借贷方是同一个账户。余额不变却产生两条分录，会让统计翻倍。 */
        SAME_ACCOUNT,
        /** 账户不存在。 */
        ACCOUNT_NOT_FOUND,
        /** 账户币种与转账币种不符。跨币种数值不可直接搬运。 */
        CURRENCY_MISMATCH,
        /** 借方余额不足，且该账户不允许为负。 */
        INSUFFICIENT_BALANCE
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
