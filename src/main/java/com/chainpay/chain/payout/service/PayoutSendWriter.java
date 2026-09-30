package com.chainpay.chain.payout.service;

import com.chainpay.chain.payout.domain.HotWallet;
import com.chainpay.chain.payout.domain.PayoutStatus;
import com.chainpay.chain.payout.domain.PayoutTxStatus;
import com.chainpay.chain.payout.repository.HotWalletRepository;
import com.chainpay.chain.payout.repository.PayoutSendRepository;
import com.chainpay.ledger.system.SystemLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发送任务的落库：对账、记 BROADCAST，各是一个系统事务——都要锁行，或者几条写要一起成败。
 * 签名与判失败在签名闸口（{@link PayoutSigningGate}）：这两步动的钱来自 web 写的行，要先复核。
 * 单独成类，因为注解靠代理生效：{@link PayoutSender} 是 final 的，调自己的方法也不经过代理；问计数、估 gas、广播这些网络留在它那边，事务外。
 */
@Service
public class PayoutSendWriter {

    private static final Logger log = LoggerFactory.getLogger(PayoutSendWriter.class);

    private final HotWalletRepository wallets;
    private final PayoutSendRepository repo;

    public PayoutSendWriter(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc) {
        this.wallets = new HotWalletRepository(systemJdbc);
        this.repo = new PayoutSendRepository(systemJdbc);
    }

    // ------------------------------------------------------------------ 对账

    /** 锁热钱包行，核 C ≤ N ≤ C + U（见 {@link PayoutSender}），越界就停发，一个事务。返回停发原因；null = 可以发。 */
    @Transactional(SystemLedger.QUALIFIER)
    public String reconcile(String wallet, String chainName, long onChain) {
        wallets.insertIfAbsent(wallet, chainName, onChain);
        HotWallet row = wallets.lockForUpdate(wallet).orElseThrow(() -> new IllegalStateException("热钱包行刚建就不见了：" + wallet));
        if (row.halted()) {
            return row.haltReason();
        }
        long unfinished = repo.unfinishedNonces(wallet);
        long next = row.nextNonce();
        if (onChain > next) {
            String reason = "链上已上链 " + onChain + " 笔，超过本库分出去的编号 " + next + "：有人在别处用了这把私钥";
            wallets.halt(wallet, reason);
            return reason;
        }
        if (next > onChain + unfinished) {
            String reason = "编号 " + next + " 超过链上 " + onChain + " 笔与未终结尝试 " + unfinished + " 之和：有分出去的编号没有尝试记录";
            wallets.halt(wallet, reason);
            return reason;
        }
        return null;
    }

    // ------------------------------------------------------------------ 广播之后

    /**
     * 广播成功后记 BROADCAST。两个实例可能同时重发同一份原文（一个成功、一个 already known）：谁先改谁算，后到的看见已改就走。
     * 提现只在第一次（SIGNED → BROADCAST）跟着改；重发 DROPPED 的、加价的替身，提现早已是 BROADCAST。
     */
    @Transactional(SystemLedger.QUALIFIER)
    public void markBroadcast(long attemptId, long payoutId, PayoutTxStatus from) {
        from.require(PayoutTxStatus.BROADCAST);
        if (!repo.moveAttempt(attemptId, from.name(), PayoutTxStatus.BROADCAST.name())) {
            log.info("尝试 {} 已被别的实例记为 BROADCAST", attemptId);
            return;
        }
        PayoutStatus.SIGNED.require(PayoutStatus.BROADCAST);
        repo.moveStatus(payoutId, PayoutStatus.SIGNED.name(), PayoutStatus.BROADCAST.name());   // 改不动 = 提现早已 BROADCAST（重发、替身）
    }
}
