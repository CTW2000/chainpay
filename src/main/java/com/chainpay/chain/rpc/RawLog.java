package com.chainpay.chain.rpc;

import java.util.List;

/**
 * {@code eth_getLogs} 返回的一条日志，<b>原样</b>——十六进制都还是字符串，这一层不解释含义。
 *
 * <p>坐标三件套里 {@code logIndex} 是<b>区块内全局编号</b>（不是每笔交易重排），
 * 所以 (blockHash, logIndex) 就能唯一定位一条日志。
 *
 * @param removed 协议定义：true when the log was removed, due to a chain reorganization。
 *                轮询 eth_getLogs 时几乎总是 false；订阅场景下才会收到 true。
 */
public record RawLog(
        String address,
        List<String> topics,
        String data,
        String blockNumber,
        String blockHash,
        String transactionHash,
        String transactionIndex,
        String logIndex,
        boolean removed) {}
