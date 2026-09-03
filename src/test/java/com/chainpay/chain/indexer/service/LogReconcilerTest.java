package com.chainpay.chain.indexer.service;

import static com.chainpay.chain.indexer.domain.BatchOutcome.INDEXED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.chainpay.chain.indexer.domain.BlockReconciliation;
import com.chainpay.chain.indexer.domain.ReconcileResult;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.ReconcileRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.rpc.RawLog;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 抽样对账：回执是事实源，getLogs 是索引。差异要两个节点都点头才动。
 *
 * <p>验收标准原话：「随机抽 100 个区块，链上 log 数 == 库内记录数」。
 */
@SpringBootTest
@DisplayName("M2-⑤ · 抽样对账")
class LogReconcilerTest extends AbstractPostgresTest {

    static final String LINK  = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String ALICE = "0x4281ecf07378ee595c564a59048801330f3084ee";
    static final String BOB   = "0x5e97b169613aff0c40a1910e597e9736c3a5ebc3";
    static final String CURSOR = "test:link:transfer";
    static final BigInteger TEN_LINK = new BigInteger("10000000000000000000");

    @Autowired
    private IndexerCursorRepository cursors;

    @Autowired
    private TransferLogRepository transferLogs;

    @Autowired
    private ChainHeadRepository heads;

    @Autowired
    private ReconcileRepository reconciles;

    @Autowired
    private PlatformTransactionManager txManager;

    private FakeChain chain;

    @BeforeEach
    void resetChainTables() {
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor, chain_head, chain_reconcile").update();
        chain = new FakeChain();
    }

    @Test
    @DisplayName("★ 安静的错被抓住并补上：日志从 getLogs 消失但回执里有，对账发现、补录、记一行；再对账干净")
    void silentMissIsCaughtAndRepaired() {
        chain.withBlocks(100);
        chain.reportSafe(90);
        chain.reportFinalized(80);
        chain.addTransfer(LINK, 20, ALICE, BOB, TEN_LINK);
        RawLog hidden = chain.addTransfer(LINK, 40, ALICE, BOB, TEN_LINK);
        chain.dropFromGetLogs(hidden);
        indexAll();
        assertThat(statusByHash()).containsOnlyKeys(FakeChain.hashOf(20));

        BlockReconciliation r = reconciler(chain).reconcileBlock(40);

        assertThat(r).isEqualTo(new BlockReconciliation(40, FakeChain.hashOf(40), 1, 0, 1, 0, 0));
        assertThat(statusByHash()).containsExactlyInAnyOrderEntriesOf(Map.of(
                FakeChain.hashOf(20), "CANONICAL", FakeChain.hashOf(40), "CANONICAL"));
        assertThat(reconcileRows()).containsExactly(List.of(40L, 1L, 0L, 1L, 0L, 0L));

        assertThat(reconciler(chain).reconcileBlock(40).isClean()).isTrue();
        assertThat(reconcileRows()).as("干净的检查不记").hasSize(1);
    }

    @Test
    @DisplayName("★ 幻影行被清掉：库里有一行链上没有的记录，两个节点都说没有，标 ORPHANED")
    void phantomRowIsOrphaned() {
        chain.withBlocks(100);
        chain.reportSafe(90);
        chain.reportFinalized(80);
        chain.addTransfer(LINK, 20, ALICE, BOB, TEN_LINK);
        indexAll();
        jdbc.sql("""
                        INSERT INTO chain_transfer_log
                            (token, from_address, to_address, value, block_number, block_hash, tx_hash, log_index)
                        VALUES (:token, :from, :to, 1, 30, :bh, :th, 0)
                        """)
                .param("token", LINK).param("from", ALICE).param("to", BOB)
                .param("bh", FakeChain.hashOf(30)).param("th", FakeChain.txHashOf(30, 0)).update();

        BlockReconciliation r = reconciler(chain).reconcileBlock(30);

        assertThat(r).isEqualTo(new BlockReconciliation(30, FakeChain.hashOf(30), 0, 1, 0, 1, 0));
        assertThat(statusByHash()).containsEntry(FakeChain.hashOf(30), "ORPHANED");
        assertThat(reconcileRows()).containsExactly(List.of(30L, 0L, 1L, 0L, 1L, 0L));
    }

    @Test
    @DisplayName("★ 只有一方说有：审计节点有、主节点没有，不动，记为 disputed")
    void disputedLogIsLeftAlone() {
        chain.withBlocks(100);
        chain.reportSafe(90);
        chain.reportFinalized(80);
        chain.addTransfer(LINK, 20, ALICE, BOB, TEN_LINK);
        indexAll();
        FakeChain audit = new FakeChain().withBlocks(100);
        audit.addTransfer(LINK, 50, ALICE, BOB, TEN_LINK);                   // 只有审计节点看到它

        BlockReconciliation r = reconciler(audit).reconcileBlock(50);

        assertThat(r).isEqualTo(new BlockReconciliation(50, FakeChain.hashOf(50), 1, 0, 0, 0, 1));
        assertThat(statusByHash()).containsOnlyKeys(FakeChain.hashOf(20));
        assertThat(reconcileRows()).containsExactly(List.of(50L, 1L, 0L, 0L, 0L, 1L));
    }

