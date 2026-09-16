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
import com.chainpay.chain.rpc.RpcAuthException;
import com.chainpay.chain.rpc.RpcFailure;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.ledger.system.SystemLedger;
import com.chainpay.ledger.system.TransientDbFailure;
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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 入账任务：把已 FINAL 的链上转账记进账本。
 *
 * <p>每条候选三步：
 * <ol>
 *   <li><b>核对（网络，事务外）</b>：主节点与审计节点各取一次该块的头，哈希必须等于库里那行的 block_hash，
 *       块号必须不高于两个节点各自的 finalized。哈希对不上 = HELD_NODE_DISAGREE 叫人；finalized 还没到则分两种：
 *       两个节点之间的差距、以及「库里的视图比两个节点都超前」的幅度都在 {@code finality-tolerance-blocks} 以内 =
 *       <b>这一轮延后</b>（不占坑、不写库，下一轮再看，节点追上来就自己记上）；超出 = HELD_NODE_DISAGREE 叫人去看节点。
 *       两条路都不记账，动钱的边界没有松。同一轮内的块头与 finalized 复用，不逐笔重问（M3-before 第 25 问）</li>
 *   <li><b>判决</b>：零值 = IGNORED_ZERO；金额只经 {@link TokenAmounts#toLedger}，装不下 = HELD_OVERFLOW；
 *       低于代币的最小入账额 = REJECTED_DUST；然后<b>信合约做的，不信合约说的</b>——向两个节点问该地址在那一块的 balanceOf，
 *       必须等于事件累计（转入减转出），对不上或问不到 = HELD_BALANCE_MISMATCH（M3-③）</li>
 *   <li><b>落库（系统池上一个事务）</b>：先占坑（INSERT … ON CONFLICT DO NOTHING，状态 POSTING），占不到 = 别的实例已处理，什么都不做；
 *       占到了才 ledger.transfer（幂等键 = 链上坐标），再把行改成 CREDITED。崩在中间整个事务回滚</li>
 * </ol>
 * 失败分三种：瞬时的（节点、数据库）让这一轮提前结束、已提交的不受影响；重试永远没用的（节点撤了我们的凭证）让这一轮 HALTED、叫人；
 * 结构性的那一笔记成 HELD_ERROR 带异常原文，队列继续。余额问不到时先按 {@link RpcFailure} 分类：合约 revert 是节点的最终回答、当场 HELD，
 * 不认识的错误码只把这一笔延后（别的候选照常），连续 {@link #UNKNOWN_BALANCE_ROUNDS} 轮仍是它才 HELD。
 * 人复核 HELD 之后改成 APPROVED 的行，下一轮不再核对、只重新占坑与记账——人永远不用手工碰账本表。
 */
public final class DepositPoster {

    private static final Logger log = LoggerFactory.getLogger(DepositPoster.class);

    /**
     * 余额问不到、而错误码又不认识时，先延后几轮再下结论——只延后这一笔，别的候选照常。5 轮 ≈ 两分半钟（post-interval 30 秒）：
     * 后端落后、网关抖动都在这个尺度上自己好；真好不了的才值得占住人工队列的一个位置。
     * 计数只在内存里，进程重启从头数——代价是多等几轮，不会漏记也不会重记。
     */
    static final int UNKNOWN_BALANCE_ROUNDS = 5;

    private final SystemLedger system;
    private final ChainReader primary;
    private final ChainReader audit;
    private final Erc20Calls primaryCalls;
    private final Erc20Calls auditCalls;
    private final int batchSize;
    private final long finalityToleranceBlocks;
    private final Function<JdbcClient, DepositRepository> repositories;
    /** 日志 id → 「余额问不到且错误码不认识」的连续轮数。只有入账这一个线程动它（调度器串行跑）。 */
    private final Map<Long, Integer> unknownBalanceRounds = new HashMap<>();

    public DepositPoster(SystemLedger system, ChainReader primary, ChainReader audit, int batchSize, long finalityToleranceBlocks) {
        this(system, primary, audit, batchSize, finalityToleranceBlocks, DepositRepository::new);
    }

    DepositPoster(SystemLedger system, ChainReader primary, ChainReader audit, int batchSize, long finalityToleranceBlocks,
                  Function<JdbcClient, DepositRepository> repositories) {
        this.system = system;
        this.primary = primary;
        this.audit = audit;
        this.primaryCalls = new Erc20Calls(primary);
        this.auditCalls = new Erc20Calls(audit);
        this.batchSize = batchSize;
        if (finalityToleranceBlocks < 0) {
            throw new IllegalArgumentException("finality-tolerance-blocks 不能为负：" + finalityToleranceBlocks);
        }
        this.finalityToleranceBlocks = finalityToleranceBlocks;
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
            } catch (RpcAuthException e) {                            // 子类写在前面：凭证被撤销不会自己好，不能混进瞬时那一桶
                return k.halt("节点拒绝了我们的凭证（" + e.getMessage() + "）：key 失效或被撤销，重试永远没用，换 key 后重启");
            } catch (JsonRpcException e) {
                return k.retryLater("核对块 " + c.blockNumber() + " 时节点失败：" + e.getMessage());
            } catch (RuntimeException e) {
                if (!TransientDbFailure.isTransient(e)) {
                    throw e;
                }
                return k.retryLater("核对块 " + c.blockNumber() + " 时数据库瞬时失败：" + e.getMessage());
            }
            if (verdict.deferred()) {
                k.defer(c, verdict);
                continue;                                             // 不占坑：下一轮它还在候选里
            }
            Outcome outcome;
            try {
                outcome = system.inTransaction(s -> apply(repositories.apply(s.jdbc()), s, c, verdict));
            } catch (RuntimeException e) {
                if (TransientDbFailure.isTransient(e)) {             // 锁等超时、拿不到连接、事务开不出来：下一轮再来，不进 HELD
                    return k.retryLater("记块 " + c.blockNumber() + " 时数据库瞬时失败：" + e.getMessage());
                }
                String reason = "入账时异常：" + e;
                log.error("入账 HELD_ERROR：块 {} 日志 {} —— {}", c.blockNumber(), c.logIndex(), reason);
                outcome = system.inTransaction(s -> holdWithError(repositories.apply(s.jdbc()), c, verdict, reason));
            }
            k.count(outcome, c);
        }
        return k.done();
    }

    /** 两个节点都点头才算 FINAL；然后零值、溢出、灰尘；最后信合约做的。 */
    private Verdict judge(DepositCandidate c, RoundCache cache) {
        BlockHeader p = cache.primaryHeader(c.blockNumber());
        BlockHeader a = cache.auditHeader(c.blockNumber());
        Instant occurredAt = Instant.ofEpochSecond(p.timestamp());
        if (!p.hash().equalsIgnoreCase(c.blockHash())) {          // 节点给的大小写不受我们控制，库里是小写
            return Verdict.held(DepositStatus.HELD_NODE_DISAGREE, amountOrNull(c), occurredAt,
                    "主节点现在说块 " + c.blockNumber() + " 的哈希是 " + p.hash() + "，库里是 " + c.blockHash()
                            + "：索引之后节点改口，或曾发生重组而视图仍判 FINAL");
        }
        if (!a.hash().equalsIgnoreCase(c.blockHash())) {
            return Verdict.held(DepositStatus.HELD_NODE_DISAGREE, amountOrNull(c), occurredAt,
                    "审计节点对块 " + c.blockNumber() + " 的哈希意见不同：它说 " + a.hash() + "，库里是 " + c.blockHash());
        }
        long primaryFinalized = cache.primaryFinalized();
        long auditFinalized = cache.auditFinalized();
        if (c.blockNumber() > Math.min(primaryFinalized, auditFinalized)) {
            long ahead = c.blockNumber() - Math.max(primaryFinalized, auditFinalized);   // 库里的视图比两个节点都超前多少
            long spread = Math.abs(primaryFinalized - auditFinalized);                   // 两个节点之间差多少
            if (ahead <= finalityToleranceBlocks && spread <= finalityToleranceBlocks) {
                return Verdict.notYet("块 " + c.blockNumber() + " 还没在两个节点上 finalized（主节点 " + primaryFinalized
                        + "，审计节点 " + auditFinalized + "）：等下一轮，不占坑");
            }
            return Verdict.held(DepositStatus.HELD_NODE_DISAGREE, amountOrNull(c), occurredAt,
                    "块 " + c.blockNumber() + " 尚未在两个节点上 finalized（主节点 " + primaryFinalized
                            + "，审计节点 " + auditFinalized + "），超出 " + finalityToleranceBlocks
                            + " 块的容忍：等不回来了，去看节点");
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
        Optional<Verdict> balance = balanceVerdict(c, amount, occurredAt);
        if (balance.isPresent()) {
            return balance.get();                                           // HELD_BALANCE_MISMATCH，或「这一笔先不判」
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
    private Optional<Verdict> balanceVerdict(DepositCandidate c, BigDecimal amount, Instant occurredAt) {
        BigInteger expected = system.inTransaction(s -> repositories.apply(s.jdbc()).netTransfersUpTo(c.toAddress(), c.token(), c.blockNumber()));
        String tag = Hex.fromLong(c.blockNumber());
        BigInteger onPrimary;
        BigInteger onAudit;
        try {
            onPrimary = primaryCalls.balanceOf(c.token(), c.toAddress(), tag);
        } catch (JsonRpcException e) {
            return unreadable(c, "主节点", e, amount, occurredAt);
        } catch (IllegalArgumentException e) {
            return Optional.of(Verdict.held(DepositStatus.HELD_BALANCE_MISMATCH, amount, occurredAt, "主节点的余额答复不成形：" + e.getMessage()));
        }
        try {
            onAudit = auditCalls.balanceOf(c.token(), c.toAddress(), tag);
        } catch (JsonRpcException e) {
            return unreadable(c, "审计节点", e, amount, occurredAt);
        } catch (IllegalArgumentException e) {
            return Optional.of(Verdict.held(DepositStatus.HELD_BALANCE_MISMATCH, amount, occurredAt, "审计节点的余额答复不成形：" + e.getMessage()));
        }
        unknownBalanceRounds.remove(c.logId());                             // 这一笔问到了：重试预算清零
        if (!onPrimary.equals(expected)) {
            return Optional.of(Verdict.held(DepositStatus.HELD_BALANCE_MISMATCH, amount, occurredAt,
                    "块 " + c.blockNumber() + " 上 " + c.toAddress() + " 的余额：合约做的 " + onPrimary
                    + "，事件累计（转入减转出）" + expected + "，差 " + onPrimary.subtract(expected)
                    + "。转账扣费 / 弹性供应 / 静默铸币 / 节点撒谎之一，人看"));
        }
        if (!onAudit.equals(onPrimary)) {
            return Optional.of(Verdict.held(DepositStatus.HELD_BALANCE_MISMATCH, amount, occurredAt,
                    "审计节点给的余额 " + onAudit + " 与主节点 " + onPrimary + " 不同（块 " + c.blockNumber() + "）"));
        }
        return Optional.empty();
    }

    /**
     * 余额问不到时，它到底意味着什么（M3-③ 补丁，2026-09-16）。{@link RpcFailure} 把「错误从哪一层来」翻成「该做什么」：
     * <ul>
     *   <li>没问到、凭证被拒：原样抛出——前者下一轮再来，后者由 {@link #post} 升级成停下叫人</li>
     *   <li>合约 revert：这是节点的最终回答，当场 HELD 等人</li>
     *   <li>带码但不认识（后端落后、非归档、配额）：<b>只把这一笔延后</b>——不占坑、不写库、这一轮接着看别的候选——
     *       连续 {@link #UNKNOWN_BALANCE_ROUNDS} 轮仍是它才 HELD。「不知道」既不能当成对上了，也不该第一时间就占住人工队列的位置；
     *       而一笔答不上来（非归档节点答不出某个老块最常见）也不该拖住排在它后面的所有入账（2026-09-15 审核改：此前是结束整轮）</li>
     * </ul>
     */
    private Optional<Verdict> unreadable(DepositCandidate c, String node, JsonRpcException e, BigDecimal amount, Instant occurredAt) {
        String problem = node + "问不到块 " + c.blockNumber() + " 上 " + c.toAddress() + " 的余额：" + e.getMessage();
        return switch (RpcFailure.of(e)) {
            case NO_ANSWER, NOT_ALLOWED -> throw e;
            case ANSWER -> {
                unknownBalanceRounds.remove(c.logId());
                yield Optional.of(Verdict.held(DepositStatus.HELD_BALANCE_MISMATCH, amount, occurredAt, problem));
            }
            case UNKNOWN -> {
                int rounds = unknownBalanceRounds.merge(c.logId(), 1, Integer::sum);
                if (rounds < UNKNOWN_BALANCE_ROUNDS) {
                    yield Optional.of(Verdict.notYet("余额问不到、错误码不认识（第 " + rounds + " 轮）：" + e.getMessage()
                            + "。只延后这一笔，下一轮再问"));
                }
                unknownBalanceRounds.remove(c.logId());
                yield Optional.of(Verdict.held(DepositStatus.HELD_BALANCE_MISMATCH, amount, occurredAt,
                        problem + "（不认识的错误码，重试 " + rounds + " 轮仍是它）"));
            }
        };
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
                return repo.holdApproved(c.approvedDepositId(), verdict.reason())
                        ? Outcome.written(DepositStatus.HELD_ERROR, verdict.reason())   // holdApproved 的 SQL 写死 HELD_ERROR
                        : Outcome.SKIPPED;
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
            return Outcome.written(verdict.status(), verdict.reason());
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
        return Outcome.written(DepositStatus.CREDITED, null);
    }

    /** 结构性异常：这一笔记成 HELD_ERROR（新事务），队列继续。 */
    private static Outcome holdWithError(DepositRepository repo, DepositCandidate c, Verdict verdict, String reason) {
        if (c.isApproved()) {
            return repo.holdApproved(c.approvedDepositId(), reason)
                    ? Outcome.written(DepositStatus.HELD_ERROR, reason) : Outcome.SKIPPED;
        }
        return repo.claim(c, DepositStatus.HELD_ERROR, verdict.amount(), reason, verdict.occurredAt()).isPresent()
                ? Outcome.written(DepositStatus.HELD_ERROR, reason) : Outcome.SKIPPED;
    }

    /**
     * 落库的结果：写进 deposit 表的<b>实际</b>状态与原因，不是判决阶段的打算（2026-09-15 改）。
     *
     * <p>两者在异常路径上会分叉：判决说记账，落库崩了，实际写进去的是 HELD_ERROR。
     * 以前这里是一个四值枚举，状态名与原因在 {@code return} 那一刻就丢了，计数与日志只好回头读 {@link Verdict}——
     * 于是那行 WARN 播报的是「原打算」（{@code CREDITED —— null}），和前一行 ERROR 自相矛盾。
     * 现在事实带在返回值里；{@code kind} 由 {@code status} 推出，两者不可能各说一套。
     */
    private record Outcome(Kind kind, DepositStatus status, String reason) {

        enum Kind { CREDITED, HELD, IGNORED, SKIPPED }

        /** 别的实例已经处理过这条日志：我们一行都没写，也就没有状态可报。 */
        static final Outcome SKIPPED = new Outcome(Kind.SKIPPED, null, null);

        /** 写进库了。kind 不另外传，从 status 推：让「分类」和「状态」永远出自同一个事实。 */
        static Outcome written(DepositStatus status, String reason) {
            Kind kind = status == DepositStatus.CREDITED ? Kind.CREDITED : status.isHeld() ? Kind.HELD : Kind.IGNORED;
            return new Outcome(kind, status, reason);
        }
    }

    private record Verdict(DepositStatus status, BigDecimal amount, String reason, Instant occurredAt) {
        static Verdict held(DepositStatus status, BigDecimal amount, Instant occurredAt, String reason) {
            return new Verdict(status, amount, reason, occurredAt);
        }

        /** 「这一轮先不判」：状态为空 = 没有结论，不占坑、不写库，下一轮再看。 */
        static Verdict notYet(String reason) {
            return new Verdict(null, null, reason, null);
        }

        boolean deferred() {
            return status == null;
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
        int deferred;

        void defer(DepositCandidate c, Verdict verdict) {
            deferred++;
            log.info("入账延后：块 {} 日志 {} —— {}", c.blockNumber(), c.logIndex(), verdict.reason());
        }

        /** 播报的是库里实际写了什么（{@link Outcome}），不是判决阶段的打算——两者在异常路径上不一样。 */
        void count(Outcome outcome, DepositCandidate c) {
            switch (outcome.kind()) {
                case CREDITED -> credited++;
                case HELD -> {
                    held++;
                    log.warn("入账 HELD：块 {} 日志 {} {} —— {}", c.blockNumber(), c.logIndex(), outcome.status(), outcome.reason());
                }
                case IGNORED -> ignored++;
                case SKIPPED -> skipped++;
            }
        }

        PostingResult retryLater(String detail) {
            return PostingResult.retryLater(examined, credited, held, ignored, skipped, deferred, detail);
        }

        /** 这一轮没跑完，而且不会自己好：要人。 */
        PostingResult halt(String detail) {
            return PostingResult.halted(examined, credited, held, ignored, skipped, deferred, detail);
        }

        PostingResult done() {
            return new PostingResult(examined, credited, held, ignored, skipped, deferred, PostingResult.Ending.COMPLETED, null);
        }
    }
}
