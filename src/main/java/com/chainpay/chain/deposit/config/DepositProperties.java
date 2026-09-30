package com.chainpay.chain.deposit.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 入账任务的配置：每轮最多处理几条；finalized 差多少块之内算「还没跟上」、这一轮延后（两个节点之间、库里视图超前两个节点，都按它比），超出才叫人。
 * 同一前缀下的收款 xpub 不在这里：它必填，只由 {@code DepositAddressDeriver} 读。
 *
 * <p>合法范围由校验器在绑定时守，配错了就拒绝启动（同 {@code ChainIndexerProperties}）：batchSize 是两个取候选查询的 LIMIT，
 * 配成 0 时每轮取到 0 条、照常「跑完」，健康检查 UP，入账却悄悄停了。
 */
@Validated
@ConfigurationProperties(prefix = "chainpay.deposit")
public record DepositProperties(@DefaultValue("50") @Min(1) int batchSize,
                                @DefaultValue("64") @Min(0) long finalityToleranceBlocks) {}



