package com.chainpay.chain.indexer.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置的合法范围由校验器守，不靠每个构造器各写一段 if-throw。
 * 特别是 degraded-after-failures：漏配时 int 默认 0，「连续 0 次失败就降级」= 第一次瞬时失败就 DEGRADED。
 */
@DisplayName("M2 · 索引器配置的约束")
class ChainIndexerPropertiesTest {

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    static final String LINK = "0x779877A7B0D9E8603169DdbD7836e478b4624789";

    @Test
    @DisplayName("★ 合法配置零违规")
    void validConfigurationHasNoViolations() {
        assertThat(VALIDATOR.validate(new ChainIndexerProperties("u", null, "sepolia", LINK, "sepolia:link", 100, null, 3, 30))).isEmpty();
    }

    @Test
    @DisplayName("★ degraded-after-failures 漏配成 0、batch-blocks 为 0、地址不是 40 位十六进制：各被校验器拦住")
    void outOfRangeValuesAreRejected() {
        assertThat(VALIDATOR.validate(new ChainIndexerProperties("u", null, "sepolia", LINK, "c", 100, null, 3, 0)))
                .extracting(v -> v.getPropertyPath().toString()).containsExactly("degradedAfterFailures");
        assertThat(VALIDATOR.validate(new ChainIndexerProperties("u", null, "sepolia", LINK, "c", 0, null, 3, 30)))
                .extracting(v -> v.getPropertyPath().toString()).containsExactly("batchBlocks");
        assertThat(VALIDATOR.validate(new ChainIndexerProperties("u", null, "sepolia", "0x12", "c", 100, null, 3, 30)))
                .extracting(v -> v.getPropertyPath().toString()).containsExactly("tokenAddress");
        assertThat(VALIDATOR.validate(new ChainIndexerProperties("u", null, " ", LINK, "c", 100, null, 3, 30)))
                .extracting(v -> v.getPropertyPath().toString()).containsExactly("chainName");
    }

    @Test
    @DisplayName("★ 记录本身带 @Validated：Boot 绑定时才会跑校验器")
    void recordIsMarkedForBootValidation() {
        assertThat(ChainIndexerProperties.class.isAnnotationPresent(org.springframework.validation.annotation.Validated.class)).isTrue();
    }
}
