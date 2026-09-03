package com.chainpay.chain.indexer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 索引器的配置，前缀 {@code chainpay.chain}。
 *
 * @param rpcUrl           主节点。<b>故意不在 application.yml 里给</b>：只从环境变量
 *                         {@code CHAINPAY_CHAIN_RPC_URL} 来，没设就不装配索引器，应用照常启动
 * @param auditRpcUrl      审计节点（M2-⑤），环境变量 {@code CHAINPAY_CHAIN_AUDIT_RPC_URL}，可不设。
 *                         对账和 finalized 核对走它。要独立于主节点才有价值：同一家的两台机器，
 *                         同一个 bug 会同时骗过两条路径。不设时用主节点自己的回执路径，能抓住索引漏日志，
 *                         抓不住节点整体撒谎
 * @param chainName        链名，只是记在 chain_head 里给人看
 * @param tokenAddress     只索引这一个合约的 Transfer
 * @param cursorName       书签名：一条链、一个币、一枚书签
 * @param batchBlocks      eth_getLogs 窗口的上限。撞上提供商的限制会减半，成功后翻倍回到这个值
 * @param startBlock       没有书签时从哪开始（该块视为已处理）。不配 = 没书签就停下，不猜
 * @param reconcileSamples 每次轮询抽几个已 finalized、已索引的块用回执对账
 */
@ConfigurationProperties(prefix = "chainpay.chain")
public record ChainIndexerProperties(
        String rpcUrl,
        String auditRpcUrl,
        String chainName,
        String tokenAddress,
        String cursorName,
        int batchBlocks,
        Long startBlock,
        int reconcileSamples) {}
