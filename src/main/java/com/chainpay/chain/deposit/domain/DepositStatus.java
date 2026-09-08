package com.chainpay.chain.deposit.domain;

/**
 * 一笔链上入账在我们这里的结局。CHECK 约束里的词表与此一致。
 *
 * <ul>
 *   <li>POSTING：只在事务内存在——先占坑再动钱，提交出来的行永远不会是它</li>
 *   <li>CREDITED：已记账，transfer_id 非空</li>
 *   <li>IGNORED_ZERO：零值转账，EIP-20 说它合法，账本说它没意义，记录不入账</li>
 *   <li>HELD_*：等人看。永远不自动变成 CREDITED，队列遇到它跳过继续</li>
 *   <li>REJECTED_DUST：低于最小入账额（M3-③）</li>
 *   <li>HELD_ERROR：入账时撞上结构性异常，等人看；瞬时失败不进这里</li>
 *   <li>APPROVED：人复核 HELD 之后改成它，任务下一轮用同一套占坑与幂等键记上</li>
 * </ul>
 */
public enum DepositStatus {
    POSTING, CREDITED, IGNORED_ZERO, REJECTED_DUST, APPROVED, HELD_OVERFLOW, HELD_NODE_DISAGREE, HELD_BALANCE_MISMATCH, HELD_ERROR;

    public boolean isHeld() {
        return name().startsWith("HELD_");
    }
}
