package com.chainpay.chain.payout.service;

import com.chainpay.chain.payout.domain.PayoutAttempt;
import com.chainpay.chain.payout.domain.PayoutStatus;
import com.chainpay.chain.payout.domain.PayoutTxStatus;
import com.chainpay.chain.payout.domain.TrackResult;
import com.chainpay.chain.payout.repository.PayoutSendRepository;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.rpc.TransactionReceipt;
import com.chainpay.ledger.system.SystemLedger;
import com.chainpay.ledger.system.TransientDbFailure;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 追踪任务（M4-③）：广播之后只有回执能宣布结局，钱只在 FINAL 之后动。它只读链、只改状态、只在最后一步记账，从不签名、不广播。
 * <ol>
 *   <li><b>BROADCAST 的尝试</b>：问回执。有回执 → MINED（带块号块哈希，status 0 也是上链），同编号的兄弟全部 REPLACED；
 *       没回执但节点还认识它 → 继续等；节点不认识它 → 若有个兄弟正被节点认着，是被替身顶掉了（REPLACED），否则是节点忘了（DROPPED，发送任务原样重发）。</li>
 *   <li><b>MINED 的尝试</b>：主节点现在说那块的哈希变了 → 重组，退回 BROADCAST 继续等；审计节点意见不同 → 等；两个节点的 finalized 都过了那块 →
 *       结算（冻结 → 托管，CONFIRMED）或解冻（回执 status 0，FAILED）。这是 M3-② 那道门：动钱要两个节点都点头。</li>
 * </ol>
 * 网络永远不在事务里；节点失败这一轮提前结束，状态原地不动，下一轮再来。
 */
public final class PayoutTracker {

    private static final Logger log = LoggerFactory.getLogger(PayoutTracker.class);

    private final SystemLedger system;
    private final ChainReader primary;
    private final ChainReader audit;

    public PayoutTracker(SystemLedger system, ChainReader primary, ChainReader audit) {
        this.system = system;
        this.primary = primary;
        this.audit = audit;
    }

    public TrackResult trackOnce() {
        Counters k = new Counters();
        try {
            for (PayoutAttempt a : find(PayoutTxStatus.BROADCAST)) {
                k.examined++;
                Optional<TransactionReceipt> receipt = primary.transactionReceipt(a.txHash());      // 网络，事务外
                if (receipt.isPresent()) {
                    recordMined(a, receipt.get(), k);
                } else if (!primary.transactionKnown(a.txHash())) {
                    forgottenOrReplaced(a, k);
                }
            }
            RoundCache cache = new RoundCache();
            for (PayoutAttempt a : find(PayoutTxStatus.MINED)) {
                k.examined++;
                BlockHeader p = cache.primaryHeader(a.blockNumber());
                if (!p.hash().equalsIgnoreCase(a.blockHash())) {
                    reorged(a, p, k);
                    continue;
                }
                BlockHeader au = cache.auditHeader(a.blockNumber());
                if (!au.hash().equalsIgnoreCase(a.blockHash())) {
                    log.warn("审计节点对块 {} 的哈希意见不同：它说 {}，库里是 {}；提现 {} 不结算，等", a.blockNumber(), au.hash(), a.blockHash(), a.payoutId());
                    k.waiting++;
                    continue;
                }
                if (a.blockNumber() > cache.primaryFinalized() || a.blockNumber() > cache.auditFinalized()) {
                    k.waiting++;
                    continue;
                }
                settle(a, k);
            }
        } catch (JsonRpcException e) {
            return k.retryLater("节点失败，这一轮提前结束：" + e.getMessage());
        } catch (RuntimeException e) {
            if (TransientDbFailure.isTransient(e)) {
                return k.retryLater("数据库瞬时失败，这一轮提前结束：" + e.getMessage());
            }
            throw e;
        }
        return k.done();
    }

    private List<PayoutAttempt> find(PayoutTxStatus status) {
        return system.inTransaction(s -> new PayoutSendRepository(s.jdbc()).findAttemptsInStatus(status.name()));
    }

    /** 回执来了：尝试 MINED、提现 MINED、同编号的兄弟 REPLACED，一个事务。 */
    private void recordMined(PayoutAttempt a, TransactionReceipt r, Counters k) {
        system.inTransaction(s -> {
            PayoutSendRepository repo = new PayoutSendRepository(s.jdbc());
            PayoutTxStatus.BROADCAST.require(PayoutTxStatus.MINED);
            if (!repo.markMined(a.id(), r.blockNumber(), r.blockHash(), !r.success(), r.gasUsed(), r.effectiveGasPrice())) {
                return null;                                                            // 别的实例先记了
            }
            PayoutStatus.BROADCAST.require(PayoutStatus.MINED);
            if (!repo.moveStatus(a.payoutId(), PayoutStatus.BROADCAST.name(), PayoutStatus.MINED.name())) {
                throw new IllegalStateException("尝试 " + a.id() + " 刚记成 MINED，它的提现 " + a.payoutId() + " 却不在 BROADCAST");
            }
            k.mined++;
            k.replaced += repo.replaceSiblings(a.hotWallet(), a.nonce(), a.id());
            log.info("提现 {} 上链：块 {}（{}），{}", a.payoutId(), r.blockNumber(), a.txHash(), r.success() ? "执行成功" : "合约 revert");
            return null;
        });
    }

