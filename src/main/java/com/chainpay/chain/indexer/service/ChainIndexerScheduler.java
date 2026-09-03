package com.chainpay.chain.indexer.service;

import com.chainpay.chain.indexer.domain.BatchOutcome;
import com.chainpay.chain.indexer.domain.BatchResult;
import com.chainpay.chain.indexer.domain.ReconcileResult;
import com.chainpay.chain.indexer.domain.ReorgResult;
import com.chainpay.chain.indexer.domain.TickOutcome;
import com.chainpay.chain.indexer.domain.TickResult;
import com.chainpay.chain.rpc.JsonRpcException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 轮询：放书签（若没有且配了起点）→ 刷新链头 → 连续推批直到追平。
 *
 * <p><b>失败分三种，处理各不相同：</b>
 * <pre>
 *   瞬时的    节点不可达、库暂时拿不到锁      → RETRY_LATER，什么都不动，下一次再来
 *   重组      书签接不上链（M2-④ 起）         → 回滚到共同祖先，REORGED；下一次轮询从祖先之后重放
 *   结构性的  finalized 倒退、解码失败、
 *             约束违反、没书签也没起点        → HALTED，停下；之后每次轮询直接返回，不再碰节点
 * </pre>
 * 分界线是「重试会不会有用」和「代码能不能自己修」。重组能自己修（链自己告诉了我们真相）；
 * finalized 之下的变动代码不该自作主张。停下来的索引器是一个报警，往前走的是定时炸弹。
 *
 * <p>两个实例同时跑不用在这里管：书签的行锁（{@link BlockIndexer} / {@link ReorgRecovery}）已经让它们互斥。
 */
public final class ChainIndexerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChainIndexerScheduler.class);

    /** 一次轮询最多推几批：追很远的块时也给别的事留出时间，下一次轮询接着追。 */
    static final int MAX_BATCHES_PER_TICK = 10;

    private final ChainHeadTracker heads;
    private final BlockIndexer indexer;
    private final ReorgRecovery recovery;
    private final LogReconciler reconciler;
    private final Long startBlock;
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private volatile String haltReason;

    /** @param startBlock 没有书签时从哪开始（该块视为已处理）；null = 没书签就停下，不猜 */
    public ChainIndexerScheduler(ChainHeadTracker heads, BlockIndexer indexer, ReorgRecovery recovery,
                                 LogReconciler reconciler, Long startBlock) {
        this.heads = heads;
        this.indexer = indexer;
        this.recovery = recovery;
        this.reconciler = reconciler;
        this.startBlock = startBlock;
    }

    @Scheduled(fixedDelayString = "${chainpay.chain.poll-interval}")
    public TickResult tick() {
        if (halted.get()) {
            return new TickResult(TickOutcome.HALTED, 0, 0, haltReason);
        }
        try {
            return poll();
        } catch (JsonRpcException | TransientDataAccessException e) {
            log.warn("轮询瞬时失败，下一次再来：{}", e.getMessage());
            return new TickResult(TickOutcome.RETRY_LATER, 0, 0, e.getMessage());
        } catch (RuntimeException e) {
            return halt(e.getMessage());
        }
    }

    private TickResult poll() {
        if (!indexer.hasCursor()) {
            if (startBlock == null) {
                return halt("没有书签，也没配 chainpay.chain.start-block：不知道从哪开始，不猜");
            }
            indexer.start(startBlock);
        }
        heads.refresh();

        int batches = 0;
        int inserted = 0;
        try {
            BatchResult result;
            do {
                result = indexer.indexNextBatch();
                if (result.outcome() == BatchOutcome.INDEXED) {
                    batches++;
                    inserted += result.logsInserted();
                }
            } while (result.outcome() == BatchOutcome.INDEXED && batches < MAX_BATCHES_PER_TICK);
            ReconcileResult reconciled = reconcileSafely();
            return new TickResult(TickOutcome.POLLED, batches, inserted,
                    reconciled.sampledBlocks().size(), reconciled.mismatches(), null);
        } catch (ReorgDetectedException e) {
            // 恢复器自己的异常（finalized 之下、节点前后不一致）从这里穿出去，由 tick 分类
            ReorgResult r = recovery.recover(e.blockNumber() - 1, e.expectedParentHash());
            String detail = r.applied()
                    ? "重组：书签 " + r.cursorBlock() + " 退到 " + r.ancestorBlock() + "，深度 " + r.depth()
                            + "，标废 " + r.orphanedLogs() + " 行；下一次轮询重放"
                    : "重组：别的实例已经恢复过，这次什么都没做";
            log.warn(detail);
            return new TickResult(TickOutcome.REORGED, batches, inserted, detail);
        }
    }

    /**
     * 对账是审计：它自己的瞬时失败（审计节点不可达、库暂时拿不到锁）不改变这次轮询的结局。
     * finalized 上的分歧是 FinalityViolationException，从这里穿出去，由 tick 停机。
     */
    private ReconcileResult reconcileSafely() {
        try {
            return reconciler.reconcile();
        } catch (JsonRpcException | TransientDataAccessException e) {
            log.warn("对账这次跳过：{}", e.getMessage());
            return new ReconcileResult(List.of(), 0);
        }
    }

    public boolean isHalted() {
        return halted.get();
    }

    public String haltReason() {
        return haltReason;
    }

    private TickResult halt(String reason) {
        haltReason = reason;
        halted.set(true);
        log.error("索引器停下，等待人工处理：{}", reason);
        return new TickResult(TickOutcome.HALTED, 0, 0, reason);
    }
}
