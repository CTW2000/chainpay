package com.chainpay.chain.indexer.domain;

/**
 * 对一个块的一次对账。
 *
 * @param expected 审计路径（回执）里属于我们代币的日志数
 * @param found    库里该块 CANONICAL 的行数
 * @param repaired 补录了几条（两个节点都确认有）
 * @param orphaned 标废了几条幻影（两个节点都确认没有）
 * @param disputed 两个节点意见不一、没动的几条
 */
public record BlockReconciliation(long blockNumber, String blockHash,
                                  int expected, int found, int repaired, int orphaned, int disputed) {

    public boolean isClean() {
        return expected == found && repaired == 0 && orphaned == 0 && disputed == 0;
    }
}
