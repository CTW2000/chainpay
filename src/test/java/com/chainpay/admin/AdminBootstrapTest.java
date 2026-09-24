package com.chainpay.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.ops.AdminBootstrap;
import com.chainpay.support.AbstractPostgresTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 第一个管理员怎么来：java -jar chainpay.jar --create-admin <用户名>，口令只从环境变量 CHAINPAY_ADMIN_PASSWORD 来，不进参数（ps 看得到参数）。 */
@DisplayName("创建第一个管理员")
class AdminBootstrapTest extends AbstractPostgresTest {

    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE admin_action, admin_session, admin_user CASCADE").update();
    }

    private String[] args(String... extra) {
        String[] base = {"--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=chainpay_app",
                "--spring.datasource.password=chainpay_app_dev", "--spring.flyway.enabled=false"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    @DisplayName("★ 口令从环境变量来：建成 0，库里是 Argon2id 散列；同名再建非 0；没给口令非 0；口令太短非 0")
    void createsFromEnvironmentOnly() {
        assertThat(AdminBootstrap.run(args("--create-admin", "root.ops"), Map.of("CHAINPAY_ADMIN_PASSWORD", "a-long-enough-password")).exitCode()).isZero();
        assertThat(jdbc.sql("SELECT password_hash FROM admin_user WHERE username = 'root.ops'").query(String.class).single()).startsWith("$argon2id$");
        assertThat(AdminBootstrap.run(args("--create-admin", "root.ops"), Map.of("CHAINPAY_ADMIN_PASSWORD", "a-long-enough-password")).exitCode()).as("已存在").isNotZero();
        assertThat(AdminBootstrap.run(args("--create-admin", "other"), Map.of()).exitCode()).as("没给口令").isNotZero();
        assertThat(AdminBootstrap.run(args("--create-admin", "other"), Map.of("CHAINPAY_ADMIN_PASSWORD", "short")).exitCode()).as("太短").isNotZero();
        assertThat(jdbc.sql("SELECT count(*) FROM admin_user").query(Long.class).single()).isEqualTo(1);
    }
}
