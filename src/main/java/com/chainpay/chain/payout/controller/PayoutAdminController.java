package com.chainpay.chain.payout.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.chainpay.chain.payout.service.PayoutApprovalService;
import com.chainpay.common.web.ApiResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理侧（回环 + 管理员令牌，同其它 /admin 接口）：核准、拒绝、限额。改的是 payout 的状态，只有系统身份能改。 */
@RestController
@RequestMapping("/admin/v1")
public class PayoutAdminController {

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}
    public record LimitRequest(@NotBlank @Pattern(regexp = "\\d{1,20}(\\.\\d{1,18})?") String perTxMax,
                               @NotBlank @Pattern(regexp = "\\d{1,20}(\\.\\d{1,18})?") String dailyMax) {}

    private final PayoutApprovalService approvals;

    public PayoutAdminController(PayoutApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/payouts/pending")
    public ApiResponse<List<Map<String, Object>>> pending() {
        return ApiResponse.ok(approvals.pending());
    }

    @PostMapping("/payouts/{id}/approve")
    public ApiResponse<Void> approve(@PathVariable long id) {
        approvals.approve(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/payouts/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable long id, @Valid @RequestBody RejectRequest request) {
        approvals.reject(id, request.reason());
        return ApiResponse.ok(null);
    }

    @PutMapping("/payout-limits/{token}")
    public ApiResponse<Void> setLimit(@PathVariable @Pattern(regexp = WithdrawalController.ADDRESS) String token, @Valid @RequestBody LimitRequest request) {
        approvals.setLimit(token, new BigDecimal(request.perTxMax()), new BigDecimal(request.dailyMax()));
        return ApiResponse.ok(null);
    }
}
