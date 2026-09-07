package com.chainpay.chain.indexer.domain;

/** 索引器的状态：正常 / 降级（还在跑，但有人该来看看）/ 停下（等人处理，重启也不恢复）。 */
public enum IndexerStatus {
    RUNNING,
    DEGRADED,
    HALTED
}
