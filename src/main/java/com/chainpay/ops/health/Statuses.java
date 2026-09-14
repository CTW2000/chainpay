package com.chainpay.ops.health;

import org.springframework.boot.health.contributor.Status;

/**
 * 本项目多出来的一档：还在跑，但有人该来看看。
 * 排在 DOWN 之后、UP 之前（application.yml 的 status.order），HTTP 200：告警按「不是 UP」触发，容器不重启。
 */
public final class Statuses {

    public static final Status DEGRADED = new Status("DEGRADED", "还在跑，但有人该来看看");

    private Statuses() {}
}
