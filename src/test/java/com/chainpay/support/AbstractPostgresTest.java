package com.chainpay.support;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.Supplier;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.system.SystemLedger;
import com.chainpay.security.service.RateLimiter;
import com.chainpay.security.service.TenantScope;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 集成测试的基类：跑<b>真的 PostgreSQL 18</b>，不用 H2——H2 的 {@code NUMERIC} 语义、事务隔离、行锁
 * 都和 Postgres 不同，而这三处正是账本最要命的地方。在 H2 上通过，只证明「在 H2 上没错」。
 */
@SpringBootTest
// test：application.yml 之上叠 application-test.yml（只写差异）。
// worker：进程角色必须恰好一个；这个上下文装着全部任务与全部凭证（系统角色……），只有 worker 容得下。
// 装配按角色拆开之后（进程拆分第 ④ 步），基类跟着拆成 web 与 worker 两个
@ActiveProfiles({"test", "worker"})
public abstract class AbstractPostgresTest {

    /**
     * 单例容器：整个 JVM 只起一个，跑完所有测试类都不停。
     *
     * <p>不用 {@code @Testcontainers} + {@code @Container}：那套注解的 static 容器生命周期<b>按测试类</b>——
     * {@code beforeAll} 启动、{@code afterAll} 停止。先跑完的类把容器停掉，后跑的类拿到已停止的容器，
     * 报 {@code Failed to obtain JDBC Connection}；只有一个测试类时看不出来。
     *
     * <p>手工 {@code start()} 之后不要手工 {@code stop()}：Ryuk 伴生容器在 JVM 退出后回收。
     */
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18")
            // 和 docker-compose 用同一份初始化脚本建角色——一处定义，两处使用
            .withCopyFileToContainer(
                    MountableFile.forHostPath("db/init/01-roles.sql"),
                    "/docker-entrypoint-initdb.d/01-roles.sql");

    /** <b>真的 Redis</b>，理由同上：限流的正确性依赖 Lua 脚本在服务端<b>原子执行</b>，任何内存桩都模拟不了。 */
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:8.10");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /** 启动冒烟测试用：把索引器真的装配起来，指向本地假节点。别的测试永远不装配。 */
    protected static final String TEST_RPC_URL_PROPERTY = "chainpay.test.rpc-url";

