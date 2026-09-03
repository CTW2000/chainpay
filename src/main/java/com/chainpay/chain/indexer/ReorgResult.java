package com.chainpay.chain.indexer;

/**
 * 一次重组恢复的结果。
 *
 * @param applied  false = 锁内发现书签已被别的实例动过（它已经恢复过了），这次什么都没做
 */
public record ReorgResult(boolean applied, long cursorBlock, long ancestorBlock, int orphanedLogs) {

    public long depth() {
        return cursorBlock - ancestorBlock;
    }

    static ReorgResult skipped(long cursorBlock) {
        return new ReorgResult(false, cursorBlock, cursorBlock, 0);
    }
}
