package com.chainpay.ops;

import com.chainpay.audit.config.AuditProperties;
import com.chainpay.audit.service.AuditService;
import com.chainpay.chain.indexer.repository.IndexerStateRepository;
import com.chainpay.chain.indexer.service.ChainIndexerScheduler;
import com.chainpay.chain.payout.repository.HotWalletRepository;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.ledger.system.SystemLedger;
import com.chainpay.ops.health.AuditHealthIndicator;
import com.chainpay.ops.health.HotWalletHealthIndicator;
import com.chainpay.ops.health.IndexerHealthIndicator;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M6-⓪ · 把三个指示器装进健康端点。两个连接池的健康与指标交给 Boot：2026-09-15 起系统池是容器里的 bean，db 组合项与 hikaricp.* 指标自动覆盖它。
 * bean 名去掉 HealthIndicator 后缀就是部件名（indexer / hotWallet / audit），application.yml 的分组按这些名字引用。
 * 读状态的每一步都在这里用 lambda 给出，指示器本身只做判定（HealthIndicatorsTest 不起容器就能测）。
 */
@Configuration
class OpsHealthConfig {

    @Bean
    IndexerHealthIndicator indexerHealthIndicator(ObjectProvider<ChainIndexerScheduler> scheduler, IndexerStateRepository states,
                                                  @Value("${chainpay.chain.cursor-name}") String cursorName) {
        return new IndexerHealthIndicator(scheduler.getIfAvailable() != null, () -> states.find(cursorName));
    }

    @Bean
    HotWalletHealthIndicator hotWalletHealthIndicator(Optional<HotWalletSigner> signer, SystemLedger system) {
        return new HotWalletHealthIndicator(signer.map(HotWalletSigner::address),
                address -> system.inTransaction(s -> new HotWalletRepository(s.jdbc()).find(address)));
    }

    @Bean
    AuditHealthIndicator auditHealthIndicator(Optional<AuditService> audit, Optional<AuditProperties> properties) {
        Duration interval = properties.map(AuditProperties::interval).orElse(Duration.ofHours(1));
        return new AuditHealthIndicator(audit.map(a -> () -> a.status(interval)));
    }
}
