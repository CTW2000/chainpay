package com.chainpay.ops.alert;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时看一眼 {@code work} 组（索引器 / 入账 / 热钱包 / 判官 / Redis），变化就叫人。
 * 读的是健康端点算出来的同一份结果：探针看到什么，告警就看到什么，没有第二套判定。
 * 首轮延迟给启动留时间（刚起来时 Redis、索引器还没就位，那不是事故）。
 */
public final class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);
    static final String GROUP = "work";

    private final java.util.function.Supplier<HealthDescriptor> work;
    private final AlertPolicy policy;
    private final WebhookSender sender;
    private final AtomicReference<Map<String, Observation>> lastObserved = new AtomicReference<>(Map.of());

    public AlertScheduler(java.util.function.Supplier<HealthDescriptor> work, AlertPolicy policy, WebhookSender sender) {
        this.work = work;
        this.policy = policy;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${chainpay.alert.interval:30s}", initialDelayString = "${chainpay.alert.initial-delay:1m}")
    public List<Alert> tick() {
        Map<String, Observation> now = observe();
        lastObserved.set(now);
        List<Alert> alerts = policy.observe(now, Instant.now());
        for (Alert a : alerts) {
            if (sender.send(a)) {
                policy.delivered(a);
            }
        }
        return alerts;
    }

    public Map<String, Observation> lastObserved() {
        return lastObserved.get();
    }

    private Map<String, Observation> observe() {
        Map<String, Observation> out = new LinkedHashMap<>();
        HealthDescriptor group = work.get();
        if (!(group instanceof CompositeHealthDescriptor composite)) {
            log.error("健康组 {} 不存在或不是组合：告警没有东西可看", GROUP);
            return out;
        }
        composite.getComponents().forEach((name, d) -> {
            Map<String, Object> details = d instanceof IndicatedHealthDescriptor ind && ind.getDetails() != null ? ind.getDetails() : Map.of();
            out.put(name, new Observation(name, d.getStatus().getCode(), details));
        });
        return out;
    }
}
