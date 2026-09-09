package com.chainpay.chain.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V21 的四张表由数据库守：状态词表、结局与账本转账的一一对应、同 nonce 只能上链一次、租户边界、角色权限。
 * 同时兑现 CLAUDE.md 的承诺：会话变量 {@code chainpay.system} 那道门在 M4 拆掉，系统权限只剩连接身份一条路。
 */
@SpringBootTest
@DisplayName("M4-⓪ · 提现四张表：约束、租户边界、角色权限；会话变量那道门已拆")
class PayoutSchemaTest extends AbstractPostgresTest {

    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String HOT = "0x1111111111111111111111111111111111111111";
    static final String DEST = "0x2222222222222222222222222222222222222222";

    @Autowired
    private JdbcClient appJdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private long acmeId;
    private long evilcoId;
    private long acmeFreeze;
    private long acmeFreeze2;
    private long evilcoFreeze;

    @BeforeEach
    void seedTwoMerchantsWithFrozenFunds() {
        jdbc.sql("TRUNCATE payout_tx, payout, payout_address, hot_wallet, deposit, deposit_address CASCADE").update();
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();
        jdbc.sql("INSERT INTO chain_token(address, symbol, decimals) VALUES (:a, 'LINK', 18) ON CONFLICT (address) DO NOTHING").param("a", LINK).update();
        acmeId = merchant("acme");
        evilcoId = merchant("evilco");
        long custody = account("chain:custody:LINK", "ASSET", true, null);
        long acmeUser = account("user:acme:LINK", "LIABILITY", false, acmeId);
        long acmeFrozen = account("user:acme:LINK:frozen", "LIABILITY", false, acmeId);
        long evilcoUser = account("user:evilco:LINK", "LIABILITY", false, evilcoId);
        long evilcoFrozen = account("user:evilco:LINK:frozen", "LIABILITY", false, evilcoId);
        ledger.transfer(new TransferCommand("seed:acme", "LINK", new BigDecimal("10"), custody, acmeUser, TransferCode.SEED, null));
        ledger.transfer(new TransferCommand("seed:evilco", "LINK", new BigDecimal("10"), custody, evilcoUser, TransferCode.SEED, null));
        acmeFreeze = ledger.transfer(new TransferCommand("frz:acme:1", "LINK", BigDecimal.ONE, acmeUser, acmeFrozen, TransferCode.WITHDRAWAL_FREEZE, null));
        acmeFreeze2 = ledger.transfer(new TransferCommand("frz:acme:2", "LINK", BigDecimal.ONE, acmeUser, acmeFrozen, TransferCode.WITHDRAWAL_FREEZE, null));
        evilcoFreeze = ledger.transfer(new TransferCommand("frz:evilco:1", "LINK", BigDecimal.ONE, evilcoUser, evilcoFrozen, TransferCode.WITHDRAWAL_FREEZE, null));
        jdbc.sql("INSERT INTO hot_wallet(address, chain, next_nonce) VALUES (:a, 'sepolia', 0)").param("a", HOT).update();
    }

    // ==================================================================
    // 约束
    // ==================================================================

