package com.chainpay.ops.health;

import com.chainpay.chain.deposit.domain.PostingResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 入账任务算不算「能干活」。告警只读 work 组：任务因为节点撤了凭证、或者一直答不上来而停住时，
 * 没有这一项就只剩日志里每 30 秒一行，没人知道。
 *
 * <p>判定：没装配 = UNKNOWN（这个进程不入账，不是坏了）；超过 {@link #STALE_AFTER_INTERVALS} 个周期没跑完一轮（含装配后一直没跑过）= DOWN；
 * 上一轮 HALTED = DOWN（重试没用，要人来）；连续 {@link #DEGRADED_AFTER_ROUNDS} 轮没跑完 = DEGRADED（还在跑，但有人该来看看）；其余 UP。
 *
 * <p>只看上一轮的结局，看得见「跑了但失败」，看不见「不跑了」：一轮卡住（挂在节点或数据库上）、抛了接不住的 Error、
 * 调度根本没开（{@code @EnableScheduling} 没挂上），结局都停在上一轮，一直 UP。所以还要量上一轮结束多久了——对账的 stale 是同一个道理。
 */
public final class DepositHealthIndicator implements HealthIndicator {

    /** 连续几轮没跑完才叫人。5 轮 ≈ 两分半钟（post-interval 30 秒）：节点偶尔抖一下不该惊动人。 */
    public static final int DEGRADED_AFTER_ROUNDS = 5;

    /**
     * 超过几个周期没跑完一轮就判 DOWN。10 个周期 ≈ 5 分钟（post-interval 30 秒）：满批一轮在慢节点上也就两三分钟，
     * 节点或库出错时一轮会提前结束、照样留下心跳；再久就是卡住了，或者根本没在跑。
     */
    public static final int STALE_AFTER_INTERVALS = 10;

    private final boolean assembled;
    private final Supplier<Optional<PostingResult>> lastTick;
    private final IntSupplier consecutiveFailures;
    /** 上一轮结束的时间；还没跑过一轮 = null。 */
    private final Supplier<Instant> lastTickAt;
    private final Duration staleAfter;
    private final Clock clock;
    /** 装配时刻：还没跑过一轮时，沉默从这里量起。 */
    private final Instant since;

    public DepositHealthIndicator(boolean assembled, Supplier<Optional<PostingResult>> lastTick, IntSupplier consecutiveFailures,
                                  Supplier<Instant> lastTickAt, Duration interval, Clock clock) {
        this.assembled = assembled;
        this.lastTick = lastTick;
        this.consecutiveFailures = consecutiveFailures;
        this.lastTickAt = lastTickAt;
        this.staleAfter = interval.multipliedBy(STALE_AFTER_INTERVALS);
        this.clock = clock;
        this.since = clock.instant();
    }

    @Override
    public Health health() {
        if (!assembled) {
            return Health.unknown()
                    .withDetail("reason", "这个进程不入账（web 进程，或 CHAINPAY_CHAIN_RPC_URL=false）")
                    .build();
        }
        Instant heartbeat = lastTickAt.get();
        Duration silence = Duration.between(heartbeat != null ? heartbeat : since, clock.instant());
        if (silence.compareTo(staleAfter) > 0) {
            String reason = heartbeat == null
                    ? "装配后 " + silence.toMinutes() + " 分钟还没跑过一轮：调度可能根本没开"
                    : "上一轮结束后 " + silence.toMinutes() + " 分钟没跑完一轮：可能卡在节点或数据库上，或抛了接不住的错误";
            return Health.down()
                    .withDetail("reason", reason)
                    .withDetail("lastTickAt", String.valueOf(heartbeat))
                    .build();
        }
        Optional<PostingResult> last = lastTick.get();
        if (last.isEmpty()) {
            return Health.up().withDetail("last", "还没跑过一轮").build();
        }
        PostingResult r = last.get();
        int unfinished = consecutiveFailures.getAsInt();
        Health.Builder b;
        if (r.halted()) {
            b = Health.down();
        } else if (unfinished >= DEGRADED_AFTER_ROUNDS) {
            b = Health.status(Statuses.DEGRADED);
        } else {
            b = Health.up();
        }
        b.withDetail("ending", r.ending().name())
                .withDetail("credited", r.credited())
                .withDetail("unfinishedRounds", unfinished);
        if (r.detail() != null) {
            b.withDetail("reason", r.detail());
        }
        return b.build();
    }
}
