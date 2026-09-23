package com.chainpay.ops.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * 进程角色的判定（纯逻辑，不起容器）。
 * 真的应用里守卫是不是在任何 bean 之前生效，由 {@link ProcessRoleBootTest} 证明；禁用名单的内容对不对，由 {@code EnvInventoryTest} 拿变量清单对。
 */
@DisplayName("进程拆分 ① · 进程角色：恰好一个，凭证放错就不启动")
class ProcessRoleTest {

    /** 一个不会碰巧出现在报错里的值：报错里出现它，就是把密钥写进了日志。 */
    private static final String SECRET = "value-that-must-never-appear-9f2c";

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    @DisplayName("没有角色、只有别的 profile、两个角色都开：一律拒绝启动，报错点名 SPRING_PROFILES_ACTIVE")
    void exactlyOneRole() {
        assertThatThrownBy(() -> ProcessRole.resolve(env()))
                .isInstanceOf(ProcessRoleException.class).hasMessageContaining("SPRING_PROFILES_ACTIVE");
        assertThatThrownBy(() -> ProcessRole.resolve(env("test")))
                .isInstanceOf(ProcessRoleException.class);
        assertThatThrownBy(() -> ProcessRole.resolve(env("web", "worker")))
                .isInstanceOf(ProcessRoleException.class).hasMessageContaining("web").hasMessageContaining("worker");
    }

    @Test
    @DisplayName("恰好一个角色时认出它；别的 profile（测试用的 test）可以同时开")
    void resolvesTheSingleRole() {
        assertThat(ProcessRole.resolve(env("web"))).isEqualTo(ProcessRole.WEB);
        assertThat(ProcessRole.resolve(env("test", "worker"))).isEqualTo(ProcessRole.WORKER);
    }

    static Stream<ProcessRole.Credential> webForbidden() {
        return ProcessRole.WEB.forbidden().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("webForbidden")
    @DisplayName("web 禁用名单上的每一项：出现就拒绝启动，报错点名变量、不带值")
    void webRefusesEachForbiddenCredential(ProcessRole.Credential c) {
        MockEnvironment env = env("web").withProperty(c.property(), SECRET);
        assertThatThrownBy(() -> ProcessRole.resolve(env))
                .isInstanceOf(ProcessRoleException.class)
                .hasMessageContaining(c.envName())
                .hasMessageNotContaining(SECRET);
    }

    @Test
    @DisplayName("几个一起出现：一次全部点名，不让人改一个、重启、再撞下一个")
    void listsEveryForbiddenCredentialAtOnce() {
        MockEnvironment env = env("web")
                .withProperty("chainpay.payout.hot-wallet-key", SECRET)
                .withProperty("chainpay.chain.rpc-url", SECRET);
        assertThatThrownBy(() -> ProcessRole.resolve(env))
                .hasMessageContaining("CHAINPAY_PAYOUT_HOT_WALLET_KEY")
                .hasMessageContaining("CHAINPAY_CHAIN_RPC_URL")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    @DisplayName("「有没有」和 @ConditionalOnProperty 同一口径：空白、false、解析不出的占位符都算没配")
    void absentMeansBlankFalseOrUnresolvable() {
        assertThat(ProcessRole.isConfigured(new MockEnvironment(), "k")).isFalse();
        assertThat(ProcessRole.isConfigured(new MockEnvironment().withProperty("k", " "), "k")).isFalse();
        assertThat(ProcessRole.isConfigured(new MockEnvironment().withProperty("k", "false"), "k")).isFalse();
        assertThat(ProcessRole.isConfigured(new MockEnvironment().withProperty("k", "FALSE"), "k")).isFalse();
        assertThat(ProcessRole.isConfigured(new MockEnvironment().withProperty("k", "${NOT_SET_ANYWHERE}"), "k")).isFalse();
        assertThat(ProcessRole.isConfigured(new MockEnvironment().withProperty("k", "x"), "k")).isTrue();

        // application.yml 里告警地址默认空、测试基类把节点设成 "false"、系统口令是没有默认值的占位符：这些 web 都能起
        MockEnvironment web = env("web")
                .withProperty("chainpay.alert.webhook-url", "")
                .withProperty("chainpay.chain.rpc-url", "false")
                .withProperty("chainpay.system-db.password", "${CHAINPAY_SYSTEM_DB_PASSWORD}");
        assertThat(ProcessRole.resolve(web)).isEqualTo(ProcessRole.WEB);
    }

    @Test
    @DisplayName("worker 该拿的都能拿：系统角色、热钱包私钥、两个节点、告警地址、签名密钥、xpub（取舍 8：记账前要用它重新派生收款地址）")
    void workerKeepsItsKeys() {
        MockEnvironment env = env("worker")
                .withProperty("chainpay.system-db.password", SECRET)
                .withProperty("chainpay.payout.hot-wallet-key", SECRET)
                .withProperty("chainpay.chain.rpc-url", SECRET)
                .withProperty("chainpay.chain.audit-rpc-url", SECRET)
                .withProperty("chainpay.alert.webhook-url", SECRET)
                .withProperty("chainpay.secret-key", SECRET)
                .withProperty("chainpay.deposit.xpub", SECRET);
        assertThat(ProcessRole.resolve(env)).isEqualTo(ProcessRole.WORKER);
    }

    @Test
    @DisplayName("建管理员的口令只属于那一条一次性命令：两个常驻进程见到它都拒绝启动")
    void adminPasswordNeverInALongRunningProcess() {
        for (String role : List.of("web", "worker")) {
            MockEnvironment env = env(role).withProperty("chainpay.admin-password", SECRET);
            assertThatThrownBy(() -> ProcessRole.resolve(env))
                    .as(role)
                    .isInstanceOf(ProcessRoleException.class)
                    .hasMessageContaining("CHAINPAY_ADMIN_PASSWORD")
                    .hasMessageNotContaining(SECRET);
        }
    }
}
