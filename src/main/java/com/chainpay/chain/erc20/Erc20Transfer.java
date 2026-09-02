package com.chainpay.chain.erc20;

import java.math.BigInteger;

/**
 * 一笔解码后的 ERC-20 转账。
 *
 * @param value <b>原始单位</b>（uint256），不是「多少个币」。链上没有小数点；
 *              {@code decimals} 只是合约给人看的提示，而且 EIP-20 说它是 OPTIONAL。
 *              换算成账本的 NUMERIC(38,18) 是 M3 入账时的事——而且要先回答
 *              M2-before 第 17 问：uint256 有 78 位，那一列的整数部分只有 20 位。
 */
public record Erc20Transfer(
        String token,
        String from,
        String to,
        BigInteger value,
        long blockNumber,
        String blockHash,
        String transactionHash,
        int logIndex) {}
