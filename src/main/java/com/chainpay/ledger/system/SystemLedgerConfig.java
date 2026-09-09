package com.chainpay.ledger.system;

import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配系统账本：同一个库（复用 spring.datasource.url），不同的身份。凭证见 {@link SystemDbProperties}。 */
@Configuration
@EnableConfigurationProperties(SystemDbProperties.class)
class SystemLedgerConfig {

    @Bean(destroyMethod = "close")
    @DependsOn("flywayInitializer")   // 启动判官要用 V22 的 ledger_judge()：先迁移，再建系统池
    SystemLedger systemLedger(@Value("${spring.datasource.url}") String jdbcUrl, SystemDbProperties system) {
        return SystemLedger.connect(jdbcUrl, system.username(), system.password(), system.maximumPoolSize(), system.lockTimeout());
    }
}
