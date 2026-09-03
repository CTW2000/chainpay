package com.chainpay.chain.indexer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 自相矛盾的错：两个节点对同一高度给出不同的哈希。
 * 头部的分歧是常态，不查；finalized 的分歧不允许，停下叫人。审计节点落后就跳过这次比对。
 */
@SpringBootTest
@DisplayName("M2-⑤ · 审计节点核对 finalized")
class ChainHeadAuditTest extends AbstractPostgresTest {

    @Autowired
    private ChainHeadRepository heads;

    @Autowired
    private PlatformTransactionManager txManager;

    private FakeChain primary;

    @BeforeEach
    void resetHead() {
        jdbc.sql("TRUNCATE chain_head").update();
        primary = new FakeChain().withBlocks(100);
        primary.reportSafe(60);
        primary.reportFinalized(20);
    }

    @Test
    @DisplayName("★ 审计节点对 finalized 那块的哈希意见不同：停下，头表不写")
    void disagreementOnTheFinalizedBlockHalts() {
        FakeChain audit = new FakeChain().withBlocks(100);
        audit.tamperHash(20, FakeChain.hashOf(999));

        assertThatThrownBy(() -> tracker(audit).refresh())
                .isInstanceOf(FinalityViolationException.class)
                .hasMessageContaining("意见不同");
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("审计节点还没到我们的 finalized 高度：跳过比对，不报警")
    void auditNodeBehindIsSkipped() {
        FakeChain audit = new FakeChain().withBlocks(10);

        tracker(audit).refresh();

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("审计节点同意：照常")
    void agreementIsNormal() {
        FakeChain audit = new FakeChain().withBlocks(100);

        tracker(audit).refresh();

        assertThat(rowCount()).isEqualTo(1);
    }

    private ChainHeadTracker tracker(FakeChain audit) {
        return new ChainHeadTracker(primary, audit, heads, new TransactionTemplate(txManager), "test");
    }

    private long rowCount() {
        return jdbc.sql("SELECT COUNT(*) FROM chain_head").query(Long.class).single();
    }
}
