package com.chainpay.chain.indexer.service;

import static com.chainpay.chain.indexer.domain.BatchOutcome.INDEXED;
import static com.chainpay.chain.indexer.domain.BatchOutcome.SKIPPED_CURSOR_MOVED;
import static com.chainpay.chain.indexer.domain.BatchOutcome.UP_TO_DATE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.erc20.TransferLogDecoder;
import com.chainpay.chain.indexer.domain.BatchResult;
import com.chainpay.chain.indexer.domain.IndexerCursor;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.rpc.Hex;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.rpc.RawLog;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M2-② 的契约：事件和书签同生同死、重复无害、书签只进不退、重组就停。
 *
 * <p>链是 {@link FakeChain}（内存），库是真的 PostgreSQL——
 * 要证明的恰恰是「数据库层面」的事：事务边界、唯一约束、行锁、CHECK。
 * 库里的断言一律用属主连接 {@code jdbc} 读，不经过被测代码。
 */
@SpringBootTest
@DisplayName("M2-② · 落库与书签")
class BlockIndexerTest extends AbstractPostgresTest {

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
    private PlatformTransactionManager txManager;

    private FakeChain chain;

    @BeforeEach
    void resetChainTables() {
        // 应用角色没有 DELETE / TRUNCATE，清表只能用属主连接
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor").update();
        chain = new FakeChain();
    }

    private BlockIndexer indexer(int batchBlocks) {
        return new BlockIndexer(chain, cursors, transferLogs,
                new TransactionTemplate(txManager), CURSOR, LINK, batchBlocks);
    }

    // ------------------------------------------------------------------ 基本功

