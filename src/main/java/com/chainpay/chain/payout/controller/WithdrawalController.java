package com.chainpay.chain.payout.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.chainpay.chain.payout.repository.WithdrawalRepository.AddressRow;
import com.chainpay.chain.payout.repository.WithdrawalRepository.WithdrawalRow;
import com.chainpay.chain.payout.service.WithdrawalService;
import com.chainpay.chain.wallet.EthAddress;
import com.chainpay.common.web.ApiResponse;
import com.chainpay.security.filter.ApiKeyAuthFilter;
import com.chainpay.security.service.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户的提现接口（M4-④）。身份由 {@link ApiKeyAuthFilter} 认证后经请求属性传入；每个方法整段跑在 {@code asMerchant} 的事务里，
 * 白名单、提现、账户都有 RLS：别的商户的行结构上带不出来。金额一律字符串；地址对外一律 EIP-55 写法，存库一律小写。
 */
@RestController
@RequestMapping("/api/v1")
public class WithdrawalController {

    static final String ADDRESS = "0x[0-9a-fA-F]{40}";

    public record RegisterAddressRequest(@NotBlank @Pattern(regexp = ADDRESS) String address, @Size(max = 64) String label) {}
    public record AddressResponse(long id, String address, String label, String status, String createdAt) {}
    public record WithdrawRequest(@NotBlank @Pattern(regexp = ADDRESS) String token, @NotBlank @Pattern(regexp = ADDRESS) String toAddress,
                                  @NotBlank @Pattern(regexp = "\\d{1,20}(\\.\\d{1,18})?") String amount,
                                  @NotBlank @Size(max = 128) String idempotencyKey) {}
    public record WithdrawalResponse(long id, String idempotencyKey, String token, String symbol, String toAddress, String amount, String rawValue,
                                     String status, String failureReason, String txHash, String createdAt, String updatedAt) {}

    private final WithdrawalService withdrawals;
    private final TenantScope tenantScope;

    public WithdrawalController(WithdrawalService withdrawals, TenantScope tenantScope) {
        this.withdrawals = withdrawals;
        this.tenantScope = tenantScope;
    }

    @PostMapping("/withdrawal-addresses")
    public ApiResponse<AddressResponse> registerAddress(@RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId,
                                                        @Valid @RequestBody RegisterAddressRequest request) {
        return ApiResponse.ok(tenantScope.asMerchant(merchantId, () -> address(withdrawals.registerAddress(merchantId, request.address(), request.label()))));
    }

    @GetMapping("/withdrawal-addresses")
    public ApiResponse<List<AddressResponse>> listAddresses(@RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId) {
        return ApiResponse.ok(tenantScope.asMerchant(merchantId, () -> withdrawals.listAddresses().stream().map(WithdrawalController::address).toList()));
    }

    @PostMapping("/withdrawal-addresses/{id}/disable")
    public ApiResponse<Void> disableAddress(@RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId, @PathVariable long id) {
        tenantScope.asMerchant(merchantId, () -> {
            withdrawals.disableAddress(id);
            return null;
        });
        return ApiResponse.ok(null);
    }

    @PostMapping("/withdrawals")
    public ApiResponse<WithdrawalResponse> withdraw(@RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId,
                                                    @Valid @RequestBody WithdrawRequest request) {
        return ApiResponse.ok(tenantScope.asMerchant(merchantId, () -> item(withdrawals.request(merchantId, request.token(), request.toAddress(),
                new BigDecimal(request.amount()), request.idempotencyKey()))));
    }

    @GetMapping("/withdrawals")
    public ApiResponse<List<WithdrawalResponse>> list(@RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId,
                                                      @RequestParam(required = false) String token, @RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(tenantScope.asMerchant(merchantId, () -> withdrawals.list(token, status, limit).stream().map(WithdrawalController::item).toList()));
    }

    private static AddressResponse address(AddressRow r) {
        return new AddressResponse(r.id(), EthAddress.checksummed(r.address()), r.label(), r.status(), r.createdAt().toString());
    }

    private static WithdrawalResponse item(WithdrawalRow r) {
        return new WithdrawalResponse(r.id(), r.idempotencyKey(), r.token(), r.symbol(), EthAddress.checksummed(r.toAddress()), r.amount().toPlainString(),
                r.rawValue().toString(), r.status(), r.failureReason(), r.txHash(), text(r.createdAt()), text(r.updatedAt()));
    }

    private static String text(Instant t) {
        return t == null ? null : t.toString();
    }
}
