package com.chainpay.chain.payout.domain;

import static com.chainpay.chain.payout.domain.PayoutStatus.BROADCAST;
import static com.chainpay.chain.payout.domain.PayoutStatus.CONFIRMED;
import static com.chainpay.chain.payout.domain.PayoutStatus.FAILED;
import static com.chainpay.chain.payout.domain.PayoutStatus.MINED;
import static com.chainpay.chain.payout.domain.PayoutStatus.PENDING_APPROVAL;
import static com.chainpay.chain.payout.domain.PayoutStatus.QUEUED;
import static com.chainpay.chain.payout.domain.PayoutStatus.REJECTED;
import static com.chainpay.chain.payout.domain.PayoutStatus.SIGNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 提现的状态机写成显式的转换表：一笔钱在「账本先扣、链上后发生」的不确定期里只能沿着表里有的边走。
 * 没有表的话，「谁都能把任何状态改成任何状态」——一次并发或一次手误就把已结算的钱再解冻一遍。
 */
@DisplayName("M4-⓪ · 提现状态机：只允许表里有的边")
class PayoutStatusTest {

    @Test
    @DisplayName("★ 主线：待核准 → 排队 → 已签名 → 已广播 → 已上链 → 已确认；核准可拒、排队与上链可失败、上链可因重组退回已广播")
    void theHappyPathAndItsSanctionedDetours() {
        assertThat(PENDING_APPROVAL.canMoveTo(QUEUED)).isTrue();
        assertThat(PENDING_APPROVAL.canMoveTo(REJECTED)).isTrue();
        assertThat(QUEUED.canMoveTo(SIGNED)).isTrue();
        assertThat(QUEUED.canMoveTo(FAILED)).as("估 gas 就 revert：没花 gas 就知道发不出去").isTrue();
        assertThat(SIGNED.canMoveTo(BROADCAST)).isTrue();
        assertThat(BROADCAST.canMoveTo(MINED)).isTrue();
        assertThat(MINED.canMoveTo(CONFIRMED)).as("FINAL 之后结算").isTrue();
        assertThat(MINED.canMoveTo(FAILED)).as("回执 status 0 且 FINAL：上链了但代币没动").isTrue();
        assertThat(MINED.canMoveTo(BROADCAST)).as("块被重组掉，回去继续等").isTrue();
    }

    @Test
    @DisplayName("★ 不在表里的边一律拒绝：不能跳步、不能回头、不能从终态出去")
    void edgesOutsideTheTableAreRefused() {
        assertThat(QUEUED.canMoveTo(CONFIRMED)).as("没广播就确认").isFalse();
        assertThat(PENDING_APPROVAL.canMoveTo(SIGNED)).as("没核准就签名").isFalse();
        assertThat(SIGNED.canMoveTo(QUEUED)).as("签了名不能回排队：nonce 已经分出去了").isFalse();
        assertThat(BROADCAST.canMoveTo(QUEUED)).isFalse();
        assertThat(BROADCAST.canMoveTo(FAILED)).as("广播后只有上链才能知道结局，不能直接判失败").isFalse();
        assertThat(CONFIRMED.canMoveTo(FAILED)).as("结算过的钱不能再变失败").isFalse();
        assertThat(FAILED.canMoveTo(QUEUED)).isFalse();
        assertThat(REJECTED.canMoveTo(QUEUED)).isFalse();
    }

    @Test
    @DisplayName("终态没有出边：CONFIRMED / FAILED / REJECTED")
    void terminalStatesHaveNoSuccessors() {
        for (PayoutStatus terminal : new PayoutStatus[] {CONFIRMED, FAILED, REJECTED}) {
            assertThat(terminal.isTerminal()).as(terminal.name()).isTrue();
            assertThat(terminal.successors()).as(terminal.name()).isEmpty();
        }
        assertThat(QUEUED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("★ require 把非法边变成异常，消息里带上两端，运维看一眼就知道哪条边被走了")
    void requireNamesBothEndsOfTheIllegalEdge() {
        assertThatThrownBy(() -> QUEUED.require(CONFIRMED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUED")
                .hasMessageContaining("CONFIRMED");
        QUEUED.require(SIGNED);   // 合法边不抛
    }

    @Test
    @DisplayName("链上尝试的状态机：已签名 → 已广播 → 上链 / 丢弃 / 被替代；丢弃可重发；上链可因重组退回；被替代是终态")
    void transactionAttemptsHaveTheirOwnTable() {
        assertThat(PayoutTxStatus.SIGNED.canMoveTo(PayoutTxStatus.BROADCAST)).isTrue();
        assertThat(PayoutTxStatus.BROADCAST.canMoveTo(PayoutTxStatus.MINED)).isTrue();
        assertThat(PayoutTxStatus.BROADCAST.canMoveTo(PayoutTxStatus.DROPPED)).isTrue();
        assertThat(PayoutTxStatus.BROADCAST.canMoveTo(PayoutTxStatus.REPLACED)).isTrue();
        assertThat(PayoutTxStatus.DROPPED.canMoveTo(PayoutTxStatus.BROADCAST)).as("同一份原文重发").isTrue();
        assertThat(PayoutTxStatus.MINED.canMoveTo(PayoutTxStatus.BROADCAST)).as("重组").isTrue();
        assertThat(PayoutTxStatus.MINED.canMoveTo(PayoutTxStatus.REPLACED)).as("重组后同 nonce 的另一笔上链").isTrue();
        assertThat(PayoutTxStatus.REPLACED.isTerminal()).isTrue();
        assertThat(PayoutTxStatus.SIGNED.canMoveTo(PayoutTxStatus.MINED)).as("没广播过的东西不会上链").isFalse();
    }
}
