package com.chainpay.ledger.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配系统账本：同一个库（复用 spring.datasource.url），不同的身份。凭证见 {@link SystemDbProperties}。 */
@Configuration
@EnableConfigurationProperties(SystemDbProperties.class)
class SystemLedgerConfig {

    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization   // 启动判官要用 V22 的 ledger_judge()：先迁移，再建系统池。公开注解，不点名 Boot 内部的 bean 名
    SystemLedger systemLedger(@Value("${spring.datasource.url}") String jdbcUrl, SystemDbProperties system) {
        return SystemLedger.connect(jdbcUrl, system.username(), system.password(), system.maximumPoolSize(), system.lockTimeout());
    }
}
