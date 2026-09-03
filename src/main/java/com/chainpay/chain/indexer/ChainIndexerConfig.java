package com.chainpay.chain.indexer;

import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.EthRpc;
import com.chainpay.chain.rpc.JsonRpcClient;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把裸奔版接进 Spring：<b>只在配了节点地址时</b>装配。
 *
 * <p>没配 {@code chainpay.chain.rpc-url}（环境变量 CHAINPAY_CHAIN_RPC_URL）时这个类整个不生效，
 * 应用照常启动——账本和 API 不该因为链节点没配而起不来。
 *
 * <p>M2-② 不加定时轮询：{@link BlockIndexer#indexNextBatch()} 由测试和探针调用。
 * M2-③ 决定了「盯着哪个头」（latest / safe / finalized）再加循环。
 */
@Configuration
@EnableConfigurationProperties(ChainIndexerProperties.class)
@ConditionalOnProperty(prefix = "chainpay.chain", name = "rpc-url")
class ChainIndexerConfig {

    @Bean
    ChainReader chainReader(ChainIndexerProperties properties) {
        return new EthRpc(new JsonRpcClient(URI.create(properties.rpcUrl())));
    }

    @Bean
    BlockIndexer blockIndexer(ChainReader chain,
                              IndexerCursorRepository cursors,
                              TransferLogRepository transferLogs,
                              PlatformTransactionManager txManager,
                              ChainIndexerProperties properties) {
        return new BlockIndexer(chain, cursors, transferLogs, new TransactionTemplate(txManager),
                properties.cursorName(), properties.tokenAddress(), properties.batchBlocks());
    }
}
