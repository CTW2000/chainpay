package com.chainpay.chain.deposit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 配置键的名字与默认值：键名写错不会报错，只会静默用默认值，所以钉住它。 */
@DisplayName("入账任务的配置")
class DepositPropertiesTest {

    @Test
    @DisplayName("★ finalized 容忍块数绑在 chainpay.deposit.finality-tolerance-blocks 上，不设时是 64")
    void bindsFinalityToleranceBlocks() {
        assertThat(bind(Map.of()).finalityToleranceBlocks())
                .as("默认两个 epoch").isEqualTo(64);
        assertThat(bind(Map.of("chainpay.deposit.finality-tolerance-blocks", "7")).finalityToleranceBlocks())
                .as("配了就用配的").isEqualTo(7);
    }

    @Test
    @DisplayName("★ 装配时把这个配置传给了入账任务：写死一个数的话 application.yml 就白配了，而所有行为测试都抓不到")
    void wiringPassesTheConfiguredTolerance() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/chainpay/chain/deposit/config/DepositPostingConfig.java"));
        Matcher call = Pattern.compile("new DepositPoster\\(([^;]*)\\);", Pattern.DOTALL).matcher(source);

        assertThat(call.find()).as("装配里应该有一处 new DepositPoster(…)").isTrue();
        assertThat(call.group(1)).as("容忍值必须来自配置").contains("properties.finalityToleranceBlocks()");
    }

    private static DepositProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("chainpay.deposit", DepositProperties.class);
    }
}
