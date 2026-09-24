package com.chainpay.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.support.AbstractPostgresTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 迁移单独一步：只起数据源与 Flyway，不装配应用、不起任何定时任务；成功 0、失败非 0。
 * 部署脚本在切换之前跑它：迁移失败时旧版本还在跑、没有切换（取舍 6）。
 */
@DisplayName("只迁移不起应用")
class MigrateOnlyTest extends AbstractPostgresTest {

    private String[] args(String... extra) {
        String[] base = {
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=chainpay_app", "--spring.datasource.password=chainpay_app_dev",
                "--spring.flyway.user=" + POSTGRES.getUsername(), "--spring.flyway.password=" + POSTGRES.getPassword(),
        };
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    /** 从迁移目录算最高版本，不写死：下一条迁移进来这条测试不该红。 */
    private static int highestMigration() throws IOException {
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.matches("V\\d+__.*\\.sql"))
                    .mapToInt(n -> Integer.parseInt(n.substring(1, n.indexOf("__")))).max().orElseThrow();
        }
    }

    @AfterEach
    void cleanFakeHistory() {
        jdbc.sql("DELETE FROM flyway_schema_history WHERE version = '9999'").update();
    }

    @Test
    @DisplayName("★ 已迁移的库：校验通过、没有新迁移、退出码 0；进程里没有装配任何调度任务")
    void migratedDatabaseValidatesAndExitsZero() throws IOException {
        Migrate.Outcome o = Migrate.run(args());
        assertThat(o.exitCode()).as(o.detail()).isEqualTo(0);
        assertThat(o.scheduledTasks()).as("只迁移，不该有任何 @Scheduled 任务被注册").isZero();
        assertThat(o.detail()).contains("V" + highestMigration());
    }

    @Test
    @DisplayName("★ 一条坏迁移：退出非 0，历史表里没有它（PostgreSQL 的 DDL 是事务的，失败的那条整体回滚）")
    void brokenMigrationExitsNonZeroAndLeavesNoTrace() throws IOException {
        Path dir = Files.createTempDirectory("broken-migration");
        Files.writeString(dir.resolve("V9998__broken.sql"), "CREATE TABLE this_is_not (valid sql;");
        Migrate.Outcome o = Migrate.run(args("--spring.flyway.locations=classpath:db/migration,filesystem:" + dir));
        assertThat(o.exitCode()).isNotZero();
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '9998'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT to_regclass('this_is_not') IS NULL").query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("★ 库里有代码里没有的未来版本（回滚到上一版的情形）：校验仍通过——否则回滚起不来")
    void futureMigrationInHistoryIsTolerated() {
        jdbc.sql("""
                INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                VALUES ((SELECT max(installed_rank) + 1 FROM flyway_schema_history), '9999', 'from the future', 'SQL', 'V9999__from_the_future.sql', 0, 'test', 1, true)
                """).update();
        Migrate.Outcome o = Migrate.run(args());
        assertThat(o.exitCode()).as(o.detail()).isEqualTo(0);
    }
}
