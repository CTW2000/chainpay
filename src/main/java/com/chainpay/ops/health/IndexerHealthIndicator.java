package com.chainpay.ops.health;

import com.chainpay.chain.indexer.domain.IndexerState;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 索引器算不算「能干活」：状态表里的行说了算（上一个进程停下的原因重启也还在）。
 * 没装配（web 进程，或节点地址写成 false）= UNKNOWN（这个进程不索引，不是坏了）；没有行 = 从没停过 = UP；RUNNING / DEGRADED / HALTED 各一档。
 */
public final class IndexerHealthIndicator implements HealthIndicator {

    private final boolean assembled;
    private final Supplier<Optional<IndexerState>> state;

    public IndexerHealthIndicator(boolean assembled, Supplier<Optional<IndexerState>> state) {
        this.assembled = assembled;
        this.state = state;
    }

    @Override
    public Health health() {
        if (!assembled) {
            return Health.unknown().withDetail("reason", "这个进程不索引（web 进程，或 CHAINPAY_CHAIN_RPC_URL=false）").build();
        }
        Optional<IndexerState> s = state.get();
        if (s.isEmpty()) {
            return Health.up().withDetail("persisted", "无（从没停过）").build();
        }
        IndexerState st = s.get();
        Health.Builder b = switch (st.status()) {
            case RUNNING -> Health.up();
            case DEGRADED -> Health.status(Statuses.DEGRADED);
            case HALTED -> Health.down();
        };
        b.withDetail("persisted", st.status().name()).withDetail("since", String.valueOf(st.since()));
        if (st.reason() != null) {
            b.withDetail("reason", st.reason());
        }
        return b.build();
    }
}
