package com.chainpay.chain.indexer.config;

import com.chainpay.chain.rpc.ChainReader;

/**
 * 主节点 + 审计节点。没配审计节点时两者是同一个对象。
 *
 * <p>做成一个 bean 而不是两个 {@code ChainReader} bean：两个同类型的 bean 会让按类型注入产生歧义。
 */
public record ChainReaders(ChainReader primary, ChainReader audit) {}
