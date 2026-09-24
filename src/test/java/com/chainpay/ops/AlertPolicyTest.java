package com.chainpay.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.ops.alert.Alert;
import com.chainpay.ops.alert.AlertPolicy;
import com.chainpay.ops.alert.Observation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 同一件事只叫一次，恢复再叫一次；没送到的下一轮再叫；UNKNOWN（没配的模块）不算事。 */
@DisplayName("告警策略")
class AlertPolicyTest {

    private final AlertPolicy policy = new AlertPolicy();
    private final Instant t = Instant.parse("2026-09-14T12:00:00Z");

    private static Map<String, Observation> seen(String... pairs) {
        Map<String, Observation> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], new Observation(pairs[i], pairs[i + 1], Map.of("reason", "r-" + pairs[i + 1])));
        }
        return m;
    }

    @Test
    @DisplayName("★ UP → DOWN 叫一次；一直 DOWN 不再叫；DOWN → UP 叫一次「恢复」")
    void alertsOnTransitionsOnly() {
        assertThat(policy.observe(seen("indexer", "UP"), t)).isEmpty();
        List<Alert> down = policy.observe(seen("indexer", "DOWN"), t);
        assertThat(down).hasSize(1);
        assertThat(down.getFirst().from()).isEqualTo("UP");
        assertThat(down.getFirst().to()).isEqualTo("DOWN");
        assertThat(down.getFirst().recovered()).isFalse();
        assertThat(down.getFirst().details()).containsEntry("reason", "r-DOWN");
        policy.delivered(down.getFirst());
        assertThat(policy.observe(seen("indexer", "DOWN"), t)).as("还是 DOWN：不重复叫").isEmpty();
        List<Alert> up = policy.observe(seen("indexer", "UP"), t);
        assertThat(up).hasSize(1);
        assertThat(up.getFirst().recovered()).isTrue();
        policy.delivered(up.getFirst());
        assertThat(policy.observe(seen("indexer", "UP"), t)).isEmpty();
    }

    @Test
    @DisplayName("★ 没送到的不算叫过：下一轮还叫，且用最新的状态")
    void undeliveredAlertsAreRepeated() {
        List<Alert> first = policy.observe(seen("audit", "DOWN"), t);
        assertThat(first).hasSize(1);
        // 没有 delivered(...)：webhook 没通
        List<Alert> second = policy.observe(seen("audit", "DEGRADED"), t);
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().from()).as("起点仍是上次送到的状态").isEqualTo("UP");
        assertThat(second.getFirst().to()).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("DEGRADED 也是事；DOWN → DEGRADED 是变化，也叫")
    void degradedCountsAndChangesBetweenBadStatesAreAlerts() {
        List<Alert> a = policy.observe(seen("indexer", "DEGRADED"), t);
        assertThat(a).hasSize(1);
        policy.delivered(a.getFirst());
        List<Alert> b = policy.observe(seen("indexer", "DOWN"), t);
        assertThat(b).hasSize(1);
        assertThat(b.getFirst().from()).isEqualTo("DEGRADED");
        assertThat(b.getFirst().recovered()).isFalse();
    }

    @Test
    @DisplayName("★ UNKNOWN 是「这个进程没配这个模块」，不是事：UP ↔ UNKNOWN 不叫；从 UNKNOWN 直接到 DOWN 才叫")
    void unknownIsNotAnIncident() {
        assertThat(policy.observe(seen("hotWallet", "UNKNOWN"), t)).isEmpty();
        assertThat(policy.observe(seen("hotWallet", "UP"), t)).isEmpty();
        assertThat(policy.observe(seen("hotWallet", "UNKNOWN"), t)).isEmpty();
        assertThat(policy.observe(seen("hotWallet", "DOWN"), t)).hasSize(1);
    }

    @Test
    @DisplayName("多个部件各自独立；第一轮就 DOWN 的（进程重启时问题还在）也叫")
    void componentsAreIndependentAndStandingProblemsAlertOnFirstObservation() {
        List<Alert> a = policy.observe(seen("indexer", "DOWN", "redis", "UP", "audit", "DEGRADED"), t);
        assertThat(a).extracting(Alert::component).containsExactly("indexer", "audit");
    }
}
