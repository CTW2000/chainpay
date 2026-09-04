package com.chainpay.chain.support;

import com.chainpay.chain.erc20.Abi;
import com.chainpay.chain.erc20.TransferLogDecoder;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.Hex;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.rpc.RawLog;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存里的一条链，实现 {@link ChainReader}。
 *
 * <p>区块哈希是 {@code sha256("block-N")}，parentHash 是上一块的哈希——
 * 于是它天然是一条「链」，而测试可以在任何一点把它弄断：
 * {@link #reorgFrom} 换一条分支（真正的重组，日志跟着分支走），
 * {@link #tamperParentHash} / {@link #tamperHash} 单独改一块，
 * {@link #reportHead} 模拟节点落后，{@link #beforeLogs} 在取日志时插一个钩子。
 *
 * <p>M2-⑤ 的三种「不可信」：{@link #limitLogsRange} 让 getLogs 像提供商一样对范围设限并报错（大声的错），
 * {@link #dropFromGetLogs} 让一条日志从 getLogs 消失但仍在回执里（安静的错），
 * 第二个 FakeChain 实例当审计节点（自相矛盾的错——两个实例的哈希是确定性的，默认一致，可各自篡改）。
 *
 * <p>日志记着它所在区块的哈希；{@link #logs} 只返回哈希和当前区块一致的日志。
 * 所有状态都是并发安全的。
 */
public final class FakeChain implements ChainReader {

    public static final String GENESIS_PARENT = "0x" + "0".repeat(64);
    private static final String ADDRESS_PADDING = "0x000000000000000000000000";

    private final ConcurrentMap<Long, BlockHeader> blocks = new ConcurrentHashMap<>();
    private final List<RawLog> logs = new CopyOnWriteArrayList<>();
    private final Set<RawLog> hiddenFromGetLogs = ConcurrentHashMap.newKeySet();
    /** 合约调用的答案：(to, data) → 返回值。没定义的调用一律 revert，和没有那个函数的合约一样。 */
    private final ConcurrentMap<String, String> callAnswers = new ConcurrentHashMap<>();
    private volatile long head = -1;
    private volatile long safe = 0;
    private volatile long finalized = 0;
    private volatile int logsRangeLimit = Integer.MAX_VALUE;
    private volatile Runnable beforeLogs = () -> { };

    /** 原始分支上第 N 块的哈希。 */
    public static String hashOf(long number) {
        return sha256("block-" + number);
    }

    /** 分支 {@code branch} 上第 N 块的哈希；branch 为 null 就是原始分支。 */
    public static String hashOf(long number, String branch) {
        return branch == null ? hashOf(number) : sha256("block-" + number + "-" + branch);
    }

    public static String txHashOf(long block, int index) {
        return sha256("tx-" + block + "-" + index);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "0x" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 造链

    /** 造到 {@code upTo}（已有的块不动），头也指到那里。新块接在前一块<b>当前</b>的哈希上。 */
    public FakeChain withBlocks(long upTo) {
        for (long n = head + 1; n <= upTo; n++) {
            if (blocks.containsKey(n)) {
                continue;
            }
            String parent = n == 0 ? GENESIS_PARENT : blocks.get(n - 1).hash();
            blocks.put(n, new BlockHeader(n, hashOf(n), parent, 1_700_000_000L + n * 12));
        }
        head = Math.max(head, upTo);
        return this;
    }

    /**
     * 真正的重组：从 {@code from} 起换成分支 {@code branch}，哈希全变、parentHash 链重新接好。
     * {@code branch} 为 null 表示换回原始分支——用来造「翻回来」。
     */
    public void reorgFrom(long from, String branch) {
        long top = blocks.keySet().stream().mapToLong(Long::longValue).max().orElse(-1);
        String parent = from == 0 ? GENESIS_PARENT : blocks.get(from - 1).hash();
        for (long n = from; n <= top; n++) {
            String hash = hashOf(n, branch);
            blocks.put(n, new BlockHeader(n, hash, parent, blocks.get(n).timestamp()));
            parent = hash;
        }
    }

    /** 节点落后：报一个比实际链更旧的头。区块本身还在。 */
    public void reportHead(long number) {
        head = number;
    }

    /** 共识层的 safe 头（已 justified）。 */
    public void reportSafe(long number) {
        safe = number;
    }

    /** 共识层的 finalized 头。测试里让它倒退，索引器必须停下。 */
    public void reportFinalized(long number) {
        finalized = number;
    }

    /** 把某块的哈希换掉（下一块的 parentHash 不跟着变）——模拟「同一个号换了哈希」。 */
    public void tamperHash(long number, String hash) {
        blocks.compute(number, (k, b) -> new BlockHeader(b.number(), hash, b.parentHash(), b.timestamp()));
    }

    /** 只改某块的 parentHash，前一块不变——造「节点前后不一致」。 */
    public void tamperParentHash(long number, String parentHash) {
        blocks.compute(number, (k, b) -> new BlockHeader(b.number(), b.hash(), parentHash, b.timestamp()));
    }

    /** 像提供商一样：getLogs 的范围超过 {@code maxBlocks} 块就报带 code 的错。0 = 一块都不给。 */
    public void limitLogsRange(int maxBlocks) {
        logsRangeLimit = maxBlocks;
    }

    /** 安静的错：这条日志从 getLogs 里消失，但回执里还在。 */
    public void dropFromGetLogs(RawLog log) {
        hiddenFromGetLogs.add(log);
    }

    /** 让某个合约地址像一个 ERC-20 一样回答 decimals() 与 symbol()。 */
    public void defineToken(String address, String symbol, int decimals) {
        defineCall(address, Abi.DECIMALS, Abi.encodeUint(java.math.BigInteger.valueOf(decimals)));
        defineCall(address, Abi.SYMBOL, Abi.encodeString(symbol));
    }

    /** 某个地址的余额（balanceOf），不分块高。 */
    public void defineBalance(String token, String holder, java.math.BigInteger balance) {
        defineCall(token, Abi.encodeCall(Abi.BALANCE_OF, holder), Abi.encodeUint(balance));
    }

    /** 原始形式：某个 (to, data) 的返回值。 */
    public void defineCall(String to, String data, String result) {
        callAnswers.put(to.toLowerCase() + ":" + data.toLowerCase(), result);
    }

    /** 造一条标准的 Transfer 日志，挂在该块<b>当前</b>的分支上；logIndex 是该分支该块内的序号。 */
    public RawLog addTransfer(String token, long block, String from, String to, BigInteger value) {
        return addTransfer(token, block, from, to, value, txHashOf(block, logsInBlock(block)));
    }

    /** 同上，但交易哈希由调用方指定——造「不成形数据」，或「同一笔交易在新分支被重新打包」。 */
    public RawLog addTransfer(String token, long block, String from, String to, BigInteger value, String txHash) {
        int index = logsInBlock(block);
        RawLog log = new RawLog(
                token,
                List.of(TransferLogDecoder.TRANSFER_TOPIC0, pad(from), pad(to)),
                String.format("0x%064x", value),
                Hex.fromLong(block),
                block(block).hash(),
                txHash,
                "0x0",
                Hex.fromLong(index),
                false);
        logs.add(log);
        return log;
    }

    /** 原样塞一条日志，形状随便——用来造解码器该拒绝的东西。 */
    public void addRawLog(RawLog log) {
        logs.add(log);
    }

    /** 每次 {@link #logs} 被调用时先跑它。两个实例的测试用它制造交错。 */
    public void beforeLogs(Runnable hook) {
        this.beforeLogs = hook;
    }

    /** 当前分支上该块已有几条日志。 */
    private int logsInBlock(long block) {
        String current = block(block).hash();
        return (int) logs.stream()
                .filter(l -> Hex.toLong(l.blockNumber()) == block && l.blockHash().equalsIgnoreCase(current))
                .count();
    }

    private static String pad(String address) {
        return ADDRESS_PADDING + address.substring(2).toLowerCase();
    }

    // ---------------------------------------------------------------- ChainReader

    @Override
    public long blockNumber() {
        return head;
    }

    @Override
    public BlockHeader block(String numberOrTag) {
        return switch (numberOrTag) {
            case "latest" -> block(head);
            case "safe" -> block(safe);
            case "finalized" -> block(finalized);
            default -> block(Hex.toLong(numberOrTag));
        };
    }

    @Override
    public BlockHeader block(long number) {
        BlockHeader b = blocks.get(number);
        if (b == null) {
            throw new JsonRpcException(null, "区块不存在：" + number);
        }
        return b;
    }

    @Override
    public List<RawLog> logs(long fromBlock, long toBlock, String address, String topic0) {
        beforeLogs.run();
        if (toBlock - fromBlock + 1 > logsRangeLimit) {
            throw new JsonRpcException(-32005, "query returned more than 10000 results（假节点：范围 "
                    + fromBlock + ".." + toBlock + " 超过 " + logsRangeLimit + " 块）");
        }
        return logs.stream()
                .filter(l -> !hiddenFromGetLogs.contains(l))
                .filter(l -> {
                    long n = Hex.toLong(l.blockNumber());
                    BlockHeader current = blocks.get(n);
                    return n >= fromBlock && n <= toBlock
                            && current != null && l.blockHash().equalsIgnoreCase(current.hash())
                            && l.address().equalsIgnoreCase(address)
                            && !l.topics().isEmpty()
                            && l.topics().get(0).equalsIgnoreCase(topic0);
                })
                .toList();
    }

    @Override
    public String call(String to, String data, String blockTag) {
        String answer = callAnswers.get(to.toLowerCase() + ":" + data.toLowerCase());
        if (answer == null) {
            throw new JsonRpcException(3, "execution reverted（假节点：" + to + " 没有定义对 "
                    + data.substring(0, Math.min(10, data.length())) + " 的回答）");
        }
        return answer;
    }

    /** 事实源：该块当前分支上的全部日志，不筛地址、不管有没有被 getLogs 藏起来。 */
    @Override
    public List<RawLog> blockReceipts(long number) {
        String current = block(number).hash();
        return logs.stream()
                .filter(l -> Hex.toLong(l.blockNumber()) == number && l.blockHash().equalsIgnoreCase(current))
                .toList();
    }
}
