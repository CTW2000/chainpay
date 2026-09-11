package com.chainpay.chain.payout.domain;

import java.util.Map;
import java.util.Set;

/**
 * 一笔链上尝试的状态。一笔提现可能签好几笔（加速、重发），同一个 nonce 只有一笔能上链（V21 的部分唯一索引）。
 *
 * <pre>
 *   SIGNED ─→ BROADCAST ─→ MINED
 *                │  ↑        │  ↑
 *                │  └────────┘  │（块被重组掉）
 *                ├─→ DROPPED ───┘（同一份原文重发）
 *                └─→ REPLACED ←─┘（同 nonce 的另一笔上链了，本笔永远不会再上链）
 * </pre>
 *
 * <p>SIGNED 到 MINED 没有边：没广播过的东西不会上链——如果链上出现了一笔我们只签没发的交易，那是有人拿到了原文。
 */
public enum PayoutTxStatus {
    SIGNED, BROADCAST, MINED, DROPPED, REPLACED;

    private static final Map<PayoutTxStatus, Set<PayoutTxStatus>> EDGES = Map.of(
            SIGNED, Set.of(BROADCAST, REPLACED),      // 签了没发出去时，同编号的另一笔（加价的替身）先上链了
            BROADCAST, Set.of(MINED, DROPPED, REPLACED),
            MINED, Set.of(BROADCAST, REPLACED),
            DROPPED, Set.of(BROADCAST, REPLACED),
            REPLACED, Set.of());

    public Set<PayoutTxStatus> successors() {
        return EDGES.get(this);
    }

    public boolean canMoveTo(PayoutTxStatus next) {
        return successors().contains(next);
    }

    public boolean isTerminal() {
        return successors().isEmpty();
    }

    public void require(PayoutTxStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalStateException("链上尝试不能从 " + this + " 变成 " + next + "：不在转换表里");
        }
    }
}
