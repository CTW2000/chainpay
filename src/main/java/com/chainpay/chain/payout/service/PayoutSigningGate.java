package com.chainpay.chain.payout.service;

import com.chainpay.chain.erc20.Abi;
import com.chainpay.chain.payout.domain.HotWallet;
import com.chainpay.chain.payout.domain.PayoutAttempt;
import com.chainpay.chain.payout.domain.PayoutStatus;
import com.chainpay.chain.payout.repository.HotWalletRepository;
import com.chainpay.chain.payout.repository.PayoutSendRepository;
import com.chainpay.chain.payout.repository.PayoutSendRepository.ReviewFacts;
import com.chainpay.chain.wallet.EthAddress;
import com.chainpay.chain.wallet.Eip1559Transaction;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.ledger.service.LedgerAmounts;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排队中的提现只从这里出去：要么签名，要么判失败解冻（进程拆分第 ③ 步的 8a；取舍 8、9）。热钱包签名器只在这里，发送任务手里没有私钥；
 * 将来把签名搬进独立进程，这个类整个搬过去，调用方只把方法调用换成远程调用。
 *
 * <p><b>为什么要复核</b>：拆分之后 web 可能被攻破，而提现行是它写的。行里没有「web 当时核过白名单、问过平台地址、算过限额」的证据，
 * 所以动钱之前按 id 从库里重读这一行，只核应用角色写不了的事实：
 * <ul>
 *   <li>冻结分录：同一商户、同币同额、从可用转进冻结（账本只追加，已经记下的分录 web 改不了）。判失败时解冻的金额也来自这一行，所以判失败之前同样要核</li>
 *   <li>收款地址：在这个商户的白名单里且 ACTIVE（外键挡住不在白名单的；停用外键挡不住，这里核）；不是平台自己的地址</li>
 *   <li>额度（只在签名前）：有核准标记（只有系统身份写得了，V31），或者单笔、当日都在限额内——当日按商户算，只算自动放行出去的</li>
 * </ul>
 * 复核不过按原因分三种处置（用户 2026-09-24 定）：像被攻破的（冻结对不上、白名单里没有、是平台地址）→ 整把钱包停发、叫人，
 * 这一笔原地不动、钱还冻着；商户在排队期间停用了地址 → 这一笔判失败、解冻；超限又没人核准 → 退回待核准。
 *
 * <p><b>锁</b>：签名的系统事务里依次拿三把——这一笔提现（认领）、这个商户的额度锁（同一商户的额度核对串行）、最后才是热钱包（拿编号）。
 * 只有「拿编号 → 签名 → 记尝试 → 编号 +1」是全平台串行的：同一个地址发出的交易编号必须连续。复核只锁到商户这一级，
 * 不同商户的提现可以同时复核。顺序固定为 提现 → 商户 → 热钱包，申请、核准、拒绝、结算都不会反着拿。
 */
public class PayoutSigningGate {

    private static final Logger log = LoggerFactory.getLogger(PayoutSigningGate.class);

    /** 一次签名的结局。 */
    public sealed interface Result permits Signed, Refused {}

    /** 签好了、落了库（SIGNED）：调用方提交后再广播。 */
    public record Signed(SignedRecord record) implements Result {}

    /** 没签。除了 {@link Refusal#TAKEN}，处置都已经随本事务提交。 */
    public record Refused(Refusal kind, String reason) implements Result {}

    public enum Refusal {
        /** 这笔已经不在「放行」：别的实例先签了，或已被拒、判失败。什么都没写，跳过。 */
        TAKEN,
        /** 钱包停发：本来就停着，或复核发现这一行不像按规矩申请来的、刚停下。这一轮到此为止，叫人。 */
        WALLET_HALTED,
        /** 这一笔判失败、解冻，队列继续：商户在排队期间停用了收款地址，或者发不出去（估 gas 就 revert、gas 超上限）。 */
        FAILED,
        /** 超限又没人核准：退回待核准，队列继续。 */
        BACK_TO_APPROVAL
    }

    record SignedRecord(long attemptId, long nonce, String rawHex, String txHash) {}

    private final HotWalletRepository wallets;
    private final PayoutSendRepository repo;
    private final PayoutLedger ledger;
    private final HotWalletSigner signer;
    private final long chainId;
    private final String wallet;

    public PayoutSigningGate(JdbcClient systemJdbc, LedgerService systemLedger, HotWalletSigner signer, long chainId) {
        this.wallets = new HotWalletRepository(systemJdbc);
        this.repo = new PayoutSendRepository(systemJdbc);
        this.ledger = new PayoutLedger(systemJdbc, systemLedger);
        this.signer = signer;
        this.chainId = chainId;
        this.wallet = EthAddress.lowercase(signer.address());
    }

