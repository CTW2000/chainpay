package com.chainpay.chain.deposit.config;

import com.chainpay.chain.deposit.service.DepositPoster;
import com.chainpay.chain.deposit.service.DepositPostingScheduler;
import com.chainpay.chain.indexer.config.ChainReaders;
import com.chainpay.ledger.system.SystemLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配入账任务：同时设了 xpub（有收款地址）和主节点（有链可问）才装配。
 * 测试基类把 rpc-url 钉成 "false"（@ConditionalOnProperty 视为未开启），所以测试里不装配，测试自己拿 FakeChain 造。
 */
@Configuration
@ConditionalOnProperty({"chainpay.deposit.xpub", "chainpay.chain.rpc-url"})
class DepositPostingConfig {

    private static final Logger log = LoggerFactory.getLogger(DepositPostingConfig.class);

    @Bean
    DepositPoster depositPoster(SystemLedger system, ChainReaders readers, DepositProperties properties) {
        log.info("入账任务已装配：每轮最多 {} 笔，两个节点都点头才记（{}）", properties.batchSize(), readers.auditMode());
        return new DepositPoster(system, readers.primary(), readers.audit(), properties.batchSize());
    }

    @Bean
    DepositPostingScheduler depositPostingScheduler(DepositPoster poster) {
        return new DepositPostingScheduler(poster);
    }
}
