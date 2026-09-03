package com.chainpay.chain.indexer.domain;

import java.util.List;

/** 一次抽样对账：抽了哪些块，其中几块有差异。 */
public record ReconcileResult(List<Long> sampledBlocks, int mismatches) {}
