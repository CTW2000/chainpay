package com.chainpay.chain.indexer;

import com.chainpay.chain.rpc.JsonRpcException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 轮询：放书签（若没有且配了起点）→ 刷新链头 → 连续推批直到追平。
 *
 * <p><b>失败分两种，处理相反：</b>
 * <pre>
 *   瞬时的    节点不可达、库暂时拿不到锁      → RETRY_LATER，什么都不动，下一次再来
 *   结构性的  重组、finalized 倒退、解码失败、
 *             约束违反、没书签也没起点        → HALTED，停下；之后每次轮询直接返回，不再碰节点
 * </pre>
 * 分界线是「重试会不会有用」。停下来的索引器是一个报警，往前走的是定时炸弹。
 * 重组的自动恢复在 M2-④；在那之前，停下就是正确的行为。
 *
 * <p>两个实例同时跑不用在这里管：书签的行锁（{@link BlockIndexer}）已经让它们互斥。
 */
public final class ChainIndexerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChainIndexerScheduler.class);

    /** 一次轮询最多推几批：追很远的块时也给别的事留出时间，下一次轮询接着追。 */
    static final int MAX_BATCHES_PER_TICK = 10;

    private final ChainHeadTracker heads;
    private final BlockIndexer indexer;
    private final Long startBlock;
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private volatile String haltReason;

    /** @param startBlock 没有书签时从哪开始（该块视为已处理）；null = 没书签就停下，不猜 */
    public ChainIndexerScheduler(ChainHeadTracker heads, BlockIndexer indexer, Long startBlock) {
        this.heads = heads;
        this.indexer = indexer;
        this.startBlock = startBlock;
    }

    @Scheduled(fixedDelayString = "${chainpay.chain.poll-interval}")
    public TickResult tick() {
        if (halted.get()) {
            return new TickResult(TickOutcome.HALTED, 0, 0, haltReason);
        }
        try {
            if (!indexer.hasCursor()) {
                if (startBlock == null) {
                    return halt("没有书签，也没配 chainpay.chain.start-block：不知道从哪开始，不猜");
                }
                indexer.start(startBlock);
            }
            heads.refresh();

            int batches = 0;
            int inserted = 0;
            BatchResult result;
            do {
                result = indexer.indexNextBatch();
                if (result.outcome() == BatchOutcome.INDEXED) {
                    batches++;
                    inserted += result.logsInserted();
                }
            } while (result.outcome() == BatchOutcome.INDEXED && batches < MAX_BATCHES_PER_TICK);
            return new TickResult(TickOutcome.POLLED, batches, inserted, null);

        } catch (JsonRpcException | TransientDataAccessException e) {
            log.warn("轮询瞬时失败，下一次再来：{}", e.getMessage());
            return new TickResult(TickOutcome.RETRY_LATER, 0, 0, e.getMessage());
        } catch (RuntimeException e) {
            return halt(e.getMessage());
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
