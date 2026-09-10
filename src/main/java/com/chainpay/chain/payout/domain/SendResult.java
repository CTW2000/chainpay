package com.chainpay.chain.payout.domain;

/** 发送任务一轮的结果。{@code haltReason} 非空 = 热钱包已停发等人；{@code retryLater} = 瞬时失败，这一轮提前结束。 */
public record SendResult(int examined, int signed, int broadcast, int resent, int failed, String haltReason, boolean retryLater, String detail) {

    public static SendResult halted(String reason) {
        return new SendResult(0, 0, 0, 0, 0, reason, false, reason);
    }

    public static SendResult retryLater(int examined, int signed, int broadcast, int resent, int failed, String detail) {
        return new SendResult(examined, signed, broadcast, resent, failed, null, true, detail);
    }

    public boolean halted() {
        return haltReason != null;
    }
}
