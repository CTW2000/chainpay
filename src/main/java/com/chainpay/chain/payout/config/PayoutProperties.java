package com.chainpay.chain.payout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 付款模块的配置：{@code chainpay.payout.*}。
 *
 * <p>{@code hot-wallet-key} 只从环境变量 {@code CHAINPAY_PAYOUT_HOT_WALLET_KEY} 来（application.yml 里故意没有它）：
 * 不设 = 付款模块整个不装配，应用照常启动。
 */
@ConfigurationProperties(prefix = "chainpay.payout")
public record PayoutProperties(String hotWalletKey) {}