    @Test
    @DisplayName("一批内的转账全部落库，书签推到批末并记下它的哈希")
    void indexesTransfersAndAdvancesTheCursor() {
        chain.withBlocks(10);
        chain.addTransfer(LINK, 3, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 7, ALICE, BOB, BigInteger.ONE);
        chain.addTransfer(LINK, 7, BOB, ALICE, BigInteger.TWO);
        chain.addTransfer(LINK, 10, ALICE, BOB, TEN_LINK);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        BatchResult result = indexer.indexNextBatch();

        assertThat(result).isEqualTo(new BatchResult(INDEXED, 1, 10, 4, 4));
        assertThat(rowCount()).isEqualTo(4);
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 10, FakeChain.hashOf(10)));

        Map<String, Object> second = jdbc.sql("""
                        SELECT token, from_address, to_address, value, block_number, block_hash,
                               tx_hash, log_index, status
                        FROM chain_transfer_log WHERE block_number = 7 AND log_index = 1
                        """)
                .query().singleRow();
        assertThat(second.get("token")).isEqualTo(LINK);
        assertThat(second.get("from_address")).isEqualTo(BOB);
        assertThat(second.get("to_address")).isEqualTo(ALICE);
        assertThat(((BigDecimal) second.get("value")).toBigIntegerExact()).isEqualTo(BigInteger.TWO);
        assertThat(second.get("block_hash")).isEqualTo(FakeChain.hashOf(7));
        assertThat(second.get("tx_hash")).isEqualTo(FakeChain.txHashOf(7, 1));
        assertThat(second.get("status")).isEqualTo("CANONICAL");
    }

    @Test
    @DisplayName("uint256 的最大值原样存取，一个数字都不丢")
    void storesTheFullUint256WithoutLoss() {
        BigInteger max = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);   // 78 位十进制
        chain.withBlocks(1);
        chain.addTransfer(LINK, 1, ALICE, BOB, max);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        indexer.indexNextBatch();

        BigDecimal stored = jdbc.sql("SELECT value FROM chain_transfer_log").query(BigDecimal.class).single();
        assertThat(stored.toBigIntegerExact()).isEqualTo(max);
    }

    @Test
    @DisplayName("零值转账是合法事件（EIP-20），照样落库")
    void acceptsZeroValueTransfers() {
        chain.withBlocks(1);
        chain.addTransfer(LINK, 1, ALICE, BOB, BigInteger.ZERO);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        BatchResult result = indexer.indexNextBatch();

        assertThat(result.logsInserted()).isEqualTo(1);
    }

    @Test
    @DisplayName("批大小封顶：12 个区块、每批 5 个，要三批，第四次报已是最新")
    void capsEachBatchAtTheConfiguredSize() {
        chain.withBlocks(12);
        BlockIndexer indexer = indexer(5);
        indexer.start(0);

        assertThat(indexer.indexNextBatch()).isEqualTo(new BatchResult(INDEXED, 1, 5, 0, 0));
        assertThat(indexer.indexNextBatch()).isEqualTo(new BatchResult(INDEXED, 6, 10, 0, 0));
        assertThat(indexer.indexNextBatch()).isEqualTo(new BatchResult(INDEXED, 11, 12, 0, 0));
        assertThat(indexer.indexNextBatch().outcome()).isEqualTo(UP_TO_DATE);
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 12, FakeChain.hashOf(12)));
    }

    @Test
    @DisplayName("start 是幂等的：书签已存在就不动它")
    void startingTwiceKeepsTheFirstCursor() {
        chain.withBlocks(10);
        BlockIndexer indexer = indexer(100);

        indexer.start(3);
        indexer.start(7);

        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 3, FakeChain.hashOf(3)));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM indexer_cursor").query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 验收标准

    @Test
    @DisplayName("★ 崩溃恢复：一批里有一条写不进去，整批回滚，书签不动（两次写入在同一事务）")
    void aFailedInsertLeavesNeitherLogsNorCursorBehind() {
        chain.withBlocks(5);
        chain.addTransfer(LINK, 2, ALICE, BOB, TEN_LINK);
        // 节点给了一个不成形的交易哈希。解码器不校验哈希形状，库的 CHECK 会拒绝它
        chain.addTransfer(LINK, 3, ALICE, BOB, TEN_LINK, "0xnot-a-hash");
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        assertThatThrownBy(indexer::indexNextBatch)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chain_transfer_log_txhash_ck");

        assertThat(rowCount()).as("第一条也不能留下：它和失败的那条在同一事务里").isZero();
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 0, FakeChain.hashOf(0)));
    }

    @Test
    @DisplayName("★ 幂等重放：把书签手工回退再跑，行数与内容一模一样，重复的被唯一约束挡掉")
    void replayAfterRewindingTheCursorIsIdempotent() {
        chain.withBlocks(20);
        chain.addTransfer(LINK, 5, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 12, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 18, BOB, ALICE, TEN_LINK);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);
        indexer.indexNextBatch();
        List<String> before = rowsSnapshot();
        assertThat(before).hasSize(3);

        // 「手工回退 1000 个区块重跑」的缩小版：书签退到 10
        jdbc.sql("UPDATE indexer_cursor SET last_block_number = 10, last_block_hash = :h WHERE name = :n")
                .param("h", FakeChain.hashOf(10)).param("n", CURSOR).update();

        BatchResult replay = indexer.indexNextBatch();

        assertThat(replay).isEqualTo(new BatchResult(INDEXED, 11, 20, 2, 0));
        assertThat(rowsSnapshot()).as("连 id 都不变：没有删了重插").isEqualTo(before);
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 20, FakeChain.hashOf(20)));
    }

    @Test
    @DisplayName("★ 节点落后：报出的头比书签旧，书签不动、不报错")
    void doesNotMoveTheCursorBackwardWhenTheNodeIsBehind() {
        chain.withBlocks(10);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);
        indexer.indexNextBatch();

        chain.reportHead(5);
        BatchResult result = indexer.indexNextBatch();

        assertThat(result.outcome()).isEqualTo(UP_TO_DATE);
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 10, FakeChain.hashOf(10)));
    }

    @Test
    @DisplayName("★ 两个实例：慢的那个不能把书签往回推——锁内重读发现书签已动，整批作废")
    void aStaleWriterCannotMoveTheCursorBackward() throws Exception {
        chain.withBlocks(30);
        chain.addTransfer(LINK, 3, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 15, ALICE, BOB, TEN_LINK);
        BlockIndexer indexer = indexer(10);
        indexer.start(0);

        // 慢实例读到书签 0、开始取 1..10 的日志时，把它按住
        CountDownLatch staleIsFetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        chain.beforeLogs(() -> {
            if ("stale-writer".equals(Thread.currentThread().getName())) {
                staleIsFetching.countDown();
                await(release);
            }
        });
        AtomicReference<BatchResult> staleResult = new AtomicReference<>();
        Thread stale = new Thread(() -> staleResult.set(indexer.indexNextBatch()), "stale-writer");
        stale.start();
        assertThat(staleIsFetching.await(5, SECONDS)).isTrue();

        // 快实例趁它取数据时推了两批：书签到 20
        assertThat(indexer.indexNextBatch().outcome()).isEqualTo(INDEXED);
        assertThat(indexer.indexNextBatch().outcome()).isEqualTo(INDEXED);
        assertThat(cursor().lastBlockNumber()).isEqualTo(20);

        release.countDown();
        stale.join(5_000);

        assertThat(staleResult.get()).isNotNull();
        assertThat(staleResult.get().outcome()).isEqualTo(SKIPPED_CURSOR_MOVED);
        assertThat(cursor()).as("书签只进不退").isEqualTo(new IndexerCursor(CURSOR, 20, FakeChain.hashOf(20)));
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 重组检测：下一块的 parentHash 接不上书签，停下，什么都不写")
    void haltsWhenTheNextBlockDoesNotChainOntoTheCursor() {
        chain.withBlocks(10);
        chain.addTransfer(LINK, 8, ALICE, BOB, TEN_LINK);
        BlockIndexer indexer = indexer(5);
        indexer.start(0);
        indexer.indexNextBatch();                                   // 书签到 5

        chain.tamperParentHash(6, FakeChain.hashOf(999));          // 6 不再接在 5 上

        assertThatThrownBy(indexer::indexNextBatch)
                .isInstanceOf(ReorgDetectedException.class)
                .hasMessageContaining("6");
        assertThat(rowCount()).isZero();
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 5, FakeChain.hashOf(5)));
    }

    @Test
    @DisplayName("★ 解码不了的日志：整批停下，连同批里正常的那条也不写")
    void haltsOnAnUndecodableLog() {
        chain.withBlocks(3);
        chain.addTransfer(LINK, 1, ALICE, BOB, TEN_LINK);
        chain.addRawLog(new RawLog(LINK, List.of(TransferLogDecoder.TRANSFER_TOPIC0), "0x",
                "0x2", FakeChain.hashOf(2), FakeChain.txHashOf(2, 0), "0x0", "0x0", false));
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        assertThatThrownBy(indexer::indexNextBatch).isInstanceOf(IllegalArgumentException.class);
        assertThat(rowCount()).isZero();
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 0, FakeChain.hashOf(0)));
    }

    @Test
    @DisplayName("合约地址不成形（比如被 YAML 转成了十进制）：构造时就拒绝，不等到第一次 getLogs")
    void rejectsATokenAddressThatIsNotHex() {
        assertThatThrownBy(() -> new BlockIndexer(chain, cursors, transferLogs,
                new TransactionTemplate(txManager), CURSOR, "682105340000000000000000000000000000000000000000", 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0x");
    }

    // ------------------------------------------------------------------ 一批的归属（2026-09-03 补丁）

    @Test
    @DisplayName("★ 撕裂的快照：取日志之前链换了分支，这批作废（瞬时），什么都不写；下一批按重组处理")
    void aReorgBetweenTheHeaderAndTheLogsInvalidatesTheBatch() {
        chain.withBlocks(10);
        chain.addTransfer(LINK, 5, ALICE, BOB, TEN_LINK);
        BlockIndexer indexer = indexer(5);
        indexer.start(0);
        indexer.indexNextBatch();                                   // 书签 5；块 5 的行 CANONICAL，记的是旧分支哈希
        chain.beforeLogs(() -> {                                     // block(6) 已取、父哈希已核对；取日志前链从块 4 起换到 A
            chain.reorgFrom(4, "A");
            chain.addTransfer(LINK, 7, ALICE, BOB, TEN_LINK);
        });

        assertThatThrownBy(indexer::indexNextBatch)
                .isInstanceOf(JsonRpcException.class)
                .hasMessageContaining("前后不一致");
        assertThat(rowCount()).as("A 分支的块 7 没有被写进去").isEqualTo(1);
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 5, FakeChain.hashOf(5)));

        chain.beforeLogs(() -> { });
        assertThatThrownBy(indexer::indexNextBatch)                  // 链稳定在 A 上：正常的重组检测接手
                .isInstanceOf(ReorgDetectedException.class);
    }

    @Test
    @DisplayName("★ 撕裂的快照（另一侧）：取完日志、取 block(to) 之前链换了分支，同样作废")
    void aReorgBetweenTheLogsAndTheLastHeaderInvalidatesTheBatch() {
        chain.withBlocks(10);
        chain.addTransfer(LINK, 8, ALICE, BOB, TEN_LINK);
        BlockIndexer indexer = indexer(5);
        indexer.start(0);
        indexer.indexNextBatch();                                   // 书签 5
        AtomicBoolean once = new AtomicBoolean(true);
        chain.beforeBlock(n -> {
            if (n == 10 && once.getAndSet(false)) {
                chain.reorgFrom(4, "A");                             // 日志来自旧分支，block(10) 却来自 A
            }
        });

        assertThatThrownBy(indexer::indexNextBatch).isInstanceOf(JsonRpcException.class);
        assertThat(rowCount()).isZero();
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 5, FakeChain.hashOf(5)));
    }

    @Test
    @DisplayName("★ 节点塞进一条不在请求范围里的日志：不是范围问题，是答非所问，停下，什么都不写")
    void haltsWhenTheNodeReturnsALogOutsideTheRequestedRange() {
        chain.withBlocks(10);
        chain.addTransfer(LINK, 3, ALICE, BOB, TEN_LINK);
        chain.injectIntoGetLogs(transferLog(50, FakeChain.hashOf(50), TEN_LINK));   // 我们问的是 1..10
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        assertThatThrownBy(indexer::indexNextBatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("范围");
        assertThat(rowCount()).isZero();
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 0, FakeChain.hashOf(0)));
    }

    @Test
    @DisplayName("★ 日志声称的块哈希和该块的头对不上：节点前后不一致，这批作废，不写")
    void rejectsALogWhoseBlockHashDoesNotMatchTheHeader() {
        chain.withBlocks(10);
        chain.addTransfer(LINK, 3, ALICE, BOB, TEN_LINK);
        chain.injectIntoGetLogs(transferLog(4, FakeChain.hashOf(4, "X"), TEN_LINK));  // 块 4 的头是原始分支
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        assertThatThrownBy(indexer::indexNextBatch).isInstanceOf(JsonRpcException.class);
        assertThat(rowCount()).isZero();
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 0, FakeChain.hashOf(0)));
    }

    @Test
    @DisplayName("★ 重放带来同一坐标、不同金额的日志：块哈希承诺了内容，两者不可能都对，停下，原行一个字不动")
    void replayWithADifferentPayloadAtTheSameCoordinateHalts() {
        chain.withBlocks(5);
        RawLog original = chain.addTransfer(LINK, 3, ALICE, BOB, TEN_LINK);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);
        indexer.indexNextBatch();                                   // 块 3 的行：10 LINK
        jdbc.sql("UPDATE indexer_cursor SET last_block_number = 0, last_block_hash = :h WHERE name = :n")
                .param("h", FakeChain.hashOf(0)).param("n", CURSOR).update();
        chain.dropFromGetLogs(original);
        chain.injectIntoGetLogs(transferLog(3, FakeChain.hashOf(3), BigInteger.ONE));   // 同坐标，金额变了

        assertThatThrownBy(indexer::indexNextBatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同一坐标");
        assertThat(valueAt(3)).isEqualTo(TEN_LINK);
        assertThat(cursor()).isEqualTo(new IndexerCursor(CURSOR, 0, FakeChain.hashOf(0)));
    }

    // ------------------------------------------------------------------ 脚手架

    /** 一条形状合法的 Transfer 日志，块号、块哈希、金额由调用方指定——造「节点撒谎」。 */
    private static RawLog transferLog(long block, String blockHash, BigInteger value) {
        return new RawLog(LINK,
                List.of(TransferLogDecoder.TRANSFER_TOPIC0, FakeChain.addressTopic(ALICE), FakeChain.addressTopic(BOB)),
                String.format("0x%064x", value), Hex.fromLong(block), blockHash,
                FakeChain.txHashOf(block, 0), "0x0", "0x0", false);
    }

    private BigInteger valueAt(long block) {
        return jdbc.sql("SELECT value FROM chain_transfer_log WHERE block_number = :b")
                .param("b", block).query(BigDecimal.class).single().toBigInteger();
    }

    private long rowCount() {
        return jdbc.sql("SELECT COUNT(*) FROM chain_transfer_log").query(Long.class).single();
    }

    private IndexerCursor cursor() {
        return jdbc.sql("SELECT name, last_block_number, last_block_hash FROM indexer_cursor WHERE name = :n")
                .param("n", CURSOR)
                .query((rs, i) -> new IndexerCursor(rs.getString(1), rs.getLong(2), rs.getString(3)))
                .single();
    }

    /** 每行的 id、坐标、金额拼成一个字符串，按 id 排——重放前后逐行对比。 */
    private List<String> rowsSnapshot() {
        return jdbc.sql("SELECT id || '|' || block_hash || '|' || log_index || '|' || value "
                        + "FROM chain_transfer_log ORDER BY id")
                .query(String.class)
                .list();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
