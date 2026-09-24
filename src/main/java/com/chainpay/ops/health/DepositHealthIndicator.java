package com.chainpay.ops.health;

import com.chainpay.chain.deposit.domain.PostingResult;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 入账任务算不算「能干活」。告警只读 work 组：任务因为节点撤了凭证、或者一直答不上来而停住时，
 * 没有这一项就只剩日志里每 30 秒一行，没人知道。
 *
 * <p>判定：没装配 = UNKNOWN（这个进程不入账，不是坏了）；上一轮 HALTED = DOWN（重试没用，要人来）；
 * 连续 {@link #DEGRADED_AFTER_ROUNDS} 轮没跑完 = DEGRADED（还在跑，但有人该来看看）；其余 UP。
 */
public final class DepositHealthIndicator implements HealthIndicator {

    /** 连续几轮没跑完才叫人。5 轮 ≈ 两分半钟（post-interval 30 秒）：节点偶尔抖一下不该惊动人。 */
    public static final int DEGRADED_AFTER_ROUNDS = 5;

    private final boolean assembled;
    private final Supplier<Optional<PostingResult>> lastTick;
    private final IntSupplier consecutiveFailures;

    public DepositHealthIndicator(boolean assembled, Supplier<Optional<PostingResult>> lastTick, IntSupplier consecutiveFailures) {
        this.assembled = assembled;
        this.lastTick = lastTick;
        this.consecutiveFailures = consecutiveFailures;
    }

    @Override
    public Health health() {
        if (!assembled) {
            return Health.unknown()
                    .withDetail("reason", "这个进程不入账（web 进程，或 CHAINPAY_CHAIN_RPC_URL=false）")
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
