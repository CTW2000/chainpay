package com.chainpay.audit.service;

import com.chainpay.audit.domain.AuditResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 定时跑对账。差异每轮 ERROR；没跑完也 ERROR；「没跑」由管理接口按上次成功距今判断，不靠这里。 */
public final class AuditScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditScheduler.class);

    private final AuditService audit;

    public AuditScheduler(AuditService audit) {
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${chainpay.audit.interval:1h}", initialDelayString = "${chainpay.audit.initial-delay:2m}")
    public AuditResult tick() {
        try {
            AuditResult r = audit.runOnce();
            if (r.failed()) {
                log.error("对账没跑完（run {}）：{}", r.runId(), r.detail());
            } else if (!r.findings().isEmpty()) {
                log.error("对账发现 {} 处差异（run {}，finalized {}）：{}", r.findings().size(), r.runId(), r.finalizedNumber(), r.detail());
            } else {
                log.info("对账 OK（run {}，finalized {}）", r.runId(), r.finalizedNumber());
            }
            return r;
        } catch (RuntimeException e) {
            log.error("对账任务异常，这一轮中止，下一轮重试：{}", e.toString());
            throw e;
        }
    }
}
