package com.chainpay.admin.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param sessionTtl   会话绝对寿命
 * @param idleTimeout  闲置多久失效
 * @param reauthWindow 敏感操作要在这么久之内再认证过
 * @param maxFailures  连续错几次锁
 * @param lockFor      锁多久
 */
@ConfigurationProperties("chainpay.admin")
public record AdminAuthProperties(@DefaultValue("12h") Duration sessionTtl, @DefaultValue("30m") Duration idleTimeout,
                                  @DefaultValue("5m") Duration reauthWindow, @DefaultValue("5") int maxFailures, @DefaultValue("15m") Duration lockFor) {

    public static AdminAuthProperties defaults() {
        return new AdminAuthProperties(Duration.ofHours(12), Duration.ofMinutes(30), Duration.ofMinutes(5), 5, Duration.ofMinutes(15));
    }
}
