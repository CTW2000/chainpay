package com.chainpay.chain.rpc;

/**
 * 区块头里我们关心的四个字段。
 *
 * <p>{@code parentHash} 是「链」字的来源：区块 N+1 的 parentHash 必须等于区块 N 的 hash。
 * 存下来的 hash 和链上现在的 parentHash 对不上，就是重组——M2-④ 全靠这一个等式。
 */
public record BlockHeader(long number, String hash, String parentHash, long timestamp) {}
