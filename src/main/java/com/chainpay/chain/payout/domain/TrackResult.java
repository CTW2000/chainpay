package com.chainpay.chain.payout.domain;

/**
 * 追踪任务一轮的结果（M4-③）。examined = 看了几个未终结的尝试；mined / confirmed / failed / dropped / replaced / reorged 是这一轮改了状态的数；
 * waiting = 上链了但还没 FINAL 或两个节点意见不同、这一轮什么都没动的数。
 */
public record TrackResult(int examined, int mined, int confirmed, int failed, int dropped, int replaced, int reorged, int waiting,
                          boolean retryLater, String detail) {

    public static TrackResult retryLater(String detail) {
        return new TrackResult(0, 0, 0, 0, 0, 0, 0, 0, true, detail);
    }
}
