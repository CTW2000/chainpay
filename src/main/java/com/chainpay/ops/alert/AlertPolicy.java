package com.chainpay.ops.alert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 什么时候叫人：只在<b>变化</b>时叫。每个部件记着「上次送到的状态」，起点是 UP；这一轮的状态和它不同就叫一次，
 * 送到了才把它记下来——没送到的下一轮还叫（用最新状态）。UP 与 UNKNOWN 视为同一档「没事」：UNKNOWN 是这个进程没配这个模块，不是事故。
 * 进程重启时状态表清零，第一轮看到的 DOWN 会再叫一次：重启不算恢复。
 */
public final class AlertPolicy {

    private static final Set<String> FINE = Set.of("UP", "UNKNOWN");

    private final Map<String, String> lastDelivered = new HashMap<>();

    static boolean isFine(String status) {
        return FINE.contains(status);
    }

    public synchronized List<Alert> observe(Map<String, Observation> now, Instant at) {
        List<Alert> alerts = new ArrayList<>();
        for (Observation o : now.values()) {
            String before = lastDelivered.getOrDefault(o.component(), "UP");
            if (isFine(before) && isFine(o.status())) {
                continue;                                                        // 没事 → 没事：不叫，也不改记录
            }
            if (before.equals(o.status())) {
                continue;                                                        // 还是那个坏状态：不重复叫
            }
            alerts.add(new Alert(o.component(), before, o.status(), o.details(), at));
        }
        return alerts;
    }

    /** 送到了：从此以这个状态为基线。 */
    public synchronized void delivered(Alert alert) {
        lastDelivered.put(alert.component(), alert.to());
    }
}
