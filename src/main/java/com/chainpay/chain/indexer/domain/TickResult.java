package com.chainpay.chain.indexer.domain;

/**
 * 一次轮询的结果。{@code detail} 在 HALTED / RETRY_LATER / REORGED 时是原因或摘要。
 *
 * @param sampled    这次抽样对账了几个块（M2-⑤）
 * @param mismatches 其中几块和回执对不上
 */
public record TickResult(TickOutcome outcome, int batches, int logsInserted,
                         int sampled, int mismatches, String detail) {

    public TickResult(TickOutcome outcome, int batches, int logsInserted, String detail) {
        this(outcome, batches, logsInserted, 0, 0, detail);
    }
}
