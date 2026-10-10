package com.chainpay.ops.health;

import com.chainpay.chain.indexer.domain.IndexerState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 索引器算不算「能干活」：状态表里的行说了算（上一个进程停下的原因重启也还在）。
 * 没装配（web 进程，或节点地址写成 false）= UNKNOWN（这个进程不索引，不是坏了）；没有行 = 从没停过 = UP；RUNNING / DEGRADED / HALTED 各一档。
 *
 * <p>但状态表只在状态变化时才写：任务卡住（挂在节点或数据库上）、抛了接不住的 Error、调度根本没开，表里还是 RUNNING（或没有行），一直 UP。
 * 所以先量上一轮结束多久了：超过「追赶预算 + {@link #STALE_AFTER_INTERVALS} 个轮询周期」没跑完一轮（含装配后一直没跑过）= DOWN。
 * 已停下（HALTED）的调度器每轮照样留下心跳，不会被算成沉默。入账健康项、对账的 stale 是同一个道理。
 */
public final class IndexerHealthIndicator implements HealthIndicator {

    /**
     * 追赶预算之外再宽限几个轮询周期。落后时一轮可以连续追赶到预算用完（默认 5 分钟），那不是卡住；
     * 预算 + 10 个周期（12 秒一轮 ≈ 2 分钟）还没结束一轮，才是。
     */
    public static final int STALE_AFTER_INTERVALS = 10;

    private final boolean assembled;
    private final Supplier<Optional<IndexerState>> state;
    /** 上一轮结束的时间；还没跑过一轮 = null。 */
    private final Supplier<Instant> lastTickAt;
    private final Duration staleAfter;
    private final Clock clock;
    /** 装配时刻：还没跑过一轮时，沉默从这里量起。 */
    private final Instant since;

    public IndexerHealthIndicator(boolean assembled, Supplier<Optional<IndexerState>> state,
                                  Supplier<Instant> lastTickAt, Duration pollInterval, Duration catchUpBudget, Clock clock) {
        this.assembled = assembled;
        this.state = state;
        this.lastTickAt = lastTickAt;
        this.staleAfter = catchUpBudget.plus(pollInterval.multipliedBy(STALE_AFTER_INTERVALS));
        this.clock = clock;
        this.since = clock.instant();
    }

    @Override
    public Health health() {
        if (!assembled) {
            return Health.unknown().withDetail("reason", "这个进程不索引（web 进程，或 CHAINPAY_CHAIN_RPC_URL=false）").build();
        }
        Optional<IndexerState> s = state.get();
        String persisted = s.map(st -> st.status().name()).orElse("无（从没停过）");
        Instant heartbeat = lastTickAt.get();
        Duration silence = Duration.between(heartbeat != null ? heartbeat : since, clock.instant());
        if (silence.compareTo(staleAfter) > 0) {
            String reason = heartbeat == null
                    ? "装配后 " + silence.toMinutes() + " 分钟还没跑过一轮：调度可能根本没开"
                    : "上一轮结束后 " + silence.toMinutes() + " 分钟没跑完一轮：可能卡在节点或数据库上，或抛了接不住的错误";
            return Health.down()
                    .withDetail("reason", reason)
                    .withDetail("persisted", persisted)
                    .withDetail("lastTickAt", String.valueOf(heartbeat))
                    .build();
        }
        if (s.isEmpty()) {
            return Health.up().withDetail("persisted", persisted).withDetail("lastTickAt", String.valueOf(heartbeat)).build();
        }
        IndexerState st = s.get();
        Health.Builder b = switch (st.status()) {
            case RUNNING -> Health.up();
            case DEGRADED -> Health.status(Statuses.DEGRADED);
            case HALTED -> Health.down();
        };
        b.withDetail("persisted", persisted)
                .withDetail("since", String.valueOf(st.since()))
                .withDetail("lastTickAt", String.valueOf(heartbeat));
        if (st.reason() != null) {
            b.withDetail("reason", st.reason());
        }
        return b.build();
    }
}
