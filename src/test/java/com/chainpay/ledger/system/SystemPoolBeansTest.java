package com.chainpay.ledger.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.ledger.service.LedgerException;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.ledger.service.LedgerServiceImpl;
import com.chainpay.support.AbstractPostgresTest;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 系统身份的四个 bean（池、事务管理器、JdbcClient、账本）都在容器里（官方双数据源的形状）。这几件事必须同时成立：
 * <ol>
 *   <li>它们是「非默认候选」：按类型注入 DataSource / 事务管理器 / JdbcClient / LedgerService 的地方永远拿到主池那一套，主连接的自动配置不退让；</li>
 *   <li>系统侧的写入加入容器里 system 的事务：{@code @Transactional("system")} 里调 inTransaction 或系统 JdbcClient，外层失败一起回滚；</li>
 *   <li>系统账本的转账必须在 system 事务里：事务外、主池事务里都当场拒绝。</li>
 * </ol>
 * 限定名在测试里写成字面量 "system"：它是对外的约定，改名要让这里红。
 */
@SpringBootTest
@DisplayName("系统池与系统事务管理器是容器里的 bean")
class SystemPoolBeansTest extends AbstractPostgresTest {

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        Probe systemTransactionalProbe(SystemLedger system, @Qualifier("system") JdbcClient systemJdbc,
                                       @Qualifier("system") LedgerService systemLedgerService) {
            return new Probe(system, systemJdbc, systemLedgerService);
        }
    }

    /** 一个贴 {@code @Transactional("system")} 的 bean：证明注解和 inTransaction 能叠在同一个事务里。 */
    static class Probe {
        private final SystemLedger system;
        private final JdbcClient systemJdbc;
        private final LedgerService systemLedgerService;

        Probe(SystemLedger system, JdbcClient systemJdbc, LedgerService systemLedgerService) {
            this.system = system;
            this.systemJdbc = systemJdbc;
            this.systemLedgerService = systemLedgerService;
        }

        @Transactional("system")
        public String connectionSeenByBoth(DataSource systemPool) {
            Object outer = TransactionSynchronizationManager.getResource(systemPool);
            Object inner = system.inTransaction(s -> TransactionSynchronizationManager.getResource(systemPool));
            return outer != null && outer == inner ? "同一个" : "不同：外层 " + outer + " / 回调 " + inner;
        }

        /** 不带限定名的 @Transactional：必须落在主池上，和系统池毫无关系（Spring 6.2 起按 defaultCandidate 选默认事务管理器，这里直接钉住而不靠间接证据）。 */
        @Transactional
        public String unqualifiedTransactionUsesTheMainPool(DataSource mainPool, DataSource systemPool) {
            boolean onMain = TransactionSynchronizationManager.getResource(mainPool) != null;
            boolean onSystem = TransactionSynchronizationManager.getResource(systemPool) != null;
            return onMain && !onSystem ? "主池" : "主池=" + onMain + " 系统池=" + onSystem;
        }

        @Transactional("system")
        public void writeThroughLedgerThenFail() {
            system.inTransaction(s -> s.jdbc().sql("INSERT INTO audit_run (started_at, status, detail) VALUES (now(), 'OK', 'system-pool-beans-test')").update());
            throw new IllegalStateException("外层失败");
        }

        /** 容器里的系统 JdbcClient 同样加入外层的 system 事务。 */
        @Transactional("system")
        public void writeThroughSystemJdbcThenFail() {
            systemJdbc.sql("INSERT INTO audit_run (started_at, status, detail) VALUES (now(), 'OK', 'system-pool-beans-test')").update();
            throw new IllegalStateException("外层失败");
        }

        /** 主池的事务不算：系统账本的 MANDATORY 只认 system 的事务管理器。 */
        @Transactional
        public long transferInsideAMainPoolTransaction(TransferCommand command) {
            return systemLedgerService.transfer(command);
        }

        @Transactional("system")
        public long transferInsideASystemTransaction(TransferCommand command) {
            return systemLedgerService.transfer(command);
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSourceByType;

    @Autowired
    private PlatformTransactionManager transactionManagerByType;

    @Autowired
    @Qualifier("system")
    private DataSource systemPool;

    @Autowired
    @Qualifier("system")
    private PlatformTransactionManager systemTransactionManager;

    @Autowired
    private Probe probe;

    @Autowired
    private JdbcClient jdbcClientByType;

    @Autowired
    @Qualifier("system")
    private JdbcClient systemJdbc;

    @Autowired
    private LedgerService ledgerByType;

    @Autowired
    @Qualifier("system")
    private LedgerService systemLedgerService;

    @Test
    @DisplayName("★ 系统池是容器里的 bean、以 chainpay_system 登录；但不是默认候选：按类型注入拿到的仍是主池与主池的事务管理器")
    void systemPoolIsABeanButNeverTheDefault() throws Exception {
        assertThat(context.getBeansOfType(DataSource.class)).containsKeys("dataSource", "systemDataSource");
        assertThat(((HikariDataSource) systemPool).getPoolName()).isEqualTo("chainpay-system");
        try (Connection c = systemPool.getConnection()) {
            assertThat(c.getMetaData().getUserName()).isEqualTo("chainpay_system");
        }
        assertThat(dataSourceByType).as("按类型注入不能拿到系统池：拿到了就等于谁都能绕过 RLS").isNotSameAs(systemPool);
        try (Connection c = dataSourceByType.getConnection()) {
            assertThat(c.getMetaData().getUserName()).isEqualTo("chainpay_app");
        }
        assertThat(transactionManagerByType).as("没写限定名的 @Transactional 必须落在主池上").isNotSameAs(systemTransactionManager);
    }

    @Test
    @DisplayName("★ 系统账本的事务就是容器里 system 的事务管理器：@Transactional(\"system\") 里调 inTransaction，看到的是同一条连接")
    void ledgerTransactionsJoinTheContainerManagedSystemTransaction() {
        assertThat(probe.connectionSeenByBoth(systemPool)).isEqualTo("同一个");
    }

    @Test
    @DisplayName("★ 不带限定名的 @Transactional 落在主池上：事务里主池有绑定的连接、系统池没有")
    void unqualifiedTransactionalRunsOnTheMainPool() {
        assertThat(probe.unqualifiedTransactionUsesTheMainPool(dataSourceByType, systemPool)).isEqualTo("主池");
    }

    @Test
    @DisplayName("★ 外层 @Transactional(\"system\") 失败，账本回调里写的行一起回滚")
    void outerSystemTransactionRollsBackTheLedgerWrite() {
        String count = "SELECT count(*) FROM audit_run WHERE detail = 'system-pool-beans-test'";
        long before = jdbc.sql(count).query(Long.class).single();
        assertThatThrownBy(probe::writeThroughLedgerThenFail).hasMessage("外层失败");
        assertThat(jdbc.sql(count).query(Long.class).single()).as("回调里插的那一行必须随外层事务回滚").isEqualTo(before);
    }

    @Test
    @DisplayName("★ 系统连接上的 JdbcClient 与账本也是限定名 system 的 bean，但不是默认候选：按类型注入拿到的仍是主池那一套")
    void systemJdbcClientAndLedgerAreBeansButNeverTheDefault() {
        assertThat(systemJdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("chainpay_system");
        assertThat(jdbcClientByType.sql("SELECT current_user").query(String.class).single())
                .as("按类型注入的 JdbcClient 必须是应用角色：拿到系统身份就等于绕过租户隔离").isEqualTo("chainpay_app");
        assertThat(AopUtils.getTargetClass(ledgerByType)).as("按类型注入的账本必须是主池那个").isEqualTo(LedgerServiceImpl.class);
        assertThat(AopUtils.getTargetClass(systemLedgerService)).isEqualTo(SystemLedgerService.class);
    }

    @Test
    @DisplayName("★ 外层 @Transactional(\"system\") 失败，容器里的系统 JdbcClient 写的行一起回滚")
    void outerSystemTransactionRollsBackTheSystemJdbcWrite() {
        String count = "SELECT count(*) FROM audit_run WHERE detail = 'system-pool-beans-test'";
        long before = jdbc.sql(count).query(Long.class).single();
        assertThatThrownBy(probe::writeThroughSystemJdbcThenFail).hasMessage("外层失败");
        assertThat(jdbc.sql(count).query(Long.class).single()).as("系统 JdbcClient 插的那一行必须随外层事务回滚").isEqualTo(before);
    }

    @Test
    @DisplayName("★ 系统账本的转账必须在 system 事务里：事务外、主池事务里都当场拒绝；system 事务里才进到账本")
    void systemLedgerTransferRequiresASystemTransaction() {
        TransferCommand zero = new TransferCommand("system-pool-beans-test", "USDT", BigDecimal.ZERO, 1, 2, TransferCode.INTERNAL, null);
        assertThatThrownBy(() -> systemLedgerService.transfer(zero))
                .as("事务外：否则系统连接上的几条 SQL 各自提交").isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> probe.transferInsideAMainPoolTransaction(zero))
                .as("主池事务不算").isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> probe.transferInsideASystemTransaction(zero))
                .as("system 事务里放行，进到账本，由它自己的校验拒绝 0 金额").isInstanceOf(LedgerException.class);
    }
}
