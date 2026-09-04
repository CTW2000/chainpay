package com.chainpay.chain.indexer.domain;

import java.time.Instant;

/** indexer_state 里的一行：这枚书签对应的索引器现在处于什么状态、为什么、从什么时候起。 */
public record IndexerState(String name, IndexerStatus status, String reason, Instant since) {}
