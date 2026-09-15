package com.chainpay.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.audit.domain.AuditResult;
import com.chainpay.audit.service.AuditService;
import com.chainpay.chain.indexer.domain.IndexerState;
import com.chainpay.chain.indexer.domain.IndexerStatus;
import com.chainpay.chain.payout.domain.HotWallet;
import com.chainpay.ops.health.AuditHealthIndicator;
import com.chainpay.ops.health.HotWalletHealthIndicator;
import com.chainpay.ops.health.IndexerHealthIndicator;
import com.chainpay.ops.health.Statuses;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * M6-⓪ · 每个指示器「什么算能干活」的判定，不起容器。
 * 指示器只做判定：拿一个状态，给一个 UP / DEGRADED / DOWN / UNKNOWN 和几行细节；读状态的那一步由装配时的 lambda 给，这里用假的。
 */
@DisplayName("M6-⓪ · 健康指示器的判定")
class HealthIndicatorsTest {

    @Nested
    @DisplayName("索引器")
    class Indexer {

        @Test
        @DisplayName("没配节点：UNKNOWN，说明原因")
        void notAssembledIsUnknown() {
            Health h = new IndexerHealthIndicator(false, Optional::empty).health();
            assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(h.getDetails().get("reason").toString()).contains("没有配置");
        }

        @Test
        @DisplayName("装配了、状态表还没有行（从没停过）：UP")
        void assembledWithoutStateRowIsUp() {
            assertThat(new IndexerHealthIndicator(true, Optional::empty).health().getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("RUNNING → UP；DEGRADED → 单独一档 DEGRADED；HALTED → DOWN 且带原因")
        void mapsEachPersistedStatus() {
            assertThat(indicator(IndexerStatus.RUNNING, null).health().getStatus()).isEqualTo(Status.UP);
            assertThat(indicator(IndexerStatus.DEGRADED, "连续 30 次瞬时失败").health().getStatus()).isEqualTo(Statuses.DEGRADED);
            Health halted = indicator(IndexerStatus.HALTED, "书签块哈希与两个节点都不同").health();
            assertThat(halted.getStatus()).isEqualTo(Status.DOWN);
            assertThat(halted.getDetails().get("reason")).isEqualTo("书签块哈希与两个节点都不同");
        }

        private IndexerHealthIndicator indicator(IndexerStatus status, String reason) {
            return new IndexerHealthIndicator(true, () -> Optional.of(new IndexerState("sepolia:link:transfer", status, reason, Instant.now())));
        }
    }

    @Nested
    @DisplayName("热钱包")
    class HotWalletCheck {

        private static final String ADDRESS = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC";

        @Test
        @DisplayName("没配私钥：UNKNOWN")
        void notAssembledIsUnknown() {
            assertThat(new HotWalletHealthIndicator(Optional.empty(), a -> Optional.empty()).health().getStatus()).isEqualTo(Status.UNKNOWN);
        }

        @Test
        @DisplayName("配了私钥、表里还没有行（一笔都没发过）：UP")
        void assembledWithoutRowIsUp() {
            assertThat(new HotWalletHealthIndicator(Optional.of(ADDRESS), a -> Optional.empty()).health().getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("ACTIVE → UP 且报下一个编号；HALTED → DOWN 且带原因")
        void mapsWalletStatus() {
            Health active = new HotWalletHealthIndicator(Optional.of(ADDRESS), a -> Optional.of(new HotWallet(a, "sepolia", 5, "ACTIVE", null))).health();
            assertThat(active.getStatus()).isEqualTo(Status.UP);
            assertThat(active.getDetails().get("nextNonce")).isEqualTo(5L);
            Health halted = new HotWalletHealthIndicator(Optional.of(ADDRESS), a -> Optional.of(new HotWallet(a, "sepolia", 5, "HALTED", "编号 4 已被链上另一笔用掉"))).health();
            assertThat(halted.getStatus()).isEqualTo(Status.DOWN);
            assertThat(halted.getDetails().get("reason").toString()).contains("编号 4");
        }

        @Test
        @DisplayName("★ 细节里不出现热钱包地址之外的任何东西：没有私钥、没有节点地址")
        void detailsCarryNoSecrets() {
            Health h = new HotWalletHealthIndicator(Optional.of(ADDRESS), a -> Optional.of(new HotWallet(a, "sepolia", 5, "ACTIVE", null))).health();
            assertThat(h.getDetails().keySet()).containsExactlyInAnyOrder("address", "nextNonce");
        }
    }

    @Nested
    @DisplayName("判官")
    class Audit {

        @Test
        @DisplayName("没装配对账：UNKNOWN")
        void notAssembledIsUnknown() {
            assertThat(new AuditHealthIndicator(Optional.empty()).health().getStatus()).isEqualTo(Status.UNKNOWN);
        }

        @Test
        @DisplayName("上次 OK 且不 stale → UP；上次 DIFF → DOWN 且报差异数；上次 FAILED 但还没 stale → DEGRADED；stale → DOWN")
        void mapsVerdictAndStaleness() {
            assertThat(indicator(run("OK", 0), false).health().getStatus()).isEqualTo(Status.UP);
            Health diff = indicator(run("DIFF", 3), false).health();
            assertThat(diff.getStatus()).isEqualTo(Status.DOWN);
            assertThat(diff.getDetails().get("findings")).isEqualTo(3);
            assertThat(indicator(run("FAILED", 0), false).health().getStatus()).isEqualTo(Statuses.DEGRADED);
            Health stale = indicator(run("OK", 0), true).health();
            assertThat(stale.getStatus()).isEqualTo(Status.DOWN);
            assertThat(stale.getDetails().get("stale")).isEqualTo(true);
        }

        @Test
        @DisplayName("装配了但一轮都还没跑：stale 为真 → DOWN，细节说明从没给过结论")
        void neverRanIsStale() {
            Health h = new AuditHealthIndicator(Optional.of(() -> new AuditService.Status(Optional.empty(), true))).health();
            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
            assertThat(h.getDetails().get("lastRun")).isEqualTo("从没给过结论");
        }

        private AuditHealthIndicator indicator(AuditResult last, boolean stale) {
            return new AuditHealthIndicator(Optional.of(() -> new AuditService.Status(Optional.of(last), stale)));
        }

        private AuditResult run(String status, int findings) {
            List<com.chainpay.audit.domain.AuditFinding> f = java.util.Collections.nCopies(findings,
                    new com.chainpay.audit.domain.AuditFinding("CUSTODY_TOTAL", com.chainpay.audit.domain.AuditKind.MISSING_IN_LEDGER, "币 LINK", "1", "2", "x"));
            return new AuditResult(7L, status, 11700301L, Instant.now(), Instant.now(), f, "d");
        }
    }
}