    /**
     * <b>被测应用</b>以普通角色 chainpay_app 连库；<b>Flyway</b> 以容器属主跑迁移。
     *
     * <p>不用 {@code @ServiceConnection}：它会把容器的超级用户交给应用，而超级用户无条件绕过 RLS——
     * 忘了调 asMerchant 也看得到全库，测试照样全绿。应用必须以生产里真正用的那个角色跑。
     */
    @DynamicPropertySource
    static void connectAsTheRealAppRole(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "chainpay_app");
        r.add("spring.datasource.password", () -> "chainpay_app_dev");   // 同 db/init/01-roles.sql
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);

        // ★ 测试不能受开发机 shell 的影响 ★
        // source 过 env/local.env 再跑 mvn test，OS 环境变量（如 CHAINPAY_CHAIN_RPC_URL）的优先级高于
        // application-test.yml，索引器会被装配起来去打真节点。DynamicPropertySource 又高于环境变量，在这里钉死。
        r.add("chainpay.secret-key", () -> "Y2hhaW5wYXktdGVzdC1rZXktbm90LWZvci1wcm9kISE=");
        // 节点地址与热钱包私钥是 worker 的必填项；写成 "false" = 明确不装配（守卫放行，@ConditionalOnProperty 不装配）。
        // 测试里永远不装配真节点的索引器、不装配热钱包：要签名的测试自己拿 Hardhat 的公开私钥造签名器。
        // 唯一的例外是启动冒烟测试：它在类加载时把这个系统属性指向本地假节点，让容器真的装配一次索引器
        r.add("chainpay.chain.rpc-url", () -> System.getProperty(TEST_RPC_URL_PROPERTY, "false"));
        r.add("chainpay.payout.hot-wallet-key", () -> "false");
        r.add("chainpay.chain.audit-rpc-url", () -> "");
        r.add("chainpay.chain.start-block", () -> "");
        // 系统池以 chainpay_system 连同一个库（同 db/init/01-roles.sql）
        r.add("chainpay.system-db.username", () -> "chainpay_system");
        r.add("chainpay.system-db.password", () -> "chainpay_system_dev");
        r.add("chainpay.system-db.lock-timeout", () -> "1s");   // 测试里等锁 1 秒就够证明「会放弃」
        // 收款地址用 Hardhat 公开助记词的账户层 xpub（m/44'/60'/0'），派出的地址是公开常数，可当已知答案
        r.add("chainpay.deposit.xpub", () -> HARDHAT_ACCOUNT_XPUB);
    }

    /** Hardhat 默认助记词 test…junk 在 m/44'/60'/0' 的 xpub（tools/xpub.sh 算出，与 Hardhat 公布的前三个地址一致）。 */
    public static final String HARDHAT_ACCOUNT_XPUB =
            "xpub6Ce9NcJvTk36xtLSrJLZqE7wtgA5deCeYs7rSQtreh4cj6ByPtrg9sD7V2FNFLPnf8heNP3FGkeV9qwfzvZNSd54JoNXVsXFYSYwHsnJxqP";

    /**
     * 用属主连接在<b>另一个事务</b>里锁住一张表（EXCLUSIVE：挡写不挡读），模拟「别的事务正握着账本」。
     * 兜底 {@code autoRelease} 之后自动放锁：被测代码若没有 lock_timeout 会一直等，测试要能以失败结束而不是挂死。
     * 关闭返回值即放锁。
     */
    protected static AutoCloseable holdExclusiveLock(String table, Duration autoRelease) throws SQLException {
        Connection blocker = DriverManager.getConnection(jdbcUrl(), ownerUsername(), ownerPassword());
        blocker.setAutoCommit(false);
        try (Statement st = blocker.createStatement()) {
            st.execute("LOCK TABLE " + table + " IN EXCLUSIVE MODE");
        }
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(autoRelease.toMillis());
                blocker.rollback();
            } catch (Exception ignored) {
                // 测试已经先放锁并关了连接
            }
        }, "lock-auto-release");
        releaser.setDaemon(true);
        releaser.start();
        return () -> {
            try {
                blocker.rollback();
            } finally {
                blocker.close();
            }
        };
    }

    /** 容器的 JDBC 地址与属主凭证，给「换一个身份连库」的测试用。 */
    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    protected static String ownerUsername() {
        return POSTGRES.getUsername();
    }

    protected static String ownerPassword() {
        return POSTGRES.getPassword();
    }

    /**
     * <b>属主</b>连接，只给测试脚手架用（TRUNCATE、seed 数据），<b>不是</b>应用的连接：
     * seed 要绕过 RLS 写任意商户的行，只有属主做得到。需要「以应用身份跑 SQL」的测试自己注入 {@code JdbcClient}。
     */
    protected final JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

    /**
     * 系统角色的连接，只给判官用（不是容器里的系统池）：{@code ledger_judge()} 只在能看到全部行的身份下给结论。
     * 不用属主连接——它能读通只因为 Testcontainers 的属主恰好是超级用户。
     */
    protected final JdbcClient judgeJdbc = JdbcClient.create(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), "chainpay_system", "chainpay_system_dev"));

    @Autowired
    protected RateLimiter rateLimiter;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    /** 容器里限定名 system 的 SQL 客户端、账本与事务管理器：系统任务与它们的写入类用的就是这三样，测试直接拿来用。 */
    @Autowired
    @Qualifier(SystemLedger.QUALIFIER)
    protected JdbcClient systemJdbc;

    @Autowired
    @Qualifier(SystemLedger.QUALIFIER)
    protected LedgerService systemLedgerService;

    @Autowired
    @Qualifier(SystemLedger.QUALIFIER)
    protected PlatformTransactionManager systemTransactionManager;

    /** 在一个系统事务里做一件事：测试要自己开 system 事务时用（生产代码写 {@code @Transactional("system")}）。回调抛出 = 整体回滚。 */
    protected <T> T inSystemTransaction(Supplier<T> work) {
        return new TransactionTemplate(systemTransactionManager).execute(status -> work.get());
    }

    /** 控制面的门是管理员会话。测试里直接用服务建用户、登录拿令牌，不走 HTTP（HTTP 那条路由由 AdminAuthApiTest 验）。 */
    @Autowired
    protected com.chainpay.admin.service.AdminAuthService adminAuth;

    /** 测试专用管理员（不存在就建）登录一次，返回会话令牌。登录那一刻算再认证过，敏感操作 5 分钟内放行。 */
    protected String adminSessionToken() {
        return adminSessionToken("ops", "ops-test-password-123!");
    }

    protected String adminSessionToken(String username, String password) {
        if (jdbc.sql("SELECT count(*) FROM admin_user WHERE username = :u").param("u", username).query(Long.class).single() == 0) {
            adminAuth.createUser(username, password);
        }
        return adminAuth.login(username, password, "127.0.0.1").token();
    }

    /** 应用角色的 JdbcClient（RLS 生效的那条连接）：给要手工 new 服务的测试用。 */
    protected org.springframework.jdbc.core.simple.JdbcClient jdbcOfApp() {
        return appJdbc;
    }

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient appJdbc;

    @Autowired
    protected TenantScope tenantScope;

    /**
     * 给直接调账本的测试用的、<b>已进入系统作用域</b>的账本（见 {@link SystemScopedLedger}）。
     * 走 HTTP 的测试不用它——它们的作用域由控制器决定。
     */
    protected LedgerService ledger;

    /**
     * 每个测试方法前清空数据与限流计数。
     *
     * <p><b>限流器必须一起重置</b>：它是单例 bean，计数跨测试累积。不重置的话，后跑的测试会因为
     * 前面的测试用掉了配额而莫名返回 429——而且<b>取决于执行顺序</b>：单独跑绿、一起跑红。
     */
    @BeforeEach
    void resetLedger() {
        ledger = new SystemScopedLedger(systemTransactionManager, systemLedgerService);
        jdbc.sql("TRUNCATE entry, transfer, account RESTART IDENTITY CASCADE").update();
        // 计数主要存在 Redis 里，只清本地等于没清
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        rateLimiter.resetAll();
    }

    /**
     * 每个测试方法结束后，三个判官<b>自动</b>核账一次。
     *
     * <p>放在 {@code @AfterEach} 而不是让每个测试自己写断言：一个从不被传唤的判官等于不存在，
     * 现有测试和将来所有新测试都自动被核一遍。<b>能靠结构保证的，不要靠纪律保证。</b>
     *
     * <p><b>但「自动跑」不等于「判了东西」：</b>不碰账本的测试（限流、凭证、体积上限……）结束时 entry 表是空的，
     * 断言 0 == 0 空洞地为真。判官真正守住的是碰了账本的那些测试；它们在多币种下仍然会响，
     * 由 {@code LedgerModelingTest.invariantJudgeWorksAcrossMultipleCurrencies} 单独证明。
     */
    @AfterEach
    void ledgerMustStayConsistent() {
        assertThat(invariantViolations())
                .as("账本内部必须平：每种币的分录之和恒为 0")
                .isZero();
        assertThat(balanceDrift())
                .as("物化余额必须等于分录求和：account.balance 一旦漂移，余额检查就建立在假数据上")
                .isZero();
        assertThat(illegalNegativeBalances())
                .as("allow_negative=false 的账户余额永远不能为负")
                .isZero();
    }

    /** 建一个普通账户（余额不得为负），返回它的 id。 */
    protected long createAccount(String code, String currency, String kind) {
        return createAccount(code, currency, kind, false);
    }

    /**
     * 建账户。
     *
     * @param allowNegative 是否允许余额为负。只有「资金来源」账户该是 true ——
     *                      复式记账里钱不能凭空出现，注资时它是那个变负的对手方
     */
    protected long createAccount(String code, String currency, String kind, boolean allowNegative) {
        return jdbc.sql("""
                        INSERT INTO account (code, currency, kind, allow_negative)
                        VALUES (:code, :currency, :kind, :allowNegative)
                        RETURNING id
                        """)
                .param("code", code)
                .param("currency", currency)
                .param("kind", kind)
                .param("allowNegative", allowNegative)
                .query(Long.class)
                .single();
    }

    /** 核心不变量：每种币的分录金额之和恒为 0。返回违反的币种数，任何时刻都必须是 0。 */
    protected long invariantViolations() {
        return judgeJdbc.sql("SELECT COUNT(*) FROM ledger_judge() WHERE check_name = 'ledger_invariant'")
                .query(Long.class)
                .single();
    }

    /** 有多少个「不该为负」的账户余额为负。任何时刻都必须是 0。 */
    protected long illegalNegativeBalances() {
        return judgeJdbc.sql("SELECT COUNT(*) FROM ledger_judge() WHERE check_name = 'negative_balance'")
                .query(Long.class)
                .single();
    }

    /**
     * 有多少个账户的<b>物化余额</b>和<b>分录求和</b>对不上。任何时刻都必须是 0。
     * 物化一个值，就是签下「永远和事实一致」的合约，合约要有人监督。
     *
     * <p>和 {@link #invariantViolations()} 管两件事：那个管账本内部平不平，这个管缓存的余额和事实对不对得上。
     * 两个都为 0，账才既平又准。
     */
    protected long balanceDrift() {
        return judgeJdbc.sql("SELECT COUNT(*) FROM ledger_judge() WHERE check_name = 'balance_consistency'")
                .query(Long.class)
                .single();
    }

    protected long transferCount() {
        return jdbc.sql("SELECT COUNT(*) FROM transfer").query(Long.class).single();
    }
}
