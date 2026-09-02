package com.chainpay.chain.erc20;

import com.chainpay.chain.rpc.Hex;
import com.chainpay.chain.rpc.RawLog;

/**
 * 把一条 {@link RawLog} 解码成 {@link Erc20Transfer}。
 *
 * <p>Solidity 里 {@code Transfer(address indexed from, address indexed to, uint256 value)}：
 * 带 {@code indexed} 的参数进 topics（节点会建索引、可按值过滤），不带的进 data。
 * 于是 topics 恰好 3 个（签名 + from + to），data 恰好一个 uint256。
 *
 * <p><b>形状不对就拒绝，绝不猜。</b>M2-before 第 19 问：非标准事件、data 长度不对，
 * 解析代码是拒绝，还是把错的数当成对的？这里的每一个 {@code require} 都是回答。
 * 一条被拒绝的日志会在上游被记录并跳过；一条被猜错的日志会变成一笔错误入账。
 */
public final class TransferLogDecoder {

    /** {@code keccak256("Transfer(address,address,uint256)")}——所有 ERC-20 的 Transfer 都是它。 */
    public static final String TRANSFER_TOPIC0 =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private static final int TOPIC_LENGTH = 66;      // 0x + 32 字节
    private static final int WORD_LENGTH = 66;       // 0x + 32 字节（一个 uint256）
    private static final String ADDRESS_PADDING = "0x000000000000000000000000";   // 12 字节的 0

    private TransferLogDecoder() {}

    public static Erc20Transfer decode(RawLog log) {
        if (log.topics().size() != 3) {
            throw new IllegalArgumentException(
                    "Transfer 应有 3 个 topics（签名+from+to），收到 " + log.topics().size());
        }
        if (!TRANSFER_TOPIC0.equalsIgnoreCase(log.topics().get(0))) {
            throw new IllegalArgumentException("topic0 不是 Transfer 的签名：" + log.topics().get(0));
        }
        if (log.data().length() != WORD_LENGTH) {
            throw new IllegalArgumentException(
                    "data 应恰好是一个 uint256（" + WORD_LENGTH + " 字符），收到 " + log.data().length());
        }
        return new Erc20Transfer(
                log.address().toLowerCase(),
                addressFromTopic(log.topics().get(1)),
                addressFromTopic(log.topics().get(2)),
                Hex.toBigInteger(log.data()),
                Hex.toLong(log.blockNumber()),
                log.blockHash(),
                log.transactionHash(),
                (int) Hex.toLong(log.logIndex()));
    }

    /**
     * topic 固定 32 字节，地址只有 20 字节，前面补 12 字节的 0。
     * 前 12 字节<b>不全是 0</b> 的 topic 不是地址——那就不该被当成地址。
     */
    private static String addressFromTopic(String topic) {
        if (topic.length() != TOPIC_LENGTH || !topic.toLowerCase().startsWith(ADDRESS_PADDING)) {
            throw new IllegalArgumentException("topic 不是补零的地址：" + topic);
        }
        return "0x" + topic.substring(ADDRESS_PADDING.length()).toLowerCase();
    }
}
