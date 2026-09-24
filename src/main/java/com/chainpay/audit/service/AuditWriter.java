package com.chainpay.audit.service;

import com.chainpay.audit.domain.AuditFinding;
import com.chainpay.audit.repository.AuditRepository;
import com.chainpay.ledger.system.SystemLedger;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一轮对账的落库：{@code audit_run} 一行与它的差异在同一个系统事务里写下，要么都在、要么都不在。
 * 单独成类，因为注解靠代理生效：{@link AuditService} 是 final 的（生成不了代理），调自己的方法也不经过代理。
 */
@Service
public class AuditWriter {

    private final JdbcClient jdbc;

    public AuditWriter(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc) {
        this.jdbc = systemJdbc;
    }

    @Transactional(SystemLedger.QUALIFIER)
    public long record(Instant started, String status, Long finalizedNumber, String finalizedHash, List<AuditFinding> findings, String detail) {
        AuditRepository repo = new AuditRepository(jdbc);
        long id = repo.insertRun(started, status, finalizedNumber, finalizedHash, findings.size(), detail);
        repo.insertFindings(id, findings);
        return id;
    }
}
