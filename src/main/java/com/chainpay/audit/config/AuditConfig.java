package com.chainpay.audit.config;

import com.chainpay.audit.service.AuditScheduler;
import com.chainpay.audit.service.AuditService;
import com.chainpay.audit.service.AuditWriter;
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

/** 对账要问链上余额：只在 worker 里，跟着主节点装配。测试基类把节点写成 "false"（不装配），测试自己拿 FakeChain 造。 */
@Configuration
@Profile("worker")
@EnableConfigurationProperties(AuditProperties.class)
@ConditionalOnProperty(name = "chainpay.chain.rpc-url", matchIfMissing = true)
class AuditConfig {

    private static final Logger log = LoggerFactory.getLogger(AuditConfig.class);

    @Bean
    AuditService auditService(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc, AuditWriter writer, ChainReaders readers, AuditProperties p) {
        log.info("对账任务已装配：每 {} 一轮，宽限 {} 块，余额两个节点都问（{}）", p.interval(), p.lagBlocks(), readers.auditMode());
        return new AuditService(systemJdbc, writer, readers.primary(), readers.audit(), p.lagBlocks());
    }

    @Bean
    AuditScheduler auditScheduler(AuditService audit) {
        return new AuditScheduler(audit);
    }
}
