package com.chainpay.chain.indexer.config;

import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.ReconcileRepository;
import com.chainpay.chain.indexer.repository.ReorgRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.indexer.service.BlockIndexer;
import com.chainpay.chain.indexer.service.ChainHeadTracker;
import com.chainpay.chain.indexer.service.ChainIndexerScheduler;
import com.chainpay.chain.indexer.service.LogReconciler;
import com.chainpay.chain.indexer.service.ReorgRecovery;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.EthRpc;
import com.chainpay.chain.rpc.JsonRpcClient;
import java.net.URI;
import java.security.SecureRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把索引器接进 Spring：<b>只在配了节点地址时</b>装配。
 *
 * <p>没配 {@code chainpay.chain.rpc-url}（环境变量 CHAINPAY_CHAIN_RPC_URL）时这个类整个不生效，
 * 应用照常启动——账本和 API 不该因为链节点没配而起不来。
 *
 * <p>轮询：{@link ChainIndexerScheduler#tick()} 按 {@code chainpay.chain.poll-interval} 定时跑。
 * {@code @EnableScheduling} 也只在这里、也只在配了节点时打开。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ChainIndexerProperties.class)
@ConditionalOnProperty(prefix = "chainpay.chain", name = "rpc-url")
class ChainIndexerConfig {

    @Bean
    ChainReaders chainReaders(ChainIndexerProperties properties) {
        ChainReader primary = new EthRpc(new JsonRpcClient(URI.create(properties.rpcUrl())));
        ChainReader audit = properties.auditRpcUrl() == null || properties.auditRpcUrl().isBlank()
                ? primary
                : new EthRpc(new JsonRpcClient(URI.create(properties.auditRpcUrl())));
        return new ChainReaders(primary, audit);
    }

    @Bean
    BlockIndexer blockIndexer(ChainReaders readers,
                              IndexerCursorRepository cursors,
                              TransferLogRepository transferLogs,
                              PlatformTransactionManager txManager,
                              ChainIndexerProperties properties) {
        return new BlockIndexer(readers.primary(), cursors, transferLogs, new TransactionTemplate(txManager),
                properties.cursorName(), properties.tokenAddress(), properties.batchBlocks());
    }

    @Bean
    ChainHeadTracker chainHeadTracker(ChainReaders readers,
                                      ChainHeadRepository heads,
                                      PlatformTransactionManager txManager,
                                      ChainIndexerProperties properties) {
        return new ChainHeadTracker(readers.primary(), readers.audit(), heads,
                new TransactionTemplate(txManager), properties.chainName());
    }

    @Bean
    ReorgRecovery reorgRecovery(ChainReaders readers,
                                IndexerCursorRepository cursors,
                                TransferLogRepository transferLogs,
                                ChainHeadRepository heads,
                                ReorgRepository reorgs,
                                PlatformTransactionManager txManager,
                                ChainIndexerProperties properties) {
        return new ReorgRecovery(readers.primary(), cursors, transferLogs, heads, reorgs,
                new TransactionTemplate(txManager), properties.cursorName());
    }

    @Bean
    LogReconciler logReconciler(ChainReaders readers,
                                IndexerCursorRepository cursors,
                                TransferLogRepository transferLogs,
                                ChainHeadRepository heads,
                                ReconcileRepository reconciles,
                                PlatformTransactionManager txManager,
                                ChainIndexerProperties properties) {
        return new LogReconciler(readers.primary(), readers.audit(), cursors, transferLogs, heads, reconciles,
                new TransactionTemplate(txManager), properties.cursorName(), properties.tokenAddress(),
                properties.reconcileSamples(), new SecureRandom());
    }

    @Bean
    ChainIndexerScheduler chainIndexerScheduler(ChainHeadTracker tracker,
                                                BlockIndexer indexer,
                                                ReorgRecovery recovery,
                                                LogReconciler reconciler,
                                                ChainIndexerProperties properties) {
        return new ChainIndexerScheduler(tracker, indexer, recovery, reconciler, properties.startBlock());
    }
}
