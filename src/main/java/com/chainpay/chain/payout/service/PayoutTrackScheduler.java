package com.chainpay.chain.payout.service;

import com.chainpay.chain.payout.domain.TrackResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 定时跑追踪任务，独立于发送任务：发送停了（钱包 HALTED），已广播的照样追到结局；追踪停了，发送照走。 */
public final class PayoutTrackScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayoutTrackScheduler.class);

    private final PayoutTracker tracker;

    public PayoutTrackScheduler(PayoutTracker tracker) {
        this.tracker = tracker;
    }

    @Scheduled(fixedDelayString = "${chainpay.payout.track-interval:15s}", initialDelayString = "${chainpay.payout.track-interval:15s}")
    public TrackResult tick() {
        try {
            TrackResult r = tracker.trackOnce();
            if (r.retryLater()) {
                log.warn("追踪这一轮提前结束，下一轮再来：{}", r.detail());
            } else if (r.examined() > 0) {
                log.info("追踪：看了 {} 个尝试，上链 {}、结算 {}、判失败 {}、丢弃 {}、作废 {}、重组退回 {}、等 FINAL {}",
                        r.examined(), r.mined(), r.confirmed(), r.failed(), r.dropped(), r.replaced(), r.reorged(), r.waiting());
            }
            return r;
        } catch (RuntimeException e) {
            log.error("追踪任务异常，这一轮中止，下一轮重试：{}", e.toString());
            return TrackResult.retryLater(e.toString());
        }
    }
}
