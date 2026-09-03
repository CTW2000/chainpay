package com.chainpay.chain.indexer.service;

/**
 * finalized 头倒退了，或者同一个号换了哈希。
 *
 * <p>这不是重组。finalized 是协议意义上的不可逆，推翻它要罚没至少三分之一的质押。
 * 看到它变，只有两种可能：节点坏了，或者链上发生了灾难。两种都不该由代码自作主张：停下叫人。
 */
public class FinalityViolationException extends RuntimeException {

    public FinalityViolationException(String message) {
        super(message);
    }
}
