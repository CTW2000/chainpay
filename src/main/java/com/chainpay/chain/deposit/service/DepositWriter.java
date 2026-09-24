package com.chainpay.chain.deposit.service;

import com.chainpay.chain.deposit.domain.DepositCandidate;
import com.chainpay.chain.deposit.domain.DepositStatus;
import com.chainpay.chain.deposit.repository.DepositRepository;
import com.chainpay.chain.deposit.service.DepositPoster.Verdict;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.ledger.system.SystemLedger;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入账的落库：先占坑、再动钱、再改状态，一个系统事务（{@link #apply}）；落库时出了结构性异常，另开一个系统事务把那一笔记成 HELD_ERROR（{@link #holdWithError}）。
 * 单独成类，因为注解靠代理生效：{@link DepositPoster} 是 final 的（生成不了代理），调自己的方法也不经过代理；核对、问余额这些网络留在它那边，事务外。
 */
@Service
public class DepositWriter {

    private final DepositRepository repo;
    private final LedgerService ledger;

    @Autowired
    public DepositWriter(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc, @Qualifier(SystemLedger.QUALIFIER) LedgerService systemLedger) {
        this(new DepositRepository(systemJdbc), systemLedger);
    }

    /** 测试换一个会在某一步出事的仓储（故障注入）；事务与账本照旧。 */
    DepositWriter(DepositRepository repo, LedgerService ledger) {
        this.repo = repo;
        this.ledger = ledger;
    }

    /** 系统池上的一个事务：先占坑，再动钱，再改状态。 */
    @Transactional(SystemLedger.QUALIFIER)
    public Outcome apply(DepositCandidate c, Verdict verdict) {
        if (c.isApproved()) {
            if (verdict.status() != DepositStatus.CREDITED) {
                return repo.holdApproved(c.approvedDepositId(), verdict.reason())
                        ? Outcome.written(DepositStatus.HELD_ERROR, verdict.reason())   // holdApproved 的 SQL 写死 HELD_ERROR
                        : Outcome.SKIPPED;
            }
            Optional<Long> claimed = repo.claimApproved(c.approvedDepositId(), verdict.amount(), verdict.occurredAt());
            if (claimed.isEmpty()) {
                return Outcome.SKIPPED;
            }
            return credit(c, verdict, claimed.get());
        }
        if (verdict.status() != DepositStatus.CREDITED) {
            Optional<Long> claimed = repo.claim(c, verdict.status(), verdict.amount(), verdict.reason(), verdict.occurredAt());
            if (claimed.isEmpty()) {
                return Outcome.SKIPPED;
            }
            return Outcome.written(verdict.status(), verdict.reason());
        }
        Optional<Long> claimed = repo.claim(c, DepositStatus.POSTING, verdict.amount(), null, verdict.occurredAt());
        if (claimed.isEmpty()) {
            return Outcome.SKIPPED;                                   // 别的实例已处理：不碰账本
        }
        return credit(c, verdict, claimed.get());
    }

    private Outcome credit(DepositCandidate c, Verdict verdict, long depositId) {
        long custody = repo.ensureCustodyAccount(c.symbol());
        long transferId = ledger.transfer(new TransferCommand(
                c.idempotencyKey(), c.symbol(), verdict.amount(), custody, c.accountId(), TransferCode.DEPOSIT, verdict.occurredAt()));
        repo.credit(depositId, transferId);
        return Outcome.written(DepositStatus.CREDITED, null);
    }

    /** 结构性异常：这一笔记成 HELD_ERROR（新事务），队列继续。 */
    @Transactional(SystemLedger.QUALIFIER)
    public Outcome holdWithError(DepositCandidate c, Verdict verdict, String reason) {
        if (c.isApproved()) {
            return repo.holdApproved(c.approvedDepositId(), reason)
                    ? Outcome.written(DepositStatus.HELD_ERROR, reason) : Outcome.SKIPPED;
        }
        return repo.claim(c, DepositStatus.HELD_ERROR, verdict.amount(), reason, verdict.occurredAt()).isPresent()
                ? Outcome.written(DepositStatus.HELD_ERROR, reason) : Outcome.SKIPPED;
    }

    /**
     * 落库的结果：写进 deposit 表的<b>实际</b>状态与原因，不是判决阶段的打算。
     *
     * <p>两者在异常路径上会分叉：判决说记账，落库崩了，实际写进去的是 HELD_ERROR。计数与日志只读这里、不回头读 {@link Verdict}；
     * {@code kind} 由 {@code status} 推出，两者不可能各说一套。
     */
    record Outcome(Kind kind, DepositStatus status, String reason) {

        enum Kind { CREDITED, HELD, IGNORED, SKIPPED }

        /** 别的实例已经处理过这条日志：我们一行都没写，也就没有状态可报。 */
        static final Outcome SKIPPED = new Outcome(Kind.SKIPPED, null, null);

        /** 写进库了。kind 从 status 推，不另外传。 */
        static Outcome written(DepositStatus status, String reason) {
            Kind kind = status == DepositStatus.CREDITED ? Kind.CREDITED : status.isHeld() ? Kind.HELD : Kind.IGNORED;
            return new Outcome(kind, status, reason);
        }
    }
}
