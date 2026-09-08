package com.chainpay.chain.deposit.domain;

/** 一轮入账的结果。retryLater = 节点瞬时失败，这一轮提前结束，已提交的不受影响。 */
public record PostingResult(int examined, int credited, int held, int ignored, int skipped, boolean retryLater, String detail) {

    public static PostingResult retryLater(int examined, int credited, int held, int ignored, int skipped, String detail) {
        return new PostingResult(examined, credited, held, ignored, skipped, true, detail);
    }
}
