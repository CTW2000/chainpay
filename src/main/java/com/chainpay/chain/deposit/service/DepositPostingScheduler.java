package com.chainpay.chain.deposit.service;

import com.chainpay.chain.deposit.domain.PostingResult;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时跑入账任务，独立于索引器：索引器停了，已 FINAL 的钱照记；入账停了，索引照走。
 * 意外异常只记 ERROR、下一轮重试（同一笔反复失败会每轮报一次，人看得见）。
 *
 * <p><b>停下就不再碰节点</b>（2026-09-15 审核改）：一轮 HALTED（节点撤了我们的凭证）之后，之后每轮直接回记住的那个结果，
 * 不再调 {@code postOnce}——「重试永远没用」就不该每 30 秒再撞一次；索引器落状态表、发送任务标钱包 HALTED，入账任务用这个内存闸门，
 * 三个任务的口径一致。闸门只在内存里：换 key 后<b>重启</b>才会再试，和 runbook 写的一致。健康项照常读到 HALTED = DOWN。
 */
public final class DepositPostingScheduler {

    private static final Logger log = LoggerFactory.getLogger(DepositPostingScheduler.class);

    private final DepositPoster poster;
    private final AtomicReference<PostingResult> lastTick = new AtomicReference<>();
    private final AtomicReference<Instant> lastTickAt = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 停下的那一轮；非空 = 闸门关着，之后不再碰节点。 */
    private final AtomicReference<PostingResult> halted = new AtomicReference<>();

    public DepositPostingScheduler(DepositPoster poster) {
        this.poster = poster;
    }

    @Scheduled(fixedDelayString = "${chainpay.deposit.post-interval:30s}", initialDelayString = "${chainpay.deposit.post-interval:30s}")
    public PostingResult tick() {
        PostingResult stuck = halted.get();
        if (stuck != null) {
            lastTickAt.set(Instant.now());
            log.error("入账仍停着，不再碰节点（重试没用；换 key 后重启才会再试）：{}", stuck.detail());
            return stuck;
        }
        try {
            PostingResult r = poster.postOnce();
            if (r.halted()) {
                halted.set(r);
                log.error("入账停下了，重试没用，要人处理；之后每轮不再碰节点，换 key 后重启：{}（本轮已记 {} 笔）", r.detail(), r.credited());
            } else if (r.retryLater()) {
                log.warn("入账这一轮提前结束，下一轮再来：{}（本轮已记 {} 笔）", r.detail(), r.credited());
            } else if (r.examined() > 0) {
                log.info("入账：看了 {} 笔，记账 {}、HELD {}、忽略 {}、别的实例先记 {}、延后 {}", r.examined(), r.credited(), r.held(), r.ignored(), r.skipped(), r.deferred());
            }
            return remember(r);
        } catch (RuntimeException e) {
            log.error("入账任务异常，这一轮中止，下一轮重试：{}", e.toString());
            return remember(PostingResult.retryLater(0, 0, 0, 0, 0, 0, e.toString()));
        }
    }

    /** 上一轮的结局；还没跑过一轮 = 空。健康项与状态接口读它。 */
    public Optional<PostingResult> lastTick() {
        return Optional.ofNullable(lastTick.get());
    }

    public Instant lastTickAt() {
        return lastTickAt.get();
    }

    /** 闸门关着吗：一轮 HALTED 之后为真，直到进程重启。 */
    public boolean halted() {
        return halted.get() != null;
    }

    /** 连续几轮没跑完（提前结束或停下）。跑完一轮清零——健康项据此把「偶尔抖一下」和「一直不行」分开。 */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    private PostingResult remember(PostingResult r) {
        lastTick.set(r);
        lastTickAt.set(Instant.now());
        if (r.retryLater() || r.halted()) {
            consecutiveFailures.incrementAndGet();
        } else {
            consecutiveFailures.set(0);
        }
        return r;
    }
}
