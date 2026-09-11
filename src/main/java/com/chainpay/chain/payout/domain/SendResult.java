package com.chainpay.chain.payout.domain;

/** 发送任务一轮的结果。bumped = 这一轮给卡住的尝试加价重发了几笔（M4-③）。 */
public record SendResult(int examined, int signed, int broadcast, int resent, int bumped, int failed, String haltReason, boolean retryLater, String detail) {

    public static SendResult halted(String reason) {
        return new SendResult(0, 0, 0, 0, 0, 0, reason, false, reason);
    }

    public static SendResult retryLater(int examined, int signed, int broadcast, int resent, int bumped, int failed, String detail) {
        return new SendResult(examined, signed, broadcast, resent, bumped, failed, null, true, detail);
    }

    public boolean halted() {
        return haltReason != null;
    }
}
