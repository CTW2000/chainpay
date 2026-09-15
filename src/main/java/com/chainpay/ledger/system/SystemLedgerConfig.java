package com.chainpay.ledger.system;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * 装配系统账本：同一个库（复用 spring.datasource.url），不同的身份。凭证见 {@link SystemDbProperties}。
 *
 * <p>系统池与系统事务管理器是容器里的 bean（2026-09-15 起），限定名 {@value SystemLedger#QUALIFIER}，而且都是
 * {@code defaultCandidate = false}：按类型注入 DataSource / 事务管理器 / JdbcClient 的地方拿不到它们，只有写明限定名才拿得到。
 * 于是主连接的自动配置不退让（Boot 4.1 实测：主数据源、主事务管理器、JdbcClient、TransactionTemplate 照常各一个），
 * 而 Boot 的 db 健康检查（组合项，子项 dataSource / systemDataSource）与 hikaricp.* 指标自动覆盖系统池。
 */
@Configuration
@EnableConfigurationProperties(SystemDbProperties.class)
class SystemLedgerConfig {

    @Bean(defaultCandidate = false)
    @Qualifier(SystemLedger.QUALIFIER)
    HikariDataSource systemDataSource(@Value("${spring.datasource.url}") String jdbcUrl, SystemDbProperties system) {
        return SystemLedger.pool(jdbcUrl, system.username(), system.password(), system.maximumPoolSize(), system.lockTimeout());
    }

    @Bean(defaultCandidate = false)
    @Qualifier(SystemLedger.QUALIFIER)
    JdbcTransactionManager systemTransactionManager(@Qualifier(SystemLedger.QUALIFIER) HikariDataSource systemDataSource) {
        return new JdbcTransactionManager(systemDataSource);
    }

    /** 池由 systemDataSource 这个 bean 自己关，这里不再关一次。 */
    @Bean(destroyMethod = "")
    @DependsOnDatabaseInitialization   // 启动判官要用 V22 的 ledger_judge()：先迁移，再自检。公开注解，不点名 Boot 内部的 bean 名
    SystemLedger systemLedger(@Qualifier(SystemLedger.QUALIFIER) HikariDataSource systemDataSource,
                              @Qualifier(SystemLedger.QUALIFIER) JdbcTransactionManager systemTransactionManager) {
        return SystemLedger.start(systemDataSource, systemTransactionManager);
    }
}
