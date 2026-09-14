package com.chainpay.audit.domain;

import java.time.Instant;
import java.util.List;

/** 一轮对账的结论。status：OK 没差异 / DIFF 有差异 / FAILED 没跑完（节点失败、分叉、库失败），FAILED 时 findings 为空、detail 是原因。 */
public record AuditResult(long runId, String status, Long finalizedNumber, Instant startedAt, Instant finishedAt, List<AuditFinding> findings, String detail) {

    public boolean failed() {
        return "FAILED".equals(status);
    }
}
