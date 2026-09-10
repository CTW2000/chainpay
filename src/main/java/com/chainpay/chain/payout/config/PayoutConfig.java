package com.chainpay.chain.payout.config;

import com.chainpay.chain.wallet.HotWalletSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 付款模块的装配：设了热钱包私钥才装配（同 M3 的收款模块设了 xpub 才装配）。
 * 启动日志只打热钱包地址——私钥进程启动后只活在 {@link HotWalletSigner} 里。
 */
@Configuration
@EnableConfigurationProperties(PayoutProperties.class)
@ConditionalOnProperty("chainpay.payout.hot-wallet-key")
public class PayoutConfig {

    private static final Logger log = LoggerFactory.getLogger(PayoutConfig.class);

    @Bean
    HotWalletSigner hotWalletSigner(PayoutProperties properties) {
        HotWalletSigner signer = HotWalletSigner.fromHex(properties.hotWalletKey());
        log.info("热钱包已装配：地址 {}（私钥只在进程内存里，不进日志）", signer.address());
        return signer;
    }
}
