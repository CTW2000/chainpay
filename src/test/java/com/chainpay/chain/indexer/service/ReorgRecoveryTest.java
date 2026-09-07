package com.chainpay.chain.indexer.service;

import static com.chainpay.chain.indexer.domain.BatchOutcome.INDEXED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.chainpay.chain.indexer.domain.BatchResult;
import com.chainpay.chain.indexer.domain.IndexerCursor;
import com.chainpay.chain.indexer.domain.ReorgResult;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.ReorgRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 重组回滚：找共同祖先、标废、退书签、记审计，然后正常重放。
 *
 * <p>验收标准原话：「手工改掉某个区块的 blockHash → 系统必须发现，并回滚该区块之后的所有派生数据」。
 * 这里的「改掉」用 {@link FakeChain#reorgFrom} 造成一次真正的分支切换，日志跟着分支走。
 */
@SpringBootTest
@DisplayName("M2-④ · 重组回滚")
class ReorgRecoveryTest extends AbstractPostgresTest {

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
    private ReorgRepository reorgs;

    @Autowired
    private PlatformTransactionManager txManager;

    private FakeChain chain;

    @BeforeEach
    void resetChainTables() {
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor, chain_head, chain_reorg").update();
        chain = new FakeChain();
    }

    @Test
    @DisplayName("★ 一块重组，交易被重新打包：旧行 ORPHANED，重放后新行 CANONICAL，视图里只剩新的")
    void recoversFromASingleBlockReorgAndReplaysTheReincludedTransfer() {
        indexChain(10, 8, 5, 10);
        String reincludedTx = FakeChain.txHashOf(10, 0);
        chain.reorgFrom(10, "A");
        chain.addTransfer(LINK, 10, ALICE, BOB, TEN_LINK, reincludedTx);   // 同一笔交易进了新的第 10 块
        chain.withBlocks(11);

        ReorgResult r = detectAndRecover();

        assertThat(r.applied()).isTrue();
        assertThat(r.cursorBlock()).isEqualTo(10);
        assertThat(r.ancestorBlock()).as("6..9 没有日志、无从证明，退到 finalized 头").isEqualTo(5);
        assertThat(r.depth()).isEqualTo(5);
        assertThat(r.orphanedLogs()).isEqualTo(1);
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 5, FakeChain.hashOf(5)));
        assertThat(statusByHash()).containsExactly(entry(FakeChain.hashOf(10), "ORPHANED"));

        BatchResult replay = indexer().indexNextBatch();                     // 6..11 重放

        assertThat(replay.outcome()).isEqualTo(INDEXED);
        assertThat(replay.logsInserted()).isEqualTo(1);
        assertThat(statusByHash()).containsExactlyInAnyOrderEntriesOf(Map.of(
                FakeChain.hashOf(10), "ORPHANED",
                FakeChain.hashOf(10, "A"), "CANONICAL"));
        assertThat(viewBlocks()).as("视图里第 10 块只有新分支那一行").containsExactly(10L);
        assertThat(viewTxHashes()).containsExactly(reincludedTx);
        assertThat(reorgRows()).containsExactly(List.of(10L, 5L, 5L, 1L));
    }

    @Test
    @DisplayName("★ 多块重组：退到能证明的最高一块（可能比分叉点低），多退不伤")
    void rollsBackToTheHighestProvableAncestorEvenBelowTheForkPoint() {
        indexChain(20, 15, 5, 12, 15, 18);
        chain.reorgFrom(14, "A");                                            // 分叉点是 13
        chain.withBlocks(21);

        ReorgResult r = detectAndRecover();

        assertThat(r.ancestorBlock()).as("我们没有 13 的哈希，只能退到 12").isEqualTo(12);
        assertThat(r.depth()).isEqualTo(8);
        assertThat(r.orphanedLogs()).isEqualTo(2);
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 12, FakeChain.hashOf(12)));
        assertThat(statusByHash()).containsExactlyInAnyOrderEntriesOf(Map.of(
                FakeChain.hashOf(12), "CANONICAL",
                FakeChain.hashOf(15), "ORPHANED",
                FakeChain.hashOf(18), "ORPHANED"));

        // 新分支上两笔交易在别的块里被重新打包
        chain.addTransfer(LINK, 16, ALICE, BOB, TEN_LINK, FakeChain.txHashOf(15, 0));
        chain.addTransfer(LINK, 19, ALICE, BOB, TEN_LINK, FakeChain.txHashOf(18, 0));
        indexer().indexNextBatch();                                          // 13..21 重放

        assertThat(viewBlocks()).containsExactly(12L, 16L, 19L);
        assertThat(rowCount()).as("3 旧 + 2 新，一行都没删").isEqualTo(5);
    }

    @Test
    @DisplayName("★ 交易没被重新打包：旧行永远 ORPHANED，视图里看不到，M3 永远不会记它")
    void aTransferThatIsNotReincludedStaysOrphanedAndInvisible() {
        indexChain(10, 8, 5, 10);
        chain.reorgFrom(10, "A");
        chain.withBlocks(11);

        detectAndRecover();
        indexer().indexNextBatch();

        assertThat(statusByHash()).containsExactly(entry(FakeChain.hashOf(10), "ORPHANED"));
        assertThat(viewBlocks()).isEmpty();
        assertThat(cursor().lastBlockNumber()).isEqualTo(11);
    }

    @Test
    @DisplayName("★ finalized 之下的行一个字节不动：祖先最低就是 finalized 头")
    void neverTouchesRowsAtOrBelowTheFinalizedHead() {
        indexChain(100, 90, 50, 30, 80);
        chain.reorgFrom(70, "A");
        chain.withBlocks(101);

        ReorgResult r = detectAndRecover();

        assertThat(r.ancestorBlock()).as("80 对不上，再往下就是 finalized 头 50").isEqualTo(50);
        assertThat(r.orphanedLogs()).isEqualTo(1);
        assertThat(statusByHash()).containsExactlyInAnyOrderEntriesOf(Map.of(
                FakeChain.hashOf(30), "CANONICAL",
                FakeChain.hashOf(80), "ORPHANED"));
        assertThat(levelOf(30)).isEqualTo("FINAL");
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 50, FakeChain.hashOf(50)));
    }

    @Test
    @DisplayName("没有链头记录就没有地板：拒绝恢复，什么都不写")
    void refusesToRecoverWithoutAChainHead() {
        indexChain(100, 90, 50, 80);
        jdbc.sql("TRUNCATE chain_head").update();
        chain.reorgFrom(90, "A");

        assertThatThrownBy(() -> recovery().recover(100, FakeChain.hashOf(100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("链头");

        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 100, FakeChain.hashOf(100)));
        assertThat(statusByHash()).containsExactly(entry(FakeChain.hashOf(80), "CANONICAL"));
    }

    @Test
    @DisplayName("★ 书签不高于 finalized 却和链上对不上：不是重组，停下，什么都不写")
    void refusesWhenTheCursorIsNotAboveFinalized() {
        indexChain(100, 100, 100, 80);                                 // finalized 就是书签这一块
        chain.reorgFrom(100, "A");                                     // 链上的块 100 换了哈希

        assertThatThrownBy(() -> recovery().recover(100, FakeChain.hashOf(100)))
                .isInstanceOf(FinalityViolationException.class)
                .hasMessageContaining("不高于 finalized");

        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 100, FakeChain.hashOf(100)));
        assertThat(reorgRows()).isEmpty();
    }

    @Test
    @DisplayName("★ 重组深过 finalized：不是重组，停下，什么都不写")
    void haltsWhenTheReorgReachesBelowFinalized() {
        indexChain(100, 90, 50, 80);
        chain.reorgFrom(40, "A");
        chain.withBlocks(101);
        ReorgDetectedException detected = detect();

        assertThatThrownBy(() -> recovery().recover(detected.blockNumber() - 1, detected.expectedParentHash()))
                .isInstanceOf(FinalityViolationException.class);

        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 100, FakeChain.hashOf(100)));
        assertThat(statusByHash()).containsExactly(entry(FakeChain.hashOf(80), "CANONICAL"));
        assertThat(reorgRows()).isEmpty();
    }

    @Test
    @DisplayName("★ 翻回来：同一行从 ORPHANED 变回 CANONICAL，id 不变")
    void resurrectsRowsWhenTheChainFlipsBack() {
        indexChain(10, 8, 5, 10);
        long idBefore = jdbc.sql("SELECT id FROM chain_transfer_log").query(Long.class).single();
        chain.reorgFrom(10, "A");
        chain.withBlocks(11);
        detectAndRecover();                                                  // 旧行 ORPHANED，书签退到 5

        chain.reorgFrom(10, null);                                           // 翻回原始分支
        BatchResult replay = indexer().indexNextBatch();                     // 6..11：旧日志又回来了

        assertThat(replay.logsInserted()).as("复活也算写入").isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT id FROM chain_transfer_log").query(Long.class).single()).isEqualTo(idBefore);
        assertThat(statusByHash()).containsExactly(entry(FakeChain.hashOf(10), "CANONICAL"));
        assertThat(viewBlocks()).containsExactly(10L);
    }

    @Test
    @DisplayName("★ 标废、退书签、记审计同生同死：事务在最后一刻回滚，三样东西一样都不能留下")
    void orphaningRewindingAndAuditingAreOneTransaction() {
        indexChain(20, 15, 5, 12, 15, 18);
        chain.reorgFrom(14, "A");
        chain.withBlocks(21);
        ReorgDetectedException detected = detect();
        TransactionTemplate rollbackAtTheEnd = new TransactionTemplate(txManager) {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return super.execute(status -> {
                    T result = action.doInTransaction(status);
                    status.setRollbackOnly();                                // 所有语句都跑完了，然后整个回滚
                    return result;
                });
            }
        };

        ReorgResult r = recovery(rollbackAtTheEnd).recover(detected.blockNumber() - 1, detected.expectedParentHash());

        assertThat(r.applied()).as("恢复器自己以为做完了").isTrue();
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 20, FakeChain.hashOf(20)));
        assertThat(statusByHash().values()).containsOnly("CANONICAL");
        assertThat(reorgRows()).isEmpty();
    }

    @Test
    @DisplayName("★ 另一个实例已经恢复过：书签号相同但哈希不同，锁内发现后什么都不做")
    void skipsWhenAnotherInstanceAlreadyRecovered() {
        indexChain(10, 8, 5, 10);
        chain.reorgFrom(10, "A");
        chain.withBlocks(11);
        ReorgDetectedException detected = detect();                          // 记的是 (10, 旧哈希)
        // 另一个实例已经回滚并重放到新分支的第 10 块：号一样，哈希不一样
        jdbc.sql("UPDATE indexer_cursor SET last_block_hash = :h WHERE name = :n")
                .param("h", FakeChain.hashOf(10, "A")).param("n", CURSOR).update();

        ReorgResult r = recovery().recover(detected.blockNumber() - 1, detected.expectedParentHash());

        assertThat(r.applied()).isFalse();
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 10, FakeChain.hashOf(10, "A")));
        assertThat(statusByHash()).containsExactly(entry(FakeChain.hashOf(10), "CANONICAL"));
        assertThat(reorgRows()).isEmpty();
    }

    @Test
    @DisplayName("书签那块的哈希还对，只是下一块接不上：节点前后不一致，按瞬时失败处理")
    void treatsAConsistentCursorBlockAsATransientNodeProblem() {
        indexChain(10, 8, 5, 10);
        chain.withBlocks(11);
        chain.tamperParentHash(11, FakeChain.hashOf(999));
        ReorgDetectedException detected = detect();

        assertThatThrownBy(() -> recovery().recover(detected.blockNumber() - 1, detected.expectedParentHash()))
                .isInstanceOf(JsonRpcException.class)
                .hasMessageContaining("前后不一致");

        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 10, FakeChain.hashOf(10)));
        assertThat(statusByHash()).containsExactly(entry(FakeChain.hashOf(10), "CANONICAL"));
        assertThat(reorgRows()).isEmpty();
    }

    // ------------------------------------------------------------------ 脚手架

    /** 建链到 upTo，放日志，刷新链头，一批索引到头。 */
    private void indexChain(long upTo, long safe, long finalized, long... logBlocks) {
        chain.withBlocks(upTo);
        chain.reportSafe(safe);
        chain.reportFinalized(finalized);
        for (long block : logBlocks) {
            chain.addTransfer(LINK, block, ALICE, BOB, TEN_LINK);
        }
        tracker().refresh();
        indexer().start(0);
        assertThat(indexer().indexNextBatch().outcome()).isEqualTo(INDEXED);
    }

    /** 索引器本该发现接不上。 */
    private ReorgDetectedException detect() {
        try {
            indexer().indexNextBatch();
        } catch (ReorgDetectedException e) {
            return e;
        }
        throw new AssertionError("索引器本该发现重组");
    }

    private ReorgResult detectAndRecover() {
        ReorgDetectedException detected = detect();
        return recovery().recover(detected.blockNumber() - 1, detected.expectedParentHash());
    }

    private BlockIndexer indexer() {
        return new BlockIndexer(chain, cursors, transferLogs, tx(), CURSOR, LINK, 1000);
    }

    private ChainHeadTracker tracker() {
        return new ChainHeadTracker(chain, heads, tx(), "test");
    }

    private ReorgRecovery recovery() {
        return recovery(tx());
    }

    private ReorgRecovery recovery(TransactionTemplate tx) {
        return new ReorgRecovery(chain, cursors, transferLogs, heads, reorgs, tx, CURSOR);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private IndexerCursor cursor() {
        return jdbc.sql("SELECT name, last_block_number, last_block_hash FROM indexer_cursor WHERE name = :n")
                .param("n", CURSOR)
                .query((rs, i) -> new IndexerCursor(rs.getString(1), rs.getLong(2), rs.getString(3)))
                .single();
    }

    private long rowCount() {
        return jdbc.sql("SELECT COUNT(*) FROM chain_transfer_log").query(Long.class).single();
    }

    /** 每一行：所在块的哈希 → 状态。哈希区分分支，块号区分不了。 */
    private Map<String, String> statusByHash() {
        return jdbc.sql("SELECT block_hash, status FROM chain_transfer_log")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<Long> viewBlocks() {
        return jdbc.sql("SELECT block_number FROM chain_transfer_confirmation ORDER BY block_number")
                .query(Long.class).list();
    }

    private List<String> viewTxHashes() {
        return jdbc.sql("SELECT tx_hash FROM chain_transfer_confirmation ORDER BY block_number")
                .query(String.class).list();
    }

    private String levelOf(long block) {
        return jdbc.sql("SELECT level FROM chain_transfer_confirmation WHERE block_number = :b")
                .param("b", block).query(String.class).single();
    }

    /** 审计表每行：书签块、祖先块、深度、标废条数。 */
    private List<List<Long>> reorgRows() {
        return jdbc.sql("SELECT cursor_block, ancestor_block, depth, orphaned_logs FROM chain_reorg ORDER BY id")
                .query((rs, i) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)))
                .list();
    }
}
