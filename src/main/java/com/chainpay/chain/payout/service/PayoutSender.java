package com.chainpay.chain.payout.service;

import com.chainpay.chain.payout.domain.HotWallet;
import com.chainpay.chain.payout.domain.PayoutAttempt;
import com.chainpay.chain.payout.domain.PayoutStatus;
import com.chainpay.chain.payout.domain.PayoutTxStatus;
import com.chainpay.chain.payout.domain.QueuedPayout;
import com.chainpay.chain.payout.domain.SendResult;
import com.chainpay.chain.payout.repository.HotWalletRepository;
import com.chainpay.chain.payout.repository.PayoutSendRepository;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.ChainSender;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.wallet.Eip1559Transaction;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 发送任务的一轮（M4-②）。顺序是硬的：
 * <ol>
 *   <li><b>对账</b>：链上计数 C（网络，事务外）；事务里锁热钱包行，核 N = C + U——库里的下一个编号 N 是意图，链上的计数 C 是真相，
 *       U 是还没结局的编号数。C &gt; N = 有人在别处用了这把私钥，整把钱包停发叫人；N &gt; C + U = 有编号没有尝试记录，同样停下。</li>
 *   <li><b>重发</b>：所有 SIGNED 的尝试原样重发——进程死在提交与广播之间留下的就是它们；节点回 already known 也算成功。</li>
 *   <li><b>排队的</b>：每笔先在事务外估 gas、取费率（合约 revert = 这笔发不出去，判失败并解冻，编号还没分）；
 *       再在一个事务里锁行、拿编号、签名、写 SIGNED 尝试、编号 +1、提交；<b>提交之后</b>才广播；成功再开短事务改 BROADCAST。</li>
 * </ol>
 * 网络永远不在事务里：握着行锁等节点会把另一个实例和连接池一起拖住。广播的回答按 {@link Outcome} 分类：瞬时的下一轮再来，
 * 结构性的停整把钱包——编号是全局的，一笔卡住后面全部卡住。
 */
public final class PayoutSender {

    private static final Logger log = LoggerFactory.getLogger(PayoutSender.class);
    private static final String TRANSFER_SELECTOR = "a9059cbb";

    private final SystemLedger system;
    private final ChainReader primary;
    private final ChainSender sender;
    private final HotWalletSigner signer;
    private final FeePolicy fees;
    private final long chainId;
    private final String chainName;
    private final int batchSize;

    public PayoutSender(SystemLedger system, ChainReader primary, ChainSender sender, HotWalletSigner signer,
                        FeePolicy fees, long chainId, int batchSize) {
        this(system, primary, sender, signer, fees, chainId, "chain-" + chainId, batchSize);
    }

    public PayoutSender(SystemLedger system, ChainReader primary, ChainSender sender, HotWalletSigner signer,
                        FeePolicy fees, long chainId, String chainName, int batchSize) {
        this.system = system;
        this.primary = primary;
        this.sender = sender;
        this.signer = signer;
        this.fees = fees;
        this.chainId = chainId;
        this.chainName = chainName;
        this.batchSize = batchSize;
    }

