package com.chainpay.chain.deposit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 收款模块的配置：账户层 xpub（m/44'/60'/0'）。只从环境变量 CHAINPAY_DEPOSIT_XPUB 来，不设 = 模块不装配。 */
@ConfigurationProperties(prefix = "chainpay.deposit")
public record DepositProperties(String xpub) {}



