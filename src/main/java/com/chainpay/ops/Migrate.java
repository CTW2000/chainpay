package com.chainpay.ops;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * 只迁移，不起应用（M6-④，取舍 6）。部署脚本在切换之前跑它：迁移失败时旧版本还在跑、没有切换。
 * 起的是一个<b>最小</b>上下文：只有数据源与 Flyway 两个自动配置，读同一份 application.yml 里的 spring.flyway.*（属主身份、校验、未来版本容忍）。
 * 应用的任何 bean 都不装配，所以没有索引器、没有发送任务、没有健康端口——它只做一件事然后退出。
 * 用法：{@code java -jar chainpay.jar --migrate-only}（{@link com.chainpay.ChainpayApplication#main} 转到这里）。
 */
public final class Migrate {

    private static final Logger log = LoggerFactory.getLogger(Migrate.class);

    /** @param scheduledTasks 进程里注册了多少 @Scheduled 任务：必须是 0（测试守着） */
    public record Outcome(int exitCode, int scheduledTasks, String detail) {}

    @Configuration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class MigrateConfig {}

    private Migrate() {}

    public static Outcome run(String[] args) {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(MigrateConfig.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off").run(args)) {
            Flyway flyway = ctx.getBean(Flyway.class);
            MigrationInfo current = flyway.info().current();
            int tasks = ctx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).values().stream().mapToInt(p -> p.getScheduledTasks().size()).sum();
            String detail = "迁移完成，schema 在 V" + (current == null ? "?" : current.getVersion()) + "（" + (current == null ? "" : current.getDescription()) + "）";
            log.info(detail);
            return new Outcome(0, tasks, detail);
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String detail = "迁移失败：" + root.getClass().getSimpleName() + ": " + root.getMessage();
            log.error(detail);
            return new Outcome(1, 0, detail);
        }
    }
}
