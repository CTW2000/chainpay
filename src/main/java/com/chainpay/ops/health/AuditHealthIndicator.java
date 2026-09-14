package com.chainpay.ops.health;

import com.chainpay.audit.domain.AuditResult;
import com.chainpay.audit.service.AuditService;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 判官算不算在干活：stale（沉默超过两个周期，含从没给过结论）= DOWN；上次 DIFF = DOWN（有差异要人看）；
 * 上次 FAILED 但还没 stale = DEGRADED（这一轮没跑完，下一轮会再来）；上次 OK = UP。没装配对账 = UNKNOWN。
 */
public final class AuditHealthIndicator implements HealthIndicator {

    private final Optional<Supplier<AuditService.Status>> status;

    public AuditHealthIndicator(Optional<Supplier<AuditService.Status>> status) {
        this.status = status;
    }

    @Override
    public Health health() {
        if (status.isEmpty()) {
            return Health.unknown().withDetail("reason", "没有配置主节点：这个进程不对账").build();
        }
        AuditService.Status s = status.get().get();
        Optional<AuditResult> last = s.lastRun();
        Health.Builder b;
        if (s.stale()) {
            b = Health.down();
        } else if (last.isEmpty()) {
            b = Health.down();
        } else {
            b = switch (last.get().status()) {
                case "OK" -> Health.up();
                case "DIFF" -> Health.down();
                default -> Health.status(Statuses.DEGRADED);
            };
        }
        b.withDetail("stale", s.stale());
        if (last.isEmpty()) {
            b.withDetail("lastRun", "从没给过结论");
        } else {
            AuditResult r = last.get();
            b.withDetail("lastRun", "run " + r.runId() + " " + r.status()).withDetail("findings", r.findings().size())
                    .withDetail("finalized", r.finalizedNumber() == null ? "无" : r.finalizedNumber()).withDetail("finishedAt", String.valueOf(r.finishedAt()));
        }
        return b.build();
    }
}
