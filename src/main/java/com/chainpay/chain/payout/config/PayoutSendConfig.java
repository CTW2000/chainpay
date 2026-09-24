package com.chainpay.chain.payout.config;

import com.chainpay.chain.indexer.config.ChainIndexerProperties;
import com.chainpay.chain.indexer.config.ChainReaders;
import com.chainpay.chain.payout.service.FeePolicy;
import com.chainpay.chain.payout.service.PayoutSendScheduler;
import com.chainpay.chain.payout.service.PayoutSendWriter;
import com.chainpay.chain.payout.service.PayoutSender;
import com.chainpay.chain.payout.service.PayoutTrackScheduler;
import com.chainpay.chain.payout.service.PayoutTrackWriter;
import com.chainpay.chain.payout.service.PayoutTracker;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 装配发送与追踪任务：只在 worker 里，要热钱包（有钱包能签）和主节点（有链可发），两个都是 worker 的必填项。
 * 测试基类把两个都写成 "false"，所以测试里不装配，测试自己拿 FakeChain 造。
 */
@Configuration
@Profile("worker")
@ConditionalOnProperty(name = {"chainpay.payout.hot-wallet-key", "chainpay.chain.rpc-url"}, matchIfMissing = true)
class PayoutSendConfig {

    private static final Logger log = LoggerFactory.getLogger(PayoutSendConfig.class);
    private static final BigInteger GWEI = BigInteger.TEN.pow(9);

    @Bean
    FeePolicy feePolicy(PayoutProperties p) {
        return new FeePolicy(gwei(p.priorityFloorGwei()), gwei(p.maxFeeGwei()), p.gasLimitCap());
    }

    @Bean
    PayoutSender payoutSender(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc, PayoutSendWriter writer, ChainReaders readers,
                              HotWalletSigner signer, FeePolicy fees, PayoutProperties p, ChainIndexerProperties chain) {
        log.info("发送任务已装配：链号 {}，每轮最多 {} 笔，小费地板 {} gwei，总费率上限 {} gwei，gas 上限 {}",
                p.chainId(), p.batchSize(), p.priorityFloorGwei(), p.maxFeeGwei(), p.gasLimitCap());
        return new PayoutSender(systemJdbc, writer, readers.primary(), readers.sender(), signer, fees, p.chainId(), chain.chainName(),
                p.batchSize(), p.stuckAfter());
    }

    @Bean
    PayoutSendScheduler payoutSendScheduler(PayoutSender sender) {
        return new PayoutSendScheduler(sender);
    }

    @Bean
    PayoutTracker payoutTracker(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc, PayoutTrackWriter writer, ChainReaders readers) {
        log.info("追踪任务已装配：回执问主节点，结算要两个节点都说 finalized 且哈希一致（{}）", readers.auditMode());
        return new PayoutTracker(systemJdbc, writer, readers.primary(), readers.audit());
    }

    @Bean
    PayoutTrackScheduler payoutTrackScheduler(PayoutTracker tracker) {
        return new PayoutTrackScheduler(tracker);
    }

    private static BigInteger gwei(long n) {
        return BigInteger.valueOf(n).multiply(GWEI);
    }
}
