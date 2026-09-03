package com.chainpay.chain.indexer;

/** 一个区块的坐标：号 + 哈希。链头表只存这两样。 */
public record HeadRef(long number, String hash) {}
