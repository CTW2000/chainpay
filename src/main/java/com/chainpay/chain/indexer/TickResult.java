package com.chainpay.chain.indexer;

/** 一次轮询的结果。{@code detail} 在 HALTED / RETRY_LATER 时是原因。 */
public record TickResult(TickOutcome outcome, int batches, int logsInserted, String detail) {}
