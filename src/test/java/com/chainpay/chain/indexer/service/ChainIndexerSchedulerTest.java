package com.chainpay.chain.indexer.service;

import static com.chainpay.chain.indexer.domain.TickOutcome.HALTED;
import static com.chainpay.chain.indexer.domain.TickOutcome.POLLED;
import static com.chainpay.chain.indexer.domain.TickOutcome.REORGED;
import static com.chainpay.chain.indexer.domain.TickOutcome.RETRY_LATER;
import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.erc20.Erc20Calls;
import com.chainpay.chain.indexer.domain.TickResult;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.ChainTokenRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.ReconcileRepository;
import com.chainpay.chain.indexer.repository.ReorgRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 一次轮询的形状：核对代币（只在第一次）→ 放书签（若没有且配了起点）→ 刷新链头 → 连续推批直到追平 → 抽样对账。
 *
 * <p>失败分三种：瞬时的（节点不可达）下次再来；重组（M2-④ 起）这一次回滚、下一次重放；
 * 结构性的（finalized 倒退、没书签没起点、代币未登记或 decimals 不一致）停下，之后每次轮询都直接返回。
 * 对账（M2-⑤）是审计，它自己的瞬时失败不改变轮询的结局。
 */
@SpringBootTest
@DisplayName("M2-③ · 轮询")
class ChainIndexerSchedulerTest extends AbstractPostgresTest {

    static final String LINK  = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String UNKNOWN_TOKEN = "0xcccccccccccccccccccccccccccccccccccccccc";
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
    private ReconcileRepository reconciles;

    @Autowired
    private ChainTokenRepository tokens;

    @Autowired
    private PlatformTransactionManager txManager;

    private FakeChain chain;

    @BeforeEach
    void resetChainTables() {
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor, chain_head, chain_reorg, chain_reconcile").update();
        jdbc.sql("UPDATE chain_token SET status = 'ACTIVE', verified_at = NULL WHERE address = :link").param("link", LINK).update();
        chain = new FakeChain();
        chain.defineToken(LINK, "LINK", 18);                        // 链上答得出，且和 V13 预置的一致
    }

    @Test
    @DisplayName("一次轮询：核对代币，刷新链头，连续推批直到追平，两张表都对，最后抽样对账")
    void aTickRefreshesHeadsAndCatchesUp() {
        chain.withBlocks(250);
        chain.addTransfer(LINK, 5, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 150, ALICE, BOB, TEN_LINK);
        chain.addTransfer(LINK, 240, BOB, ALICE, TEN_LINK);
        chain.reportSafe(200);
        chain.reportFinalized(100);
        ChainIndexerScheduler scheduler = scheduler(chain, LINK, 0L, 100);

        TickResult result = scheduler.tick();

        assertThat(result.outcome()).isEqualTo(POLLED);
        assertThat(result.batches()).as("1..100、101..200、201..250 三批").isEqualTo(3);
        assertThat(result.logsInserted()).isEqualTo(3);
        assertThat(result.sampled()).as("每次轮询抽 2 块").isEqualTo(2);
        assertThat(result.mismatches()).isZero();
        assertThat(cursorBlock()).isEqualTo(250);
        assertThat(headNumbers()).containsExactly(250L, 200L, 100L);
        assertThat(levelOf(5)).isEqualTo("FINAL");
        assertThat(levelOf(150)).isEqualTo("SAFE");
        assertThat(levelOf(240)).isEqualTo("SEEN");
        assertThat(jdbc.sql("SELECT verified_at IS NOT NULL FROM chain_token WHERE address = :l").param("l", LINK)
                .query(Boolean.class).single()).as("第一次轮询核对了代币").isTrue();
    }

