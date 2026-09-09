package com.chainpay.chain.payout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.ledger.service.LedgerException;
import com.chainpay.ledger.service.LedgerException.Reason;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 出账是「账本先扣，链上后发生」：中间那段不确定期用一个冻结账户表达。
 * 冻结走商户自己的连接与作用域（申请时），结算与解冻走系统身份（链上有结果时）。
 * 三笔都是普通的 ledger.transfer，各有幂等键——「同一笔业务只结算一次」由 M0 的唯一约束守。
 */
@SpringBootTest
@DisplayName("M4-⓪ · 提现的三笔账本流：冻结、结算、解冻")
class PayoutLedgerTest extends AbstractPostgresTest {

    private static final BigDecimal ONE = new BigDecimal("1");

    @Autowired
    private LedgerService appLedger;

    @Autowired
    private JdbcClient appJdbc;

    private long acmeId;
    private long userAccount;
    private long custodyAccount;

    @BeforeEach
    void seedAcmeWithTwentyFiveLink() {
        jdbc.sql("TRUNCATE deposit, deposit_address CASCADE").update();
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();
        acmeId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('acme', 'Acme') RETURNING id").query(Long.class).single();
        userAccount = account("user:acme:LINK", "LIABILITY", false, acmeId);
        custodyAccount = account("chain:custody:LINK", "ASSET", true, null);
        ledger.transfer(new TransferCommand("seed:acme", "LINK", new BigDecimal("25"), custodyAccount, userAccount,
                TransferCode.SEED, null));
    }

    @Test
    @DisplayName("★ 冻结：可用 25 → 24，冻结账户按需建在商户名下并 +1，code 是 WITHDRAWAL_FREEZE")
    void freezeMovesAvailableIntoAMerchantOwnedFrozenAccount() {
        long transferId = freeze("req-1", "1");

        assertThat(transferId).isPositive();
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("24");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("1");
        assertThat(jdbc.sql("SELECT merchant_id FROM account WHERE code = 'user:acme:LINK:frozen'").query(Long.class).single())
                .as("冻结账户属于商户：RLS 与余额接口都靠这一列").isEqualTo(acmeId);
        assertThat(codeOf(transferId)).isEqualTo("WITHDRAWAL_FREEZE");
    }

    @Test
    @DisplayName("★ 同一申请重复冻结是同一笔：幂等键来自申请，不来自时间")
    void freezeIsIdempotentPerRequest() {
        long first = freeze("req-2", "1");
        long second = freeze("req-2", "1");

        assertThat(second).isEqualTo(first);
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("24");
        assertThat(transferCount()).as("注资 + 一次冻结").isEqualTo(2);
    }

    @Test
    @DisplayName("★ 余额不够：拒绝，且什么都没写——连按需建的冻结账户都随事务回滚")
    void freezeBeyondAvailableWritesNothing() {
        assertThatThrownBy(() -> freeze("req-3", "100"))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).reason())
                .isEqualTo(Reason.INSUFFICIENT_BALANCE);

        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("25");
        assertThat(jdbc.sql("SELECT count(*) FROM account WHERE code = 'user:acme:LINK:frozen'").query(Long.class).single())
                .as("冻结账户的创建和冻结在同一个事务里，一起回滚").isZero();
    }

    @Test
    @DisplayName("★ 结算（系统身份）：冻结 → 托管镜像，镜像的绝对值就是平台还替商户托管着多少，code 是 WITHDRAWAL")
    void settleMovesFrozenIntoTheCustodyMirror() {
        freeze("req-4", "1");
        long frozen = accountId("user:acme:LINK:frozen");

        long settleId = systemLedger.inTransaction(s -> new PayoutLedger(s.jdbc(), s.ledger())
                .settle(41L, "LINK", ONE, frozen, custodyAccount, Instant.now()));

        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("0");
        assertThat(balance("chain:custody:LINK")).as("注资时 −25，付出去 1 之后 −24").isEqualByComparingTo("-24");
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("24");
        assertThat(codeOf(settleId)).isEqualTo("WITHDRAWAL");
    }

    @Test
    @DisplayName("★ 解冻（系统身份）：冻结 → 可用，钱回到商户手里，code 是 WITHDRAWAL_REVERSE")
    void reverseReturnsFrozenFundsToTheMerchant() {
        freeze("req-5", "1");
        long frozen = accountId("user:acme:LINK:frozen");

        long reverseId = systemLedger.inTransaction(s -> new PayoutLedger(s.jdbc(), s.ledger())
                .reverse(42L, "LINK", ONE, frozen, userAccount, Instant.now()));

        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("25");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("0");
        assertThat(codeOf(reverseId)).isEqualTo("WITHDRAWAL_REVERSE");
    }

    @Test
    @DisplayName("★ 结算过的钱不能再解冻：冻结账户不许为负，第二个结局被余额约束拦住，不靠代码记得")
    void aSettledPayoutCannotAlsoBeReversed() {
        freeze("req-6", "1");
        long frozen = accountId("user:acme:LINK:frozen");
        systemLedger.inTransaction(s -> new PayoutLedger(s.jdbc(), s.ledger())
                .settle(43L, "LINK", ONE, frozen, custodyAccount, Instant.now()));

        assertThatThrownBy(() -> systemLedger.inTransaction(s -> new PayoutLedger(s.jdbc(), s.ledger())
                .reverse(43L, "LINK", ONE, frozen, userAccount, Instant.now())))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).reason())
                .isEqualTo(Reason.INSUFFICIENT_BALANCE);

        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("24");
        assertThat(balance("chain:custody:LINK")).isEqualByComparingTo("-24");
    }

    // ------------------------------------------------------------------

    /** 申请时的冻结：走商户自己的连接与作用域，冻结账户按需建。 */
    private long freeze(String requestKey, String amount) {
        return tenantScope.asMerchant(acmeId, () -> {
            PayoutLedger payoutLedger = new PayoutLedger(appJdbc, appLedger);
            long frozen = payoutLedger.ensureFrozenAccount(acmeId, "acme", "LINK");
            return payoutLedger.freeze(requestKey, "LINK", new BigDecimal(amount), userAccount, frozen, Instant.now());
        });
    }

    private long account(String code, String kind, boolean allowNegative, Long merchantId) {
        return jdbc.sql("INSERT INTO account(code, currency, kind, allow_negative, merchant_id) VALUES (:c, 'LINK', :k, :n, :m) RETURNING id")
                .param("c", code).param("k", kind).param("n", allowNegative).param("m", merchantId)
                .query(Long.class).single();
    }

    private long accountId(String code) {
        return jdbc.sql("SELECT id FROM account WHERE code = :c").param("c", code).query(Long.class).single();
    }

    private BigDecimal balance(String code) {
        return jdbc.sql("SELECT balance FROM account WHERE code = :c").param("c", code).query(BigDecimal.class).single();
    }

    private String codeOf(long transferId) {
        return jdbc.sql("SELECT code FROM transfer WHERE id = :id").param("id", transferId).query(String.class).single();
    }
}