    public SendResult sendOnce() {
        String wallet = signer.address().toLowerCase(Locale.ROOT);
        Counters k = new Counters();

        // 1. 对账：链上计数在事务外问，核对与停发在事务里做
        long onChain;
        try {
            onChain = primary.transactionCount(wallet, "latest").longValueExact();
        } catch (JsonRpcException e) {
            return k.retryLater("问链上计数时节点失败：" + e.getMessage());
        }
        String halt = system.inTransaction(s -> reconcile(s.jdbc(), wallet, onChain));
        if (halt != null) {
            return SendResult.halted(halt);
        }

        // 2. 重发：SIGNED 的尝试原样重发
        List<PayoutAttempt> signed = system.inTransaction(s -> new PayoutSendRepository(s.jdbc()).findAttempts(wallet, PayoutTxStatus.SIGNED.name()));
        for (PayoutAttempt attempt : signed) {
            Outcome outcome = broadcast(attempt.rawHex(), attempt.txHash(), attempt.nonce());
            switch (outcome.kind()) {
                case OK -> {
                    markBroadcast(attempt.id(), attempt.payoutId());
                    k.resent++;
                }
                case TRANSIENT -> {
                    return k.retryLater(outcome.detail());
                }
                case HALT -> {
                    haltWallet(wallet, outcome.detail());
                    return k.halted(outcome.detail());
                }
            }
        }

        // 3. 排队的
        List<QueuedPayout> queue = system.inTransaction(s -> new PayoutSendRepository(s.jdbc()).findQueued(batchSize));
        for (QueuedPayout payout : queue) {
            k.examined++;
            byte[] data = transferCalldata(payout.toAddress(), payout.rawValue());

            FeePolicy.Fees quoted;
            try {
                BigInteger estimated = primary.estimateGas(wallet, payout.token(), "0x" + HexFormat.of().formatHex(data));
                quoted = fees.quote(primary.feeQuote(), estimated);
            } catch (JsonRpcException e) {
                if (e.code() == null) {
                    return k.retryLater("估 gas 或取费率时节点失败：" + e.getMessage());
                }
                if (fail(payout, "估 gas 失败，这笔发不出去（节点说：" + e.getMessage() + "）")) {
                    k.failed++;
                }
                continue;
            } catch (FeePolicy.FeeTooHighException e) {
                return k.retryLater(e.getMessage());
            } catch (FeePolicy.GasTooHighException e) {
                if (fail(payout, e.getMessage())) {
                    k.failed++;
                }
                continue;
            }

            SignedRecord record;
            try {
                FeePolicy.Fees f = quoted;
                record = system.inTransaction(s -> signAndRecord(s.jdbc(), wallet, payout, data, f));
            } catch (TransientDataAccessException e) {
                return k.retryLater("分配编号时数据库瞬时失败：" + e.getMessage());
            } catch (TakenByAnotherInstance e) {
                continue;
            } catch (WalletHalted e) {
                return k.halted(e.getMessage());
            }
            k.signed++;

            Outcome outcome = broadcast(record.rawHex(), record.txHash(), record.nonce());
            switch (outcome.kind()) {
                case OK -> {
                    markBroadcast(record.attemptId(), payout.id());
                    k.broadcast++;
                }
                case TRANSIENT -> {
                    return k.retryLater(outcome.detail());
                }
                case HALT -> {
                    haltWallet(wallet, outcome.detail());
                    return k.halted(outcome.detail());
                }
            }
        }
        return k.done();
    }

    // ------------------------------------------------------------------ 对账

