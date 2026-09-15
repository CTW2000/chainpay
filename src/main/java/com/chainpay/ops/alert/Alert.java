package com.chainpay.ops.alert;

import java.time.Instant;
import java.util.Map;

/** 要叫人的一件事：某个部件从上次送到的状态变成了现在的状态。to 是 UP 就是「恢复」。 */
public record Alert(String component, String from, String to, Map<String, Object> details, Instant at) {

    public boolean recovered() {
        return AlertPolicy.isFine(to);
    }

    /** 一行给人看的字：🔴 出事、🟢 恢复；细节挑几项。 */
    public String text() {
        StringBuilder sb = new StringBuilder(recovered() ? "🟢 " : "🔴 ").append("chainpay/").append(component).append(": ").append(from).append(" → ").append(to);
        details.forEach((k, v) -> sb.append("｜").append(k).append('=').append(v));
        return sb.toString();
    }
}
