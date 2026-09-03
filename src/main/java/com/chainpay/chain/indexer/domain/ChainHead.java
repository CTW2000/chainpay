package com.chainpay.chain.indexer.domain;

/** 最后一次看到的三个头。{@code finalized ≤ safe ≤ latest} 是协议保证的顺序。 */
public record ChainHead(HeadRef latest, HeadRef safe, HeadRef finalized) {}
