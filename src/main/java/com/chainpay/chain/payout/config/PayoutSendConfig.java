package com.chainpay.chain.payout.config;

import com.chainpay.chain.indexer.config.ChainIndexerProperties;
import com.chainpay.chain.indexer.config.ChainReaders;
import com.chainpay.chain.payout.service.FeePolicy;
import com.chainpay.chain.payout.service.PayoutSendScheduler;
import com.chainpay.chain.payout.service.PayoutSender;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配发送任务：同时设了热钱包私钥（有钱包能签）和主节点（有链可发）才装配。
 * 测试基类把 rpc-url 钉成 "false"，所以测试里不装配，测试自己拿 FakeChain 造。
 */
@Configuration
@ConditionalOnProperty({"chainpay.payout.hot-wallet-key", "chainpay.chain.rpc-url"})
class PayoutSendConfig {

    private static final Logger log = LoggerFactory.getLogger(PayoutSendConfig.class);
    private static final BigInteger GWEI = BigInteger.TEN.pow(9);

    @Bean
    FeePolicy feePolicy(PayoutProperties p) {
        return new FeePolicy(gwei(p.priorityFloorGwei()), gwei(p.maxFeeGwei()), p.gasLimitCap());
    }

    @Bean
    PayoutSender payoutSender(SystemLedger system, ChainReaders readers, HotWalletSigner signer, FeePolicy fees,
                              PayoutProperties p, ChainIndexerProperties chain) {
        log.info("发送任务已装配：链号 {}，每轮最多 {} 笔，小费地板 {} gwei，总费率上限 {} gwei，gas 上限 {}",
                p.chainId(), p.batchSize(), p.priorityFloorGwei(), p.maxFeeGwei(), p.gasLimitCap());
        return new PayoutSender(system, readers.primary(), readers.sender(), signer, fees, p.chainId(), chain.chainName(), p.batchSize());
    }

    @Bean
    PayoutSendScheduler payoutSendScheduler(PayoutSender sender) {
        return new PayoutSendScheduler(sender);
    }

    private static BigInteger gwei(long n) {
        return BigInteger.valueOf(n).multiply(GWEI);
    }
}
