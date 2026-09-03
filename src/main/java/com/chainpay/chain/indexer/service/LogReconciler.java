package com.chainpay.chain.indexer.service;

import com.chainpay.chain.erc20.Erc20Transfer;
import com.chainpay.chain.erc20.TransferLogDecoder;
import com.chainpay.chain.indexer.domain.BlockReconciliation;
import com.chainpay.chain.indexer.domain.ChainHead;
import com.chainpay.chain.indexer.domain.IndexerCursor;
import com.chainpay.chain.indexer.domain.LogCoordinate;
import com.chainpay.chain.indexer.domain.ReconcileResult;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.ReconcileRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.RawLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 抽样对账：用回执这条<b>事实源</b>路径重新数一遍，和库里比。
 *
 * <p>为什么要有它：{@code eth_getLogs} 从 logsBloom 建的索引里取数据，索引没收录的日志就不在
 * 结果里，而响应里没有任何字段提示「少了」（SQD 记录的 Polygon 74614768：getLogs 848 条，
 * 回执 856 条）。安静的错只能事后用另一条路径发现。
 *
 * <p><b>抽哪些块：</b>不高于书签（我们索引过）、不高于 finalized（答案不会再变）、不早于起点。
 * 每次轮询抽几块，Sepolia 一天 7200 块，一天大约把整条链过一遍。
 *
 * <p><b>差异怎么处理：两个节点都点头才动。</b>
 * <pre>
 *   回执有、库里没有 → 主节点回执也确认有 → 补录（复活型写入）
 *   库里有、回执没有 → 主节点回执也确认没有 → 标 ORPHANED（当初被骗记下的幻影）
 *   只有一方说有     → 不动，记为 disputed，等人看
 * </pre>
 * 没配审计节点时两条路径是同一个节点：能抓住「索引漏了」，抓不住「节点整体撒谎」。
 *
 * <p>抽到的块已经 finalized，两个节点对它的哈希还不一致，那不是对账差异，
 * 是 {@link FinalityViolationException}：停下叫人。
 */
public final class LogReconciler {

    /** 抽样窗口：finalized 往回这么多块。 */
    static final long SAMPLE_WINDOW = 7_200;

    private final ChainReader primary;
    private final ChainReader audit;
    private final IndexerCursorRepository cursors;
    private final TransferLogRepository transferLogs;
    private final ChainHeadRepository heads;
    private final ReconcileRepository reconciles;
    private final TransactionTemplate tx;
    private final String cursorName;
    private final String token;
    private final int samplesPerRun;
    private final RandomGenerator random;

    public LogReconciler(ChainReader primary,
                         ChainReader audit,
                         IndexerCursorRepository cursors,
                         TransferLogRepository transferLogs,
                         ChainHeadRepository heads,
                         ReconcileRepository reconciles,
                         TransactionTemplate tx,
                         String cursorName,
                         String token,
                         int samplesPerRun,
                         RandomGenerator random) {
        this.primary = primary;
        this.audit = audit;
        this.cursors = cursors;
        this.transferLogs = transferLogs;
        this.heads = heads;
        this.reconciles = reconciles;
        this.tx = tx;
        this.cursorName = cursorName;
        this.token = token.toLowerCase();
        this.samplesPerRun = samplesPerRun;
        this.random = random;
    }

    /** 抽 samplesPerRun 个块各对一遍。没有书签或链头时什么都不做。 */
    public ReconcileResult reconcile() {
        Optional<IndexerCursor> cursor = cursors.find(cursorName);
        Optional<ChainHead> head = heads.find();
        if (cursor.isEmpty() || head.isEmpty()) {
            return new ReconcileResult(List.of(), 0);
        }
        long upper = Math.min(cursor.get().lastBlockNumber(), head.get().finalized().number());
        long start = cursors.startBlock(cursorName).orElse(0L);
        long lower = Math.max(start + 1, upper - SAMPLE_WINDOW + 1);
        if (lower > upper) {
            return new ReconcileResult(List.of(), 0);
        }
        List<Long> sampled = new ArrayList<>();
        int mismatches = 0;
        for (int i = 0; i < samplesPerRun; i++) {
            long block = lower + random.nextLong(upper - lower + 1);
            sampled.add(block);
            if (!reconcileBlock(block).isClean()) {
                mismatches++;
            }
        }
        return new ReconcileResult(List.copyOf(sampled), mismatches);
    }