    @Test
    @DisplayName("★ 审计节点在一个已 finalized 的高度上是另一条分支：不是对账差异，停下")
    void auditNodeOnAnotherBranchAtAFinalizedHeightHalts() {
        chain.withBlocks(100);
        chain.reportSafe(90);
        chain.reportFinalized(80);
        chain.addTransfer(LINK, 40, ALICE, BOB, TEN_LINK);
        indexAll();
        FakeChain audit = new FakeChain().withBlocks(100);
        audit.reorgFrom(40, "A");

        assertThatThrownBy(() -> reconciler(audit).reconcileBlock(40))
                .isInstanceOf(FinalityViolationException.class);

        assertThat(statusByHash()).containsExactly(entry(FakeChain.hashOf(40), "CANONICAL"));
        assertThat(reconcileRows()).isEmpty();
    }

    @Test
    @DisplayName("抽样只抽已 finalized 的块：书签 100、finalized 60，抽出的块都不超过 60，也不早于起点")
    void samplesStayAtOrBelowFinalizedAndAfterTheStartBlock() {
        chain.withBlocks(100);
        chain.reportSafe(90);
        chain.reportFinalized(60);
        tracker().refresh();
        indexer(1000).start(10);
        assertThat(indexer(1000).indexNextBatch().outcome()).isEqualTo(INDEXED);

        ReconcileResult result = reconciler(chain, 20, new Random(42)).reconcile();

        assertThat(result.sampledBlocks()).hasSize(20)
                .allSatisfy(b -> assertThat(b).isBetween(11L, 60L));
        assertThat(result.mismatches()).isZero();
    }

    @Test
    @DisplayName("抽样只抽已索引的块：finalized 90 但书签才到 30，抽出的块都不超过 30")
    void samplesStayAtOrBelowTheCursor() {
        chain.withBlocks(100);
        chain.reportSafe(95);
        chain.reportFinalized(90);
        tracker().refresh();
        indexer(30).start(0);
        assertThat(indexer(30).indexNextBatch().outcome()).isEqualTo(INDEXED);      // 书签 30

        ReconcileResult result = reconciler(chain, 20, new Random(7)).reconcile();

        assertThat(result.sampledBlocks()).hasSize(20)
                .allSatisfy(b -> assertThat(b).isBetween(1L, 30L));
    }

    @Test
    @DisplayName("干净的块什么都不记")
    void cleanBlockLeavesNoTrace() {
        chain.withBlocks(100);
        chain.reportSafe(90);
        chain.reportFinalized(80);
        chain.addTransfer(LINK, 20, ALICE, BOB, TEN_LINK);
        indexAll();

        BlockReconciliation r = reconciler(chain).reconcileBlock(20);

        assertThat(r.isClean()).isTrue();
        assertThat(r.expected()).isEqualTo(1);
        assertThat(reconcileRows()).isEmpty();
    }

    // ------------------------------------------------------------------ 脚手架

    /** 刷新链头，从 0 放书签，一批索引到头。 */
    private void indexAll() {
        tracker().refresh();
        indexer(1000).start(0);
        assertThat(indexer(1000).indexNextBatch().outcome()).isEqualTo(INDEXED);
    }

    private BlockIndexer indexer(int batchBlocks) {
        return new BlockIndexer(chain, cursors, transferLogs, tx(), CURSOR, LINK, batchBlocks);
    }

    private ChainHeadTracker tracker() {
        return new ChainHeadTracker(chain, heads, tx(), "test");
    }

    private LogReconciler reconciler(FakeChain audit) {
        return reconciler(audit, 3, new Random(1));
    }

    private LogReconciler reconciler(FakeChain audit, int samples, Random random) {
        return new LogReconciler(chain, audit, cursors, transferLogs, heads, reconciles, tx(),
                CURSOR, LINK, samples, random);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private Map<String, String> statusByHash() {
        return jdbc.sql("SELECT block_hash, status FROM chain_transfer_log")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 审计表每行：块号、expected、found、repaired、orphaned、disputed。 */
    private List<List<Long>> reconcileRows() {
        return jdbc.sql("SELECT block_number, expected, found, repaired, orphaned, disputed "
                        + "FROM chain_reconcile ORDER BY id")
                .query((rs, i) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6)))
                .list();
    }
}
