package com.chainpay.audit.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 对账配置 {@code chainpay.audit.*}。
 *
 * @param interval  多久对一次账。上次 OK / DIFF 距今超过两个周期 = stale（管理接口报出来）
 * @param lagBlocks 「FINAL 的收款日志应该已经入账」的宽限：块 ≤ finalized − lag 的才要求有入账行，给入账任务几轮时间
 */
@ConfigurationProperties(prefix = "chainpay.audit")
public record AuditProperties(@DefaultValue("1h") Duration interval, @DefaultValue("10") int lagBlocks) {}
