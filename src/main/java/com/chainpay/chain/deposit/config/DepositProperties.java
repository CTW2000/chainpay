package com.chainpay.chain.deposit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 收款模块的配置：账户层 xpub（只从环境变量 CHAINPAY_DEPOSIT_XPUB 来，不设 = 模块不装配）；入账任务每轮最多处理几条。 */
@ConfigurationProperties(prefix = "chainpay.deposit")
public record DepositProperties(String xpub, @DefaultValue("50") int batchSize) {}



