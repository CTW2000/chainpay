package com.chainpay.audit.controller;

import com.chainpay.audit.config.AuditProperties;
import com.chainpay.audit.domain.AuditFinding;
import com.chainpay.audit.domain.AuditResult;
import com.chainpay.audit.service.AuditService;
import com.chainpay.common.web.ApiResponse;
import com.chainpay.common.web.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理侧：上次对账的结论与差异；「没跑」= stale。POST run 立刻跑一轮（演练与人工核对用）。
 * 对账只在设了主节点时装配（{@code Optional}）：没装配的部署回 404，而不是假装跑过。
 */
@RestController
@RequestMapping("/admin/v1")
public class AuditController {

    public record RunView(long id, String status, Long finalizedNumber, String startedAt, String finishedAt, int findings, String detail) {}
    public record StatusView(boolean stale, RunView lastRun, List<AuditFinding> findings) {}

    private final Optional<AuditService> audit;
    private final Duration interval;

    public AuditController(Optional<AuditService> audit, Optional<AuditProperties> properties) {
        this.audit = audit;
        this.interval = properties.map(AuditProperties::interval).orElse(Duration.ofHours(1));
    }

    @GetMapping("/audit")
    public ResponseEntity<ApiResponse<StatusView>> status() {
        if (audit.isEmpty()) {
            return notAssembled();
        }
        AuditService.Status s = audit.get().status(interval);
        return ResponseEntity.ok(ApiResponse.ok(new StatusView(s.stale(), s.lastRun().map(AuditController::view).orElse(null),
                s.lastRun().map(AuditResult::findings).orElse(List.of()))));
    }

    @PostMapping("/audit/run")
    public ResponseEntity<ApiResponse<StatusView>> run() {
        if (audit.isEmpty()) {
            return notAssembled();
        }
        AuditResult r = audit.get().runOnce();
        return ResponseEntity.ok(ApiResponse.ok(new StatusView(false, view(r), r.findings())));
    }

    private static ResponseEntity<ApiResponse<StatusView>> notAssembled() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ErrorCode.INVALID_REQUEST, "对账未装配：这个进程不对账（web 进程，或 CHAINPAY_CHAIN_RPC_URL=false）"));
    }

    private static RunView view(AuditResult r) {
        return new RunView(r.runId(), r.status(), r.finalizedNumber(), r.startedAt().toString(), r.finishedAt() == null ? null : r.finishedAt().toString(),
                r.findings().size(), r.detail());
    }
}