    /** 节点不认识这笔：被替身顶掉（有个兄弟正被节点认着）→ REPLACED；否则节点忘了 → DROPPED，发送任务原样重发。 */
    private void forgottenOrReplaced(PayoutAttempt a, Counters k) {
        List<PayoutAttempt> siblings = system.inTransaction(s -> new PayoutSendRepository(s.jdbc()).siblings(a.hotWallet(), a.nonce(), a.id()));
        boolean replaced = siblings.stream()
                .filter(sib -> !PayoutTxStatus.valueOf(sib.status()).isTerminal())
                .anyMatch(sib -> primary.transactionKnown(sib.txHash()));
        PayoutTxStatus to = replaced ? PayoutTxStatus.REPLACED : PayoutTxStatus.DROPPED;
        PayoutTxStatus.BROADCAST.require(to);
        system.inTransaction(s -> {
            if (new PayoutSendRepository(s.jdbc()).moveAttempt(a.id(), PayoutTxStatus.BROADCAST.name(), to.name())) {
                if (replaced) {
                    k.replaced++;
                } else {
                    k.dropped++;
                    log.warn("节点忘了提现 {} 的尝试 {}（编号 {}）：DROPPED，发送任务下一轮原样重发", a.payoutId(), a.txHash(), a.nonce());
                }
            }
            return null;
        });
    }

    /** 那块被重组掉了：尝试与提现都退回 BROADCAST，继续等回执。 */
    private void reorged(PayoutAttempt a, BlockHeader nowAt, Counters k) {
        log.warn("提现 {} 所在的块 {} 被重组（库里 {}，主节点现在说 {}）：退回 BROADCAST 继续等", a.payoutId(), a.blockNumber(), a.blockHash(), nowAt.hash());
        system.inTransaction(s -> {
            PayoutSendRepository repo = new PayoutSendRepository(s.jdbc());
            PayoutTxStatus.MINED.require(PayoutTxStatus.BROADCAST);
            if (!repo.unmine(a.id())) {
                return null;
            }
            PayoutStatus.MINED.require(PayoutStatus.BROADCAST);
            if (!repo.moveStatus(a.payoutId(), PayoutStatus.MINED.name(), PayoutStatus.BROADCAST.name())) {
                throw new IllegalStateException("尝试 " + a.id() + " 刚退回 BROADCAST，它的提现 " + a.payoutId() + " 却不在 MINED");
            }
            k.reorged++;
            return null;
        });
    }

    /** FINAL 了：先锁提现行看状态（别的实例可能先到），再记账、再改状态，一个事务。 */
    private void settle(PayoutAttempt a, Counters k) {
        system.inTransaction(s -> {
            PayoutSendRepository repo = new PayoutSendRepository(s.jdbc());
            if (!PayoutStatus.MINED.name().equals(repo.lockStatus(a.payoutId()))) {
                return null;
            }
            PayoutSendRepository.Settlement t = repo.findSettlement(a.payoutId());
            PayoutLedger ledger = new PayoutLedger(s.jdbc(), s.ledger());
            if (Boolean.TRUE.equals(a.reverted())) {
                PayoutStatus.MINED.require(PayoutStatus.FAILED);
                long reverse = ledger.reverse(t.payoutId(), t.symbol(), t.amount(), t.frozenAccountId(), t.userAccountId(), Instant.now());
                repo.markFailedFromMined(t.payoutId(), "链上执行失败（回执 status 0，块 " + a.blockNumber() + "）：编号已用、gas 已扣，转账没发生", reverse);
                k.failed++;
                log.warn("提现 {} FINAL 但合约 revert：解冻，FAILED", a.payoutId());
            } else {
                PayoutStatus.MINED.require(PayoutStatus.CONFIRMED);
                long settle = ledger.settle(t.payoutId(), t.symbol(), t.amount(), t.frozenAccountId(), t.custodyAccountId(), Instant.now());
                repo.markConfirmed(t.payoutId(), settle);
                k.confirmed++;
                log.info("提现 {} FINAL：结算 {} {}，CONFIRMED", a.payoutId(), t.amount(), t.symbol());
            }
            return null;
        });
    }

    /** 同一轮内块头与 finalized 复用，不逐笔重问（同 DepositPoster）。 */
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
        int mined;
        int confirmed;
        int failed;
        int dropped;
        int replaced;
        int reorged;
        int waiting;

        TrackResult done() {
            return new TrackResult(examined, mined, confirmed, failed, dropped, replaced, reorged, waiting, false, null);
        }

        TrackResult retryLater(String detail) {
            return new TrackResult(examined, mined, confirmed, failed, dropped, replaced, reorged, waiting, true, detail);
        }
    }
}
