package com.chainpay.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 测试环境的配置必须是「主配置 + 测试覆盖」，不能是「整份替换」：src/test/resources 里放一个同名的
 * application.yml，Spring Boot 会按类路径资源整份替换主配置，主配置里的设置在任何测试里都不生效，也没有任何报错。
 *
 * <p>判据用 connection-timeout 而不是 pool-size：后者和 Hikari 默认值恰好相等，分不出「配了」和「没配」。
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
        // 不加引号的 0x779877A7… 被解析成整数，再转回字符串成了 48 位十进制：
        // 节点对每一次 eth_getLogs 都回 Invalid params，窗口一路减到 1 块然后停机
        assertThat(env.getProperty("chainpay.chain.token-address"))
                .as("到达应用的地址必须是 0x + 40 位十六进制，不是十进制数")
                .matches("0x[0-9a-fA-F]{40}");
    }
}
