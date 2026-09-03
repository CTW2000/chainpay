package com.chainpay.chain.indexer.domain;

/** 一次轮询的三种结局。 */
public enum TickOutcome {
    /** 刷新了链头、推进到追平（或到了本次上限）。 */
    POLLED,
    /** 瞬时失败（节点不可达、库超时……）：什么都没动，下一次再来。 */
    RETRY_LATER,
    /** 发现重组并已回滚：标废、退书签、记审计。下一次轮询从祖先之后重放。 */
    REORGED,
    /** 结构性失败（重组、finalized 倒退、没有书签且没配起点）：停下，之后每次轮询都直接返回。 */
    HALTED
}
