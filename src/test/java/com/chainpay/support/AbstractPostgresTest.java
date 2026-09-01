package com.chainpay.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.security.service.RateLimiter;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 所有账本测试的基类：跑<b>真的 PostgreSQL 18</b>，不用 H2。
 *
 * <p>为什么不用 H2：H2 的 {@code NUMERIC} 语义、事务隔离级别、行锁行为
 * 和 Postgres 都不一样 —— 而这三处正好是本项目最要命的地方。
 * 用 H2 测试通过，只能证明「在 H2 上没错」。
 *
 * <p>容器是 {@code static} 的，整个测试类共享一个，不会每个方法起一次。
 */
@SpringBootTest
public abstract class AbstractPostgresTest {

    /**
     * 单例容器：整个 JVM 只起一个，跑完所有测试类都不停。
     *
     * <p><b>为什么不用 {@code @Testcontainers} + {@code @Container}：</b>
     * 那套注解的 static 容器生命周期是<b>按测试类</b>的 —— 每个类
     * {@code beforeAll} 启动、{@code afterAll} 停止。只有一个测试类时看不出问题；
     * 加第二个类的那一刻，先跑完的类会把容器停掉，后跑的类拿到一个已停止的容器，
     * 报 {@code Failed to obtain JDBC Connection}。
     *
     * <p>这个坑是加 {@code LedgerModelingTest} 时真实踩到的：新测试全绿，
     * <b>却把原本全绿的 4 个测试弄挂了</b>。
     *
     * <p>手工 {@code start()} 之后不需要也不应该手工 {@code stop()} ——
     * Testcontainers 的 Ryuk 伴生容器会在 JVM 退出后回收。
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    /**
     * 测试跑<b>真的 Redis</b>，理由和真 Postgres 一样。
     *
     * <p>限流的正确性依赖 Lua 脚本的<b>原子执行</b> ——
     * 那是 Redis 服务端的行为，任何内存桩都模拟不了。
     * 用桩测出来的「通过」不能证明真 Redis 上也通过。
     */
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:8.10");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    /** 限流器是单例，计数跨测试累积，必须在每个测试前重置。 */
    @Autowired
    protected RateLimiter rateLimiter;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    /**
     * 每个测试方法前清空数据与限流计数，保证测试之间互不影响。
     *
     * <p><b>限流器必须一起重置</b>：它是单例 bean，计数会跨测试累积。
     * 不重置的话，跑在后面的测试会因为「前面的测试已经把配额用掉了」
     * 而莫名其妙地返回 429 —— 而且这种失败<b>取决于测试执行顺序</b>，
     * 单独跑绿、一起跑红，是最难查的一类测试问题。
     */
    @BeforeEach
    void resetLedger() {
        jdbc.sql("TRUNCATE entry, transfer, account RESTART IDENTITY CASCADE").update();
        // Redis 里的计数也要清 —— 现在计数主要存在那里，只清本地等于没清。
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        rateLimiter.resetAll();
    }

    /**
     * 每个测试方法结束后，三个判官<b>自动</b>核账一次。
     *
     * <p><b>为什么放在这里，而不是让每个测试自己写断言：</b>
     *
     * <p>V2 引入 {@code balance_consistency} 视图和 {@link #balanceDrift()} 之后，
     * helper 写好了、注释写清楚了、视图也建了 —— <b>但没有任何一个测试调用它</b>。
     * 一个从不被传唤的判官等于不存在。
     *
     * <p>这正是本项目已经付过学费的形状：flow-pay 的 {@code 4acbfb2}（权限被静默清空）
     * 有一个能抓住它的 e2e 用例，那个用例写对了、也能跑，但从不在 CI 执行 ——
     * <b>测试的价值 = 抓 bug 的能力 × 被运行的频率，后者为 0 时前者再高也是 0。</b>
     *
     * <p>所以修法不是"给现有的四个测试各加一行"（那还是靠纪律，而纪律刚失效过），
     * 而是让核账<b>自动发生</b>：放进 {@code @AfterEach}，现有测试和将来所有新测试
     * 都自动被守护，想漏都漏不掉。
     *
     * <p><b>能靠结构保证的，不要靠纪律保证。</b>
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

    /**
     * 核心不变量：每种币的分录金额之和必须恒为 0。
     *
     * <p>返回违反不变量的币种数。任何时刻调用都必须是 0。
     */
    protected long invariantViolations() {
        return jdbc.sql("SELECT COUNT(*) FROM ledger_invariant WHERE total <> 0")
                .query(Long.class)
                .single();
    }

    /** 有多少个「不该为负」的账户余额为负。任何时刻都必须是 0。 */
    protected long illegalNegativeBalances() {
        return jdbc.sql("SELECT COUNT(*) FROM account_balance WHERE balance < 0 AND NOT allow_negative")
                .query(Long.class)
                .single();
    }

    /**
     * 有多少个账户的<b>物化余额</b>和<b>分录求和</b>对不上。任何时刻都必须是 0。
     *
     * <p>这是 V2 引入 {@code account.balance} 列之后新增的判官。
     * 物化一个值就等于签下「永远和事实保持一致」的合约，而合约需要有人监督。
     *
     * <p>它和 {@link #invariantViolations()} 管的是两件不同的事：
     * <ul>
     *   <li>{@code invariantViolations} —— 账本内部平不平（每种币分录之和为 0）</li>
     *   <li>{@code balanceDrift}        —— 缓存的余额和事实对不对得上</li>
     * </ul>
     * 两个都为 0，账才既平又准。
     */
    protected long balanceDrift() {
        return jdbc.sql("SELECT COUNT(*) FROM balance_consistency WHERE stored <> computed")
                .query(Long.class)
                .single();
    }

    /** transfer 表里的记录条数。 */
    protected long transferCount() {
        return jdbc.sql("SELECT COUNT(*) FROM transfer").query(Long.class).single();
    }
}
