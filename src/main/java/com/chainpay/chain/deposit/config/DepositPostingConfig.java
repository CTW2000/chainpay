package com.chainpay.chain.deposit.config;

import com.chainpay.chain.deposit.service.DepositPoster;
import com.chainpay.chain.deposit.service.DepositPostingScheduler;
import com.chainpay.chain.deposit.service.DepositWriter;
import com.chainpay.chain.indexer.config.ChainReaders;
import com.chainpay.ledger.system.SystemLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 装配入账任务：只在 worker 里，跟着主节点装配（节点地址是 worker 的必填项）；收款 xpub 也必填，不是条件。
 * 测试基类把节点写成 "false"（@ConditionalOnProperty 视为不装配），所以测试里不装配，测试自己拿 FakeChain 造。
 */
@Configuration
@Profile("worker")
@ConditionalOnProperty(name = "chainpay.chain.rpc-url", matchIfMissing = true)
@EnableConfigurationProperties(DepositProperties.class)
class DepositPostingConfig {

    private static final Logger log = LoggerFactory.getLogger(DepositPostingConfig.class);

    @Bean
    DepositPoster depositPoster(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc, DepositWriter writer, ChainReaders readers,
                                DepositProperties properties) {
        log.info("入账任务已装配：每轮最多 {} 笔，两个节点都点头才记（{}）；finalized 落后 {} 块以内算「还没跟上」，超出才叫人",
                properties.batchSize(), readers.auditMode(), properties.finalityToleranceBlocks());
        return new DepositPoster(systemJdbc, writer, readers.primary(), readers.audit(), properties.batchSize(), properties.finalityToleranceBlocks());
    }

    @Bean
    DepositPostingScheduler depositPostingScheduler(DepositPoster poster) {
        return new DepositPostingScheduler(poster);
    }
}
