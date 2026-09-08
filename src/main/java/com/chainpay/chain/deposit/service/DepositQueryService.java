package com.chainpay.chain.deposit.service;

import com.chainpay.chain.deposit.repository.DepositQueryRepository;
import com.chainpay.chain.deposit.repository.DepositQueryRepository.AddressRow;
import com.chainpay.chain.deposit.repository.DepositQueryRepository.DepositRow;
import com.chainpay.chain.deposit.repository.DepositQueryRepository.TokenRow;
import com.chainpay.chain.deposit.service.DepositAddressService.UnsupportedTokenException;
import com.chainpay.chain.erc20.AmountOverflowException;
import com.chainpay.chain.erc20.TokenAmounts;
import com.chainpay.chain.wallet.EthAddress;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * 商户视角的入账查询。必须在 asMerchant 作用域里调（控制器负责）。
 * 在路上的钱的金额在这里换算：链上只有原始单位，账本单位只经 {@link TokenAmounts#toLedger}，装不下就给 null（原始单位照给）。
 */
public final class DepositQueryService {

    /** available = 账本余额；pending = 在路上的合计（换算不了时为 null）。 */
    public record Balance(String token, String symbol, BigDecimal available, BigDecimal pending) {}

    static final int MAX_LIMIT = 200;

    private final DepositQueryRepository repository;

    public DepositQueryService(DepositQueryRepository repository) {
        this.repository = repository;
    }

    public List<AddressRow> listAddresses() {
        return repository.listAddresses();
    }

    public List<DepositRow> listDeposits(String token, String status, int limit) {
        String normalizedToken = token == null || token.isBlank() ? null : EthAddress.lowercase(token);
        String normalizedStatus = status == null || status.isBlank() ? null : status;
        int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository.listDeposits(normalizedToken, normalizedStatus, clamped).stream()
                .map(row -> row.amount() != null ? row : withConvertedAmount(row))
                .toList();
    }

    public Balance balance(String token) {
        String normalized = EthAddress.lowercase(token);
        TokenRow chainToken = repository.findToken(normalized)
                .orElseThrow(() -> new UnsupportedTokenException("代币未登记：" + normalized));
        BigDecimal available = repository.availableBalance(normalized)
                .orElse(BigDecimal.ZERO.setScale(TokenAmounts.LEDGER_SCALE));
        BigInteger pendingRaw = repository.pendingRaw(normalized);
        return new Balance(normalized, chainToken.symbol(), available, toLedgerOrNull(pendingRaw, chainToken.decimals()));
    }

    private static DepositRow withConvertedAmount(DepositRow row) {
        return new DepositRow(row.token(), row.symbol(), row.decimals(), row.address(), row.rawValue(),
                toLedgerOrNull(row.rawValue(), row.decimals()), row.status(), row.level(), row.confirmations(),
                row.blockNumber(), row.txHash(), row.logIndex(), row.occurredAt(), row.creditedAt());
    }

    private static BigDecimal toLedgerOrNull(BigInteger raw, int decimals) {
        try {
            return TokenAmounts.toLedger(raw, decimals);
        } catch (AmountOverflowException e) {
            return null;
        }
    }
}
