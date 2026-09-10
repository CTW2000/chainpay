package com.chainpay.chain.payout.service;

import com.chainpay.chain.payout.domain.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时跑发送任务，独立于索引器与入账任务。停发的钱包每轮报一次 ERROR，人看得见；
 * 意外异常只记 ERROR、下一轮重试——编号已经在库里，下一轮的重发段会把签了没广播的补上。
 */
public final class PayoutSendScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayoutSendScheduler.class);

    private final PayoutSender sender;

    public PayoutSendScheduler(PayoutSender sender) {
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${chainpay.payout.send-interval:10s}", initialDelayString = "${chainpay.payout.send-interval:10s}")
    public SendResult tick() {
        try {
            SendResult r = sender.sendOnce();
            if (r.halted()) {
                log.error("热钱包停发，等人处理：{}", r.haltReason());
            } else if (r.retryLater()) {
                log.warn("发送这一轮提前结束，下一轮再来：{}（本轮签 {}、广播 {}、重发 {}）", r.detail(), r.signed(), r.broadcast(), r.resent());
            } else if (r.examined() > 0 || r.resent() > 0) {
                log.info("发送：看了 {} 笔，签 {}、广播 {}、重发 {}、判失败 {}", r.examined(), r.signed(), r.broadcast(), r.resent(), r.failed());
            }
            return r;
        } catch (RuntimeException e) {
            log.error("发送任务异常，这一轮中止，下一轮重试：{}", e.toString());
            return SendResult.retryLater(0, 0, 0, 0, 0, e.toString());
        }
    }
}
