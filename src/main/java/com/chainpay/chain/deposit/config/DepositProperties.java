package com.chainpay.chain.deposit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 入账任务的配置：每轮最多处理几条；finalized 差多少块之内算「还没跟上」、这一轮延后（两个节点之间、库里视图超前两个节点，都按它比），超出才叫人。
 * 同一前缀下的收款 xpub 不在这里：它必填，只由 {@code DepositAddressDeriver} 读。
 */
@ConfigurationProperties(prefix = "chainpay.deposit")
public record DepositProperties(@DefaultValue("50") int batchSize, @DefaultValue("64") long finalityToleranceBlocks) {}



