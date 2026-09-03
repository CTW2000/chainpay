package com.chainpay.chain.indexer;

import static com.chainpay.chain.indexer.TickOutcome.HALTED;
import static com.chainpay.chain.indexer.TickOutcome.POLLED;
import static com.chainpay.chain.indexer.TickOutcome.RETRY_LATER;
import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 一次轮询的形状：放书签（若没有且配了起点）→ 刷新链头 → 连续推批直到追平。
 *
 * <p>失败分两种：瞬时的（节点不可达）下次再来；结构性的（重组、finalized 倒退、没书签没起点）停下，
 * 之后每次轮询都直接返回，不再碰节点。停下来的索引器是一个报警，往前走的是定时炸弹。
 */
@SpringBootTest
@DisplayName("M2-③ · 轮询")
class ChainIndexerSchedulerTest extends AbstractPostgresTest {

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
    private PlatformTransactionManager txManager;

    private FakeChain chain;

    @BeforeEach
    void resetChainTables() {
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor, chain_head").update();
        chain = new FakeChain();
    }

    @Test
    @DisplayName("一次轮询：刷新链头，连续推批直到追平，两张表都对")
    void aTickRefreshesHeadsAndCatchesUp() {
        chain.withBlocks(250);
        chain.addTransfer(LINK, 5, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 150, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 240, BOB, ALICE, TEN_LINK);
        chain.reportSafe(200);
        chain.reportFinalized(100);
        ChainIndexerScheduler scheduler = scheduler(0L, 100);

        TickResult result = scheduler.tick();

        assertThat(result.outcome()).isEqualTo(POLLED);
        assertThat(result.batches()).as("1..100、101..200、201..250 三批").isEqualTo(3);
        assertThat(result.logsInserted()).isEqualTo(3);
        assertThat(cursorBlock()).isEqualTo(250);
        assertThat(headNumbers()).containsExactly(250L, 200L, 100L);
        assertThat(levelOf(5)).isEqualTo("FINAL");
        assertThat(levelOf(150)).isEqualTo("SAFE");
        assertThat(levelOf(240)).isEqualTo("SEEN");
    }

    @Test
    @DisplayName("★ 重组：停下，书签不动；之后的轮询直接返回，不再碰节点")
    void haltsOnReorgAndStaysHalted() {
        chain.withBlocks(10);
        chain.reportSafe(5);
        chain.reportFinalized(2);
        ChainIndexerScheduler scheduler = scheduler(0L, 5);
        scheduler.tick();                                            // 书签到 10

        chain.withBlocks(20);
        chain.tamperParentHash(11, FakeChain.hashOf(999));
        TickResult halted = scheduler.tick();

        assertThat(halted.outcome()).isEqualTo(HALTED);
        assertThat(halted.detail()).contains("11");
        assertThat(scheduler.isHalted()).isTrue();
        assertThat(cursorBlock()).isEqualTo(10);

        AtomicBoolean touchedTheNode = new AtomicBoolean(false);
        chain.beforeLogs(() -> touchedTheNode.set(true));
        assertThat(scheduler.tick().outcome()).isEqualTo(HALTED);
        assertThat(touchedTheNode).isFalse();
        assertThat(cursorBlock()).isEqualTo(10);
    }

    @Test
    @DisplayName("★ 瞬时失败不停下：这次 RETRY_LATER、什么都没写，下一次成功")
    void transientFailureIsRetriedNextTick() {
        chain.withBlocks(10);
        chain.reportSafe(5);
        chain.reportFinalized(2);
        chain.addTransfer(LINK, 3, ALICE, BOB, TEN_LINK);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        chain.beforeLogs(() -> {
            if (failOnce.getAndSet(false)) {
                throw new JsonRpcException(null, "节点不可达");
            }
        });
        ChainIndexerScheduler scheduler = scheduler(0L, 100);

        TickResult first = scheduler.tick();
        assertThat(first.outcome()).isEqualTo(RETRY_LATER);
        assertThat(first.detail()).contains("节点不可达");
        assertThat(scheduler.isHalted()).isFalse();
        assertThat(rowCount()).isZero();
        assertThat(cursorBlock()).isEqualTo(0);

        TickResult second = scheduler.tick();
        assertThat(second.outcome()).isEqualTo(POLLED);
        assertThat(rowCount()).isEqualTo(1);
        assertThat(cursorBlock()).isEqualTo(10);
    }

    @Test
    @DisplayName("没有书签也没配起点：停下，说清原因")
    void haltsWhenThereIsNoCursorAndNoStartBlock() {
        chain.withBlocks(10);
        ChainIndexerScheduler scheduler = scheduler(null, 100);

        TickResult result = scheduler.tick();

        assertThat(result.outcome()).isEqualTo(HALTED);
        assertThat(result.detail()).contains("start-block");
        assertThat(scheduler.isHalted()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM indexer_cursor").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("配了起点：第一次轮询自动放书签，起点之前的日志不索引")
    void placesTheCursorAtTheConfiguredStartBlock() {
        chain.withBlocks(10);
        chain.reportSafe(5);
        chain.reportFinalized(2);
        chain.addTransfer(LINK, 3, ALICE, BOB, TEN_LINK);           // 起点之前
        chain.addTransfer(LINK, 7, ALICE, BOB, TEN_LINK);           // 起点之后
        ChainIndexerScheduler scheduler = scheduler(4L, 100);

        scheduler.tick();

        assertThat(cursorBlock()).isEqualTo(10);
        assertThat(jdbc.sql("SELECT block_number FROM chain_transfer_log").query(Long.class).list())
                .containsExactly(7L);
    }

    // ------------------------------------------------------------------ 脚手架

    private ChainIndexerScheduler scheduler(Long startBlock, int batchBlocks) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        BlockIndexer indexer = new BlockIndexer(chain, cursors, transferLogs, tx, CURSOR, LINK, batchBlocks);
        ChainHeadTracker tracker = new ChainHeadTracker(chain, heads, tx, "test");
        return new ChainIndexerScheduler(tracker, indexer, startBlock);
    }

    private long cursorBlock() {
        return jdbc.sql("SELECT last_block_number FROM indexer_cursor WHERE name = :n")
                .param("n", CURSOR).query(Long.class).single();
    }

    private long rowCount() {
        return jdbc.sql("SELECT COUNT(*) FROM chain_transfer_log").query(Long.class).single();
    }

    private List<Long> headNumbers() {
        return jdbc.sql("SELECT latest_number, safe_number, finalized_number FROM chain_head")
                .query((rs, i) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .single();
    }

    private String levelOf(long block) {
        return jdbc.sql("SELECT level FROM chain_transfer_confirmation WHERE block_number = :b")
                .param("b", block).query(String.class).single();
    }
}
