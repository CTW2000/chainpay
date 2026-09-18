package com.chainpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 入口的分派（2026-09-18）：一个 jar 三种用法，后两种是一次性命令——跑完交出退出码就结束，绝不能顺手起完整应用。
 *
 * <p>{@link ChainpayApplication#oneShot} 只决定、只跑那一件事，迁移与建管理员换成计数的假动作，不连库；
 * 最后一条另起一个 JVM 真跑 {@code main}，守的是那行 {@code System.exit}——它在测试进程里调不得。
 */
@DisplayName("入口 · 一次性模式的分派")
class ChainpayApplicationTest {

    private final AtomicInteger migrations = new AtomicInteger();
    private final AtomicInteger admins = new AtomicInteger();
    private final IntSupplier migrate = () -> {
        migrations.incrementAndGet();
        return 7;
    };
    private final IntSupplier createAdmin = () -> {
        admins.incrementAndGet();
        return 9;
    };

    @Test
    @DisplayName("★ --migrate-only：只跑迁移，它的退出码原样交出去（部署脚本第 ④ 步靠它判断成败）")
    void migrateOnlyRunsJustTheMigration() {
        assertThat(ChainpayApplication.oneShot(new String[] {"--migrate-only"}, migrate, createAdmin)).hasValue(7);
        assertThat(migrations).hasValue(1);
        assertThat(admins).hasValue(0);
    }

    @Test
    @DisplayName("--create-admin <用户名>：只建管理员，它的退出码原样交出去")
    void createAdminRunsJustTheBootstrap() {
        assertThat(ChainpayApplication.oneShot(new String[] {"--create-admin", "ops"}, migrate, createAdmin)).hasValue(9);
        assertThat(admins).hasValue(1);
        assertThat(migrations).hasValue(0);
    }

    @Test
    @DisplayName("没有模式参数、只有 Spring 自己的参数：不是一次性命令，交给完整应用")
    void ordinaryArgumentsStartTheApplication() {
        for (String[] args : List.of(new String[0], new String[] {"--server.port=9000"}, new String[] {"--debug"},
                new String[] {"--spring.profiles.active=test"})) {
            assertThat(ChainpayApplication.oneShot(args, migrate, createAdmin)).as(Arrays.toString(args)).isEmpty();
        }
        assertThat(migrations).hasValue(0);
        assertThat(admins).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--migrate_only", "--migrate-only=true", "--migrateonly", "--create-admin=ops", "--create_admin"})
    @DisplayName("★ 模式参数写错一个字：拒绝（退出码 2），什么都不跑——以前会被 Spring 当成没人认识的配置项忽略，静默起完整应用")
    void aMistypedModeIsRefused(String typo) {
        assertThat(ChainpayApplication.oneShot(new String[] {typo}, migrate, createAdmin)).hasValue(ChainpayApplication.USAGE);
        assertThat(migrations).hasValue(0);
        assertThat(admins).hasValue(0);
    }

    @Test
    @DisplayName("两个一次性模式同时出现：拒绝——以前是先出现的那个生效、另一个悄悄忽略，退出码还是 0")
    void bothModesAtOnceAreRefused() {
        assertThat(ChainpayApplication.oneShot(new String[] {"--migrate-only", "--create-admin", "ops"}, migrate, createAdmin))
                .hasValue(ChainpayApplication.USAGE);
        assertThat(ChainpayApplication.oneShot(new String[] {"--create-admin", "ops", "--migrate-only"}, migrate, createAdmin))
                .hasValue(ChainpayApplication.USAGE);
        assertThat(migrations).hasValue(0);
        assertThat(admins).hasValue(0);
    }

    @Test
    @DisplayName("★ 另起一个 JVM 真跑 main：写错的模式参数以退出码 2 结束，完整应用没有起来——守着 main 里那行 System.exit")
    void theRealEntryPointExitsInsteadOfStartingTheApplication() throws Exception {
        Path log = Files.createTempFile("chainpay-entry", ".log");
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ChainpayApplication.class.getName(), "--migrate_only")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().keySet().removeIf(k -> k.startsWith("CHAINPAY_") || k.startsWith("SPRING_"));   // 万一真起了应用，也连不上任何真东西
        Process p = pb.start();
        try {
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("入口应该很快结束").isTrue();
            String out = Files.readString(log);
            assertThat(p.exitValue()).as("退出码；输出：%n%s", out).isEqualTo(ChainpayApplication.USAGE);
            assertThat(out).contains("不认识的模式参数").doesNotContain("Starting ChainpayApplication").doesNotContain(":: Spring Boot ::");
        } finally {
            p.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
