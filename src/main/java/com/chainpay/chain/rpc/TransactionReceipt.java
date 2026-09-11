package com.chainpay.chain.rpc;

import java.math.BigInteger;

/**
 * 一笔交易的回执（{@code eth_getTransactionReceipt}）：有回执 = 已经写进某个块。
 * {@code success} 是回执的 status（1 成功、0 合约 revert）——<b>status 0 也是上链</b>，nonce 已被用掉、gas 已经扣了，只是转账没发生。
 * {@code blockHash} 记的是节点此刻认为它所在的块；块被重组掉时节点会回 null，交易退回内存池或消失。
 */
public record TransactionReceipt(String txHash, boolean success, long blockNumber, String blockHash, long gasUsed, BigInteger effectiveGasPrice) {}
