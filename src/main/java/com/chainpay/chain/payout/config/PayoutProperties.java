package com.chainpay.chain.payout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 付款模块的配置：{@code chainpay.payout.*}。
 *
 * @param hotWalletKey     热钱包私钥，只从环境变量 {@code CHAINPAY_PAYOUT_HOT_WALLET_KEY} 来（application.yml 里故意没有它）：
 *                         不设 = 付款模块整个不装配，应用照常启动
 * @param chainId          签进交易里的链号（EIP-155）：签错链号的交易别的链不认，同一把私钥在别的链上也发不出去。Sepolia = 11155111
 * @param batchSize        发送任务每轮最多签几笔
 * @param priorityFloorGwei 小费地板：节点建议低于它时按它出，太低会一直排不上
 * @param maxFeeGwei       总费率上限：2 × 基础费 + 小费超过它这一轮不发，等回落
 * @param gasLimitCap      gasLimit 上限：估算 × 1.2 超过它说明这笔交易不正常，直接判失败
 */
@ConfigurationProperties(prefix = "chainpay.payout")
public record PayoutProperties(
        String hotWalletKey,
        @DefaultValue("11155111") long chainId,
        @DefaultValue("10") int batchSize,
        @DefaultValue("1") long priorityFloorGwei,
        @DefaultValue("100") long maxFeeGwei,
        @DefaultValue("200000") long gasLimitCap) {}
