package com.chainpay.ops.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.chainpay.ChainpayApplication;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 真的应用（读真的 application.yml）：守卫在创建任何 bean 之前拦下。
 *
 * <p>两件事只有起真的应用才证明得了：
 * <ol>
 *   <li><b>守卫接进了应用</b>，而且跑在最前面：拒绝启动的根因必须是 {@link ProcessRoleException}，
 *       不是连接池、占位符或节点地址的报错。库地址故意指向一个连不上的端口——守卫漏了，应用会在那里撞墙报别的错，而不是去碰开发库。</li>
 *   <li><b>名单上的名字是真的</b>：运维设的是环境变量，应用读的是配置键，两者靠 application.yml 的占位符或 Spring 的宽松映射连起来。
 *       逐项设「运维真正会设的那个环境变量」，证明它真的会被认出来：禁用项被拦住，必填项不再报缺——名单上的名字拼错一个字，
 *       从名单派生的单元测试照样绿，那条规矩却永远不触发。</li>
 * </ol>
 * 不需要 Testcontainers：每次启动都停在守卫那一步。
 */
@DisplayName("真的应用：凭证放错，在碰任何连接之前就拒绝启动")
class ProcessRoleBootTest {

    private static final String SECRET = "value-that-must-never-appear-4b7d";

    static Stream<ProcessRole.Credential> webForbidden() {
        return ProcessRole.WEB.forbidden().stream();
    }

    static Stream<ProcessRole.Credential> workerForbidden() {
        return ProcessRole.WORKER.forbidden().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("webForbidden")
    @DisplayName("web 的环境里设了禁用名单上的环境变量：拒绝启动，根因是守卫，报错点名、不带值")
    void webRefusesToStart(ProcessRole.Credential c) {
        assertRefused(start(Map.of(c.envName(), SECRET), "web"), c.envName());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("workerForbidden")
    @DisplayName("worker 的环境里设了禁用名单上的环境变量：同样拒绝启动")
    void workerRefusesToStart(ProcessRole.Credential c) {
        assertRefused(start(Map.of(c.envName(), SECRET), "worker"), c.envName());
    }

    static Stream<ProcessRole.Requirement> workerRequired() {
        return ProcessRole.WORKER.required().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("workerRequired")
    @DisplayName("必填名单上的名字是真的：设了运维会设的那个环境变量，守卫就不再说它缺")
    void eachRequirementIsMetByItsRealEnvVar(ProcessRole.Requirement r) {
        // 另设一个禁用项，让守卫照样拦下（应用不往下走、不去连库），再看报错里还点不点它的名
        Throwable failure = start(Map.of(r.envName(), SECRET, "CHAINPAY_ADMIN_PASSWORD", SECRET), "worker");
        assertRefused(failure, "CHAINPAY_ADMIN_PASSWORD");
        assertThat(rootCause(failure)).hasMessageNotContaining(r.envName());
    }

    @Test
    @DisplayName("★ worker 没设节点地址和热钱包私钥：拒绝启动，根因是守卫，两个一次点名")
    void workerRefusesWithoutItsRequirements() {
        Throwable failure = start(Map.of(), "worker");
        assertRefused(failure, "CHAINPAY_CHAIN_RPC_URL");
        assertRefused(failure, "CHAINPAY_PAYOUT_HOT_WALLET_KEY");
    }

    @Test
    @DisplayName("★ 留空等于没设：同样在守卫这一步拒绝（@ConditionalOnProperty 把空串当「有」，以前留空会装配到一半才炸）")
    void blankIsMissing() {
        Throwable failure = start(Map.of("CHAINPAY_CHAIN_RPC_URL", "", "CHAINPAY_PAYOUT_HOT_WALLET_KEY", " "), "worker");
        assertRefused(failure, "CHAINPAY_CHAIN_RPC_URL");
        assertRefused(failure, "CHAINPAY_PAYOUT_HOT_WALLET_KEY");
    }

    @Test
    @DisplayName("没给角色：拒绝启动，报错点名 SPRING_PROFILES_ACTIVE")
    void refusesWithoutARole() {
        assertRefused(start(Map.of()), "SPRING_PROFILES_ACTIVE");
    }

    @Test
    @DisplayName("两个角色都给：拒绝启动")
    void refusesWithBothRoles() {
        assertRefused(start(Map.of(), "web", "worker"), "SPRING_PROFILES_ACTIVE");
    }

    private static void assertRefused(Throwable failure, String named) {
        assertThat(failure).as("应用不该起来").isNotNull();
        assertThat(rootCause(failure)).as("根因必须是守卫：别的报错说明守卫没接上，或者排在了创建 bean 之后")
                .isInstanceOf(ProcessRoleException.class)
                .hasMessageContaining(named)
                .hasMessageNotContaining(SECRET);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    /**
     * 起一次真的应用，系统环境整份换成给定的这几个变量：开发机 source 过 env/local.env 再跑测试时，
     * 真实的环境变量不能混进来（同 AbstractPostgresTest 的理由）。
     */
    private static Throwable start(Map<String, Object> envVars, String... profiles) {
        Map<String, Object> vars = new HashMap<>(envVars);
        vars.putIfAbsent("CHAINPAY_DB_URL", "jdbc:postgresql://localhost:1/role-guard-test");   // 守卫漏了也碰不到开发机上真在跑的库
        vars.putIfAbsent("CHAINPAY_REDIS_PORT", "1");                                          // 同理：开发机的 Redis 发布在 6380
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars));
        return catchThrowable(() -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(ChainpayApplication.class)
                    .environment(env)
                    .profiles(profiles)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .logStartupInfo(false)
                    .run()) {
                // 走到这里就是守卫没拦住：catchThrowable 返回 null，assertRefused 会报「应用不该起来」
            }
        });
    }
}
