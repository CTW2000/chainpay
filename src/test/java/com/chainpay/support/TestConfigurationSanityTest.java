package com.chainpay.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 测试环境的配置必须是「主配置 + 测试覆盖」，不能是「整份替换」。
 *
 * <p>质询扫描 5.6：src/test/resources/application.yml 与主配置同名，Spring Boot
 * 按类路径资源整份替换，于是主配置里「故意开小以暴露连接池竞争」的
 * hikari.maximum-pool-size / connection-timeout 在任何测试里都没生效——
 * 池子是 10 只因为那是 HikariCP 的内置默认，connection-timeout 实测 30000 而非配置的 3000。
 * 一段描述得很认真的设置，从未被执行过。
 *
 * <p>这条钉住的是「测试拿到的是主配置的值」。用 connection-timeout 而不是 pool-size
 * 做判据，因为后者和 Hikari 默认值恰好相等，分不出「配了」和「没配」。
 */
@SpringBootTest
@DisplayName("测试配置叠加主配置")
class TestConfigurationSanityTest extends AbstractPostgresTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.core.env.Environment env;

    @Test
    @DisplayName("★ 主配置的 hikari.connection-timeout=3000 必须在测试里生效")
    void mainConfigurationReachesTests() {
        // 第一层：主配置有没有进 Environment（分清「没加载」和「加载了没绑上」）
        assertThat(env.getProperty("spring.datasource.hikari.connection-timeout"))
                .as("主配置 application.yml 没有被加载进测试的 Environment")
                .isEqualTo("3000");

        // 第二层：Environment 里的值有没有绑到真实的连接池上
        var hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getConnectionTimeout())
                .as("30000 = Hikari 默认值 = 主配置被整份替换掉了")
                .isEqualTo(3000L);
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("★ 合约地址必须以字符串到达应用：YAML 1.1 会把不加引号的 0x 十六进制当整数")
    void tokenAddressSurvivesYamlAsAString() {
        // 2026-09-03 本地起应用实测：不加引号的 0x779877A7… 被解析成整数，再转回字符串成了 48 位十进制，
        // Alchemy 对每一次 eth_getLogs 都回 Invalid params，窗口一路减到 1 块然后停机。
        assertThat(env.getProperty("chainpay.chain.token-address"))
                .as("到达应用的地址必须是 0x + 40 位十六进制，不是十进制数")
                .matches("0x[0-9a-fA-F]{40}");
    }
}
