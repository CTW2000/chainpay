package com.chainpay.chain.indexer.service;

import static com.chainpay.chain.indexer.domain.BatchOutcome.INDEXED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.indexer.domain.BatchResult;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.support.AbstractPostgresTest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 大声的错：提供商对 eth_getLogs 的范围设限并报带 code 的错，各家数字不同且不事先告诉你。
 * 对策是对半分；成功后<b>不</b>翻倍撞回去，而是记住失败过的尺寸、向它二分逼近（M3-⑤ 演练补丁）；
 * 减到一块还失败就停下——那不是范围问题。
 *
 * <p>为什么不能「成功后翻倍」（2026-09-08 真环境实测）：Alchemy 免费层把范围限在 10 块，
 * 翻倍策略在这个固定上限上永远震荡——12 败、6 成、翻倍到 12 再败，一半的调用注定失败，每轮只前进 60 到 100 块。
 * TCP 的拥塞控制也是丢包减半，但通畅后是慢慢加，不是立刻翻倍。
 */
@SpringBootTest
@DisplayName("M2-⑤ · 自适应窗口")
class AdaptiveWindowTest extends AbstractPostgresTest {

    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String CURSOR = "test:link:transfer";

    @Autowired
    private IndexerCursorRepository cursors;

    @Autowired
    private TransferLogRepository transferLogs;

    @Autowired
    private PlatformTransactionManager txManager;

    private FakeChain chain;

    @BeforeEach
    void resetChainTables() {
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor CASCADE").update();
        chain = new FakeChain().withBlocks(100);
    }

    @Test
    @DisplayName("★ 对半分直到提供商接受：上限 30 块、批 100 → 100 败、50 败、25 成；成功后向已知上限二分逼近，不翻倍撞回去")
    void halvesUntilTheProviderAccepts() {
        chain.limitLogsRange(30);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        BatchResult first = indexer.indexNextBatch();

        assertThat(first).isEqualTo(new BatchResult(INDEXED, 1, 25, 0, 0));
        assertThat(indexer.currentWindow()).as("50 刚失败过：在 25 与 50 之间二分，而不是翻倍回 50").isEqualTo(37);

        BatchResult second = indexer.indexNextBatch();

        assertThat(second).as("37 也撞上限：退回最近成功过的 25，不必从 18 重新爬").isEqualTo(new BatchResult(INDEXED, 26, 50, 0, 0));
        assertThat(indexer.currentWindow()).as("在 25 与 37 之间二分").isEqualTo(31);
    }

    @Test
    @DisplayName("★ 固定上限（Alchemy 免费层 10 块）：记住天花板后每批只问一次，不再一半调用注定失败")
    void settlesAtTheCeilingInsteadOfBouncingOnIt() {
        chain = new FakeChain().withBlocks(3_000);
        chain.limitLogsRange(10);
        AtomicInteger getLogsCalls = new AtomicInteger();
        chain.beforeLogs(getLogsCalls::incrementAndGet);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        for (int i = 0; i < 60; i++) {
            indexer.indexNextBatch();
        }

        int failures = getLogsCalls.get() - 60;
        assertThat(failures).as("收敛到上限的代价是有限的几次失败，不是每批一次").isLessThanOrEqualTo(8);
        assertThat(indexer.currentWindow()).as("停在提供商的上限上").isEqualTo(10);
        BatchResult next = indexer.indexNextBatch();
        assertThat(next.toBlock() - next.fromBlock() + 1).as("每批正好 10 块").isEqualTo(10);
        assertThat(getLogsCalls.get()).as("这一批只问了一次").isEqualTo(60 + failures + 1);
    }

    @Test
    @DisplayName("★ 上限解除后不立刻翻倍回去：连续成功满 REPROBE_AFTER 批才忘掉天花板试探一次，然后回到配置值")
    void probesAgainOnlyAfterAStreakOfSuccesses() {
        chain = new FakeChain().withBlocks(20_000);
        chain.limitLogsRange(30);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);
        indexer.indexNextBatch();                                    // 100 败、50 败、25 成 → 窗口 37
        chain.limitLogsRange(Integer.MAX_VALUE);                     // 提供商放宽了，但我们不知道

        int batchesUntilFullWindow = 0;
        while (indexer.currentWindow() < 100) {
            indexer.indexNextBatch();
            batchesUntilFullWindow++;
            assertThat(batchesUntilFullWindow).as("试探不该迟到太久").isLessThan(BlockIndexer.REPROBE_AFTER + 20);
        }
        assertThat(batchesUntilFullWindow)
                .as("连续成功满一个周期之前，窗口不越过记住的上限——立刻翻倍就是演练里的震荡")
                .isGreaterThanOrEqualTo(BlockIndexer.REPROBE_AFTER - 2);
    }

    @Test
    @DisplayName("★ 减到一块还失败：不是范围问题，停下，书签不动")
    void haltsWhenASingleBlockStillFails() {
        chain.limitLogsRange(0);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        assertThatThrownBy(indexer::indexNextBatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("单块");
        assertThat(jdbc.sql("SELECT last_block_number FROM indexer_cursor").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("传输失败（code 为空）不缩窗口：那是瞬时的，下次再来")
    void transportFailuresDoNotShrinkTheWindow() {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        chain.beforeLogs(() -> {
            if (failOnce.getAndSet(false)) {
                throw new JsonRpcException(null, "节点不可达");
            }
        });
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        assertThatThrownBy(indexer::indexNextBatch).isInstanceOf(JsonRpcException.class);

        assertThat(indexer.currentWindow()).isEqualTo(100);
        assertThat(indexer.indexNextBatch()).isEqualTo(new BatchResult(INDEXED, 1, 100, 0, 0));
    }

    private BlockIndexer indexer(int batchBlocks) {
        return new BlockIndexer(chain, cursors, transferLogs, new TransactionTemplate(txManager),
                CURSOR, LINK, batchBlocks);
    }
}
