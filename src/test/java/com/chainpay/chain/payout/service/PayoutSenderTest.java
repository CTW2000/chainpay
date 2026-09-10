package com.chainpay.chain.payout.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.payout.domain.SendResult;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.wallet.Eip1559Transaction;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 先落库再广播：编号在事务里分，原文先写进库再发；崩溃只会落在提交与广播之间，而那个状态靠重发同一份原文恢复。
 * 对账：库里的下一个编号是意图，链上的计数是真相，链比库多 = 有人在别处用了这把钥匙，整把钱包停发。
 */
@SpringBootTest
@DisplayName("M4-② · 发送：编号、签名、先落库再广播")
class PayoutSenderTest extends AbstractPayoutSendingTest {

    @Test
    @DisplayName("★ 一笔排队的提现：签名、落库、广播；编号从链上的计数开始，原文里是 transfer(收款人, 金额)")
    void signsRecordsAndBroadcastsOneQueuedPayout() {
        long id = queued("1");

        SendResult result = sender().sendOnce();

        assertThat(result.broadcast()).isEqualTo(1);
        assertThat(payoutStatus(id)).isEqualTo("BROADCAST");
        List<Map<String, Object>> attempts = attempts();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("status")).isEqualTo("BROADCAST");
        assertThat(((Number) attempts.get(0).get("nonce")).longValue()).isZero();
        assertThat(chain.mempool()).extracting(t -> t.hash()).containsExactly((String) attempts.get(0).get("tx_hash"));
        Eip1559Transaction tx = Eip1559Transaction.decode(HexFormat.of().parseHex(((String) attempts.get(0).get("raw_tx")).substring(2))).transaction();
        assertThat(tx.chainId()).isEqualTo(SEPOLIA);
        assertThat(tx.to()).isEqualTo(LINK);
        assertThat(HexFormat.of().formatHex(tx.data())).startsWith("a9059cbb").contains(DEST.substring(2)).endsWith("0de0b6b3a7640000");
        assertThat(((Number) wallet().get("next_nonce")).longValue()).as("分出去一个编号").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 十笔排队：编号 0 到 9 连续，按申请顺序")
    void tenQueuedPayoutsGetContiguousNoncesInOrder() {
        List<Long> ids = IntStream.range(0, 10).mapToObj(i -> queued("1")).toList();

        SendResult result = sender().sendOnce();

        assertThat(result.broadcast()).isEqualTo(10);
        assertThat(attempts()).extracting(a -> ((Number) a.get("nonce")).longValue()).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(attempts()).extracting(a -> ((Number) a.get("payout_id")).longValue()).containsExactlyElementsOf(ids);
        assertThat(chain.pendingNonces(HOT)).hasSize(10);
    }

    @Test
    @DisplayName("★ 两个实例同时发：行锁把编号串行化，十笔各恰好一次尝试、编号不重不漏")
    void twoSendersNeverShareANonce() throws Exception {
        IntStream.range(0, 10).forEach(i -> queued("1"));
        Runnable loop = () -> {
            for (int round = 0; round < 30; round++) {
                long queuedLeft = jdbc.sql("SELECT count(*) FROM payout WHERE status = 'QUEUED'").query(Long.class).single();
                if (queuedLeft == 0) {
                    return;
                }
                sender().sendOnce();
            }
        };
        Thread a = new Thread(loop, "sender-a");
        Thread b = new Thread(loop, "sender-b");
        a.start();
        b.start();
        a.join(60_000);
        b.join(60_000);

        assertThat(attempts()).hasSize(10);
        assertThat(attempts()).extracting(x -> ((Number) x.get("nonce")).longValue()).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(attempts()).extracting(x -> ((Number) x.get("payout_id")).longValue()).doesNotHaveDuplicates();
        assertThat(jdbc.sql("SELECT count(*) FROM payout WHERE status = 'BROADCAST'").query(Long.class).single()).isEqualTo(10);
    }

