package com.chainpay.chain.payout.controller;

import com.chainpay.chain.payout.service.HotWalletFundingService;
import com.chainpay.chain.payout.service.HotWalletFundingService.Funding;
import com.chainpay.common.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理侧（回环 + 令牌）：登记一笔外部注资、列出已登记的。金额从日志来，请求体里没有金额字段——结构上就不给人填。 */
@RestController
@RequestMapping("/admin/v1/hot-wallet")
public class HotWalletFundingController {

    public record RegisterRequest(@NotBlank String txHash, Integer logIndex, @Size(max = 500) String note) {}
    public record FundingView(long id, String hotWallet, String token, String rawValue, long blockNumber, String txHash, int logIndex, String note,
                              String registeredAt) {}

    private final HotWalletFundingService fundings;

    public HotWalletFundingController(HotWalletFundingService fundings) {
        this.fundings = fundings;
    }

    @com.chainpay.admin.web.Sensitive
    @PostMapping("/fundings")
    public ApiResponse<FundingView> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(view(fundings.register(request.txHash(), request.logIndex(), request.note())));
    }

    @GetMapping("/fundings")
    public ApiResponse<List<FundingView>> list() {
        return ApiResponse.ok(fundings.list().stream().map(HotWalletFundingController::view).toList());
    }

    private static FundingView view(Funding f) {
        return new FundingView(f.id(), f.hotWallet(), f.token(), f.rawValue().toString(), f.blockNumber(), f.txHash(), f.logIndex(), f.note(),
                f.registeredAt().toString());
    }
}
