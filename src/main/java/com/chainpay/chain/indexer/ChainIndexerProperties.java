package com.chainpay.chain.indexer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 索引器的配置，前缀 {@code chainpay.chain}。
 *
 * @param rpcUrl       节点地址。<b>故意不在 application.yml 里给</b>：只从环境变量
 *                     {@code CHAINPAY_CHAIN_RPC_URL} 来，没设就不装配索引器，应用照常启动。
 *                     现在用 publicnode 的免 key 节点；需要 key 时（限流、要归档数据）再申请
 * @param chainName    链名，只是记在 chain_head 里给人看
 * @param tokenAddress 只索引这一个合约的 Transfer。M2-⑥ 做白名单之前，先只认一个币
 * @param cursorName   书签名：一条链、一个币、一枚书签
 * @param batchBlocks  每批区块数。publicnode 实测 800 块不撞上限，但各家上限不同且不事先告诉你，
 *                     M2-⑤ 做自适应减半之前先保守
 * @param startBlock   没有书签时从哪开始（该块视为已处理）。不配 = 没书签就停下，不猜。
 *                     轮询间隔 {@code poll-interval} 由 @Scheduled 直接读，不经过这里
 */
@ConfigurationProperties(prefix = "chainpay.chain")
public record ChainIndexerProperties(
        String rpcUrl,
        String chainName,
        String tokenAddress,
        String cursorName,
        int batchBlocks,
        Long startBlock) {}