    @Test
    @DisplayName("★ 配置的代币不在白名单里：停下，什么都不做")
    void haltsWhenTheTokenIsNotRegistered() {
        chain.withBlocks(10);
        chain.reportSafe(5);
        chain.reportFinalized(2);
        chain.defineToken(UNKNOWN_TOKEN, "UNK", 18);

        TickResult result = scheduler(chain, UNKNOWN_TOKEN, 0L, 100).tick();

        assertThat(result.outcome()).isEqualTo(HALTED);
        assertThat(result.detail()).contains("未登记");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM indexer_cursor").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("★ 链上的 decimals 和白名单不一致：停下——表被改过或合约被升级，不猜")
    void haltsWhenOnChainDecimalsDisagreeWithTheRegistry() {
        chain.withBlocks(10);
        chain.reportSafe(5);
        chain.reportFinalized(2);
        chain.defineToken(LINK, "LINK", 6);

        TickResult result = scheduler(chain, LINK, 0L, 100).tick();

        assertThat(result.outcome()).isEqualTo(HALTED);
        assertThat(result.detail()).contains("不一致");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM indexer_cursor").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("对账自己的瞬时失败不改变轮询结局：审计节点不可达，POLLED 照旧，只是抽了 0 块")
    void reconcileFailureDoesNotChangeTheTickOutcome() {
        chain.withBlocks(250);
        chain.reportSafe(200);
        chain.reportFinalized(100);
        FakeChain unreachableAudit = new FakeChain();               // 一块都没有：问什么都抛

        TickResult result = scheduler(unreachableAudit, LINK, 0L, 100).tick();

        assertThat(result.outcome()).isEqualTo(POLLED);
        assertThat(result.sampled()).isZero();
        assertThat(cursorBlock()).isEqualTo(250);
    }

    @Test
    @DisplayName("★ finalized 倒退：停下，书签不动；之后的轮询直接返回，不再碰节点")
    void haltsOnFinalityViolationAndStaysHalted() {
        chain.withBlocks(10);
        chain.reportSafe(5);
        chain.reportFinalized(2);
        ChainIndexerScheduler scheduler = scheduler(chain, LINK, 0L, 5);
        scheduler.tick();                                            // 书签到 10，链头 finalized 2

        chain.reportFinalized(1);
        TickResult halted = scheduler.tick();

        assertThat(halted.outcome()).isEqualTo(HALTED);
        assertThat(halted.detail()).contains("倒退");
        assertThat(scheduler.isHalted()).isTrue();
        assertThat(cursorBlock()).isEqualTo(10);

        AtomicBoolean touchedTheNode = new AtomicBoolean(false);
        chain.beforeLogs(() -> touchedTheNode.set(true));
        assertThat(scheduler.tick().outcome()).isEqualTo(HALTED);
        assertThat(touchedTheNode).isFalse();
        assertThat(cursorBlock()).isEqualTo(10);
    }

    @Test
    @DisplayName("★ 重组：这一次轮询回滚（REORGED），下一次从祖先之后重放；旧行 ORPHANED、新行 CANONICAL")
    void recoversFromAReorgAndReplaysOnTheNextTick() {
        chain.withBlocks(10);
        chain.reportSafe(8);
        chain.reportFinalized(5);
        chain.addTransfer(LINK, 10, ALICE, BOB, TEN_LINK);
        ChainIndexerScheduler scheduler = scheduler(chain, LINK, 0L, 100);
        assertThat(scheduler.tick().outcome()).isEqualTo(POLLED);  // 书签 10
        String reincludedTx = FakeChain.txHashOf(10, 0);
        chain.reorgFrom(10, "A");
        chain.addTransfer(LINK, 10, ALICE, BOB, TEN_LINK, reincludedTx);
        chain.withBlocks(11);

        TickResult reorged = scheduler.tick();

        assertThat(reorged.outcome()).isEqualTo(REORGED);
        assertThat(reorged.detail()).contains("深度 5");
        assertThat(scheduler.isHalted()).isFalse();
        assertThat(cursorBlock()).as("6..9 无从证明，退到 finalized 头").isEqualTo(5);

        TickResult replayed = scheduler.tick();

        assertThat(replayed.outcome()).isEqualTo(POLLED);
        assertThat(cursorBlock()).isEqualTo(11);
        assertThat(statusByHash()).containsExactlyInAnyOrderEntriesOf(Map.of(
                FakeChain.hashOf(10), "ORPHANED",
                FakeChain.hashOf(10, "A"), "CANONICAL"));
        assertThat(jdbc.sql("SELECT block_number FROM chain_transfer_confirmation").query(Long.class).list())
                .containsExactly(10L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM chain_reorg").query(Long.class).single()).isEqualTo(1);
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
        ChainIndexerScheduler scheduler = scheduler(chain, LINK, 0L, 100);

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
    @DisplayName("★ 核对代币时节点瞬时失败：RETRY_LATER 不停下、不算核对过；下一次成功才核对、才推批")
    void transientFailureDuringTokenVerificationIsRetried() {
        chain.withBlocks(10);
        chain.reportSafe(5);
        chain.reportFinalized(2);
        chain.addTransfer(LINK, 3, ALICE, BOB, TEN_LINK);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        chain.beforeCall(() -> {
            if (failOnce.getAndSet(false)) {
                throw new JsonRpcException(null, "超时（20000 ms，含正文）· eth_call");
            }
        });
        ChainIndexerScheduler scheduler = scheduler(chain, LINK, 0L, 100);

        TickResult first = scheduler.tick();
        assertThat(first.outcome()).isEqualTo(RETRY_LATER);
        assertThat(first.detail()).contains("eth_call");
        assertThat(scheduler.isHalted()).isFalse();
        assertThat(verified(LINK)).as("没问到不等于核对过").isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM indexer_cursor").query(Long.class).single()).isZero();

        TickResult second = scheduler.tick();
        assertThat(second.outcome()).isEqualTo(POLLED);
        assertThat(verified(LINK)).isTrue();
        assertThat(cursorBlock()).isEqualTo(10);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("没有书签也没配起点：停下，说清原因")
    void haltsWhenThereIsNoCursorAndNoStartBlock() {
        chain.withBlocks(10);
        ChainIndexerScheduler scheduler = scheduler(chain, LINK, null, 100);

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
        ChainIndexerScheduler scheduler = scheduler(chain, LINK, 4L, 100);

        scheduler.tick();

        assertThat(cursorBlock()).isEqualTo(10);
        assertThat(jdbc.sql("SELECT block_number FROM chain_transfer_log").query(Long.class).list())
                .containsExactly(7L);
    }

    // ------------------------------------------------------------------ 脚手架

    private ChainIndexerScheduler scheduler(FakeChain audit, String token, Long startBlock, int batchBlocks) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        BlockIndexer indexer = new BlockIndexer(chain, cursors, transferLogs, tx, CURSOR, token, batchBlocks);
        ChainHeadTracker tracker = new ChainHeadTracker(chain, heads, tx, "test");
        ReorgRecovery recovery = new ReorgRecovery(chain, cursors, transferLogs, heads, reorgs, tx, CURSOR);
        LogReconciler reconciler = new LogReconciler(chain, audit, cursors, transferLogs, heads, reconciles, tx,
                CURSOR, token, 2, new Random(1));
        TokenRegistry registry = new TokenRegistry(new Erc20Calls(chain), tokens);
        return new ChainIndexerScheduler(tracker, indexer, recovery, reconciler, registry, token, startBlock);
    }

    private boolean verified(String token) {
        return jdbc.sql("SELECT verified_at IS NOT NULL FROM chain_token WHERE address = :a")
                .param("a", token).query(Boolean.class).single();
    }

    private long cursorBlock() {
        return jdbc.sql("SELECT last_block_number FROM indexer_cursor WHERE name = :n")
                .param("n", CURSOR).query(Long.class).single();
    }

    private long rowCount() {
        return jdbc.sql("SELECT COUNT(*) FROM chain_transfer_log").query(Long.class).single();
    }

    private Map<String, String> statusByHash() {
        return jdbc.sql("SELECT block_hash, status FROM chain_transfer_log")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
