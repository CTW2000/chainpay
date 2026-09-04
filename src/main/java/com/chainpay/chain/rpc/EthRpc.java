package com.chainpay.chain.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 以太坊的几个 {@code eth_*} 方法，把十六进制翻译成 Java 类型。
 *
 * <p>只做「翻译」，不做「判断」：这里不知道什么是 ERC-20、不知道什么是确认数。
 * 那些知识分别在 {@code chain.erc20} 和索引器里。
 *
 * <p>这是外部不可信 JSON 进入系统的唯一一层。节点少给一个字段，Jackson 的 {@code get} 返回 null，
 * 接着的 {@code asString()} 是一个不指名字段的空指针——和本模块其它每一处「形状不对就说清收到了什么」的纪律相反
 * （2026-09-03 质询扫描 10.3）。所以每个字段都经 {@link #text}：缺了就指名道姓地拒绝，交给轮询器停下。
 */
public class EthRpc implements ChainReader {

    private final JsonRpcClient rpc;

    public EthRpc(JsonRpcClient rpc) {
        this.rpc = rpc;
    }

    /** 节点眼中的最新区块号（{@code latest}）。注意：不同节点、同一节点前后两次都可能不一致。 */
    @Override
    public long blockNumber() {
        return Hex.toLong(text(rpc.call("eth_blockNumber"), "eth_blockNumber 的结果"));
    }

    /**
     * 按区块号（十六进制字符串）或标签取区块头。
     *
     * <p>标签除了 {@code latest} 还有合并后才有的 {@code safe} 与 {@code finalized}——
     * 链自己告诉你哪一段是不可逆的，不必自己数确认数。
     */
    @Override
    public BlockHeader block(String numberOrTag) {
        JsonNode b = rpc.call("eth_getBlockByNumber", numberOrTag, false);
        if (b == null || b.isNull()) {
            throw new JsonRpcException(null, "区块不存在：" + numberOrTag);
        }
        String context = "eth_getBlockByNumber 的区块头";
        return new BlockHeader(
                Hex.toLong(text(b, "number", context)),
                text(b, "hash", context),
                text(b, "parentHash", context),
                Hex.toLong(text(b, "timestamp", context)));
    }

    @Override
    public BlockHeader block(long number) {
        return block(Hex.fromLong(number));
    }

    /**
     * {@code eth_getLogs}：某个合约在 [from, to] 区块范围内、topic0 匹配的全部日志。
     *
     * <p>提供商对范围和条数各有上限（2 000 块 / 10 000 条 / 50 块……），撞上限时以
     * {@link JsonRpcException}（带 code）的形式报错——调用方据此减半重试。这一层不自动分页。
     */
    @Override
    public List<RawLog> logs(long fromBlock, long toBlock, String address, String topic0) {
        JsonNode result = rpc.call("eth_getLogs", Map.of(
                "fromBlock", Hex.fromLong(fromBlock),
                "toBlock", Hex.fromLong(toBlock),
                "address", address,
                "topics", List.of(topic0)));
        if (result == null || !result.isArray()) {
            throw new IllegalArgumentException("eth_getLogs 的结果不是数组：节点返回的形状不对");
        }
        List<RawLog> logs = new ArrayList<>();
        for (JsonNode l : result) {
            logs.add(toRawLog(l, "eth_getLogs 的日志"));
        }
        return logs;
    }

    /** {@code eth_getBlockReceipts}：一个块里所有回执的所有日志。geth 1.13 起、多数提供商支持。 */
    @Override
    public List<RawLog> blockReceipts(long number) {
        JsonNode receipts = rpc.call("eth_getBlockReceipts", Hex.fromLong(number));
        if (receipts == null || receipts.isNull()) {
            throw new JsonRpcException(null, "区块不存在（回执）：" + number);
        }
        if (!receipts.isArray()) {
            throw new IllegalArgumentException("eth_getBlockReceipts 的结果不是数组：节点返回的形状不对");
        }
        List<RawLog> logs = new ArrayList<>();
        for (JsonNode receipt : receipts) {
            JsonNode receiptLogs = receipt.get("logs");
            if (receiptLogs == null || !receiptLogs.isArray()) {
                throw new IllegalArgumentException("eth_getBlockReceipts 的回执缺少字段 logs：节点返回的形状不对");
            }
            for (JsonNode l : receiptLogs) {
                logs.add(toRawLog(l, "eth_getBlockReceipts 的日志"));
            }
        }
        return logs;
    }

    /** {@code eth_call}。返回 "0x" 表示合约什么都没返回（比如没有这个函数又有 fallback）。 */
    @Override
    public String call(String to, String data, String blockTag) {
        JsonNode result = rpc.call("eth_call", Map.of("to", to, "data", data), blockTag);
        return result == null || result.isNull() ? "0x" : result.asString();
    }

    private static RawLog toRawLog(JsonNode l, String context) {
        JsonNode topicsNode = l.get("topics");
        if (topicsNode == null || !topicsNode.isArray()) {
            throw new IllegalArgumentException(context + " 缺少字段 topics：节点返回的形状不对");
        }
        List<String> topics = new ArrayList<>();
        topicsNode.forEach(t -> topics.add(t.asString()));
        return new RawLog(
                text(l, "address", context),
                List.copyOf(topics),
                text(l, "data", context),
                text(l, "blockNumber", context),
                text(l, "blockHash", context),
                text(l, "transactionHash", context),
                text(l, "transactionIndex", context),
                text(l, "logIndex", context),
                l.has("removed") && l.get("removed").asBoolean());
    }

    /** 必需的字符串字段：缺了或为 null 就指名道姓地拒绝。 */
    private static String text(JsonNode node, String field, String context) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(context + " 缺少字段 " + field + "：节点返回的形状不对");
        }
        return value.asString();
    }

    private static String text(JsonNode value, String context) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(context + " 为空：节点返回的形状不对");
        }
        return value.asString();
    }
}
