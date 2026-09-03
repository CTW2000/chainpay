package com.chainpay.chain.indexer.service;

/**
 * 下一批第一个区块的 parentHash 和书签上的哈希对不上：链在书签处（或更深）被重组了。
 *
 * <p>M2-② 的处理是<b>停下</b>：不写、不推书签、抛出。在 M2-④ 写出正确的回滚之前，
 * 继续往前索引等于把一条错的链当事实记下来。停下来的索引器是一个报警，往前走的是定时炸弹。
 */
public class ReorgDetectedException extends RuntimeException {

    private final long blockNumber;
    private final String expectedParentHash;
    private final String actualParentHash;

    public ReorgDetectedException(long blockNumber, String expectedParentHash, String actualParentHash) {
        super("区块 " + blockNumber + " 的 parentHash 与书签不符：书签 " + expectedParentHash
                + "，链上 " + actualParentHash + "。链已重组，索引器停下等待 M2-④ 的回滚");
        this.blockNumber = blockNumber;
        this.expectedParentHash = expectedParentHash;
        this.actualParentHash = actualParentHash;
    }

    public long blockNumber() {
        return blockNumber;
    }

    public String expectedParentHash() {
        return expectedParentHash;
    }

    public String actualParentHash() {
        return actualParentHash;
    }
}
