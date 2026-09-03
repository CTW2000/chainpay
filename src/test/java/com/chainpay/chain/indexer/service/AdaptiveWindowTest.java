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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 大声的错：提供商对 eth_getLogs 的范围设限并报带 code 的错，各家数字不同且不事先告诉你。
 * 对策是对半分、翻倍回；减到一块还失败就停下——那不是范围问题。
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
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor").update();
        chain = new FakeChain().withBlocks(100);
    }

    @Test
    @DisplayName("★ 对半分直到提供商接受：上限 30 块、批 100 → 100 败、50 败、25 成；成功后翻倍")
    void halvesUntilTheProviderAccepts() {
        chain.limitLogsRange(30);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);

        BatchResult first = indexer.indexNextBatch();

        assertThat(first).isEqualTo(new BatchResult(INDEXED, 1, 25, 0, 0));
        assertThat(indexer.currentWindow()).as("成功后翻倍").isEqualTo(50);

        BatchResult second = indexer.indexNextBatch();

        assertThat(second).as("50 又撞上限，再减到 25").isEqualTo(new BatchResult(INDEXED, 26, 50, 0, 0));
    }

    @Test
    @DisplayName("上限解除后窗口翻倍回到配置值，不会一直小下去")
    void growsBackToTheCapAfterTheLimitIsLifted() {
        chain.limitLogsRange(30);
        BlockIndexer indexer = indexer(100);
        indexer.start(0);
        indexer.indexNextBatch();                                    // 窗口 25 成功 → 50
        chain.limitLogsRange(Integer.MAX_VALUE);

        assertThat(indexer.indexNextBatch()).isEqualTo(new BatchResult(INDEXED, 26, 75, 0, 0));
        assertThat(indexer.currentWindow()).isEqualTo(100);
        assertThat(indexer.indexNextBatch()).isEqualTo(new BatchResult(INDEXED, 76, 100, 0, 0));
        assertThat(indexer.currentWindow()).as("封顶在配置值").isEqualTo(100);
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
