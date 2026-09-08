package com.chainpay.chain.deposit.service;

import com.chainpay.chain.deposit.domain.PostingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时跑入账任务，独立于索引器：索引器停了，已 FINAL 的钱照记；入账停了，索引照走。
 * 意外异常只记 ERROR、下一轮重试（同一笔反复失败会每轮报一次，人看得见）；停机状态表留给 M3-③。
 */
public final class DepositPostingScheduler {

    private static final Logger log = LoggerFactory.getLogger(DepositPostingScheduler.class);

    private final DepositPoster poster;

    public DepositPostingScheduler(DepositPoster poster) {
        this.poster = poster;
    }

    @Scheduled(fixedDelayString = "${chainpay.deposit.post-interval:30s}", initialDelayString = "${chainpay.deposit.post-interval:30s}")
    public PostingResult tick() {
        try {
            PostingResult r = poster.postOnce();
            if (r.retryLater()) {
                log.warn("入账这一轮提前结束，下一轮再来：{}（本轮已记 {} 笔）", r.detail(), r.credited());
            } else if (r.examined() > 0) {
                log.info("入账：看了 {} 笔，记账 {}、HELD {}、忽略 {}、别的实例先记 {}", r.examined(), r.credited(), r.held(), r.ignored(), r.skipped());
            }
            return r;
        } catch (RuntimeException e) {
            log.error("入账任务异常，这一轮中止，下一轮重试：{}", e.toString());
            return PostingResult.retryLater(0, 0, 0, 0, 0, e.toString());
        }
    }
}