    /** 热钱包的地址，库里的写法（小写）。 */
    public String wallet() {
        return wallet;
    }

    public long chainId() {
        return chainId;
    }

    /** 签第 N 笔提现：认领、复核、处置或签名落库，一个系统事务。费率由调用方在事务外问好；收款人与金额只从库里这一行来。 */
    @Transactional(SystemLedger.QUALIFIER)
    public Result sign(long payoutId, FeePolicy.Fees quoted) {
        Optional<ReviewFacts> locked = repo.lockForReview(payoutId);                          // ① 认领这一笔
        if (locked.isEmpty() || !PayoutStatus.QUEUED.name().equals(locked.get().status())) {
            return new Refused(Refusal.TAKEN, "提现 " + payoutId + " 已不在放行状态");
        }
        ReviewFacts p = locked.get();
        repo.lockMerchantForLimits(p.merchantId());                                             // ② 同一商户的额度核对串行

        String suspicious = suspicious(p);                                                       // ③ 复核
        if (suspicious != null) {
            return halt(suspicious);
        }
        if (!"ACTIVE".equals(p.whitelistStatus())) {
            return failAndUnfreeze(p, "收款地址 " + p.toAddress() + " 在排队期间被商户停用：这一笔不发，解冻");
        }
        String overLimit = overLimit(p);
        if (overLimit != null) {
            PayoutStatus.QUEUED.require(PayoutStatus.PENDING_APPROVAL);
            repo.moveStatus(p.id(), PayoutStatus.QUEUED.name(), PayoutStatus.PENDING_APPROVAL.name());
            log.warn("提现 {} 退回待核准：{}", p.id(), overLimit);
            return new Refused(Refusal.BACK_TO_APPROVAL, overLimit);
        }

        HotWallet row = wallets.lockForUpdate(wallet)                                           // ④ 只有这一步锁热钱包
                .orElseThrow(() -> new IllegalStateException("热钱包行不见了：" + wallet));
        if (row.halted()) {
            return new Refused(Refusal.WALLET_HALTED, row.haltReason());
        }
        PayoutStatus.QUEUED.require(PayoutStatus.SIGNED);
        if (!repo.moveStatus(p.id(), PayoutStatus.QUEUED.name(), PayoutStatus.SIGNED.name())) {
            throw new IllegalStateException("提现 " + p.id() + " 锁着且在放行状态，却改不成 SIGNED");
        }
        long nonce = row.nextNonce();
        Eip1559Transaction tx = new Eip1559Transaction(chainId, nonce, quoted.maxPriorityFeePerGas(), quoted.maxFeePerGas(), quoted.gasLimit(),
                p.token(), BigInteger.ZERO, transferCalldata(p.toAddress(), p.rawValue()));
        SignedRecord record = signAndStore(p.id(), nonce, tx, quoted.gasLimit(), quoted);
        wallets.advanceNonce(wallet, nonce);
        return new Signed(record);
    }

    /**
     * 这一笔发不出去（估 gas 就 revert、gas 超上限）：判失败、解冻，编号还没分出去。一个系统事务。
     * 解冻的金额来自 web 写的那一行，所以和签名走同一道复核：被攻破的 web 可以故意把金额写得比热钱包余额还大，让估 gas 必然失败——
     * 不核就按这个金额解冻，多出来的部分挪的是同一商户其它在途提现的冻结款。像被攻破的 → 整把钱包停发、不解冻。
     * 两个实例同时看到同一笔 revert：后到的等先到的提交（行锁），看见已经 FAILED 就走。
     */
    @Transactional(SystemLedger.QUALIFIER)
    public Refused fail(long payoutId, String reason) {
        Optional<ReviewFacts> locked = repo.lockForReview(payoutId);
        if (locked.isEmpty() || !PayoutStatus.QUEUED.name().equals(locked.get().status())) {
            return new Refused(Refusal.TAKEN, "提现 " + payoutId + " 已不在放行状态");
        }
        ReviewFacts p = locked.get();
        String suspicious = suspicious(p);
        if (suspicious != null) {
            return halt(suspicious);
        }
        return failAndUnfreeze(p, reason);
    }

