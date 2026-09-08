package com.chainpay.chain.deposit.config;

import com.chainpay.chain.deposit.repository.DepositAddressRepository;
import com.chainpay.chain.deposit.service.DepositAddressService;
import com.chainpay.chain.indexer.repository.ChainTokenRepository;
import com.chainpay.chain.wallet.DepositAddressDeriver;
import com.chainpay.chain.wallet.ExtendedPublicKey;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 装配收款地址模块：设了 CHAINPAY_DEPOSIT_XPUB 才装配，不设时应用照常启动（同索引器）。
 * 启动日志打出 xpub 指纹与 0/0 的地址：运营对照钱包的第一个账户，就能确认配进来的是自己那把 xpub。xpub 本身不进日志。
 */
@Configuration
@ConditionalOnProperty("chainpay.deposit.xpub")
@EnableConfigurationProperties(DepositProperties.class)
class DepositConfig {

    private static final Logger log = LoggerFactory.getLogger(DepositConfig.class);

    @Bean
    DepositAddressDeriver depositAddressDeriver(DepositProperties properties) {
        DepositAddressDeriver deriver = new DepositAddressDeriver(properties.xpub());
        String fingerprint = HexFormat.of().formatHex(ExtendedPublicKey.parse(properties.xpub()).fingerprint());
        log.info("收款地址模块已装配：账户层 xpub 指纹 {}，m/44'/60'/0'/0/0 = {}（应等于钱包的第一个账户）",
                fingerprint, deriver.addressAt(0));
        return deriver;
    }

    @Bean
    DepositAddressService depositAddressService(DepositAddressDeriver deriver, DepositAddressRepository addresses,
                                                ChainTokenRepository tokens, PlatformTransactionManager txManager) {
        return new DepositAddressService(deriver, addresses, tokens, new TransactionTemplate(txManager));
    }
}
