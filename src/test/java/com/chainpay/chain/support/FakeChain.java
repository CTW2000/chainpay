package com.chainpay.chain.support;

import com.chainpay.chain.erc20.Abi;
import com.chainpay.chain.erc20.TransferLogDecoder;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.wallet.Keccak256;
import com.chainpay.chain.wallet.Eip1559Transaction;
import com.chainpay.chain.wallet.Ecdsa;
import com.chainpay.chain.rpc.FeeQuote;
import com.chainpay.chain.rpc.ChainSender;
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
 * {@link #reportHead} 模拟节点落后，{@link #beforeLogs} / {@link #beforeCall} / {@link #beforeBlock} 在取日志 / 问合约 / 取区块头时插一个钩子，
 * {@link #injectIntoGetLogs} 让 getLogs 像撒谎的节点一样塞进范围外或哈希不对的日志。
 *
 * <p>M2-⑤ 的三种「不可信」：{@link #limitLogsRange} 让 getLogs 像提供商一样对范围设限并报错（大声的错），
 * {@link #dropFromGetLogs} 让一条日志从 getLogs 消失但仍在回执里（安静的错），
 * 第二个 FakeChain 实例当审计节点（自相矛盾的错——两个实例的哈希是确定性的，默认一致，可各自篡改）。
 *
 * <p>日志记着它所在区块的哈希；{@link #logs} 只返回哈希和当前区块一致的日志。
 * 所有状态都是并发安全的。
 */
public final class FakeChain implements ChainReader, ChainSender {

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
    private volatile Runnable beforeCall = () -> { };
    private volatile java.util.function.LongConsumer beforeBlock = n -> { };
    /** 撒谎的节点塞进 getLogs 响应里的日志：不看范围、不看分支、不看地址。 */
    private final List<RawLog> injectedIntoGetLogs = new CopyOnWriteArrayList<>();

    // ------------------------------------------------------------------ M4-②：内存池
    /** 一笔在内存池里等打包的交易。 */
    public record PendingTx(String hash, String from, long nonce, byte[] raw) {}

    /** 地址 → 已上链的笔数（nonce 的真相）。 */
    private final ConcurrentMap<String, Long> minedCount = new ConcurrentHashMap<>();
    /** 哈希 → 待打包。 */
    private final ConcurrentMap<String, PendingTx> mempool = new ConcurrentHashMap<>();
    /** 已上链的交易哈希（③ 用；② 只用来回答 transactionKnown）。 */
    private final Set<String> minedHashes = ConcurrentHashMap.newKeySet();
    private volatile BigInteger estimatedGas = BigInteger.valueOf(52_000);
    private volatile JsonRpcException estimateFailure;
    private volatile FeeQuote feeQuote = new FeeQuote(BigInteger.valueOf(10_000_000_000L), BigInteger.valueOf(1_500_000_000L));
    private volatile JsonRpcException sendRejection;
    private volatile Runnable beforeSend = () -> { };
    private volatile Runnable afterSend = () -> { };

    public void answerEstimateGas(long gas) {
        this.estimatedGas = BigInteger.valueOf(gas);
        this.estimateFailure = null;
    }

    /** 让 eth_estimateGas 像合约 revert 那样失败（带 code）。 */
    public void failEstimateGas(int code, String message) {
        this.estimateFailure = new JsonRpcException(code, message);
    }

    public void quoteFees(BigInteger baseFeePerGas, BigInteger maxPriorityFeePerGas) {
        this.feeQuote = new FeeQuote(baseFeePerGas, maxPriorityFeePerGas);
    }

    /** 让广播被节点拒绝（带 code），比如 insufficient funds。 */
    public void rejectSends(int code, String message) {
        this.sendRejection = new JsonRpcException(code, message);
    }

    public void acceptSends() {
        this.sendRejection = null;
    }

    /** 广播前的钩子：抛异常 = 请求根本没到节点（传输失败）。 */
    public void beforeSend(Runnable hook) {
        this.beforeSend = hook;
    }

    /** 广播后的钩子：抛异常 = 节点收下了但回答没送到（模拟提交与记录之间崩溃）。 */
    public void afterSend(Runnable hook) {
        this.afterSend = hook;
    }

    /** 别人用这把私钥在别处发了一笔并上链：已上链笔数 +1，我们的库对此一无所知。 */
    public void externalTransactionFrom(String address) {
        minedCount.merge(address.toLowerCase(), 1L, Long::sum);
    }

    public java.util.Collection<PendingTx> mempool() {
        return List.copyOf(mempool.values());
    }

    public List<Long> pendingNonces(String address) {
        return mempool.values().stream().filter(t -> t.from().equalsIgnoreCase(address)).map(PendingTx::nonce).sorted().toList();
    }

    @Override
    public BigInteger transactionCount(String address, String tag) {
        long mined = minedCount.getOrDefault(address.toLowerCase(), 0L);
        if (!"pending".equals(tag)) {
            return BigInteger.valueOf(mined);
        }
        long next = mined;
        while (hasPending(address, next)) {
            next++;
        }
        return BigInteger.valueOf(next);
    }

    private boolean hasPending(String address, long nonce) {
        return mempool.values().stream().anyMatch(t -> t.from().equalsIgnoreCase(address) && t.nonce() == nonce);
    }

    @Override
    public BigInteger estimateGas(String from, String to, String data) {
        beforeCall.run();
        if (estimateFailure != null) {
            throw estimateFailure;
        }
        return estimatedGas;
    }

    @Override
    public FeeQuote feeQuote() {
        return feeQuote;
    }

    @Override
    public boolean transactionKnown(String txHash) {
        return mempool.containsKey(txHash.toLowerCase()) || minedHashes.contains(txHash.toLowerCase());
    }

    /**
     * 像节点一样收原文：按内容识别（同一份原文再发 = already known），编号低于已上链笔数 = nonce too low，
     * 同编号已有一笔在池里 = replacement transaction underpriced（③ 再学会比费率）。
     */
    @Override
    public String sendRawTransaction(byte[] raw) {
        beforeSend.run();
        synchronized (mempool) {                                   // 钩子之外的部分原子：节点一次只收一份
            return accept(raw);
        }
    }

    private String accept(byte[] raw) {
        if (sendRejection != null) {
            throw sendRejection;
        }
        Eip1559Transaction.Decoded decoded = Eip1559Transaction.decode(raw);
        String from = Ecdsa.recoverAddress(decoded.transaction().signingHash(), decoded.signature()).toLowerCase();
        String hash = "0x" + java.util.HexFormat.of().formatHex(Keccak256.hash(raw));
        if (mempool.containsKey(hash) || minedHashes.contains(hash)) {
            throw new JsonRpcException(-32000, "already known");
        }
        long nonce = decoded.transaction().nonce();
        if (nonce < minedCount.getOrDefault(from, 0L)) {
            throw new JsonRpcException(-32000, "nonce too low: next nonce " + minedCount.get(from) + ", tx nonce " + nonce);
        }
        if (hasPending(from, nonce)) {
            throw new JsonRpcException(-32000, "replacement transaction underpriced");
        }
        mempool.put(hash, new PendingTx(hash, from, nonce, raw.clone()));
        afterSend.run();
        return hash;
    }

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

    /** 某个地址在某一块上的余额：入账任务问的是「那一块」的状态，同一地址在不同块上余额不同。 */
    public void defineBalanceAt(String token, String holder, long block, java.math.BigInteger balance) {
        callAnswers.put(token.toLowerCase() + ":" + Abi.encodeCall(Abi.BALANCE_OF, holder).toLowerCase() + ":" + Hex.fromLong(block),
                Abi.encodeUint(balance));
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
                List.of(TransferLogDecoder.TRANSFER_TOPIC0, addressTopic(from), addressTopic(to)),
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

    /** 每次 eth_call 前先跑一下：在里面抛异常就是模拟「问合约」时节点失败，写库就是模拟另一个实例插队。 */
    public void beforeCall(Runnable hook) {
        this.beforeCall = hook;
    }

    /** 每次取某个区块头之前先跑它（参数是块号）：在里面重组，就是「取头和取日志之间链换了分支」。 */
    public void beforeBlock(java.util.function.LongConsumer hook) {
        this.beforeBlock = hook;
    }

    /** 撒谎的节点：这条日志会出现在每一次 getLogs 的响应里，不管问的是什么范围。 */
    public void injectIntoGetLogs(RawLog log) {
        injectedIntoGetLogs.add(log);
    }

    /** 当前分支上该块已有几条日志。 */
    private int logsInBlock(long block) {
        String current = block(block).hash();
        return (int) logs.stream()
                .filter(l -> Hex.toLong(l.blockNumber()) == block && l.blockHash().equalsIgnoreCase(current))
                .count();
    }

    /** 地址作为 indexed 参数进 topic 的形状：左补 12 字节的零。 */
    public static String addressTopic(String address) {
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
        beforeBlock.accept(number);
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
        List<RawLog> answer = new java.util.ArrayList<>(logs.stream()
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
                .toList());
        answer.addAll(injectedIntoGetLogs);
        return List.copyOf(answer);
    }

    @Override
    public String call(String to, String data, String blockTag) {
        beforeCall.run();
        String key = to.toLowerCase() + ":" + data.toLowerCase();
        String answer = callAnswers.get(key + ":" + blockTag);            // 先找按块高定义的，再退回不分块高的
        if (answer == null) {
            answer = callAnswers.get(key);
        }
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
