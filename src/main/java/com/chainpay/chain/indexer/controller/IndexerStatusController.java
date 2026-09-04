package com.chainpay.chain.indexer.controller;

import com.chainpay.chain.indexer.config.ChainReaders;
import com.chainpay.chain.indexer.domain.ChainHead;
import com.chainpay.chain.indexer.domain.IndexerCursor;
import com.chainpay.chain.indexer.domain.IndexerState;
import com.chainpay.chain.indexer.domain.TickResult;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.IndexerStateRepository;
import com.chainpay.chain.indexer.repository.ReconcileRepository;
import com.chainpay.chain.indexer.service.ChainIndexerScheduler;
import com.chainpay.common.web.ApiResponse;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读的索引器状态：给运维一个能问的地方。
 *
 * <p>「停下叫人」需要的是<b>状态</b>而不是<b>事件</b>：一行 ERROR 响一次就过去了，这个接口任何时候问都能答。
 * 挂在 {@code /admin/} 前缀下，和其它管理接口同一道门（回环地址 + 管理员令牌）；它不改任何东西。
 *
 * <p>两个视角并列：{@code status} 是<b>这个进程</b>的视角（没配节点就是 NOT_CONFIGURED），
 * {@code persistedStatus} 是<b>状态表</b>的视角（上一个进程停下的原因，重启也还在）。
 */
@RestController
@RequestMapping("/admin/v1")
public class IndexerStatusController {

    public record IndexerStatusResponse(String status, String persistedStatus, String reason, Instant since,
                                        String cursorName, Long cursorBlock, Long latestBlock, Long safeBlock,
                                        Long finalizedBlock, Long lagBlocks, String lastTickOutcome, Instant lastTickAt,
                                        int consecutiveFailures, String auditMode, long disputedBlocks) {}

    private final ObjectProvider<ChainIndexerScheduler> scheduler;
    private final ObjectProvider<ChainReaders> readers;
    private final IndexerStateRepository states;
    private final IndexerCursorRepository cursors;
    private final ChainHeadRepository heads;
    private final ReconcileRepository reconciles;
    private final String cursorName;

    public IndexerStatusController(ObjectProvider<ChainIndexerScheduler> scheduler,
                                   ObjectProvider<ChainReaders> readers,
                                   IndexerStateRepository states,
                                   IndexerCursorRepository cursors,
                                   ChainHeadRepository heads,
                                   ReconcileRepository reconciles,
                                   @Value("${chainpay.chain.cursor-name}") String cursorName) {
        this.scheduler = scheduler;
        this.readers = readers;
        this.states = states;
        this.cursors = cursors;
        this.heads = heads;
        this.reconciles = reconciles;
        this.cursorName = cursorName;
    }

    @GetMapping("/indexer")
    public ApiResponse<IndexerStatusResponse> status() {
        ChainIndexerScheduler running = scheduler.getIfAvailable();
        ChainReaders chainReaders = readers.getIfAvailable();
        Optional<IndexerState> state = states.find(cursorName);
        Optional<IndexerCursor> cursor = cursors.find(cursorName);
        Optional<ChainHead> head = heads.find();

        String persisted = state.map(s -> s.status().name()).orElse(null);
        String status = running == null ? "NOT_CONFIGURED" : persisted == null ? "RUNNING" : persisted;
        String reason = state.map(IndexerState::reason)
                .orElse(running == null ? "没有配置 CHAINPAY_CHAIN_RPC_URL：这个进程不索引" : null);
        Long cursorBlock = cursor.map(IndexerCursor::lastBlockNumber).orElse(null);
        Long latest = head.map(h -> h.latest().number()).orElse(null);
        Long lag = cursorBlock == null || latest == null ? null : latest - cursorBlock;
        TickResult last = running == null ? null : running.lastTick().orElse(null);

        return ApiResponse.ok(new IndexerStatusResponse(
                status, persisted, reason, state.map(IndexerState::since).orElse(null),
                cursorName, cursorBlock, latest,
                head.map(h -> h.safe().number()).orElse(null),
                head.map(h -> h.finalized().number()).orElse(null),
                lag,
                last == null ? null : last.outcome().name(),
                running == null ? null : running.lastTickAt(),
                running == null ? 0 : running.consecutiveFailures(),
                chainReaders == null ? "未装配（没有配置节点）" : chainReaders.auditMode(),
                reconciles.disputedBlocks()));
    }
}
