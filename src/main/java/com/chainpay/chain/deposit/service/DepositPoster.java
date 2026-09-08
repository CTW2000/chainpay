package com.chainpay.chain.deposit.service;

import com.chainpay.chain.deposit.domain.DepositCandidate;
import com.chainpay.chain.deposit.domain.DepositStatus;
import com.chainpay.chain.deposit.domain.PostingResult;
import com.chainpay.chain.deposit.repository.DepositRepository;
import com.chainpay.chain.erc20.AmountOverflowException;
import com.chainpay.chain.erc20.TokenAmounts;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 入账任务：把已 FINAL 的链上转账记进账本。
 *
 * <p>每条候选三步：
 * <ol>
 *   <li><b>核对（网络，事务外）</b>：主节点与审计节点各取一次该块的头，哈希必须等于库里那行的 block_hash，
 *       块号必须不高于两个节点各自的 finalized。任何一条不满足 = HELD_NODE_DISAGREE，不记账。这是动钱的边界，
 *       索引器无论怎么错都过不了这道门（CLAUDE.md「M3 前置条件」）。同一轮内的块头与 finalized 复用，不逐笔重问（M3-before 第 25 问）</li>
 *   <li><b>判决（纯计算）</b>：零值 = IGNORED_ZERO；金额只经 {@link TokenAmounts#toLedger}，装不下 = HELD_OVERFLOW</li>
 *   <li><b>落库（系统池上一个事务）</b>：先占坑（INSERT … ON CONFLICT DO NOTHING，状态 POSTING），占不到 = 别的实例已处理，什么都不做；
 *       占到了才 ledger.transfer（幂等键 = 链上坐标），再把行改成 CREDITED。崩在中间整个事务回滚</li>
 * </ol>
 * 节点瞬时失败让这一轮提前结束（已提交的不受影响）；HELD 不卡队列。
 */
public final class DepositPoster {

    private static final Logger log = LoggerFactory.getLogger(DepositPoster.class);

    private final SystemLedger system;
    private final ChainReader primary;
    private final ChainReader audit;
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
        this.batchSize = batchSize;
        this.repositories = repositories;
    }

    public PostingResult postOnce() {
        return post(candidates());
    }

    List<DepositCandidate> candidates() {
        return system.inTransaction(s -> repositories.apply(s.jdbc()).findFinalUnposted(batchSize));
    }

    PostingResult post(List<DepositCandidate> candidates) {
        int examined = 0;
        int credited = 0;
        int held = 0;
        int ignored = 0;
        int skipped = 0;
        Map<Long, BlockHeader> primaryHeaders = new HashMap<>();
        Map<Long, BlockHeader> auditHeaders = new HashMap<>();
        Long primaryFinalized = null;
        Long auditFinalized = null;
        for (DepositCandidate c : candidates) {
            examined++;
            Verdict verdict;
            try {
                if (primaryFinalized == null) {
                    primaryFinalized = primary.block("finalized").number();
                    auditFinalized = audit.block("finalized").number();
                }
                BlockHeader p = primaryHeaders.computeIfAbsent(c.blockNumber(), primary::block);
                BlockHeader a = auditHeaders.computeIfAbsent(c.blockNumber(), audit::block);
                verdict = judge(c, p, a, primaryFinalized, auditFinalized);
            } catch (JsonRpcException e) {
                return PostingResult.retryLater(examined, credited, held, ignored, skipped,
                        "核对块 " + c.blockNumber() + " 时节点失败：" + e.getMessage());
            }
            Outcome outcome = system.inTransaction(s -> apply(repositories.apply(s.jdbc()), s, c, verdict));
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
        return new PostingResult(examined, credited, held, ignored, skipped, false, null);
    }

    /** 两个节点都点头才算 FINAL；然后零值、溢出。 */
    private static Verdict judge(DepositCandidate c, BlockHeader p, BlockHeader a, long primaryFinalized, long auditFinalized) {
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
        if (c.blockNumber() > primaryFinalized || c.blockNumber() > auditFinalized) {
            return Verdict.held(DepositStatus.HELD_NODE_DISAGREE, amountOrNull(c), occurredAt,
                    "块 " + c.blockNumber() + " 尚未在两个节点上 finalized（主节点 " + primaryFinalized + "，审计节点 " + auditFinalized + "）");
        }
        if (c.rawValue().signum() == 0) {
            return new Verdict(DepositStatus.IGNORED_ZERO, BigDecimal.ZERO, null, occurredAt);
        }
        try {
            return new Verdict(DepositStatus.CREDITED, TokenAmounts.toLedger(c.rawValue(), c.decimals()), null, occurredAt);
        } catch (AmountOverflowException e) {
            return Verdict.held(DepositStatus.HELD_OVERFLOW, null, occurredAt, e.getMessage());
        }
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
        long custody = repo.ensureCustodyAccount(c.symbol());
        long transferId = session.ledger().transfer(new TransferCommand(
                c.idempotencyKey(), c.symbol(), verdict.amount(), custody, c.accountId(), TransferCode.DEPOSIT, verdict.occurredAt()));
        repo.credit(claimed.get(), transferId);
        return Outcome.CREDITED;
    }

    private enum Outcome { CREDITED, HELD, IGNORED, SKIPPED }

    private record Verdict(DepositStatus status, BigDecimal amount, String reason, Instant occurredAt) {
        static Verdict held(DepositStatus status, BigDecimal amount, Instant occurredAt, String reason) {
            return new Verdict(status, amount, reason, occurredAt);
        }
    }
}
