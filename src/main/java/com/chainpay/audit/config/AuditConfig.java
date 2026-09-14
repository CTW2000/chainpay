package com.chainpay.audit.config;

import com.chainpay.audit.service.AuditScheduler;
import com.chainpay.audit.service.AuditService;
import com.chainpay.chain.indexer.config.ChainReaders;
import com.chainpay.ledger.system.SystemLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 有主节点才装配对账（要问链上余额）。测试基类把 rpc-url 钉成 "false"，测试自己拿 FakeChain 造。 */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
@ConditionalOnProperty("chainpay.chain.rpc-url")
class AuditConfig {

    private static final Logger log = LoggerFactory.getLogger(AuditConfig.class);

    @Bean
    AuditService auditService(SystemLedger system, ChainReaders readers, AuditProperties p) {
        log.info("对账任务已装配：每 {} 一轮，宽限 {} 块，余额两个节点都问（{}）", p.interval(), p.lagBlocks(), readers.auditMode());
        return new AuditService(system, readers.primary(), readers.audit(), p.lagBlocks());
    }

    @Bean
    AuditScheduler auditScheduler(AuditService audit) {
        return new AuditScheduler(audit);
    }
}
