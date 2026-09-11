package com.chainpay.chain.deposit.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import com.chainpay.chain.deposit.domain.DepositAddress;
import com.chainpay.chain.deposit.repository.DepositQueryRepository.DepositRow;
import com.chainpay.chain.deposit.service.DepositAddressService;
import com.chainpay.chain.deposit.service.DepositQueryService;
import com.chainpay.chain.deposit.service.DepositQueryService.Balance;
import com.chainpay.chain.wallet.EthAddress;
import com.chainpay.common.web.ApiResponse;
import com.chainpay.security.filter.ApiKeyAuthFilter;
import com.chainpay.security.service.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户的收款接口（M3-④），形状照币安 / OKX 的 deposit-address 与 deposit-history。
 *
 * <p>身份由 {@link ApiKeyAuthFilter} 认证完毕后经请求属性传入；每个方法整段跑在 {@code asMerchant} 的事务里，
 * 地址表与入账表的 RLS 让别人的行根本查不出来——不需要也没有「按 id 查一条」的接口，
 * 于是「不存在」和「不是你的」连区分的机会都没有。
 *
 * <p>对外的规矩：金额一律字符串；地址给 EIP-55 写法（存库是小写）；HELD 只露状态不露原因（原因里有节点与哈希细节）；
 * 请求体只有 token 一个字段，地址、序号都由服务端派生（Mass Assignment）。
 * 设了 CHAINPAY_DEPOSIT_XPUB 才装配，没设时这些路径是 404。
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty("chainpay.deposit.xpub")
public class DepositController {

    public record CreateAddressRequest(@NotBlank String token) {}

    public record AddressResponse(String address, String token, String symbol, String status) {}

    /**
     * 一条入账。status 是 PENDING（在路上，还没记账）或 deposit 表里的状态；level 是 SEEN / SAFE / FINAL；
     * amount 是账本单位的字符串，装不下账本时为 null，rawValue 永远有。
     */
    public record DepositItem(String token, String symbol, String address, String amount, String rawValue, String status,
                              String level, long confirmations, long blockNumber, String txHash, int logIndex,
                              String occurredAt, String creditedAt) {}

    /** available 已记账可用；pending 在路上（SEEN / SAFE / 已 FINAL 未记）的合计，换算不了时为 null。 */
    public record BalanceResponse(String token, String symbol, String available, String pending, String frozen) {}

    private final DepositAddressService addresses;
    private final DepositQueryService queries;
    private final TenantScope tenantScope;

    public DepositController(DepositAddressService addresses, DepositQueryService queries, TenantScope tenantScope) {
        this.addresses = addresses;
        this.queries = queries;
        this.tenantScope = tenantScope;
    }

    /** 一户一币一址，幂等：再申请返回同一个地址。 */
    @PostMapping("/deposit-addresses")
    public ApiResponse<AddressResponse> create(@RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId,
                                               @Valid @RequestBody CreateAddressRequest request) {
        return ApiResponse.ok(tenantScope.asMerchant(merchantId, () -> {
            DepositAddress allocated = addresses.allocate(merchantId, request.token());
            String symbol = queries.balance(allocated.token()).symbol();
            return new AddressResponse(EthAddress.checksummed(allocated.address()), allocated.token(), symbol, allocated.status());
        }));
    }

    @GetMapping("/deposit-addresses")
    public ApiResponse<List<AddressResponse>> list(@RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId) {
        return ApiResponse.ok(tenantScope.asMerchant(merchantId, () -> queries.listAddresses().stream()
                .map(a -> new AddressResponse(EthAddress.checksummed(a.address()), a.token(), a.symbol(), a.status()))
                .toList()));
    }

    @GetMapping("/deposits")
    public ApiResponse<List<DepositItem>> deposits(@RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId,
                                                   @RequestParam(required = false) String token,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(tenantScope.asMerchant(merchantId, () -> queries.listDeposits(token, status, limit).stream()
                .map(DepositController::item)
                .toList()));
    }

    @GetMapping("/deposits/balance")
    public ApiResponse<BalanceResponse> balance(@RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId,
                                                @RequestParam String token) {
        return ApiResponse.ok(tenantScope.asMerchant(merchantId, () -> {
            Balance b = queries.balance(token);
            return new BalanceResponse(b.token(), b.symbol(), text(b.available()), text(b.pending()), text(b.frozen()));
        }));
    }

    private static DepositItem item(DepositRow r) {
        return new DepositItem(r.token(), r.symbol(), EthAddress.checksummed(r.address()), text(r.amount()),
                r.rawValue().toString(), r.status(), r.level(), r.confirmations(), r.blockNumber(), r.txHash(), r.logIndex(),
                text(r.occurredAt()), text(r.creditedAt()));
    }

    private static String text(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private static String text(Instant t) {
        return t == null ? null : t.toString();
    }
}
