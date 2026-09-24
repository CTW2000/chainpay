package com.chainpay.ops.health;

import com.chainpay.chain.payout.domain.HotWallet;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * 热钱包算不算「能付」：hot_wallet 表里它那一行的状态。没装配（web 进程，或私钥写成 false）= UNKNOWN；还没有行（一笔都没发过）= UP；HALTED = DOWN 带原因。
 * 细节里只有地址和下一个编号——地址本来就是公开的，私钥、节点地址永远不进来。
 */
public final class HotWalletHealthIndicator implements HealthIndicator {

    private final Optional<String> address;
    private final Function<String, Optional<HotWallet>> lookup;

    public HotWalletHealthIndicator(Optional<String> address, Function<String, Optional<HotWallet>> lookup) {
        this.address = address;
        this.lookup = lookup;
    }

    @Override
    public Health health() {
        if (address.isEmpty()) {
            return Health.unknown().withDetail("reason", "这个进程不付款（web 进程，或 CHAINPAY_PAYOUT_HOT_WALLET_KEY=false）").build();
        }
        String a = address.get();
        Optional<HotWallet> w = lookup.apply(a);
        if (w.isEmpty()) {
            return Health.up().withDetail("address", a).withDetail("nextNonce", "无（一笔都没发过）").build();
        }
        HotWallet wallet = w.get();
        if (wallet.halted()) {
            return Health.down().withDetail("address", a).withDetail("nextNonce", wallet.nextNonce())
                    .withDetail("reason", wallet.haltReason() == null ? "HALTED" : wallet.haltReason()).build();
        }
        return Health.up().withDetail("address", a).withDetail("nextNonce", wallet.nextNonce()).build();
    }
}
