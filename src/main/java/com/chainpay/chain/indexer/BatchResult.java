package com.chainpay.chain.indexer;

/**
 * 一批的结果。
 *
 * @param logsSeen     从链上取到并解码成功的条数
 * @param logsInserted 真正写进库的条数；重放时它小于 logsSeen，差值就是被唯一约束挡掉的重复
 */
public record BatchResult(BatchOutcome outcome, long fromBlock, long toBlock, int logsSeen, int logsInserted) {

    static BatchResult upToDate(long cursor) {
        return new BatchResult(BatchOutcome.UP_TO_DATE, cursor + 1, cursor, 0, 0);
    }

    static BatchResult skipped(long fromBlock, long toBlock, int logsSeen) {
        return new BatchResult(BatchOutcome.SKIPPED_CURSOR_MOVED, fromBlock, toBlock, logsSeen, 0);
    }
}