    @Test
    @DisplayName("★ 状态词表由 CHECK 守：词表外的状态插不进去")
    void statusVocabularyIsEnforced() {
        assertThatThrownBy(() -> insertPayout(acmeId, "k-bogus", "BOGUS", acmeFreeze, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("★ CONFIRMED 当且仅当有结算转账：没结算不能叫确认，结算了也不能不叫确认")
    void confirmedMeansSettled() {
        assertThatThrownBy(() -> insertPayout(acmeId, "k-c1", "CONFIRMED", acmeFreeze, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPayout(acmeId, "k-c2", "QUEUED", acmeFreeze, evilcoFreeze, null, null))
                .as("带着结算转账却不是 CONFIRMED").isInstanceOf(DataIntegrityViolationException.class);

        insertPayout(acmeId, "k-c3", "CONFIRMED", acmeFreeze2, evilcoFreeze, null, null);
    }

    @Test
    @DisplayName("★ FAILED / REJECTED 当且仅当有解冻转账，且必须写原因")
    void failedAndRejectedMeanReversedWithAReason() {
        assertThatThrownBy(() -> insertPayout(acmeId, "k-f1", "FAILED", acmeFreeze, null, null, "回执 status 0"))
                .as("没解冻不能叫失败").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPayout(acmeId, "k-f2", "FAILED", acmeFreeze, null, evilcoFreeze, null))
                .as("解冻了但没写原因").isInstanceOf(DataIntegrityViolationException.class);

        insertPayout(acmeId, "k-f3", "REJECTED", acmeFreeze2, null, evilcoFreeze, "人工拒绝：超限");
    }

    @Test
    @DisplayName("★ 结算与解冻不能同时存在：一笔钱只有一个结局")
    void oneOutcomeOnly() {
        assertThatThrownBy(() -> insertPayout(acmeId, "k-o1", "CONFIRMED", acmeFreeze, acmeFreeze2, evilcoFreeze, "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("★ 幂等键按商户唯一：同商户重复申请撞唯一约束，别的商户可以用同一个键")
    void idempotencyKeyIsUniquePerMerchant() {
        insertPayout(acmeId, "k-same", "QUEUED", acmeFreeze, null, null, null);

        assertThatThrownBy(() -> insertPayout(acmeId, "k-same", "QUEUED", acmeFreeze2, null, null, null))
                .isInstanceOf(DuplicateKeyException.class);
        insertPayout(evilcoId, "k-same", "QUEUED", evilcoFreeze, null, null, null);
    }

    @Test
    @DisplayName("★ 一笔冻结转账只能属于一笔提现：freeze_transfer_id 唯一")
    void aFreezeTransferBelongsToExactlyOnePayout() {
        insertPayout(acmeId, "k-u1", "QUEUED", acmeFreeze, null, null, null);

        assertThatThrownBy(() -> insertPayout(acmeId, "k-u2", "QUEUED", acmeFreeze, null, null, null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("★ 同一把热钱包的同一个 nonce 只能有一笔 MINED：部分唯一索引守着「加速后只有一笔上链」")
    void onlyOneMinedAttemptPerNonce() {
        long payoutId = insertPayout(acmeId, "k-n1", "BROADCAST", acmeFreeze, null, null, null);
        insertAttempt(payoutId, 7, "0x" + "a".repeat(64), "MINED", 100L);
        insertAttempt(payoutId, 7, "0x" + "b".repeat(64), "REPLACED", null);

        assertThatThrownBy(() -> insertAttempt(payoutId, 7, "0x" + "c".repeat(64), "MINED", 101L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("MINED 的尝试必须带上链的块号；没上链的不能带")
    void minedAttemptsCarryTheirBlock() {
        long payoutId = insertPayout(acmeId, "k-n2", "BROADCAST", acmeFreeze, null, null, null);

        assertThatThrownBy(() -> insertAttempt(payoutId, 1, "0x" + "d".repeat(64), "MINED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAttempt(payoutId, 1, "0x" + "e".repeat(64), "BROADCAST", 100L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ==================================================================
    // 租户边界与角色
    // ==================================================================

    @Test
    @DisplayName("★ 商户只看得到自己的提现与白名单，也写不进别人的名下")
    void merchantsSeeOnlyTheirOwnPayoutsAndAddresses() {
        insertPayout(acmeId, "k-r1", "QUEUED", acmeFreeze, null, null, null);
        insertPayout(evilcoId, "k-r2", "QUEUED", evilcoFreeze, null, null, null);
        jdbc.sql("INSERT INTO payout_address(merchant_id, address) VALUES (:m, :a)").param("m", evilcoId).param("a", DEST).update();

        long payoutsSeen = tenantScope.asMerchant(acmeId, () -> appJdbc.sql("SELECT count(*) FROM payout").query(Long.class).single());
        long addressesSeen = tenantScope.asMerchant(acmeId, () -> appJdbc.sql("SELECT count(*) FROM payout_address").query(Long.class).single());
        assertThat(payoutsSeen).isEqualTo(1);
        assertThat(addressesSeen).isZero();

        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> appJdbc
                .sql("INSERT INTO payout_address(merchant_id, address) VALUES (:m, :a)").param("m", evilcoId).param("a", HOT).update()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
    }

    @Test
    @DisplayName("★ 系统身份看得到全部提现，但删不了：提现表对谁都是只追加的")
    void systemRoleSeesAllButCannotDelete() {
        insertPayout(acmeId, "k-s1", "QUEUED", acmeFreeze, null, null, null);
        insertPayout(evilcoId, "k-s2", "QUEUED", evilcoFreeze, null, null, null);

        long seenBySystem = systemLedger.inTransaction(s -> s.jdbc().sql("SELECT count(*) FROM payout").query(Long.class).single());
        assertThat(seenBySystem).isEqualTo(2);
        for (String table : new String[] {"payout", "payout_tx", "hot_wallet", "payout_address"}) {
            assertThatThrownBy(() -> systemLedger.inTransaction(s -> s.jdbc().sql("DELETE FROM " + table).update()))
                    .as(table).isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("permission denied");
        }
    }

    @Test
    @DisplayName("★ 应用角色碰不到热钱包与链上尝试的写入：它们是系统任务的东西；读链上尝试可以（商户接口要显示哈希）")
    void appRoleCannotWriteHotWalletOrAttempts() {
        assertThatThrownBy(() -> appJdbc.sql("INSERT INTO hot_wallet(address, chain, next_nonce) VALUES (:a, 'sepolia', 0)").param("a", DEST).update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("UPDATE payout_tx SET status = 'DROPPED'").update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("UPDATE hot_wallet SET next_nonce = 99").update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");

        assertThat(appJdbc.sql("SELECT count(*) FROM payout_tx").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("★ 会话变量那道门已拆：应用连接上把 chainpay.system 设成 on，仍然一行都看不到；is_system_scope() 不存在了")
    void theSessionVariableDoorIsGone() {
        Long visible = new TransactionTemplate(txManager).execute(status -> {
            appJdbc.sql("SELECT set_config('chainpay.system', 'on', true)").query(String.class).single();
            return appJdbc.sql("SELECT count(*) FROM account").query(Long.class).single();
        });

        assertThat(visible).as("系统权限只剩连接身份一条路").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_proc WHERE proname = 'is_system_scope'").query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------

    private long merchant(String code) {
        return jdbc.sql("INSERT INTO merchant(code, name) VALUES (:c, :c) RETURNING id").param("c", code).query(Long.class).single();
    }

    private long account(String code, String kind, boolean allowNegative, Long merchantId) {
        return jdbc.sql("INSERT INTO account(code, currency, kind, allow_negative, merchant_id) VALUES (:c, 'LINK', :k, :n, :m) RETURNING id")
                .param("c", code).param("k", kind).param("n", allowNegative).param("m", merchantId)
                .query(Long.class).single();
    }

    private long insertPayout(long merchantId, String key, String status, long freezeId, Long settleId, Long reverseId, String reason) {
        return jdbc.sql("""
                        INSERT INTO payout (merchant_id, idempotency_key, token, to_address, amount, raw_value, status,
                                            freeze_transfer_id, settle_transfer_id, reverse_transfer_id, failure_reason)
                        VALUES (:m, :k, :t, :to, 1, 1000000000000000000, :s, :f, :se, :re, :why)
                        RETURNING id
                        """)
                .param("m", merchantId).param("k", key).param("t", LINK).param("to", DEST).param("s", status)
                .param("f", freezeId).param("se", settleId).param("re", reverseId).param("why", reason)
                .query(Long.class).single();
    }

    private void insertAttempt(long payoutId, long nonce, String txHash, String status, Long blockNumber) {
        jdbc.sql("""
                        INSERT INTO payout_tx (payout_id, hot_wallet, nonce, tx_hash, raw_tx, gas_limit, max_fee_per_gas,
                                               max_priority_fee_per_gas, status, block_number, block_hash)
                        VALUES (:p, :w, :n, :h, '0x02f8', 65000, 30000000000, 1500000000, :s, :b, :bh)
                        """)
                .param("p", payoutId).param("w", HOT).param("n", nonce).param("h", txHash).param("s", status)
                .param("b", blockNumber).param("bh", blockNumber == null ? null : "0x" + "f".repeat(64))
                .update();
    }
}
