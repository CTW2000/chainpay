package com.chainpay.ops.role;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.wallet.HotWalletSigner;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.util.ClassUtils;

/**
 * 只属于 worker 的装配类：装配条件看节点地址或热钱包私钥的，全部扫出来过一遍。
 *
 * <p>三条规矩：web 一个都不装配（它不许拿这两把钥匙）；worker 里两项必填，没配、留空都拒绝启动，报错点名变量、不带值；
 * 只有字面值 false 才不装配（测试基类用它关掉这些模块）。
 * 真的应用里，没配、留空的在守卫（{@link ProcessRole}）那一步就被拦下；这里不带守卫，证明装配类自己也不会「没配就悄悄不装配」。
 *
 * <p>读真的 application.yml（链名、合约地址这些非密钥配置），但拿掉操作系统环境：开发机 source 过 env/local.env 再跑测试，
 * 真的节点地址和私钥不能混进来（同 AbstractPostgresTest 的理由）。
 */
@DisplayName("只属于 worker 的装配：web 不装配；worker 里节点与热钱包必填，只有 false 才不装配")
class WorkerOnlyAssemblyTest {

    private static final String RPC_URL = "chainpay.chain.rpc-url";
    private static final String HOT_WALLET_KEY = "chainpay.payout.hot-wallet-key";

    /** Hardhat 默认助记词第 0 个账户：公开的测试私钥（tools/check-secrets.allow 放行）与它的地址。 */
    private static final String HARDHAT_KEY_0 = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final String HARDHAT_ADDRESS_0 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    /** 装配条件看这两个键的配置类。扫出来而不是手写：新加一个也逃不掉下面几条。 */
    private static final List<Class<?>> MODULES = scan();

    @Test
    @DisplayName("扫出来的正是这五个：索引、入账、对账、热钱包、发送与追踪（新加一个，这里先红：想清楚它在 web 里装不装）")
    void theModulesAreTheseFive() {
        assertThat(MODULES).extracting(Class::getSimpleName).containsExactlyInAnyOrder(
                "ChainIndexerConfig", "DepositPostingConfig", "AuditConfig", "PayoutConfig", "PayoutSendConfig");
    }

    @Test
    @DisplayName("web：一个都不装配，也不因为没配而拒绝启动——这两把钥匙它本来就不许拿")
    void webAssemblesNone() {
        runner("web", MODULES).run(context -> {
            assertThat(context).hasNotFailed();
            MODULES.forEach(module -> assertThat(context).doesNotHaveBean(module));
        });
    }

    @Test
    @DisplayName("worker 里两项都写成 false：一个都不装配（测试基类就这样关掉它们）")
    void falseSwitchesThemOff() {
        runner("worker", MODULES).withPropertyValues(RPC_URL + "=false", HOT_WALLET_KEY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            MODULES.forEach(module -> assertThat(context).doesNotHaveBean(module));
        });
    }

    @Test
    @DisplayName("★ worker 没配节点地址、或者留空：拒绝启动，报错点名 CHAINPAY_CHAIN_RPC_URL（以前没配是悄悄不装配索引器）")
    void workerRefusesWithoutANode() {
        ApplicationContextRunner indexer = runner("worker", List.of(module("ChainIndexerConfig")));
        indexer.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("CHAINPAY_CHAIN_RPC_URL"));
        indexer.withPropertyValues(RPC_URL + "=").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("CHAINPAY_CHAIN_RPC_URL"));
    }

    @Test
    @DisplayName("★ worker 没配热钱包私钥、留空、或者形状不对：拒绝启动，报错点名 CHAINPAY_PAYOUT_HOT_WALLET_KEY、不带值")
    void workerRefusesWithoutAHotWalletKey() {
        ApplicationContextRunner payout = runner("worker", List.of(module("PayoutConfig")));
        payout.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("CHAINPAY_PAYOUT_HOT_WALLET_KEY"));
        payout.withPropertyValues(HOT_WALLET_KEY + "=").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("CHAINPAY_PAYOUT_HOT_WALLET_KEY"));
        payout.withPropertyValues(HOT_WALLET_KEY + "=0x1234").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("CHAINPAY_PAYOUT_HOT_WALLET_KEY").hasMessageNotContaining("0x1234"));
    }

    @Test
    @DisplayName("worker 配了热钱包私钥：装配出签名器，地址对得上")
    void workerAssemblesTheHotWallet() {
        runner("worker", List.of(module("PayoutConfig"))).withPropertyValues(HOT_WALLET_KEY + "=" + HARDHAT_KEY_0).run(context -> {
            assertThat(context).hasSingleBean(HotWalletSigner.class);
            assertThat(context.getBean(HotWalletSigner.class).address()).isEqualToIgnoringCase(HARDHAT_ADDRESS_0);
        });
    }

    /** 以某个角色起一个只含这些配置类的容器：读真的 application.yml，不读操作系统环境。 */
    private static ApplicationContextRunner runner(String role, List<Class<?>> configs) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().setActiveProfiles(role);
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(configs.toArray(Class<?>[]::new));
    }

    private static Class<?> module(String simpleName) {
        return MODULES.stream().filter(m -> m.getSimpleName().equals(simpleName)).findFirst().orElseThrow();
    }

    private static List<Class<?>> scan() {
        // 只看注解，不评估条件：扫描器默认拿当前环境去算 @ConditionalOnProperty，没配节点就把这几个类全跳过，扫出一个空名单
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) {
                MergedAnnotation<ConditionalOnProperty> condition =
                        reader.getAnnotationMetadata().getAnnotations().get(ConditionalOnProperty.class);
                return condition.isPresent() && keys(condition).stream().anyMatch(k -> k.equals(RPC_URL) || k.equals(HOT_WALLET_KEY));
            }
        };
        return scanner.findCandidateComponents("com.chainpay").stream()
                .<Class<?>>map(definition -> ClassUtils.resolveClassName(definition.getBeanClassName(), null))
                .toList();
    }

    /** 条件看的完整键：prefix 与 value / name 拼起来（这两个属性不是 @AliasFor 别名，条件类自己二选一，所以两个都读）。 */
    private static List<String> keys(MergedAnnotation<ConditionalOnProperty> condition) {
        String prefix = condition.getString("prefix");
        return Stream.concat(Arrays.stream(condition.getStringArray("value")), Arrays.stream(condition.getStringArray("name")))
                .map(name -> prefix.isEmpty() ? name : prefix + "." + name)
                .toList();
    }
}
