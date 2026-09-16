package com.chainpay.chain.deposit.domain;

/**
 * 一轮入账的结果。三种结局：
 * <ul>
 *   <li>{@code COMPLETED} —— 这一轮走完了（记账 / HELD / 忽略 / 别的实例先记，都算走完）</li>
 *   <li>{@code RETRY_LATER} —— 节点或库瞬时失败，提前结束；已提交的不受影响，下一轮整轮再来</li>
 *   <li>{@code HALTED} —— 重试永远没用（节点撤了我们的凭证），要人来处理</li>
 * </ul>
 * deferred = 块还没在两个节点上 finalized，这一轮先不判、也没占坑，下一轮再看。
 */
public record PostingResult(int examined, int credited, int held, int ignored, int skipped, int deferred,
                            Ending ending, String detail) {

    public enum Ending { COMPLETED, RETRY_LATER, HALTED }

    public static PostingResult retryLater(int examined, int credited, int held, int ignored, int skipped, int deferred, String detail) {
        return new PostingResult(examined, credited, held, ignored, skipped, deferred, Ending.RETRY_LATER, detail);
    }

    public static PostingResult halted(int examined, int credited, int held, int ignored, int skipped, int deferred, String detail) {
        return new PostingResult(examined, credited, held, ignored, skipped, deferred, Ending.HALTED, detail);
    }

    /** 这一轮没跑完，但下一轮会自己好。 */
    public boolean retryLater() {
        return ending == Ending.RETRY_LATER;
    }

    /** 这一轮没跑完，而且不会自己好：要人。 */
    public boolean halted() {
        return ending == Ending.HALTED;
    }
}