    /** 返回停发原因；null = 可以发。 */
    private String reconcile(JdbcClient jdbc, String wallet, long onChain) {
        HotWalletRepository wallets = new HotWalletRepository(jdbc);
        wallets.insertIfAbsent(wallet, chainName, onChain);
        HotWallet row = wallets.lockForUpdate(wallet).orElseThrow(() -> new IllegalStateException("热钱包行刚建就不见了：" + wallet));
        if (row.halted()) {
            return row.haltReason();
        }
        long unfinished = new PayoutSendRepository(jdbc).unfinishedNonces(wallet);
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

    private record SignedRecord(long attemptId, long nonce, String rawHex, String txHash) {}

    private static final class TakenByAnotherInstance extends RuntimeException {
        TakenByAnotherInstance(long payoutId) {
            super("提现 " + payoutId + " 已被别的实例签了");
        }
    }

    private static final class WalletHalted extends RuntimeException {
        WalletHalted(String reason) {
            super(reason);
        }
    }

    private SignedRecord signAndRecord(JdbcClient jdbc, String wallet, QueuedPayout payout, byte[] data, FeePolicy.Fees quoted) {
        HotWalletRepository wallets = new HotWalletRepository(jdbc);
        PayoutSendRepository repo = new PayoutSendRepository(jdbc);
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

    // ------------------------------------------------------------------ 广播与它的回答

    enum Kind { OK, TRANSIENT, HALT }

    record Outcome(Kind kind, String detail) {}

    private Outcome broadcast(String rawHex, String txHash, long nonce) {
        byte[] raw = HexFormat.of().parseHex(rawHex.substring(2));
        try {
            sender.sendRawTransaction(raw);
            return new Outcome(Kind.OK, txHash);
        } catch (JsonRpcException e) {
            if (e.code() == null) {
                return new Outcome(Kind.TRANSIENT, "广播编号 " + nonce + " 时节点失败，下一轮重发同一份原文：" + e.getMessage());
            }
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("already known") || message.contains("known transaction") || message.contains("alreadyknown")) {
                return new Outcome(Kind.OK, txHash);                     // 节点早收下了（重发、上次回答丢了）
            }
            if (message.contains("nonce too low")) {
                try {
                    if (primary.transactionKnown(txHash)) {
                        return new Outcome(Kind.OK, txHash);             // 是我们这笔，已经上链，只是回答丢了
                    }
                } catch (JsonRpcException lookup) {
                    return new Outcome(Kind.TRANSIENT, "编号 " + nonce + " 疑似已用，核对时节点失败：" + lookup.getMessage());
                }
                return new Outcome(Kind.HALT, "编号 " + nonce + " 已被链上另一笔用掉（nonce too low），而节点不认识我们这笔 " + txHash
                        + "：有人在别处用了这把私钥");
            }
            return new Outcome(Kind.HALT, "节点拒绝广播编号 " + nonce + "（" + e.getMessage() + "）：编号已分出去，需要人处理");
        }
    }

    /** 广播成功后记 BROADCAST。两个实例可能同时重发同一份原文（一个成功、一个 already known）：谁先改谁算，后到的看见已改就走。 */
    private void markBroadcast(long attemptId, long payoutId) {
        system.inTransaction(s -> {
            PayoutSendRepository repo = new PayoutSendRepository(s.jdbc());
            PayoutTxStatus.SIGNED.require(PayoutTxStatus.BROADCAST);
            if (!repo.moveAttempt(attemptId, PayoutTxStatus.SIGNED.name(), PayoutTxStatus.BROADCAST.name())) {
                log.info("尝试 {} 已被别的实例记为 BROADCAST", attemptId);
                return null;
            }
            PayoutStatus.SIGNED.require(PayoutStatus.BROADCAST);
            if (!repo.moveStatus(payoutId, PayoutStatus.SIGNED.name(), PayoutStatus.BROADCAST.name())) {
                throw new IllegalStateException("尝试 " + attemptId + " 刚从 SIGNED 改成 BROADCAST，它的提现 " + payoutId + " 却不在 SIGNED");
            }
            return null;
        });
    }

    private void haltWallet(String wallet, String reason) {
        log.error("热钱包 {} 停发：{}", wallet, reason);
        system.inTransaction(s -> {
            new HotWalletRepository(s.jdbc()).halt(wallet, reason);
            return null;
        });
    }

    /**
     * 估 gas 就失败：编号还没分出去，这笔直接判失败并解冻，同一个事务。
     * 先锁这笔提现的行再看状态：两个实例可能同时看到同一笔 revert，后到的等先到的提交，看见已经 FAILED 就走。
     * 返回 false = 别的实例先判了。
     */
    private boolean fail(QueuedPayout payout, String reason) {
        return system.inTransaction(s -> {
            PayoutSendRepository repo = new PayoutSendRepository(s.jdbc());
            if (!PayoutStatus.QUEUED.name().equals(repo.lockStatus(payout.id()))) {
                log.info("提现 {} 已被别的实例定了结局", payout.id());
                return false;
            }
            log.warn("提现 {} 判失败：{}", payout.id(), reason);
            PayoutStatus.QUEUED.require(PayoutStatus.FAILED);
            long reverse = new PayoutLedger(s.jdbc(), s.ledger()).reverse(payout.id(), payout.symbol(), payout.amount(),
                    payout.frozenAccountId(), payout.userAccountId(), Instant.now());
            repo.markFailed(payout.id(), reason, reverse);
            return true;
        });
    }

    /** transfer(address,uint256)：4 字节选择子 + 左补零的 32 字节地址 + 32 字节金额。 */
    static byte[] transferCalldata(String to, BigInteger rawValue) {
        byte[] out = new byte[68];
        System.arraycopy(HexFormat.of().parseHex(TRANSFER_SELECTOR), 0, out, 0, 4);
        System.arraycopy(HexFormat.of().parseHex(to.substring(2)), 0, out, 16, 20);
        byte[] amount = rawValue.toByteArray();
        int start = amount[0] == 0 ? 1 : 0;
        System.arraycopy(amount, start, out, 68 - (amount.length - start), amount.length - start);
        return out;
    }

    private static final class Counters {
        int examined;
        int signed;
        int broadcast;
        int resent;
        int failed;

        SendResult done() {
            return new SendResult(examined, signed, broadcast, resent, failed, null, false, null);
        }

        SendResult retryLater(String detail) {
            return SendResult.retryLater(examined, signed, broadcast, resent, failed, detail);
        }

        SendResult halted(String reason) {
            return new SendResult(examined, signed, broadcast, resent, failed, reason, false, reason);
        }
    }
}
