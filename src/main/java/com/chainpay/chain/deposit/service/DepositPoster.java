package com.chainpay.chain.deposit.service;

import com.chainpay.chain.deposit.domain.DepositCandidate;
import com.chainpay.chain.deposit.domain.DepositStatus;
import com.chainpay.chain.deposit.domain.PostingResult;
import com.chainpay.chain.deposit.repository.DepositRepository;
import com.chainpay.chain.erc20.AmountOverflowException;
import com.chainpay.chain.erc20.Erc20Calls;
import com.chainpay.chain.erc20.TokenAmounts;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.Hex;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 入账任务：把已 FINAL 的链上转账记进账本。
 *
 * <p>每条候选三步：
 * <ol>
 *   <li><b>核对（网络，事务外）</b>：主节点与审计节点各取一次该块的头，哈希必须等于库里那行的 block_hash，
 *       块号必须不高于两个节点各自的 finalized。任何一条不满足 = HELD_NODE_DISAGREE，不记账。这是动钱的边界，
 *       索引器无论怎么错都过不了这道门。同一轮内的块头与 finalized 复用，不逐笔重问（M3-before 第 25 问）</li>
 *   <li><b>判决</b>：零值 = IGNORED_ZERO；金额只经 {@link TokenAmounts#toLedger}，装不下 = HELD_OVERFLOW；
 *       低于代币的最小入账额 = REJECTED_DUST；然后<b>信合约做的，不信合约说的</b>——向两个节点问该地址在那一块的 balanceOf，
 *       必须等于事件累计（转入减转出），对不上或问不到 = HELD_BALANCE_MISMATCH（M3-③）</li>
 *   <li><b>落库（系统池上一个事务）</b>：先占坑（INSERT … ON CONFLICT DO NOTHING，状态 POSTING），占不到 = 别的实例已处理，什么都不做；
 *       占到了才 ledger.transfer（幂等键 = 链上坐标），再把行改成 CREDITED。崩在中间整个事务回滚</li>
 * </ol>
 * 失败分两种：瞬时的（节点、数据库）让这一轮提前结束、已提交的不受影响；结构性的这一笔记成 HELD_ERROR 带异常原文，队列继续。
 * 人复核 HELD 之后改成 APPROVED 的行，下一轮不再核对、只重新占坑与记账——人永远不用手工碰账本表。
 */
public final class DepositPoster {

    private static final Logger log = LoggerFactory.getLogger(DepositPoster.class);

    private final SystemLedger system;
    private final ChainReader primary;
    private final ChainReader audit;
    private final Erc20Calls primaryCalls;
    private final Erc20Calls auditCalls;
    private final int batchSize;
    private final Function<JdbcClient, DepositRepository> repositories;

    public DepositPoster(SystemLedger system, ChainReader primary, ChainReader audit, int batchSize) {
        this(system, primary, audit, batchSize, DepositRepository::new);
    }

    DepositPoster(SystemLedger system, ChainReader primary, ChainReader audit, int batchSize,
                  Function<JdbcClient, DepositRepository> repositories) {
        this.system = system;
        this.primary = primary;
        this.audit = audit;
        this.primaryCalls = new Erc20Calls(primary);
        this.auditCalls = new Erc20Calls(audit);
        this.batchSize = batchSize;
        this.repositories = repositories;
    }

    public PostingResult postOnce() {
        return post(candidates());
    }

    /** 人核准的行排在前面：它们已经等过人了。 */
    List<DepositCandidate> candidates() {
        return system.inTransaction(s -> {
            DepositRepository repo = repositories.apply(s.jdbc());
            List<DepositCandidate> all = new ArrayList<>(repo.findApproved(batchSize));
            all.addAll(repo.findFinalUnposted(batchSize));
            return all;
        });
    }

    PostingResult post(List<DepositCandidate> candidates) {
        Counters k = new Counters();
        RoundCache cache = new RoundCache();
        for (DepositCandidate c : candidates) {
            k.examined++;
            Verdict verdict;
            try {
                verdict = c.isApproved() ? judgeApproved(c, cache) : judge(c, cache);
            } catch (JsonRpcException e) {
                return k.retryLater("核对块 " + c.blockNumber() + " 时节点失败：" + e.getMessage());
            } catch (TransientDataAccessException e) {
                return k.retryLater("核对块 " + c.blockNumber() + " 时数据库瞬时失败：" + e.getMessage());
            }
            Outcome outcome;
            try {
                outcome = system.inTransaction(s -> apply(repositories.apply(s.jdbc()), s, c, verdict));
            } catch (TransientDataAccessException e) {
                return k.retryLater("记块 " + c.blockNumber() + " 时数据库瞬时失败：" + e.getMessage());
            } catch (RuntimeException e) {
                String reason = "入账时异常：" + e;
                log.error("入账 HELD_ERROR：块 {} 日志 {} —— {}", c.blockNumber(), c.logIndex(), reason);
                outcome = system.inTransaction(s -> holdWithError(repositories.apply(s.jdbc()), c, verdict, reason));
            }
            k.count(outcome, c, verdict);
        }
        return k.done();
    }

    /** 两个节点都点头才算 FINAL；然后零值、溢出、灰尘；最后信合约做的。 */
    private Verdict judge(DepositCandidate c, RoundCache cache) {
        BlockHeader p = cache.primaryHeader(c.blockNumber());
        BlockHeader a = cache.auditHeader(c.blockNumber());
        Instant occurredAt = Instant.ofEpochSecond(p.timestamp());
        if (!p.hash().equals(c.blockHash())) {
            return Verdict.held(DepositStatus.HELD_NODE_DISAGREE, amountOrNull(c), occurredAt,
                    "主节点现在说块 " + c.blockNumber() + " 的哈希是 " + p.hash() + "，库里是 " + c.blockHash()
                            + "：索引之后节点改口，或曾发生重组而视图仍判 FINAL");
        }
        if (!a.hash().equals(c.blockHash())) {
            return Verdict.held(DepositStatus.HELD_NODE_DISAGREE, amountOrNull(c), occurredAt,
                    "审计节点对块 " + c.blockNumber() + " 的哈希意见不同：它说 " + a.hash() + "，库里是 " + c.blockHash());
        }
        if (c.blockNumber() > cache.primaryFinalized() || c.blockNumber() > cache.auditFinalized()) {
            return Verdict.held(DepositStatus.HELD_NODE_DISAGREE, amountOrNull(c), occurredAt,
                    "块 " + c.blockNumber() + " 尚未在两个节点上 finalized（主节点 " + cache.primaryFinalized()
                            + "，审计节点 " + cache.auditFinalized() + "）");
        }
        if (c.rawValue().signum() == 0) {
            return new Verdict(DepositStatus.IGNORED_ZERO, BigDecimal.ZERO, null, occurredAt);
        }
        BigDecimal amount;
        try {
            amount = TokenAmounts.toLedger(c.rawValue(), c.decimals());
        } catch (AmountOverflowException e) {
            return Verdict.held(DepositStatus.HELD_OVERFLOW, null, occurredAt, e.getMessage());
        }
        if (c.minDeposit().signum() > 0 && amount.compareTo(c.minDeposit()) < 0) {
            return new Verdict(DepositStatus.REJECTED_DUST, amount,
                    "金额 " + amount.toPlainString() + " 低于代币最小入账额 " + c.minDeposit().toPlainString() + "，记录不入账", occurredAt);
        }
        Optional<String> mismatch = balanceMismatch(c);
        if (mismatch.isPresent()) {
            return Verdict.held(DepositStatus.HELD_BALANCE_MISMATCH, amount, occurredAt, mismatch.get());
        }
        return new Verdict(DepositStatus.CREDITED, amount, null, occurredAt);
    }

    /** 人核准的行：不再核对，只要金额算得出。 */
    private Verdict judgeApproved(DepositCandidate c, RoundCache cache) {
        Instant occurredAt = Instant.ofEpochSecond(cache.primaryHeader(c.blockNumber()).timestamp());
        if (c.rawValue().signum() == 0) {
            return Verdict.held(DepositStatus.HELD_ERROR, null, occurredAt, "人工核准的行是零值转账，账本不接受零金额");
        }
        try {
            return new Verdict(DepositStatus.CREDITED, TokenAmounts.toLedger(c.rawValue(), c.decimals()), null, occurredAt);
        } catch (AmountOverflowException e) {
            return Verdict.held(DepositStatus.HELD_ERROR, null, occurredAt, "人工核准的行金额装不下账本：" + e.getMessage());
        }
    }

    /**
     * 信合约做的，不信合约说的：地址在那一块的 balanceOf 必须等于事件累计（转入减转出，原始单位）。
     * 转账扣费、弹性供应、静默铸币、节点撒谎，都会在这里对不上。问不到也不记——「不知道」和「对上了」不是一回事。
     */
    private Optional<String> balanceMismatch(DepositCandidate c) {
        BigInteger expected = system.inTransaction(s -> repositories.apply(s.jdbc()).netTransfersUpTo(c.toAddress(), c.token(), c.blockNumber()));
        String tag = Hex.fromLong(c.blockNumber());
        BigInteger onPrimary;
        BigInteger onAudit;
        try {
            onPrimary = primaryCalls.balanceOf(c.token(), c.toAddress(), tag);
        } catch (JsonRpcException e) {
            if (e.code() == null) {
                throw e;                                                    // 传输失败：这一轮重试
            }
            return Optional.of("主节点问不到块 " + c.blockNumber() + " 上 " + c.toAddress() + " 的余额：" + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Optional.of("主节点的余额答复不成形：" + e.getMessage());
        }
        try {
            onAudit = auditCalls.balanceOf(c.token(), c.toAddress(), tag);
        } catch (JsonRpcException e) {
            if (e.code() == null) {
                throw e;
            }
            return Optional.of("审计节点问不到块 " + c.blockNumber() + " 上 " + c.toAddress() + " 的余额：" + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Optional.of("审计节点的余额答复不成形：" + e.getMessage());
        }
        if (!onPrimary.equals(expected)) {
            return Optional.of("块 " + c.blockNumber() + " 上 " + c.toAddress() + " 的余额：合约做的 " + onPrimary
                    + "，事件累计（转入减转出）" + expected + "，差 " + onPrimary.subtract(expected)
                    + "。转账扣费 / 弹性供应 / 静默铸币 / 节点撒谎之一，人看");
        }
        if (!onAudit.equals(onPrimary)) {
            return Optional.of("审计节点给的余额 " + onAudit + " 与主节点 " + onPrimary + " 不同（块 " + c.blockNumber() + "）");
        }
        return Optional.empty();
    }

    private static BigDecimal amountOrNull(DepositCandidate c) {
        try {
            return TokenAmounts.toLedger(c.rawValue(), c.decimals());
        } catch (AmountOverflowException e) {
            return null;
        }
    }

    /** 系统池上的一个事务：先占坑，再动钱，再改状态。 */
    private static Outcome apply(DepositRepository repo, SystemLedger.Session session, DepositCandidate c, Verdict verdict) {
        if (c.isApproved()) {
            if (verdict.status() != DepositStatus.CREDITED) {
                return repo.holdApproved(c.approvedDepositId(), verdict.reason()) ? Outcome.HELD : Outcome.SKIPPED;
            }
            Optional<Long> claimed = repo.claimApproved(c.approvedDepositId(), verdict.amount(), verdict.occurredAt());
            if (claimed.isEmpty()) {
                return Outcome.SKIPPED;
            }
            return credit(repo, session, c, verdict, claimed.get());
        }
        if (verdict.status() != DepositStatus.CREDITED) {
            Optional<Long> claimed = repo.claim(c, verdict.status(), verdict.amount(), verdict.reason(), verdict.occurredAt());
            if (claimed.isEmpty()) {
                return Outcome.SKIPPED;
            }
            return verdict.status().isHeld() ? Outcome.HELD : Outcome.IGNORED;
        }
        Optional<Long> claimed = repo.claim(c, DepositStatus.POSTING, verdict.amount(), null, verdict.occurredAt());
        if (claimed.isEmpty()) {
            return Outcome.SKIPPED;                                   // 别的实例已处理：不碰账本
        }
        return credit(repo, session, c, verdict, claimed.get());
    }

    private static Outcome credit(DepositRepository repo, SystemLedger.Session session, DepositCandidate c, Verdict verdict, long depositId) {
        long custody = repo.ensureCustodyAccount(c.symbol());
        long transferId = session.ledger().transfer(new TransferCommand(
                c.idempotencyKey(), c.symbol(), verdict.amount(), custody, c.accountId(), TransferCode.DEPOSIT, verdict.occurredAt()));
        repo.credit(depositId, transferId);
        return Outcome.CREDITED;
    }

    /** 结构性异常：这一笔记成 HELD_ERROR（新事务），队列继续。 */
    private static Outcome holdWithError(DepositRepository repo, DepositCandidate c, Verdict verdict, String reason) {
        if (c.isApproved()) {
            return repo.holdApproved(c.approvedDepositId(), reason) ? Outcome.HELD : Outcome.SKIPPED;
        }
        return repo.claim(c, DepositStatus.HELD_ERROR, verdict.amount(), reason, verdict.occurredAt()).isPresent()
                ? Outcome.HELD : Outcome.SKIPPED;
    }

    private enum Outcome { CREDITED, HELD, IGNORED, SKIPPED }

    private record Verdict(DepositStatus status, BigDecimal amount, String reason, Instant occurredAt) {
        static Verdict held(DepositStatus status, BigDecimal amount, Instant occurredAt, String reason) {
            return new Verdict(status, amount, reason, occurredAt);
        }
    }

    /** 同一轮内块头与 finalized 复用。 */
    private final class RoundCache {
        private final Map<Long, BlockHeader> primaryHeaders = new HashMap<>();
        private final Map<Long, BlockHeader> auditHeaders = new HashMap<>();
        private Long primaryFinalized;
        private Long auditFinalized;

        BlockHeader primaryHeader(long number) {
            return primaryHeaders.computeIfAbsent(number, primary::block);
        }

        BlockHeader auditHeader(long number) {
            return auditHeaders.computeIfAbsent(number, audit::block);
        }

        long primaryFinalized() {
            if (primaryFinalized == null) {
                primaryFinalized = primary.block("finalized").number();
            }
            return primaryFinalized;
        }

        long auditFinalized() {
            if (auditFinalized == null) {
                auditFinalized = audit.block("finalized").number();
            }
            return auditFinalized;
        }
    }

    private static final class Counters {
        int examined;
        int credited;
        int held;
        int ignored;
        int skipped;

        void count(Outcome outcome, DepositCandidate c, Verdict verdict) {
            switch (outcome) {
                case CREDITED -> credited++;
                case HELD -> {
                    held++;
                    log.warn("入账 HELD：块 {} 日志 {} {} —— {}", c.blockNumber(), c.logIndex(), verdict.status(), verdict.reason());
                }
                case IGNORED -> ignored++;
                case SKIPPED -> skipped++;
            }
        }

        PostingResult retryLater(String detail) {
            return PostingResult.retryLater(examined, credited, held, ignored, skipped, detail);
        }

        PostingResult done() {
            return new PostingResult(examined, credited, held, ignored, skipped, false, null);
        }
    }
}
