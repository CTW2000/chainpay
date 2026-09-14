package com.chainpay.ops.health;

import com.chainpay.ledger.system.SystemLedger;
import java.util.function.Supplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 系统连接池（chainpay_system）通不通。Actuator 自带的 db 指示器只认容器里的 DataSource bean，系统池不是 bean（理由见 SystemLedger），所以这里手工问一次。
 * 细节只有池名和四个数；失败时只有异常的类名与消息（连接串在异常里从不出现——Hikari 的超时消息只有池名）。
 */
public final class SystemDbHealthIndicator implements HealthIndicator {

    private final Supplier<SystemLedger.PoolStats> ping;

    public SystemDbHealthIndicator(Supplier<SystemLedger.PoolStats> ping) {
        this.ping = ping;
    }

    @Override
    public Health health() {
        SystemLedger.PoolStats s;
        try {
            s = ping.get();
        } catch (RuntimeException e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage()).build();
        }
        return Health.up().withDetail("pool", s.pool()).withDetail("active", s.active()).withDetail("idle", s.idle())
                .withDetail("total", s.total()).withDetail("waiting", s.waiting()).build();
    }
}
