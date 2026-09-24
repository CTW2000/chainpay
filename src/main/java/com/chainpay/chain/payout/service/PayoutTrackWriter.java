package com.chainpay.chain.payout.service;

import com.chainpay.chain.payout.domain.PayoutAttempt;
import com.chainpay.chain.payout.domain.PayoutStatus;
import com.chainpay.chain.payout.domain.PayoutTxStatus;
import com.chainpay.chain.payout.repository.PayoutSendRepository;
import com.chainpay.chain.rpc.TransactionReceipt;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.system.SystemLedger;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 追踪任务的落库：记上链、退回（重组）、结算，各是一个系统事务——都是几条写要一起成败。
 * 单独成类，因为注解靠代理生效：{@link PayoutTracker} 是 final 的，调自己的方法也不经过代理；问回执、问块头这些网络留在它那边，事务外。
 * 结果由返回值带回去计数：事务提交了才算数。
 */
@Service
public class PayoutTrackWriter {

    private static final Logger log = LoggerFactory.getLogger(PayoutTrackWriter.class);

    private final PayoutSendRepository repo;
    private final PayoutLedger ledger;

    public PayoutTrackWriter(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc, @Qualifier(SystemLedger.QUALIFIER) LedgerService systemLedger) {
        this.repo = new PayoutSendRepository(systemJdbc);
        this.ledger = new PayoutLedger(systemJdbc, systemLedger);
    }

    /** 回执来了：尝试 MINED、提现 MINED、同编号的兄弟 REPLACED，一个事务。返回顶掉的兄弟数；空 = 别的实例先记了。 */
    @Transactional(SystemLedger.QUALIFIER)
    public OptionalInt recordMined(PayoutAttempt a, TransactionReceipt r) {
        PayoutTxStatus.BROADCAST.require(PayoutTxStatus.MINED);
        if (!repo.markMined(a.id(), r.blockNumber(), r.blockHash(), !r.success(), r.gasUsed(), r.effectiveGasPrice())) {
            return OptionalInt.empty();                                                 // 别的实例先记了
        }
        PayoutStatus.BROADCAST.require(PayoutStatus.MINED);
        if (!repo.moveStatus(a.payoutId(), PayoutStatus.BROADCAST.name(), PayoutStatus.MINED.name())) {
            throw new IllegalStateException("尝试 " + a.id() + " 刚记成 MINED，它的提现 " + a.payoutId() + " 却不在 BROADCAST");
        }
        int replaced = repo.replaceSiblings(a.hotWallet(), a.nonce(), a.id());
        log.info("提现 {} 上链：块 {}（{}），{}", a.payoutId(), r.blockNumber(), a.txHash(), r.success() ? "执行成功" : "合约 revert");
        return OptionalInt.of(replaced);
    }

    /** 那块被重组掉了：尝试与提现都退回 BROADCAST，继续等回执，一个事务。返回 false = 别的实例先退了。 */
    @Transactional(SystemLedger.QUALIFIER)
    public boolean reorged(PayoutAttempt a) {
        PayoutTxStatus.MINED.require(PayoutTxStatus.BROADCAST);
        if (!repo.unmine(a.id())) {
            return false;
        }
        PayoutStatus.MINED.require(PayoutStatus.BROADCAST);
        if (!repo.moveStatus(a.payoutId(), PayoutStatus.MINED.name(), PayoutStatus.BROADCAST.name())) {
            throw new IllegalStateException("尝试 " + a.id() + " 刚退回 BROADCAST，它的提现 " + a.payoutId() + " 却不在 MINED");
        }
        return true;
    }

    /** FINAL 了：先锁提现行看状态（别的实例可能先到），再记账、再改状态，一个事务。返回结局（FAILED / CONFIRMED）；空 = 别的实例先结了。 */
    @Transactional(SystemLedger.QUALIFIER)
    public Optional<PayoutStatus> settle(PayoutAttempt a) {
        if (!PayoutStatus.MINED.name().equals(repo.lockStatus(a.payoutId()))) {
            return Optional.empty();
        }
        PayoutSendRepository.Settlement t = repo.findSettlement(a.payoutId());
        if (Boolean.TRUE.equals(a.reverted())) {
            PayoutStatus.MINED.require(PayoutStatus.FAILED);
            long reverse = ledger.reverse(t.payoutId(), t.symbol(), t.amount(), t.frozenAccountId(), t.userAccountId(), Instant.now());
            repo.markFailedFromMined(t.payoutId(), "链上执行失败（回执 status 0，块 " + a.blockNumber() + "）：编号已用、gas 已扣，转账没发生", reverse);
            log.warn("提现 {} FINAL 但合约 revert：解冻，FAILED", a.payoutId());
            return Optional.of(PayoutStatus.FAILED);
        }
        PayoutStatus.MINED.require(PayoutStatus.CONFIRMED);
        long settle = ledger.settle(t.payoutId(), t.symbol(), t.amount(), t.frozenAccountId(), t.custodyAccountId(), Instant.now());
        repo.markConfirmed(t.payoutId(), settle);
        log.info("提现 {} FINAL：结算 {} {}，CONFIRMED", a.payoutId(), t.amount(), t.symbol());
        return Optional.of(PayoutStatus.CONFIRMED);
    }
}
