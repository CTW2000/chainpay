package com.chainpay.chain.payout.service;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 提现的三笔账本流。出账是「账本先扣、链上后发生」，中间那段不确定期用一个冻结账户表达：
 *
 * <pre>
 *   申请   user:acme:LINK        → user:acme:LINK:frozen   WITHDRAWAL_FREEZE    键 withdrawal:&lt;申请幂等键&gt;:freeze
 *   FINAL  user:acme:LINK:frozen → chain:custody:LINK      WITHDRAWAL           键 withdrawal:&lt;提现 id&gt;:settle
 *   失败   user:acme:LINK:frozen → user:acme:LINK          WITHDRAWAL_REVERSE   键 withdrawal:&lt;提现 id&gt;:reverse
 * </pre>
 *
 * <p>三笔都是普通的 {@link LedgerService#transfer}，「同一笔业务只结算一次」由 M0 的幂等键唯一约束守；
 * 「结算过的不能再解冻」由冻结账户不许为负守——第二个结局在余额那一层就被拦住，不靠代码记得。
 *
 * <p>本类不挑连接：冻结在申请时走商户自己的连接与 {@code asMerchant} 作用域（冻结账户属于商户，RLS 放行且幂等键落在商户名下）；
 * 结算与解冻在链上有结果时走 {@code SystemLedger}（系统身份）。调用方把对应的 {@link JdbcClient} 与账本传进来。
 */
public final class PayoutLedger {

    private final JdbcClient jdbc;
    private final LedgerService ledger;

    public PayoutLedger(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /** 冻结账户的编码：商户在该币上的账户后面加 :frozen。 */
    public static String frozenAccountCode(String merchantCode, String symbol) {
        return "user:" + merchantCode + ":" + symbol + ":frozen";
    }

    /**
     * 冻结账户按需建（同 M3 的 ensureAccount：ON CONFLICT DO NOTHING，让唯一约束裁决并发）。
     * 在申请的事务里调：申请失败整个回滚，不会留下一个没人用的空账户。
     */
    public long ensureFrozenAccount(long merchantId, String merchantCode, String symbol) {
        String code = frozenAccountCode(merchantCode, symbol);
        jdbc.sql("""
                        INSERT INTO account (code, currency, kind, merchant_id)
                        VALUES (:code, :currency, 'LIABILITY', :m)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", code).param("currency", symbol).param("m", merchantId)
                .update();
        return jdbc.sql("SELECT id FROM account WHERE code = :code")
                .param("code", code)
                .query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("冻结账户 " + code + " 已存在但不属于本商户，或不可见"));
    }

    /** 申请：可用 → 冻结。幂等键来自申请，同一申请重发得到同一笔转账。 */
    public long freeze(String idempotencyKey, String symbol, BigDecimal amount, long userAccountId, long frozenAccountId, Instant at) {
        return ledger.transfer(new TransferCommand("withdrawal:" + idempotencyKey + ":freeze", symbol, amount,
                userAccountId, frozenAccountId, TransferCode.WITHDRAWAL_FREEZE, at));
    }

    /** 链上 FINAL：冻结 → 托管镜像。镜像的绝对值就是平台还替商户托管着多少。 */
    public long settle(long payoutId, String symbol, BigDecimal amount, long frozenAccountId, long custodyAccountId, Instant at) {
        return ledger.transfer(new TransferCommand("withdrawal:" + payoutId + ":settle", symbol, amount,
                frozenAccountId, custodyAccountId, TransferCode.WITHDRAWAL, at));
    }

    /** 失败或被拒：冻结 → 可用，钱回到商户手里。 */
    public long reverse(long payoutId, String symbol, BigDecimal amount, long frozenAccountId, long userAccountId, Instant at) {
        return ledger.transfer(new TransferCommand("withdrawal:" + payoutId + ":reverse", symbol, amount,
                frozenAccountId, userAccountId, TransferCode.WITHDRAWAL_REVERSE, at));
    }
}
