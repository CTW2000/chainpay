package com.chainpay.chain.payout.service;

import com.chainpay.chain.payout.domain.HotWallet;
import com.chainpay.chain.payout.domain.PayoutStatus;
import com.chainpay.chain.payout.domain.PayoutTxStatus;
import com.chainpay.chain.payout.domain.QueuedPayout;
import com.chainpay.chain.payout.repository.HotWalletRepository;
import com.chainpay.chain.payout.repository.PayoutSendRepository;
import com.chainpay.chain.wallet.Eip1559Transaction;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发送任务的落库：对账、签名落库、记 BROADCAST、判失败，各是一个系统事务——都要锁行，或者几条写要一起成败。
 * 单独成类，因为注解靠代理生效：{@link PayoutSender} 是 final 的，调自己的方法也不经过代理；问计数、估 gas、广播这些网络留在它那边，事务外。
 * 这个 bean 不持有私钥：签名那一步的签名器由调用方随调用传进来。
 */
@Service
public class PayoutSendWriter {

    private static final Logger log = LoggerFactory.getLogger(PayoutSendWriter.class);

    private final HotWalletRepository wallets;
    private final PayoutSendRepository repo;
    private final PayoutLedger ledger;

    public PayoutSendWriter(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc, @Qualifier(SystemLedger.QUALIFIER) LedgerService systemLedger) {
        this.wallets = new HotWalletRepository(systemJdbc);
        this.repo = new PayoutSendRepository(systemJdbc);
        this.ledger = new PayoutLedger(systemJdbc, systemLedger);
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

    // ------------------------------------------------------------------ 签名与落库（一个事务）

    record SignedRecord(long attemptId, long nonce, String rawHex, String txHash) {}

    static final class TakenByAnotherInstance extends RuntimeException {
        TakenByAnotherInstance(long payoutId) {
            super("提现 " + payoutId + " 已被别的实例签了");
        }
    }

    static final class WalletHalted extends RuntimeException {
        WalletHalted(String reason) {
            super(reason);
        }
    }

    /**
     * 锁行、占住这笔、拿编号、签名、写 SIGNED 尝试、编号 +1，一个事务；提交之后调用方才广播。签名要用刚在锁里拿到的编号，所以在事务里签。
     * 抛出 = 整个事务回滚：{@link TakenByAnotherInstance}（别的实例先签了这笔）、{@link WalletHalted}（钱包停发了）。
     */
    @Transactional(SystemLedger.QUALIFIER)
    public SignedRecord signAndRecord(HotWalletSigner signer, long chainId, String wallet, QueuedPayout payout, byte[] data, FeePolicy.Fees quoted) {
        HotWallet row = wallets.lockForUpdate(wallet).orElseThrow(() -> new IllegalStateException("热钱包行不见了：" + wallet));
        if (row.halted()) {
            throw new WalletHalted(row.haltReason());
        }
        PayoutStatus.QUEUED.require(PayoutStatus.SIGNED);
        if (!repo.moveStatus(payout.id(), PayoutStatus.QUEUED.name(), PayoutStatus.SIGNED.name())) {
            throw new TakenByAnotherInstance(payout.id());
        }
        long nonce = row.nextNonce();
        Eip1559Transaction tx = new Eip1559Transaction(chainId, nonce, quoted.maxPriorityFeePerGas(), quoted.maxFeePerGas(),
                quoted.gasLimit(), payout.token(), BigInteger.ZERO, data);
        Eip1559Transaction.Signed signedTx = signer.sign(tx);
        String rawHex = "0x" + HexFormat.of().formatHex(signedTx.raw());
        String txHash = "0x" + HexFormat.of().formatHex(signedTx.hash());
        long attemptId = repo.insertAttempt(payout.id(), wallet, nonce, txHash, rawHex, quoted.gasLimit(),
                quoted.maxFeePerGas(), quoted.maxPriorityFeePerGas());
        wallets.advanceNonce(wallet, nonce);
        return new SignedRecord(attemptId, nonce, rawHex, txHash);
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

    /**
     * 估 gas 就失败：编号还没分出去，这笔直接判失败并解冻，同一个事务。
     * 先锁这笔提现的行再看状态：两个实例可能同时看到同一笔 revert，后到的等先到的提交，看见已经 FAILED 就走。
     * 返回 false = 别的实例先判了。
     */
    @Transactional(SystemLedger.QUALIFIER)
    public boolean fail(QueuedPayout payout, String reason) {
        if (!PayoutStatus.QUEUED.name().equals(repo.lockStatus(payout.id()))) {
            log.info("提现 {} 已被别的实例定了结局", payout.id());
            return false;
        }
        log.warn("提现 {} 判失败：{}", payout.id(), reason);
        PayoutStatus.QUEUED.require(PayoutStatus.FAILED);
        long reverse = ledger.reverse(payout.id(), payout.symbol(), payout.amount(),
                payout.frozenAccountId(), payout.userAccountId(), Instant.now());
        repo.markFailed(payout.id(), reason, reverse);
        return true;
    }
}
