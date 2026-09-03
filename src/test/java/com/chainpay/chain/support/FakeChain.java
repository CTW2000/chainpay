package com.chainpay.chain.support;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存里的一条链，实现 {@link ChainReader}。
 *
 * <p>区块哈希是 {@code sha256("block-N")}，parentHash 是上一块的哈希——
 * 于是它天然是一条「链」，而测试可以在任何一点把它弄断：
 * {@link #tamperParentHash} 模拟重组，{@link #reportHead} 模拟节点落后，
 * {@link #beforeLogs} 在取日志时插一个钩子，用来制造两个实例的交错。
 *
 * <p>所有状态都是并发安全的：两个实例的测试会从两个线程同时读它。
 */
public final class FakeChain implements ChainReader {

    public static final String GENESIS_PARENT = "0x" + "0".repeat(64);
    private static final String ADDRESS_PADDING = "0x000000000000000000000000";

    private final ConcurrentMap<Long, BlockHeader> blocks = new ConcurrentHashMap<>();
    private final List<RawLog> logs = new CopyOnWriteArrayList<>();
    private volatile long head = -1;
    private volatile long safe = 0;
    private volatile long finalized = 0;
    private volatile Runnable beforeLogs = () -> { };

    public static String hashOf(long number) {
        return sha256("block-" + number);
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

    /** 从创世块一直造到 {@code upTo}，头也指到那里。 */
    public FakeChain withBlocks(long upTo) {
        for (long n = head + 1; n <= upTo; n++) {
            String parent = n == 0 ? GENESIS_PARENT : hashOf(n - 1);
            blocks.put(n, new BlockHeader(n, hashOf(n), parent, 1_700_000_000L + n * 12));
        }
        head = Math.max(head, upTo);
        return this;
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

    /** 模拟重组：把某块的 parentHash 改掉，让它不再接在前一块上。 */
    public void tamperParentHash(long number, String parentHash) {
        blocks.compute(number, (k, b) -> new BlockHeader(b.number(), b.hash(), parentHash, b.timestamp()));
    }

    /** 造一条标准的 Transfer 日志，logIndex 是该块内的序号。 */
    public RawLog addTransfer(String token, long block, String from, String to, BigInteger value) {
        return addTransfer(token, block, from, to, value, txHashOf(block, logsInBlock(block)));
    }

    /** 同上，但交易哈希由调用方指定——用来造「节点给了不成形数据」的场景。 */
    public RawLog addTransfer(String token, long block, String from, String to, BigInteger value, String txHash) {
        int index = logsInBlock(block);
        RawLog log = new RawLog(
                token,
                List.of(TransferLogDecoder.TRANSFER_TOPIC0, pad(from), pad(to)),
                String.format("0x%064x", value),
                Hex.fromLong(block),
                hashOf(block),
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

    private int logsInBlock(long block) {
        return (int) logs.stream().filter(l -> Hex.toLong(l.blockNumber()) == block).count();
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
        return logs.stream()
                .filter(l -> {
                    long n = Hex.toLong(l.blockNumber());
                    return n >= fromBlock && n <= toBlock
                            && l.address().equalsIgnoreCase(address)
                            && !l.topics().isEmpty()
                            && l.topics().get(0).equalsIgnoreCase(topic0);
                })
                .toList();
    }
}
