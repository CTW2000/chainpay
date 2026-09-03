package com.chainpay.chain.indexer.domain;

/** 书签：某个索引器处理到了哪个区块，以及那个区块的哈希（重组检测的依据）。 */
public record IndexerCursor(String name, long lastBlockNumber, String lastBlockHash) {}