    /**
     * 加价的替身：同一个编号、同一笔转账，只换费率。原文按 id 从库里那次尝试读（应用角色写不了 payout_tx），不收调用方递来的原文。
     * 先落库（SIGNED、编号不动）再由调用方广播。返回空 = 那次尝试不是这把钱包的。
     */
    public Optional<SignedRecord> signReplacement(long attemptId, FeePolicy.Fees bumped) {
        Optional<PayoutAttempt> found = repo.findAttempt(attemptId).filter(a -> wallet.equals(a.hotWallet()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PayoutAttempt stuck = found.get();
        Eip1559Transaction old = Eip1559Transaction.decode(HexFormat.of().parseHex(stuck.rawHex().substring(2))).transaction();
        Eip1559Transaction tx = new Eip1559Transaction(chainId, stuck.nonce(), bumped.maxPriorityFeePerGas(), bumped.maxFeePerGas(), stuck.gasLimit(),
                old.to(), old.value(), old.data());
        return Optional.of(signAndStore(stuck.payoutId(), stuck.nonce(), tx, stuck.gasLimit(), bumped));
    }

    /** transfer(address,uint256) 的 calldata，编码在 {@link Abi#transfer}（ABI 编码只在那一处）。 */
    static byte[] transferCalldata(String to, BigInteger rawValue) {
        return HexFormat.of().parseHex(Abi.transfer(to, rawValue).substring(2));
    }

    // ------------------------------------------------------------------ 复核与处置

    /** 像被攻破的：这一行不是按规矩申请来的。返回停发原因；null = 没问题。 */
    private static String suspicious(ReviewFacts p) {
        if (!p.freezeMatches()) {
            return "提现 " + p.id() + " 的冻结分录对不上（要同一商户、同币同额、从可用转进冻结）：不签、不解冻，整把钱包停发——这一行不像按规矩申请来的，查 web";
        }
        if (p.whitelistStatus() == null) {
            return "提现 " + p.id() + " 的收款地址 " + p.toAddress() + " 不在这个商户的白名单里：不签、不解冻，整把钱包停发——外键本该挡住它，查库";
        }
        if (p.platformAddress()) {
            return "提现 " + p.id() + " 的收款地址 " + p.toAddress() + " 是平台自己的地址：不签、不解冻，整把钱包停发——申请时就该被拒，查 web";
        }
        return null;
    }

    /** 超限又没人核准：返回原因；null = 可以发。当日额度要在拿了商户的额度锁之后再算。 */
    private String overLimit(ReviewFacts p) {
        if (p.approvedBy() != null) {
            return null;                                                                          // 有人核准过：不看限额
        }
        if (p.perTxMax() == null) {
            return "这种代币没定限额，只能人工核准";
        }
        if (p.amount().compareTo(p.perTxMax()) > 0) {
            return "金额 " + LedgerAmounts.text(p.amount()) + " 超过单笔上限 " + LedgerAmounts.text(p.perTxMax()) + "，没有人核准";
        }
        BigDecimal released = repo.releasedWithoutApproval(p.merchantId(), p.token(), p.createdAt());
        if (released.add(p.amount()).compareTo(p.dailyMax()) > 0) {
            return "这个商户当天已自动放行 " + LedgerAmounts.text(released) + "，加上这笔 " + LedgerAmounts.text(p.amount())
                    + " 超过当日上限 " + LedgerAmounts.text(p.dailyMax()) + "，没有人核准";
        }
        return null;
    }

    private Refused halt(String reason) {
        log.error("热钱包 {} 停发：{}", wallet, reason);
        wallets.halt(wallet, reason);
        return new Refused(Refusal.WALLET_HALTED, reason);
    }

    /** 判失败、解冻。只在冻结分录核过之后调：解冻的金额 = 这一行的金额 = 当初冻结的金额。 */
    private Refused failAndUnfreeze(ReviewFacts p, String reason) {
        PayoutStatus.QUEUED.require(PayoutStatus.FAILED);
        long reverse = ledger.reverse(p.id(), p.symbol(), p.amount(), p.frozenAccountId(), p.userAccountId(), Instant.now());
        repo.markFailed(p.id(), reason, reverse);
        log.warn("提现 {} 判失败：{}", p.id(), reason);
        return new Refused(Refusal.FAILED, reason);
    }

    private SignedRecord signAndStore(long payoutId, long nonce, Eip1559Transaction tx, long gasLimit, FeePolicy.Fees fees) {
        Eip1559Transaction.Signed signed = signer.sign(tx);
        String rawHex = "0x" + HexFormat.of().formatHex(signed.raw());
        String txHash = "0x" + HexFormat.of().formatHex(signed.hash());
        long attemptId = repo.insertAttempt(payoutId, wallet, nonce, txHash, rawHex, gasLimit, fees.maxFeePerGas(), fees.maxPriorityFeePerGas());
        return new SignedRecord(attemptId, nonce, rawHex, txHash);
    }
}
