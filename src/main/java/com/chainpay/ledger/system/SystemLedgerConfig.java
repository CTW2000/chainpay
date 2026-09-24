package com.chainpay.ledger.system;

import com.chainpay.ledger.service.LedgerService;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * 装配系统账本：同一个库（复用 spring.datasource.url），不同的身份。凭证见 {@link SystemDbProperties}。
 *
 * <p>系统池、系统事务管理器、系统连接上的 JdbcClient、绑在它上面的账本（{@link SystemLedgerService}）都是限定名
 * {@value SystemLedger#QUALIFIER} 的 bean，而且都是 {@code defaultCandidate = false}：按类型注入 DataSource / 事务管理器 /
 * JdbcClient / LedgerService 的地方拿不到它们，只有写明限定名才拿得到。于是主连接的自动配置照常各建一份，
 * Boot 的 db 健康检查与 hikaricp.* 指标也自动覆盖系统池。系统侧的写法：注入后两个 bean，方法上写 {@code @Transactional("system")}。
 *
 * <p><b>四个 {@code defaultCandidate = false} 都是承重墙：</b>去掉池上的，Boot 对主数据源的自动配置整体退让，
 * 应用侧的 JdbcClient 悄悄连成系统身份（带 BYPASSRLS，读会绕过租户隔离）；去掉事务管理器上的，
 * {@code asMerchant} 的事务开在系统池上，租户变量设不上；去掉 JdbcClient 上的，Boot 自动配置的主 JdbcClient 退让，
 * 按类型注入的地方全都悄悄拿到系统身份，应用照常启动；去掉账本上的，两个候选撞车、应用起不来。SystemPoolBeansTest 抓得住这四种。
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

    /** 系统连接上的 SQL 客户端：等锁超时翻成瞬时异常（{@link SystemLedger#jdbcClient}）。 */
    @Bean(defaultCandidate = false)
    @Qualifier(SystemLedger.QUALIFIER)
    JdbcClient systemJdbcClient(@Qualifier(SystemLedger.QUALIFIER) HikariDataSource systemDataSource) {
        return SystemLedger.jdbcClient(systemDataSource);
    }

    /** 绑在系统连接上的账本：转账必须已经在 system 事务里（{@link SystemLedgerService}）。 */
    @Bean(defaultCandidate = false)
    @Qualifier(SystemLedger.QUALIFIER)
    LedgerService systemLedgerService(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbcClient) {
        return new SystemLedgerService(systemJdbcClient);
    }

    @Bean
    @DependsOnDatabaseInitialization   // 启动判官要用 V22 的 ledger_judge()：先迁移，再自检。公开注解，不点名 Boot 内部的 bean 名
    SystemLedger systemLedger(@Qualifier(SystemLedger.QUALIFIER) HikariDataSource systemDataSource,
                              @Qualifier(SystemLedger.QUALIFIER) JdbcTransactionManager systemTransactionManager) {
        return SystemLedger.start(systemDataSource, systemTransactionManager);
    }
}