    @Test
    @DisplayName("★ 提交后、广播前死掉（传输失败）：尝试停在 SIGNED；下一轮重发同一份原文，编号不变，内存池里只有一笔")
    void crashBetweenCommitAndBroadcastIsRecoveredByResendingTheSameRaw() {
        long id = queued("1");
        AtomicBoolean dropOnce = new AtomicBoolean(true);
        chain.beforeSend(() -> {
            if (dropOnce.getAndSet(false)) {
                throw new JsonRpcException(null, "节点不可达");
            }
        });

        SendResult first = sender().sendOnce();
        assertThat(first.retryLater()).isTrue();
        assertThat(payoutStatus(id)).isEqualTo("SIGNED");
        Map<String, Object> signed = attempts().get(0);
        assertThat(signed.get("status")).isEqualTo("SIGNED");
        assertThat(chain.mempool()).isEmpty();

        SendResult second = sender().sendOnce();

        assertThat(second.resent()).isEqualTo(1);
        assertThat(payoutStatus(id)).isEqualTo("BROADCAST");
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().get(0).get("tx_hash")).isEqualTo(signed.get("tx_hash"));
        assertThat(chain.mempool()).extracting(t -> t.hash()).containsExactly((String) signed.get("tx_hash"));
        assertThat(((Number) wallet().get("next_nonce")).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 节点收下了但回答没送到：重发得到 already known，当成功，不会有第二笔")
    void alreadyKnownCountsAsBroadcast() {
        long id = queued("1");
        AtomicBoolean loseReplyOnce = new AtomicBoolean(true);
        chain.afterSend(() -> {
            if (loseReplyOnce.getAndSet(false)) {
                throw new JsonRpcException(null, "读响应超时");
            }
        });

        SendResult first = sender().sendOnce();
        assertThat(first.retryLater()).isTrue();
        assertThat(payoutStatus(id)).isEqualTo("SIGNED");
        assertThat(chain.mempool()).as("节点其实已经收下").hasSize(1);

        SendResult second = sender().sendOnce();

        assertThat(second.resent()).isEqualTo(1);
        assertThat(payoutStatus(id)).isEqualTo("BROADCAST");
        assertThat(chain.mempool()).hasSize(1);
    }

    @Test
    @DisplayName("★ 链上的计数超过库里分出去的编号：有人在别处用了这把钥匙，整把钱包 HALTED，一笔都不再签")
    void chainAheadOfTheDatabaseHaltsTheWallet() {
        queued("1");
        sender().sendOnce();
        long second = queued("1");
        chain.externalTransactionFrom(HOT);
        chain.externalTransactionFrom(HOT);

        SendResult result = sender().sendOnce();

        assertThat(result.halted()).isTrue();
        assertThat(result.haltReason()).contains("2").contains("1");
        assertThat(wallet().get("status")).isEqualTo("HALTED");
        assertThat((String) wallet().get("halt_reason")).isNotBlank();
        assertThat(payoutStatus(second)).as("停发后排队的原地不动").isEqualTo("QUEUED");
        assertThat(attempts()).hasSize(1);
    }

    @Test
    @DisplayName("★ 估 gas 就 revert（热钱包代币不够）：这笔判 FAILED、解冻、写原因；编号没分出去")
    void estimateGasRevertFailsThePayoutAndRefunds() {
        long id = queued("1");
        chain.failEstimateGas(3, "execution reverted: ERC20: transfer amount exceeds balance");

        SendResult result = sender().sendOnce();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(payoutStatus(id)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_reason FROM payout WHERE id = :id").param("id", id).query(String.class).single()).contains("exceeds balance");
        assertThat(jdbc.sql("SELECT reverse_transfer_id FROM payout WHERE id = :id").param("id", id).query(Long.class).single()).isPositive();
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("25");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("0");
        assertThat(attempts()).isEmpty();
        assertThat(((Number) wallet().get("next_nonce")).longValue()).isZero();
    }

    @Test
    @DisplayName("★ 费率超过上限：这一轮不发，申请留在 QUEUED，等费率回落")
    void feeAboveTheCapWaits() {
        long id = queued("1");
        chain.quoteFees(GWEI.multiply(BigInteger.valueOf(100)), GWEI.multiply(BigInteger.TWO));   // 2 × 100 + 2 = 202 gwei > 上限 50

        SendResult result = sender().sendOnce();

        assertThat(result.retryLater()).isTrue();
        assertThat(result.detail()).contains("费率");
        assertThat(payoutStatus(id)).isEqualTo("QUEUED");
        assertThat(attempts()).isEmpty();
    }

    @Test
    @DisplayName("★ 广播时 insufficient funds：钱包 HALTED、尝试停在 SIGNED；人充值并恢复后，下一轮重发同一份原文")
    void insufficientFundsAtBroadcastHaltsUntilResumed() {
        long id = queued("1");
        chain.rejectSends(-32000, "insufficient funds for gas * price + value");

        SendResult result = sender().sendOnce();

        assertThat(result.halted()).isTrue();
        assertThat(wallet().get("status")).isEqualTo("HALTED");
        assertThat((String) wallet().get("halt_reason")).contains("insufficient funds");
        assertThat(payoutStatus(id)).isEqualTo("SIGNED");
        assertThat(attempts().get(0).get("status")).isEqualTo("SIGNED");

        chain.acceptSends();
        jdbc.sql("UPDATE hot_wallet SET status = 'ACTIVE', halt_reason = NULL WHERE address = :a").param("a", HOT).update();
        SendResult resumed = sender().sendOnce();

        assertThat(resumed.resent()).isEqualTo(1);
        assertThat(payoutStatus(id)).isEqualTo("BROADCAST");
        assertThat(chain.mempool()).hasSize(1);
    }

    @Test
    @DisplayName("★ 第一次启动没有热钱包行：从链上的计数开始，不从 0 猜")
    void firstRunStartsFromTheChainCount() {
        for (int i = 0; i < 5; i++) {
            chain.externalTransactionFrom(HOT);
        }
        queued("1");

        sender().sendOnce();

        assertThat(((Number) attempts().get(0).get("nonce")).longValue()).isEqualTo(5);
        assertThat(((Number) wallet().get("next_nonce")).longValue()).isEqualTo(6);
    }

    @Test
    @DisplayName("★ 两个实例同时看到同一笔估 gas 就 revert：只解冻一次、写一次原因，谁都不炸")
    void twoInstancesBothSeeingARevertRefundExactlyOnce() throws Exception {
        long id = queued("1");
        chain.failEstimateGas(3, "execution reverted: ERC20: transfer amount exceeds balance");
        CyclicBarrier bothEstimating = new CyclicBarrier(2);
        chain.beforeCall(() -> await(bothEstimating));            // 两个实例都拿到了这笔、都在估 gas，才放行

        List<SendResult> results = runTwo(() -> sender().sendOnce());

        assertThat(results).extracting(SendResult::failed).containsExactlyInAnyOrder(1, 0);
        assertThat(payoutStatus(id)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM transfer WHERE idempotency_key = :k").param("k", "withdrawal:" + id + ":reverse").query(Long.class).single()).isEqualTo(1);
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("25");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("★ 两个实例同时重发同一份 SIGNED 原文：一个成功一个 already known，状态只改一次，谁都不炸")
    void twoInstancesResendingTheSameSignedAttemptDoNotCollide() throws Exception {
        long id = queued("1");
        AtomicBoolean dropOnce = new AtomicBoolean(true);
        chain.beforeSend(() -> {
            if (dropOnce.getAndSet(false)) {
                throw new JsonRpcException(null, "节点不可达");
            }
        });
        assertThat(sender().sendOnce().retryLater()).isTrue();
        assertThat(payoutStatus(id)).isEqualTo("SIGNED");
        CyclicBarrier bothSending = new CyclicBarrier(2);
        chain.beforeSend(() -> await(bothSending));               // 两个实例都读到了 SIGNED、都在广播，才放行

        List<SendResult> results = runTwo(() -> sender().sendOnce());

        assertThat(results).extracting(SendResult::resent).containsExactly(1, 1);
        assertThat(results).extracting(SendResult::halted).containsExactly(false, false);
        assertThat(payoutStatus(id)).isEqualTo("BROADCAST");
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().get(0).get("status")).isEqualTo("BROADCAST");
        assertThat(chain.mempool()).hasSize(1);
    }

    private static List<SendResult> runTwo(java.util.concurrent.Callable<SendResult> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SendResult> a = pool.submit(task);
            Future<SendResult> b = pool.submit(task);
            return List.of(a.get(), b.get());                      // 任何一个实例炸了，get 会把异常抛出来，测试就红
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("另一个实例没来", e);
        }
    }
}
