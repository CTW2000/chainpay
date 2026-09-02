package com.chainpay.chain.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 以太坊的几个 {@code eth_*} 方法，把十六进制翻译成 Java 类型。
 *
 * <p>只做「翻译」，不做「判断」：这里不知道什么是 ERC-20、不知道什么是确认数。
 * 那些知识分别在 {@code chain.erc20} 和后续的索引器里。
 */
public class EthRpc {

    private final JsonRpcClient rpc;

    public EthRpc(JsonRpcClient rpc) {
        this.rpc = rpc;
    }

    /** 节点眼中的最新区块号（{@code latest}）。注意：不同节点、同一节点前后两次都可能不一致。 */
    public long blockNumber() {
        return Hex.toLong(rpc.call("eth_blockNumber").asString());
    }

    /**
     * 按区块号（十六进制字符串）或标签取区块头。
     *
     * <p>标签除了 {@code latest} 还有合并后才有的 {@code safe} 与 {@code finalized}——
     * 链自己告诉你哪一段是不可逆的，不必自己数确认数。Sepolia 实测二者分别落后约 35 / 66 块。
     */
    public BlockHeader block(String numberOrTag) {
        JsonNode b = rpc.call("eth_getBlockByNumber", numberOrTag, false);
        if (b == null || b.isNull()) {
            throw new JsonRpcException(null, "区块不存在：" + numberOrTag);
        }
        return new BlockHeader(
                Hex.toLong(b.get("number").asString()),
                b.get("hash").asString(),
                b.get("parentHash").asString(),
                Hex.toLong(b.get("timestamp").asString()));
    }

    public BlockHeader block(long number) {
        return block(Hex.fromLong(number));
    }

    /**
     * {@code eth_getLogs}：某个合约在 [from, to] 区块范围内、topic0 匹配的全部日志。
     *
     * <p>提供商对范围和条数各有上限（2 000 块 / 10 000 条 / 50 块……），撞上限时以
     * {@link JsonRpcException}（带 code）的形式报错——调用方据此减半重试。这一层不自动分页。
     */
    public List<RawLog> logs(long fromBlock, long toBlock, String address, String topic0) {
        JsonNode result = rpc.call("eth_getLogs", Map.of(
                "fromBlock", Hex.fromLong(fromBlock),
                "toBlock", Hex.fromLong(toBlock),
                "address", address,
                "topics", List.of(topic0)));
        List<RawLog> logs = new ArrayList<>();
        for (JsonNode l : result) {
            List<String> topics = new ArrayList<>();
            l.get("topics").forEach(t -> topics.add(t.asString()));
            logs.add(new RawLog(
                    l.get("address").asString(),
                    List.copyOf(topics),
                    l.get("data").asString(),
                    l.get("blockNumber").asString(),
                    l.get("blockHash").asString(),
                    l.get("transactionHash").asString(),
                    l.get("transactionIndex").asString(),
                    l.get("logIndex").asString(),
                    l.has("removed") && l.get("removed").asBoolean()));
        }
        return logs;
    }
}
