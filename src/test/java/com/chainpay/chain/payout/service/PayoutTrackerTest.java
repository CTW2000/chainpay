package com.chainpay.chain.payout.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.payout.domain.SendResult;
import com.chainpay.chain.payout.domain.TrackResult;
import com.chainpay.chain.support.FakeChain;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 广播之后只有回执能宣布结局：回执 → MINED；等两个节点都说那块 finalized 且哈希一致 → 结算（CONFIRMED）或解冻（FAILED）。
 * 中间的一切（重组、节点忘了、卡住）都只是回到某个中间状态再等，钱始终冻着。
 */
@SpringBootTest
@DisplayName("M4-③ · 追踪：回执、FINAL 结算、重组、丢弃、卡单加价")
class PayoutTrackerTest extends AbstractPayoutSendingTest {

    @BeforeEach
    void chainWithBlocks() {
        chain.withBlocks(20);
        chain.reportFinalized(2);
    }

    private String broadcastOne() {
        long id = queued("1");
        assertThat(sender().sendOnce().broadcast()).isEqualTo(1);
        assertThat(payoutStatus(id)).isEqualTo("BROADCAST");
        return (String) attempts().get(0).get("tx_hash");
    }

    private long payoutId() {
        return ((Number) attempts().get(0).get("payout_id")).longValue();
    }

