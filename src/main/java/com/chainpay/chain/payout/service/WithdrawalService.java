package com.chainpay.chain.payout.service;

import com.chainpay.chain.deposit.service.DepositAddressService.UnsupportedTokenException;
import com.chainpay.chain.erc20.TokenAmounts;
import com.chainpay.chain.payout.domain.PayoutStatus;
import com.chainpay.chain.payout.repository.WithdrawalRepository;
import com.chainpay.chain.payout.repository.WithdrawalRepository.AddressRow;
import com.chainpay.chain.payout.repository.WithdrawalRepository.Limit;
import com.chainpay.chain.payout.repository.WithdrawalRepository.TokenRow;
import com.chainpay.chain.payout.repository.WithdrawalRepository.WithdrawalRow;
import com.chainpay.chain.wallet.EthAddress;
import com.chainpay.common.web.ErrorCode;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 商户侧的提现（M4-④）。整段在控制器的 {@code asMerchant} 事务里跑，走商户连接：白名单、提现、账户都有 RLS。
 *
 * <p>申请一笔提现的顺序：先把形状与规则都验完（代币、金额、白名单、平台地址），再锁本商户那一行把同一商户的申请串行化，
 * 然后在锁内看幂等键、算当日汇总、定状态、冻结、插行——冻结与插行在同一个事务里，插不进去冻结一起回滚。
 * 「提到平台自己的收款地址」要看所有商户的地址表，商户连接看不到别家的行，所以那一问走系统身份，只回答是或否。
 */
@Service
public class WithdrawalService {

    private final WithdrawalRepository repo;
    private final PayoutLedger payoutLedger;
    private final SystemLedger system;

    public WithdrawalService(JdbcClient jdbc, LedgerService ledger, SystemLedger system) {
        this.repo = new WithdrawalRepository(jdbc);
        this.payoutLedger = new PayoutLedger(jdbc, ledger);
        this.system = system;
    }

    public AddressRow registerAddress(long merchantId, String address, String label) {
        String lower = EthAddress.lowercase(address);
        rejectPlatformAddress(lower);
        return repo.upsertAddress(merchantId, lower, label);
    }

    public List<AddressRow> listAddresses() {
        return repo.listAddresses();
    }

    public void disableAddress(long id) {
        if (!repo.disableAddress(id)) {
            throw new WithdrawalRejectedException(HttpStatus.BAD_REQUEST, ErrorCode.ADDRESS_NOT_WHITELISTED, "白名单里没有这个地址，或它已停用");
        }
    }

    public WithdrawalRow request(long merchantId, String tokenAddress, String toAddress, BigDecimal requested, String idempotencyKey) {
        String token = EthAddress.lowercase(tokenAddress);
        TokenRow t = repo.findToken(token).filter(row -> "ACTIVE".equals(row.status()))
                .orElseThrow(() -> new UnsupportedTokenException("代币不在白名单里或已停用：" + token));
        BigDecimal amount = ledgerAmount(requested, t.decimals());
        String to = EthAddress.lowercase(toAddress);

        String merchantCode = repo.lockMerchant(merchantId);                   // 同一商户的申请从这里起串行

        Optional<WithdrawalRow> existing = repo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            WithdrawalRow e = existing.get();
            if (e.token().equals(token) && e.toAddress().equals(to) && e.amount().compareTo(amount) == 0) {
                return e;                                                       // 同一申请重发：同一笔
            }
            throw new WithdrawalRejectedException(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_CONFLICT, "幂等键已用于另一笔不同的申请");
        }
        rejectPlatformAddress(to);                                              // 平台地址永远拒绝，先于「没登记」
        repo.findAddress(to).filter(a -> "ACTIVE".equals(a.status()))
                .orElseThrow(() -> new WithdrawalRejectedException(HttpStatus.BAD_REQUEST, ErrorCode.ADDRESS_NOT_WHITELISTED, "收款地址不在白名单里或已停用，先登记"));

        long userAccount = repo.ensureUserAccount(merchantId, merchantCode, t.symbol());
        long frozenAccount = payoutLedger.ensureFrozenAccount(merchantId, merchantCode, t.symbol());
        PayoutStatus status = routeByLimit(token, amount);
        long freeze = payoutLedger.freeze(idempotencyKey, t.symbol(), amount, userAccount, frozenAccount, Instant.now());   // 余额不够在这里被账本拒
        long id = repo.insert(merchantId, idempotencyKey, token, to, amount, amount.movePointRight(t.decimals()).toBigIntegerExact(), status.name(), freeze);
        return repo.findById(id);
    }

    public List<WithdrawalRow> list(String token, String status, int limit) {
        return repo.list(token == null ? null : EthAddress.lowercase(token), status, Math.max(1, Math.min(limit, 200)));
    }

    /** 冻着的钱：该币没有冻结账户就是 0。 */
    public BigDecimal frozen(String symbol) {
        return repo.frozenBalance(symbol);
    }

    /** 没定过限额 = 一律人工；单笔超上限或当日累计超上限 = 人工；否则直接排队。 */
    private PayoutStatus routeByLimit(String token, BigDecimal amount) {
        Optional<Limit> limit = repo.findLimit(token);
        if (limit.isEmpty() || amount.compareTo(limit.get().perTxMax()) > 0) {
            return PayoutStatus.PENDING_APPROVAL;
        }
        if (repo.sumToday(token).add(amount).compareTo(limit.get().dailyMax()) > 0) {
            return PayoutStatus.PENDING_APPROVAL;
        }
        return PayoutStatus.QUEUED;
    }

    /** 金额：正数、小数位不超过代币的 decimals（否则链上表示不了）、也不超过账本的 18 位。 */
    private static BigDecimal ledgerAmount(BigDecimal requested, int decimals) {
        if (requested == null || requested.signum() <= 0) {
            throw new WithdrawalRejectedException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_AMOUNT, "金额必须大于 0");
        }
        int scale = requested.stripTrailingZeros().scale();
        if (scale > decimals || scale > TokenAmounts.LEDGER_SCALE) {
            throw new WithdrawalRejectedException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_AMOUNT, "小数位超过这种代币能表示的 " + Math.min(decimals, TokenAmounts.LEDGER_SCALE) + " 位");
        }
        return requested.setScale(TokenAmounts.LEDGER_SCALE);
    }

    /** 平台自己的收款地址（任何商户的）不能当提现目标：那是内部转账。商户连接看不到别家的行，走系统身份只问是或否。 */
    private void rejectPlatformAddress(String lower) {
        boolean platform = system.inTransaction(s -> s.jdbc().sql("SELECT EXISTS (SELECT 1 FROM deposit_address WHERE address = :a)")
                .param("a", lower).query(Boolean.class).single());
        if (platform) {
            throw new WithdrawalRejectedException(HttpStatus.BAD_REQUEST, ErrorCode.INTERNAL_ADDRESS, "这是平台的收款地址，不能作为提现目标");
        }
    }
}
