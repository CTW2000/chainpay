package com.chainpay.chain.indexer.service;

import com.chainpay.chain.indexer.domain.BatchOutcome;
import com.chainpay.chain.indexer.domain.BatchResult;
import com.chainpay.chain.indexer.domain.IndexerState;
import com.chainpay.chain.indexer.domain.IndexerStatus;
import com.chainpay.chain.indexer.domain.ReconcileResult;
import com.chainpay.chain.indexer.domain.ReorgResult;
import com.chainpay.chain.indexer.domain.TickOutcome;
import com.chainpay.chain.indexer.domain.TickResult;
import com.chainpay.chain.indexer.repository.IndexerStateRepository;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.rpc.RpcAuthException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 轮询：放书签（若没有且配了起点）→ 刷新链头 → 连续推批直到追平。
 *
 * <p><b>失败分四种，处理各不相同：</b>
 * <pre>
 *   瞬时的    节点不可达、库暂时拿不到锁      → RETRY_LATER，什么都不动，下一次再来；
 *                                            连续到阈值 → DEGRADED（还在跑，但该有人来看），每轮报错
 *   凭证      节点回 401 / 403                 → HALTED：key 失效或被撤销，重试永远没用
 *   重组      书签接不上链（M2-④ 起）         → 回滚到共同祖先，REORGED；下一次轮询从祖先之后重放
 *   结构性的  finalized 倒退、解码失败、
 *             约束违反、没书签也没起点        → HALTED，停下；之后每次轮询直接返回，不再碰节点
 * </pre>
 * 分界线是「重试会不会有用」和「代码能不能自己修」。重组能自己修（链自己告诉了我们真相）；
 * finalized 之下的变动代码不该自作主张。停下来的索引器是一个报警，往前走的是定时炸弹。
 *
 * <p><b>状态落库（M2-⑥ 补丁 3）：</b>「停下叫人」需要的是状态不是事件。停机、降级、恢复都写进
 * {@code indexer_state}；进程启动先读它，HALTED 就不碰节点——重启不算恢复，人把它改回 RUNNING 才算。
 * 否则结构性原因在自动拉起下会变成静默的重启死循环。
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
    private final TokenRegistry registry;
    private final IndexerStateRepository states;
    private final String name;
    private final String token;
    private final Long startBlock;
    private final int degradedAfterFailures;
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final AtomicBoolean tokenVerified = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<TickResult> lastTick = new AtomicReference<>();
    private volatile Instant lastTickAt;
    private volatile String haltReason;

    /**
     * @param name                  状态表里的名字，和书签同名：一枚书签一个索引器
     * @param startBlock            没有书签时从哪开始（该块视为已处理）；null = 没书签就停下，不猜
     * @param degradedAfterFailures 连续几次瞬时失败（或审计节点连续几次答不出）后降级
     */
    public ChainIndexerScheduler(ChainHeadTracker heads, BlockIndexer indexer, ReorgRecovery recovery,
                                 LogReconciler reconciler, TokenRegistry registry, IndexerStateRepository states,
                                 String name, String token, Long startBlock, int degradedAfterFailures) {
        this.heads = heads;
        this.indexer = indexer;
        this.recovery = recovery;
        this.reconciler = reconciler;
        this.registry = registry;
        this.states = states;
        this.name = name;
        this.token = token;
        this.startBlock = startBlock;
        this.degradedAfterFailures = degradedAfterFailures;
    }

    @Scheduled(fixedDelayString = "${chainpay.chain.poll-interval}")
    public TickResult tick() {
        if (halted.get()) {
            return remember(new TickResult(TickOutcome.HALTED, 0, 0, haltReason));
        }
        try {
            Optional<IndexerState> persisted = states.find(name);
            if (persisted.isPresent() && persisted.get().status() == IndexerStatus.HALTED) {
                // 上一个进程（或另一个实例）停下的：状态表说了算，重启不算恢复
                haltReason = persisted.get().reason();
                halted.set(true);
                log.error("索引器已被停下（indexer_state {}，自 {}）：{}。{}", name, persisted.get().since(), haltReason,
                        recoveryHint());
                return remember(new TickResult(TickOutcome.HALTED, 0, 0, haltReason));
            }
            if (persisted.isEmpty()) {
                states.set(name, IndexerStatus.RUNNING, null);
            }
            TickResult result = poll();
            consecutiveFailures.set(0);
            checkAuditNode();
            return remember(result);
        } catch (RpcAuthException e) {
            return remember(halt("节点拒绝了我们的凭证（" + e.getMessage() + "）：key 失效或被撤销不会自己好，换 key 后重启"));
        } catch (JsonRpcException | TransientDataAccessException e) {
            return remember(retryLater(e.getMessage()));
        } catch (RuntimeException e) {
            return remember(halt(e.getMessage()));
        }
    }

    private TickResult poll() {
        if (!tokenVerified.get()) {
            // 第一次轮询先核对代币：未登记、已停用、decimals 与链上不一致都是结构性问题，停下；节点答不出是瞬时的
            registry.requireUsable(token);
            registry.verifyAgainstChain(token);
            tokenVerified.set(true);
        }
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

    /** 瞬时失败：计数，到阈值就降级并从 WARN 升成每轮一条 ERROR。 */
    private TickResult retryLater(String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= degradedAfterFailures) {
            degrade("连续 " + failures + " 次瞬时失败，最近一次：" + reason);
        } else {
            log.warn("轮询瞬时失败（连续第 {} 次），下一次再来：{}", failures, reason);
        }
        return new TickResult(TickOutcome.RETRY_LATER, 0, 0, reason);
    }

    /** 审计节点连续答不出 = 双节点核对名存实亡。索引照常，但状态要说出来。 */
    private void checkAuditNode() {
        int skips = heads.consecutiveAuditSkips();
        if (skips >= degradedAfterFailures) {
            degrade("审计节点连续 " + skips + " 次答不出 finalized 块：双节点核对名存实亡，检查 CHAINPAY_CHAIN_AUDIT_RPC_URL");
        } else {
            recoverIfDegraded();
        }
    }

    private void degrade(String reason) {
        if (degraded.compareAndSet(false, true)) {
            writeState(IndexerStatus.DEGRADED, reason);
            log.error("索引器降级（还在跑，但该有人来看）：{}", reason);
        } else {
            log.error("索引器仍在降级：{}", reason);
        }
    }

    private void recoverIfDegraded() {
        if (degraded.compareAndSet(true, false)) {
            writeState(IndexerStatus.RUNNING, null);
            log.info("索引器恢复正常");
        }
    }

    public boolean isHalted() {
        return halted.get();
    }

    public String haltReason() {
        return haltReason;
    }

    public Optional<TickResult> lastTick() {
        return Optional.ofNullable(lastTick.get());
    }

    public Instant lastTickAt() {
        return lastTickAt;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    private TickResult halt(String reason) {
        haltReason = reason;
        halted.set(true);
        writeState(IndexerStatus.HALTED, reason);
        log.error("索引器停下，等待人工处理：{}。{}", reason, recoveryHint());
        return new TickResult(TickOutcome.HALTED, 0, 0, reason);
    }

    /** 状态写不进去（比如停机原因本身就是库坏了）不能再抛：内存里的 halted 已经立住，日志里说明就好。 */
    private void writeState(IndexerStatus status, String reason) {
        try {
            states.set(name, status, reason);
        } catch (RuntimeException e) {
            log.error("状态 {} 没能写进 indexer_state（{}）：{}", status, name, e.getMessage());
        }
    }

    private String recoveryHint() {
        return "恢复：处理完原因后 UPDATE indexer_state SET status = 'RUNNING', reason = NULL WHERE name = '" + name
                + "'，再重启；每种原因该做什么见 docs/runbook/chain-indexer.md";
    }

    private TickResult remember(TickResult result) {
        lastTick.set(result);
        lastTickAt = Instant.now();
        return result;
    }
}