    /** 对一个块：问两个节点，比回执和库，差异按「两个节点都点头」处理，有差异才记审计。 */
    public BlockReconciliation reconcileBlock(long blockNumber) {
        // ① 两个节点对这个（已 finalized 的）块是不是同一个块。网络在事务外
        BlockHeader ours = primary.block(blockNumber);
        BlockHeader theirs = audit.block(blockNumber);
        if (!theirs.hash().equalsIgnoreCase(ours.hash())) {
            throw new FinalityViolationException("两个节点对已 finalized 的块 " + blockNumber + " 意见不同：主节点 "
                    + ours.hash() + "，审计节点 " + theirs.hash() + "。不知道信谁，停下叫人");
        }

        // ② 事实源：审计节点回执里属于我们代币的 Transfer；③ 库里 CANONICAL 的行
        Map<String, Erc20Transfer> expected = ourTransfers(audit.blockReceipts(blockNumber));
        Map<String, LogCoordinate> found = new LinkedHashMap<>();
        for (LogCoordinate c : transferLogs.canonicalLogsInBlock(blockNumber)) {
            found.put(key(c.blockHash(), c.logIndex()), c);
        }
        List<Erc20Transfer> missing = expected.entrySet().stream()
                .filter(e -> !found.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        List<LogCoordinate> phantoms = found.entrySet().stream()
                .filter(e -> !expected.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        if (missing.isEmpty() && phantoms.isEmpty()) {
            return new BlockReconciliation(blockNumber, ours.hash(), expected.size(), found.size(), 0, 0, 0);
        }

        // ④ 差异要两个节点都点头：主节点的回执
        Set<String> primaryHas = ourTransfers(primary.blockReceipts(blockNumber)).keySet();
        List<Erc20Transfer> toRepair = missing.stream()
                .filter(t -> primaryHas.contains(key(t.blockHash(), t.logIndex()))).toList();
        List<LogCoordinate> toOrphan = phantoms.stream()
                .filter(c -> !primaryHas.contains(key(c.blockHash(), c.logIndex()))).toList();
        int disputed = (missing.size() - toRepair.size()) + (phantoms.size() - toOrphan.size());

        // ⑤ 事务：补录、标废、记审计
        return tx.execute(status -> {
            int repaired = transferLogs.recordCanonical(toRepair);
            int orphaned = 0;
            for (LogCoordinate c : toOrphan) {
                orphaned += transferLogs.orphanOne(c.blockHash(), c.logIndex());
            }
            BlockReconciliation result = new BlockReconciliation(blockNumber, ours.hash(),
                    expected.size(), found.size(), repaired, orphaned, disputed);
            reconciles.record(result);
            return result;
        });
    }

    /** 回执里属于我们代币的 Transfer，按 (blockHash, logIndex) 索引。 */
    private Map<String, Erc20Transfer> ourTransfers(List<RawLog> receiptLogs) {
        Map<String, Erc20Transfer> out = new LinkedHashMap<>();
        for (RawLog raw : receiptLogs) {
            boolean ours = raw.address().equalsIgnoreCase(token)
                    && !raw.topics().isEmpty()
                    && raw.topics().get(0).equalsIgnoreCase(TransferLogDecoder.TRANSFER_TOPIC0);
            if (ours) {
                Erc20Transfer t = TransferLogDecoder.decode(raw);
                out.put(key(t.blockHash(), t.logIndex()), t);
            }
        }
        return out;
    }

    private static String key(String blockHash, int logIndex) {
        return blockHash.toLowerCase() + "#" + logIndex;
    }
}
