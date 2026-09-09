package com.chainpay.ledger.system;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 系统连接的凭证：{@code chainpay.system-db.*}。
 *
 * <p>密码故意没有默认值：不设就起不来，理由同 secret-key / admin-token。
 * URL 复用 {@code spring.datasource.url}——同一个库，不同的身份。
 */
@ConfigurationProperties(prefix = "chainpay.system-db")
public record SystemDbProperties(
        @DefaultValue("chainpay_system") String username,
        String password,
        @DefaultValue("2") int maximumPoolSize,
        /** 系统连接等锁的上限；超时按瞬时失败处理（M3-⑤ 演练补丁）。 */
        @DefaultValue("5s") Duration lockTimeout
) {}
