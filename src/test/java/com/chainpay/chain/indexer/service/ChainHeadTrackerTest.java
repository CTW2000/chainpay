package com.chainpay.chain.indexer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.indexer.domain.ChainHead;
import com.chainpay.chain.indexer.domain.HeadRef;
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
 * 链头只进不退。
 *
 * <p>三个头的规矩不一样：finalized 倒退或换哈希是灾难，停下；
 * safe / latest 倒退只是节点落后，保留旧值。
 */
@SpringBootTest
@DisplayName("M2-③ · 链头追踪")
class ChainHeadTrackerTest extends AbstractPostgresTest {

    static final String CHAIN = "test";

    @Autowired
    private ChainHeadRepository heads;

    @Autowired
    private PlatformTransactionManager txManager;

    private FakeChain chain;
    private ChainHeadTracker tracker;

    /** 表里的一行：链名 + 三个头。 */
    private record Row(String chain, ChainHead head) {}

    @BeforeEach
    void resetHead() {
        jdbc.sql("TRUNCATE chain_head").update();
        chain = new FakeChain().withBlocks(100);
        chain.reportSafe(60);
        chain.reportFinalized(20);
        tracker = new ChainHeadTracker(chain, heads, new TransactionTemplate(txManager), CHAIN);
    }

    @Test
    @DisplayName("第一次刷新建行：链名、三个头的号和哈希")
    void firstRefreshInsertsTheRow() {
        ChainHead returned = tracker.refresh();

        assertThat(returned).isEqualTo(head(100, 60, 20));
        assertThat(row()).isEqualTo(new Row(CHAIN, head(100, 60, 20)));
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("头前进就更新，三个一起")
    void advancesAllThreeHeads() {
        tracker.refresh();
        chain.withBlocks(150);
        chain.reportSafe(95);
        chain.reportFinalized(60);

        tracker.refresh();

        assertThat(row().head()).isEqualTo(head(150, 95, 60));
    }

    @Test
    @DisplayName("★ 节点落后：latest / safe 倒退时保留旧值，finalized 前进照常更新")
    void keepsOldLatestAndSafeWhenTheNodeIsBehind() {
        tracker.refresh();
        chain.reportHead(90);
        chain.reportSafe(55);
        chain.reportFinalized(25);

        ChainHead merged = tracker.refresh();

        assertThat(merged).isEqualTo(head(100, 60, 25));
        assertThat(row().head()).isEqualTo(head(100, 60, 25));
    }

    @Test
    @DisplayName("★ finalized 倒退：停下，头表不动")
    void haltsWhenFinalizedRegresses() {
        tracker.refresh();
        chain.reportFinalized(19);

        assertThatThrownBy(tracker::refresh)
                .isInstanceOf(FinalityViolationException.class)
                .hasMessageContaining("倒退");
        assertThat(row().head()).isEqualTo(head(100, 60, 20));
    }

    @Test
    @DisplayName("★ finalized 同一个号换了哈希：停下，头表不动")
    void haltsWhenTheFinalizedHashChanges() {
        tracker.refresh();
        chain.tamperHash(20, FakeChain.hashOf(999));

        assertThatThrownBy(tracker::refresh)
                .isInstanceOf(FinalityViolationException.class)
                .hasMessageContaining("哈希");
        assertThat(row().head()).isEqualTo(head(100, 60, 20));
    }

    @Test
    @DisplayName("节点给出乱序的头（safe 比 latest 新）：拒绝，头表不动")
    void rejectsDisorderedHeads() {
        tracker.refresh();
        chain.reportHead(50);                                        // latest 50 < safe 60

        assertThatThrownBy(tracker::refresh)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("顺序");
        assertThat(row().head()).isEqualTo(head(100, 60, 20));
    }

    // ------------------------------------------------------------------ 脚手架

    private static ChainHead head(long latest, long safe, long finalized) {
        return new ChainHead(
                new HeadRef(latest, FakeChain.hashOf(latest)),
                new HeadRef(safe, FakeChain.hashOf(safe)),
                new HeadRef(finalized, FakeChain.hashOf(finalized)));
    }

    private Row row() {
        return jdbc.sql("""
                        SELECT chain, latest_number, latest_hash, safe_number, safe_hash,
                               finalized_number, finalized_hash
                        FROM chain_head
                        """)
                .query((rs, i) -> new Row(rs.getString("chain"), new ChainHead(
                        new HeadRef(rs.getLong("latest_number"), rs.getString("latest_hash")),
                        new HeadRef(rs.getLong("safe_number"), rs.getString("safe_hash")),
                        new HeadRef(rs.getLong("finalized_number"), rs.getString("finalized_hash")))))
                .single();
    }

    private long rowCount() {
        return jdbc.sql("SELECT COUNT(*) FROM chain_head").query(Long.class).single();
    }
}
