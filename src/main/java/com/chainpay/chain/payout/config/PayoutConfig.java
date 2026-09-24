package com.chainpay.chain.payout.config;

import com.chainpay.chain.wallet.HotWalletSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 付款模块的装配：<b>只在 worker 里</b>。热钱包私钥是 worker 的必填项：没配、留空由进程角色守卫在造 bean 之前拦下；
 * 形状不对（或者容器里没有守卫）在这里拒绝启动，报错点名变量、不带值。只有写成 {@code false} 才不装配——测试基类这样关掉它，部署脚本不放行。
 * 启动日志只打热钱包地址——私钥进程启动后只活在 {@link HotWalletSigner} 里。
 */
@Configuration
@Profile("worker")
@EnableConfigurationProperties(PayoutProperties.class)
@ConditionalOnProperty(name = "chainpay.payout.hot-wallet-key", matchIfMissing = true)
public class PayoutConfig {

    private static final Logger log = LoggerFactory.getLogger(PayoutConfig.class);

    @Bean
    HotWalletSigner hotWalletSigner(PayoutProperties properties) {
        HotWalletSigner signer;
        try {
            signer = HotWalletSigner.fromHex(properties.hotWalletKey());
        } catch (IllegalArgumentException e) {
            // 签名器的报错只说长度与范围、不带值；这里补上变量名。不挂原异常：挂上去，启动报错最底下那条 Caused by 又变回不点名的那句
            throw new IllegalArgumentException("CHAINPAY_PAYOUT_HOT_WALLET_KEY：" + e.getMessage());
        }
        log.info("热钱包已装配：地址 {}（私钥只在进程内存里，不进日志）", signer.address());
        return signer;
    }
}
