package com.chainpay.chain.indexer.domain;

/** 一条日志在库里的坐标：块哈希 + 块内序号唯一，交易哈希用来到另一个节点核对。 */
public record LogCoordinate(String blockHash, int logIndex, String txHash) {}