    @Test
    @DisplayName("★ 回执来了（status 1）：尝试 MINED 带块号块哈希，提现 MINED；还没 FINAL，一分钱不动")
    void receiptMovesToMinedButDoesNotSettle() {
        String hash = broadcastOne();
        chain.mine(5, hash, true);

        TrackResult r = tracker().trackOnce();

        assertThat(r.mined()).isEqualTo(1);
        Map<String, Object> a = jdbc.sql("SELECT status, block_number, block_hash, reverted FROM payout_tx").query().singleRow();
        assertThat(a.get("status")).isEqualTo("MINED");
        assertThat(((Number) a.get("block_number")).longValue()).isEqualTo(5);
        assertThat(a.get("block_hash")).isEqualTo(FakeChain.hashOf(5));
        assertThat(a.get("reverted")).isEqualTo(false);
        assertThat(payoutStatus(payoutId())).isEqualTo("MINED");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("★ 两个节点都说块 5 finalized 且哈希一致：结算（冻结 → 托管），CONFIRMED，结算键只记一次")
    void finalizedAndAgreedSettles() {
        String hash = broadcastOne();
        FakeChain audit = new FakeChain().withBlocks(20);
        chain.mine(5, hash, true);
        tracker(audit).trackOnce();
        chain.reportFinalized(5);
        audit.reportFinalized(5);

        TrackResult r = tracker(audit).trackOnce();

        assertThat(r.confirmed()).isEqualTo(1);
        assertThat(payoutStatus(payoutId())).isEqualTo("CONFIRMED");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("0");
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("24");
        assertThat(transfersWithKey("withdrawal:" + payoutId() + ":settle")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT settle_transfer_id FROM payout").query(Long.class).single()).isPositive();
        assertThat(tracker(audit).trackOnce().confirmed()).as("再跑一轮什么都不做").isZero();
    }

    @Test
    @DisplayName("★ 回执 status 0（合约 revert）：也是上链、编号已用；FINAL 后判 FAILED、解冻、写原因")
    void revertedReceiptFailsAndRefundsAtFinality() {
        String hash = broadcastOne();
        chain.mine(5, hash, false);
        tracker().trackOnce();
        assertThat(payoutStatus(payoutId())).as("没 FINAL 之前不下结论").isEqualTo("MINED");
        chain.reportFinalized(5);

        TrackResult r = tracker().trackOnce();

        assertThat(r.failed()).isEqualTo(1);
        assertThat(payoutStatus(payoutId())).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_reason FROM payout").query(String.class).single()).contains("status 0");
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("25");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("0");
        assertThat(transfersWithKey("withdrawal:" + payoutId() + ":reverse")).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 主节点 finalized 还没过那块：等，钱不动")
    void notFinalizedWaits() {
        String hash = broadcastOne();
        chain.mine(5, hash, true);
        tracker().trackOnce();
        chain.reportFinalized(4);

        TrackResult r = tracker().trackOnce();

        assertThat(r.waiting()).isEqualTo(1);
        assertThat(payoutStatus(payoutId())).isEqualTo("MINED");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("★ 审计节点对块 5 的哈希意见不同：不结算，等（同 M3-② 的门，动钱要两个节点都点头）")
    void auditDisagreementWaits() {
        String hash = broadcastOne();
        FakeChain audit = new FakeChain().withBlocks(20);
        chain.mine(5, hash, true);
        tracker(audit).trackOnce();
        chain.reportFinalized(5);
        audit.reportFinalized(5);
        audit.reorgFrom(5, "audit-sees-another-block");

        TrackResult r = tracker(audit).trackOnce();

        assertThat(r.waiting()).isEqualTo(1);
        assertThat(r.confirmed()).isZero();
        assertThat(payoutStatus(payoutId())).isEqualTo("MINED");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("★ 块 5 被重组掉：退回 BROADCAST 继续等；再次上链到块 6、FINAL 后结算，结算只发生一次")
    void reorgReturnsToBroadcastAndSettlesOnceAfterRemining() {
        String hash = broadcastOne();
        chain.mine(5, hash, true);
        tracker().trackOnce();
        chain.reorgFrom(5, "b");

        TrackResult afterReorg = tracker().trackOnce();

        assertThat(afterReorg.reorged()).isEqualTo(1);
        assertThat(attempts().get(0).get("status")).isEqualTo("BROADCAST");
        assertThat(payoutStatus(payoutId())).isEqualTo("BROADCAST");
        assertThat(chain.mempool()).as("节点把它退回了内存池").hasSize(1);

        chain.mine(6, hash, true);
        tracker().trackOnce();
        chain.reportFinalized(6);
        TrackResult settled = tracker().trackOnce();

        assertThat(settled.confirmed()).isEqualTo(1);
        assertThat(payoutStatus(payoutId())).isEqualTo("CONFIRMED");
        assertThat(((Number) jdbc.sql("SELECT block_number FROM payout_tx").query().singleRow().get("block_number")).longValue()).isEqualTo(6);
        assertThat(transfersWithKey("withdrawal:" + payoutId() + ":settle")).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 节点把没上链的那笔忘了（不在池里、没回执）：DROPPED；发送任务下一轮原样重发，编号不变")
    void forgottenPendingIsDroppedThenResent() {
        String hash = broadcastOne();
        chain.forget(hash);

        TrackResult r = tracker().trackOnce();

        assertThat(r.dropped()).isEqualTo(1);
        assertThat(attempts().get(0).get("status")).isEqualTo("DROPPED");
        assertThat(payoutStatus(payoutId())).as("提现仍算 BROADCAST：只是节点的一时状态").isEqualTo("BROADCAST");

        SendResult resend = sender().sendOnce();

        assertThat(resend.resent()).isEqualTo(1);
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().get(0).get("status")).isEqualTo("BROADCAST");
        assertThat(chain.mempool()).extracting(t -> t.hash()).containsExactly(hash);
    }

    @Test
    @DisplayName("★ 卡住：同编号加价 25% 再发一笔；旧的被节点顶掉 → REPLACED；新的上链 → MINED，提现照常走到底")
    void stuckAttemptIsBumpedAndTheOldOneReplaced() {
        String first = broadcastOne();

        SendResult bump = sender(Duration.ZERO).sendOnce();

        assertThat(bump.bumped()).isEqualTo(1);
        assertThat(attempts()).hasSize(2);
        Map<String, Object> a = jdbc.sql("SELECT tx_hash, status, max_fee_per_gas, max_priority_fee_per_gas FROM payout_tx ORDER BY id").query().listOfRows().get(0);
        Map<String, Object> b = jdbc.sql("SELECT tx_hash, status, max_fee_per_gas, max_priority_fee_per_gas FROM payout_tx ORDER BY id").query().listOfRows().get(1);
        assertThat(b.get("status")).isEqualTo("BROADCAST");
        BigInteger oldFee = new java.math.BigDecimal(a.get("max_fee_per_gas").toString()).toBigInteger();
        BigInteger newFee = new java.math.BigDecimal(b.get("max_fee_per_gas").toString()).toBigInteger();
        assertThat(newFee.multiply(BigInteger.valueOf(100))).isGreaterThanOrEqualTo(oldFee.multiply(BigInteger.valueOf(125)));
        assertThat(((Number) attempts().get(1).get("nonce")).longValue()).as("同一个编号").isZero();
        assertThat(chain.mempool()).extracting(t -> t.hash()).containsExactly((String) b.get("tx_hash"));

        TrackResult afterBump = tracker().trackOnce();
        assertThat(afterBump.replaced()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM payout_tx WHERE tx_hash = :h").param("h", first).query(String.class).single()).isEqualTo("REPLACED");

        chain.mine(7, (String) b.get("tx_hash"), true);
        assertThat(tracker().trackOnce().mined()).isEqualTo(1);
        chain.reportFinalized(7);
        TrackResult settled = tracker().trackOnce();

        assertThat(settled.confirmed()).isEqualTo(1);
        assertThat(payoutStatus(payoutId())).isEqualTo("CONFIRMED");
        assertThat(sender(Duration.ZERO).sendOnce().bumped()).as("终结之后不再加价").isZero();
    }

    @Test
    @DisplayName("★ 加价后超过费率上限：这一轮不加，等费率回落；旧尝试原地不动")
    void bumpAboveTheCapWaits() {
        broadcastOne();
        chain.quoteFees(GWEI.multiply(BigInteger.valueOf(30)), GWEI.multiply(BigInteger.TWO));   // 2 × 30 + 2 = 62 gwei > 上限 50

        SendResult r = sender(Duration.ZERO).sendOnce();

        assertThat(r.bumped()).isZero();
        assertThat(r.detail()).contains("费率");
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().get(0).get("status")).isEqualTo("BROADCAST");
    }

    @Test
    @DisplayName("★ 追踪时节点不可达：这一轮提前结束，什么都不改")
    void nodeOutageEndsTheRoundEarly() {
        String hash = broadcastOne();
        chain.mine(5, hash, true);
        chain.beforeBlock(n -> { throw new com.chainpay.chain.rpc.JsonRpcException(null, "节点不可达"); });

        TrackResult r = tracker().trackOnce();

        assertThat(r.retryLater()).isTrue();
        assertThat(attempts().get(0).get("status")).isIn("BROADCAST", "MINED");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("1");
    }
}
