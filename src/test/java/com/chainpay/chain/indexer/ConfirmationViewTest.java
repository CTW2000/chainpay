package com.chainpay.chain.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.support.FakeChain;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 确认等级是<b>算出来的</b>：视图按最后一次看到的链头，给每条在链上的日志一个 SEEN / SAFE / FINAL。
 *
 * <p>要证明的四件事：三个等级各算各的；头前进时等级只升不降；被抛弃的行不出现；
 * 日志比记下的头还新时 confirmations 夹到 0。
 */
@SpringBootTest
@DisplayName("M2-③ · 确认等级视图")
class ConfirmationViewTest extends AbstractPostgresTest {

    static final String LINK  = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String ALICE = "0x4281ecf07378ee595c564a59048801330f3084ee";
    static final String BOB   = "0x5e97b169613aff0c40a1910e597e9736c3a5ebc3";
    static final String CURSOR = "test:link:transfer";
    static final String CHAIN = "test";
    static final BigInteger TEN_LINK = new BigInteger("10000000000000000000");

    @Autowired
    private IndexerCursorRepository cursors;

    @Autowired
    private TransferLogRepository transferLogs;

    @Autowired
    private ChainHeadRepository heads;

    @Autowired
    private PlatformTransactionManager txManager;

    private FakeChain chain;

    @BeforeEach
    void resetChainTables() {
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor, chain_head").update();
        chain = new FakeChain();
    }

    @Test
    @DisplayName("三个等级各算各的：块 ≤ finalized 是 FINAL，≤ safe 是 SAFE，其余 SEEN；confirmations = 头 − 块号 + 1")
    void derivesTheThreeLevelsFromTheHeads() {
        indexThreeTransfers();
        chain.reportSafe(60);
        chain.reportFinalized(20);
        tracker().refresh();

        assertThat(levels()).containsExactlyInAnyOrderEntriesOf(Map.of(10L, "FINAL", 50L, "SAFE", 90L, "SEEN"));
        assertThat(confirmations()).containsExactlyInAnyOrderEntriesOf(Map.of(10L, 91L, 50L, 51L, 90L, 11L));
    }

    @Test
    @DisplayName("★ 等级只升不降：头前进后 SEEN 变 SAFE、SAFE 变 FINAL；节点落后一次，等级不动")
    void levelsOnlyMoveUpAsTheHeadsAdvance() {
        indexThreeTransfers();
        chain.reportSafe(60);
        chain.reportFinalized(20);
        tracker().refresh();

        chain.withBlocks(150);
        chain.reportSafe(95);
        chain.reportFinalized(60);
        tracker().refresh();
        assertThat(levels()).containsExactlyInAnyOrderEntriesOf(Map.of(10L, "FINAL", 50L, "FINAL", 90L, "SAFE"));

        // 切到一个落后的节点：三个头都比上次小，或者相等
        chain.reportHead(120);
        chain.reportSafe(70);
        chain.reportFinalized(60);
        tracker().refresh();
        assertThat(levels()).containsExactlyInAnyOrderEntriesOf(Map.of(10L, "FINAL", 50L, "FINAL", 90L, "SAFE"));
        assertThat(confirmations()).containsEntry(90L, 61L);   // latest 仍是 150
    }

    @Test
    @DisplayName("★ 被抛弃的行不出现在视图里：它不在链上，谈不上确认")
    void orphanedRowsAreInvisible() {
        indexThreeTransfers();
        chain.reportSafe(60);
        chain.reportFinalized(20);
        tracker().refresh();

        jdbc.sql("UPDATE chain_transfer_log SET status = 'ORPHANED' WHERE block_number = 50").update();

        assertThat(levels()).containsOnlyKeys(10L, 90L);
    }

    @Test
    @DisplayName("日志比记下的头还新时，confirmations 夹到 0，不出负数")
    void confirmationsNeverGoNegative() {
        chain.withBlocks(95);
        chain.reportSafe(60);
        chain.reportFinalized(20);
        tracker().refresh();                                        // 头表记到 95

        chain.withBlocks(100);                                      // 链又长了，头表还没刷新
        chain.addTransfer(LINK, 100, ALICE, BOB, TEN_LINK);
        indexer().start(0);
        indexer().indexNextBatch();

        assertThat(confirmations()).containsEntry(100L, 0L);
        assertThat(levels()).containsEntry(100L, "SEEN");
    }

    // ------------------------------------------------------------------ 脚手架

    /** 三条日志：深（10）、中（50）、浅（90），一批索引完。 */
    private void indexThreeTransfers() {
        chain.withBlocks(100);
        chain.addTransfer(LINK, 10, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 50, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 90, BOB, ALICE, TEN_LINK);
        indexer().start(0);
        indexer().indexNextBatch();
    }

    private BlockIndexer indexer() {
        return new BlockIndexer(chain, cursors, transferLogs, tx(), CURSOR, LINK, 1000);
    }

    private ChainHeadTracker tracker() {
        return new ChainHeadTracker(chain, heads, tx(), CHAIN);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private Map<Long, String> levels() {
        return jdbc.sql("SELECT block_number, level FROM chain_transfer_confirmation")
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<Long, Long> confirmations() {
        return jdbc.sql("SELECT block_number, confirmations FROM chain_transfer_confirmation")
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getLong(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
