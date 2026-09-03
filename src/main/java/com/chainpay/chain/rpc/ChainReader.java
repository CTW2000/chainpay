package com.chainpay.chain.rpc;

import java.util.List;

/**
 * 索引器眼中的「链」：四个只读问题。
 *
 * <p>生产实现是 {@link EthRpc}（真节点）；测试实现是内存里的 FakeChain——
 * 想让链长什么样就长什么样：头落后、哈希突变、日志缺失，都是一行代码的事。
 * 用 HTTP 假节点也能做到，但每个场景都要拼 JSON，重得没人愿意多写一个用例。
 * 这是依赖注入的第二个用处：第一次是换端点，这次是换整条链。
 */
public interface ChainReader {

    /** 节点眼中的最新区块号（{@code latest}）。 */
    long blockNumber();

    /** 按区块号（十六进制字符串）或标签（latest / safe / finalized）取区块头。 */
    BlockHeader block(String numberOrTag);

    BlockHeader block(long number);

    /** 某合约在 [from, to] 内、topic0 匹配的全部日志，原样。 */
    List<RawLog> logs(long fromBlock, long toBlock, String address, String topic0);
}
