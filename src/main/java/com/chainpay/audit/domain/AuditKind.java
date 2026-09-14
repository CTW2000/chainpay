package com.chainpay.audit.domain;

/** 三种差异（LEARNING-PATH M5）加两种「对账自己给不出结论」的情形。 */
public enum AuditKind {
    /** 链上有、库内无：漏账（漏了日志、入账任务没处理、没有事件的铸币、外部注资没登记）。 */
    MISSING_IN_LEDGER,
    /** 库内有、链上无：假账（重组没回滚、手工改库、留着的日志链上不认）。最危险的一种。 */
    MISSING_ON_CHAIN,
    /** 两边都有、金额不符：算错（换算、手工改小）。 */
    AMOUNT_MISMATCH,
    /** 两个节点对同一处数据意见不同：对账给不出结论，等人看。 */
    DISPUTED,
    /** 账本判官 ledger_judge() 的一行。 */
    JUDGE
}
