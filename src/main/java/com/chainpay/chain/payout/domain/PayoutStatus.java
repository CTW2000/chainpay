package com.chainpay.chain.payout.domain;

import java.util.Map;
import java.util.Set;

/**
 * 一笔提现的状态。CHECK 约束里的词表与此一致（V21）。
 *
 * <p><b>转换表是显式的</b>：一笔钱在「账本先扣、链上后发生」的不确定期里只能沿着表里有的边走。
 * 没有表的话，「谁都能把任何状态改成任何状态」——一次并发或一次手误就把已结算的钱再解冻一遍。
 *
 * <pre>
 *   PENDING_APPROVAL ─→ QUEUED ─→ SIGNED ─→ BROADCAST ─→ MINED ─→ CONFIRMED
 *          │              │                      ↑          │
 *          ↓              ↓                      └──────────┤（块被重组掉，回去继续等）
 *       REJECTED        FAILED（估 gas 就 revert）          ↓
 *                                                        FAILED（回执 status 0 且 FINAL）
 * </pre>
 *
 * <p>BROADCAST 没有直接到 FAILED 的边：广播之后只有链上的回执能宣布结局，代码不能凭「等太久」判失败——
 * 那笔可能正在别的节点的内存池里等着上链。等太久的对策是加速或换节点（M4-③），不是判死。
 */
public enum PayoutStatus {
    PENDING_APPROVAL, QUEUED, SIGNED, BROADCAST, MINED, CONFIRMED, FAILED, REJECTED;

    private static final Map<PayoutStatus, Set<PayoutStatus>> EDGES = Map.of(
            PENDING_APPROVAL, Set.of(QUEUED, REJECTED),
            QUEUED, Set.of(SIGNED, FAILED),
            SIGNED, Set.of(BROADCAST),
            BROADCAST, Set.of(MINED),
            MINED, Set.of(CONFIRMED, FAILED, BROADCAST),
            CONFIRMED, Set.of(),
            FAILED, Set.of(),
            REJECTED, Set.of());

    /** 从这个状态允许走到哪些状态。 */
    public Set<PayoutStatus> successors() {
        return EDGES.get(this);
    }

    public boolean canMoveTo(PayoutStatus next) {
        return successors().contains(next);
    }

    /** 终态：没有出边。CONFIRMED / FAILED / REJECTED。 */
    public boolean isTerminal() {
        return successors().isEmpty();
    }

    /** 非法边直接抛，消息带上两端，运维看一眼就知道哪条边被走了。 */
    public void require(PayoutStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalStateException("提现状态不能从 " + this + " 变成 " + next + "：不在转换表里");
        }
    }
}
