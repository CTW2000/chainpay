package com.chainpay.chain;

import com.chainpay.chain.erc20.TransferLogDecoder;
import com.chainpay.chain.rpc.EthRpc;
import com.chainpay.chain.rpc.JsonRpcClient;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * M2-①「裸奔版」：对着真实的 Sepolia 打一遍，把数据打印出来看。
 *
 * <p><b>默认不跑</b>：设了 CHAINPAY_SEPOLIA_RPC 才启用。默认测试集必须离线——
 * 一个依赖公网节点的测试，红了分不清是代码错还是节点抽风。
 *
 * <pre>
 *   CHAINPAY_SEPOLIA_RPC=https://ethereum-sepolia-rpc.publicnode.com mvn test -Dtest=SepoliaProbeTest
 * </pre>
 */
@DisplayName("M2-① · Sepolia 探针（需网络）")
@EnabledIfEnvironmentVariable(named = "CHAINPAY_SEPOLIA_RPC", matches = ".+")
class SepoliaProbeTest {

    static final String LINK_SEPOLIA = "0x779877A7B0D9E8603169DdbD7836e478b4624789";

    @Test
    @DisplayName("latest / safe / finalized 三个头，以及最近的 LINK 转账")
    void probe() {
        var rpc = new EthRpc(new JsonRpcClient(URI.create(System.getenv("CHAINPAY_SEPOLIA_RPC"))));

        long latest = rpc.blockNumber();
        var safe = rpc.block("safe");
        var finalized = rpc.block("finalized");
        System.out.printf(">>> latest=%d  safe=%d (-%d)  finalized=%d (-%d)%n",
                latest, safe.number(), latest - safe.number(),
                finalized.number(), latest - finalized.number());

        var head = rpc.block("latest");
        System.out.printf(">>> 头区块 %d hash=%s parent=%s%n", head.number(), head.hash(), head.parentHash());

        var logs = rpc.logs(latest - 800, latest, LINK_SEPOLIA, TransferLogDecoder.TRANSFER_TOPIC0);
        System.out.printf(">>> 最近 800 块内 LINK Transfer 日志 %d 条%n", logs.size());
        logs.stream().limit(5).map(TransferLogDecoder::decode).forEach(t ->
                System.out.printf("    块 %d #%d  %s → %s  %s raw%n",
                        t.blockNumber(), t.logIndex(), t.from(), t.to(), t.value()));
    }
}
