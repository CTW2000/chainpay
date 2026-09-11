package com.chainpay.chain.payout.repository;

import com.chainpay.chain.payout.domain.PayoutAttempt;
import com.chainpay.chain.payout.domain.QueuedPayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 发送任务用到的提现与尝试（系统连接）。状态变更一律带「期望的当前状态」做守卫，改不动就是别的实例先动了。 */
public class PayoutSendRepository {

    private static final String ATTEMPT_COLUMNS = "id, payout_id, hot_wallet, nonce, tx_hash, raw_tx, status, gas_limit, max_fee_per_gas, "
            + "max_priority_fee_per_gas, block_number, block_hash, reverted, created_at";
    private static final RowMapper<PayoutAttempt> ATTEMPT = (rs, i) -> new PayoutAttempt(
            rs.getLong("id"), rs.getLong("payout_id"), rs.getString("hot_wallet"), rs.getLong("nonce"),
            rs.getString("tx_hash"), rs.getString("raw_tx"), rs.getString("status"),
            rs.getLong("gas_limit"), rs.getBigDecimal("max_fee_per_gas").toBigIntegerExact(), rs.getBigDecimal("max_priority_fee_per_gas").toBigIntegerExact(),
            rs.getObject("block_number", Long.class), rs.getString("block_hash"), rs.getObject("reverted", Boolean.class),
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());

    /** 结算或解冻一笔提现要知道的：币、金额、三个账户。 */
    public record Settlement(long payoutId, String symbol, BigDecimal amount, long userAccountId, long frozenAccountId, long custodyAccountId) {}

    private final JdbcClient jdbc;

    public PayoutSendRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 排队中的提现，按申请顺序；连同发它需要的代币、收款人、原始金额与失败时解冻要用的两个账户。 */
    public List<QueuedPayout> findQueued(int limit) {
        return jdbc.sql("""
                        SELECT p.id, p.merchant_id, p.token, t.symbol, p.to_address, p.amount, p.raw_value,
                               ua.id AS user_account, fa.id AS frozen_account
                        FROM payout p
                        JOIN merchant m ON m.id = p.merchant_id
                        JOIN chain_token t ON t.address = p.token
                        JOIN account ua ON ua.code = 'user:' || m.code || ':' || t.symbol
                        JOIN account fa ON fa.code = 'user:' || m.code || ':' || t.symbol || ':frozen'
                        WHERE p.status = 'QUEUED'
                        ORDER BY p.id
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, i) -> new QueuedPayout(rs.getLong("id"), rs.getLong("merchant_id"), rs.getString("token"), rs.getString("symbol"),
                        rs.getString("to_address"), rs.getBigDecimal("amount"), rs.getBigDecimal("raw_value").toBigIntegerExact(),
                        rs.getLong("user_account"), rs.getLong("frozen_account")))
                .list();
    }

    /** 改提现状态，带期望的当前状态；返回 false = 别的实例先改了。 */
    public boolean moveStatus(long payoutId, String from, String to) {
        return jdbc.sql("UPDATE payout SET status = :to, updated_at = now() WHERE id = :id AND status = :from")
                .param("id", payoutId).param("from", from).param("to", to).update() == 1;
    }

    public long insertAttempt(long payoutId, String hotWallet, long nonce, String txHash, String rawHex, long gasLimit,
                              BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas) {
        return jdbc.sql("""
                        INSERT INTO payout_tx (payout_id, hot_wallet, nonce, tx_hash, raw_tx, gas_limit, max_fee_per_gas, max_priority_fee_per_gas, status)
                        VALUES (:p, :w, :n, :h, :raw, :gas, :maxFee, :tip, 'SIGNED')
                        RETURNING id
                        """)
                .param("p", payoutId).param("w", hotWallet).param("n", nonce).param("h", txHash).param("raw", rawHex)
                .param("gas", gasLimit).param("maxFee", new BigDecimal(maxFeePerGas)).param("tip", new BigDecimal(maxPriorityFeePerGas))
                .query(Long.class).single();
    }

    public List<PayoutAttempt> findAttempts(String hotWallet, String status) {
        return jdbc.sql("SELECT " + ATTEMPT_COLUMNS + " FROM payout_tx WHERE hot_wallet = :w AND status = :s ORDER BY nonce, id")
                .param("w", hotWallet).param("s", status).query(ATTEMPT).list();
    }

    /** 所有钱包里处于某状态、且所属提现还没终结的尝试（追踪任务不认钱包，它只看链；终结了的提现不再追）。 */
    public List<PayoutAttempt> findAttemptsInStatus(String status) {
        String columns = java.util.Arrays.stream(ATTEMPT_COLUMNS.split(", ")).map(c -> "t." + c).collect(java.util.stream.Collectors.joining(", "));
        return jdbc.sql("SELECT " + columns + " FROM payout_tx t JOIN payout p ON p.id = t.payout_id "
                        + "WHERE t.status = :s AND p.status NOT IN ('CONFIRMED', 'FAILED', 'REJECTED') ORDER BY t.hot_wallet, t.nonce, t.id")
                .param("s", status).query(ATTEMPT).list();
    }

    /** 同钱包同编号的其它尝试（加价的替身们）。 */
    public List<PayoutAttempt> siblings(String hotWallet, long nonce, long exceptAttemptId) {
        return jdbc.sql("SELECT " + ATTEMPT_COLUMNS + " FROM payout_tx WHERE hot_wallet = :w AND nonce = :n AND id <> :id ORDER BY id")
                .param("w", hotWallet).param("n", nonce).param("id", exceptAttemptId).query(ATTEMPT).list();
    }

    /** 回执来了：BROADCAST → MINED，带块号块哈希；改不动 = 别的实例先记了。 */
    public boolean markMined(long attemptId, long blockNumber, String blockHash, boolean reverted, long gasUsed, BigInteger effectiveGasPrice) {
        return jdbc.sql("""
                        UPDATE payout_tx SET status = 'MINED', block_number = :b, block_hash = :h, reverted = :r, gas_used = :g,
                               effective_gas_price = :p, updated_at = now()
                        WHERE id = :id AND status = 'BROADCAST'
                        """)
                .param("id", attemptId).param("b", blockNumber).param("h", blockHash.toLowerCase(java.util.Locale.ROOT)).param("r", reverted)
                .param("g", gasUsed).param("p", new BigDecimal(effectiveGasPrice)).update() == 1;
    }

    /** 块被重组掉了：MINED → BROADCAST，块的三列清空（CHECK 约束要求 MINED ⇔ 带块）。 */
    public boolean unmine(long attemptId) {
        return jdbc.sql("UPDATE payout_tx SET status = 'BROADCAST', block_number = NULL, block_hash = NULL, reverted = NULL, gas_used = NULL, "
                        + "effective_gas_price = NULL, updated_at = now() WHERE id = :id AND status = 'MINED'")
                .param("id", attemptId).update() == 1;
    }

    /** 同编号的另一笔上链了（或顶掉了它）：把还没结局的兄弟全标 REPLACED。返回改了几行。 */
    public int replaceSiblings(String hotWallet, long nonce, long exceptAttemptId) {
        return jdbc.sql("UPDATE payout_tx SET status = 'REPLACED', updated_at = now() "
                        + "WHERE hot_wallet = :w AND nonce = :n AND id <> :id AND status IN ('SIGNED', 'BROADCAST', 'DROPPED')")
                .param("w", hotWallet).param("n", nonce).param("id", exceptAttemptId).update();
    }

    public Settlement findSettlement(long payoutId) {
        return jdbc.sql("""
                        SELECT p.id, t.symbol, p.amount, ua.id AS user_account, fa.id AS frozen_account, ca.id AS custody_account
                        FROM payout p
                        JOIN merchant m ON m.id = p.merchant_id
                        JOIN chain_token t ON t.address = p.token
                        JOIN account ua ON ua.code = 'user:' || m.code || ':' || t.symbol
                        JOIN account fa ON fa.code = 'user:' || m.code || ':' || t.symbol || ':frozen'
                        JOIN account ca ON ca.code = 'chain:custody:' || t.symbol
                        WHERE p.id = :id
                        """)
                .param("id", payoutId)
                .query((rs, i) -> new Settlement(rs.getLong("id"), rs.getString("symbol"), rs.getBigDecimal("amount"),
                        rs.getLong("user_account"), rs.getLong("frozen_account"), rs.getLong("custody_account")))
                .single();
    }

    /** FINAL 且结算了：MINED → CONFIRMED，带结算转账。CHECK 约束要求 CONFIRMED ⇔ 有结算。 */
    public boolean markConfirmed(long payoutId, long settleTransferId) {
        return jdbc.sql("UPDATE payout SET status = 'CONFIRMED', settle_transfer_id = :t, updated_at = now() WHERE id = :id AND status = 'MINED'")
                .param("id", payoutId).param("t", settleTransferId).update() == 1;
    }

    /** FINAL 但链上执行失败（回执 status 0）：MINED → FAILED，带原因与解冻转账。 */
    public boolean markFailedFromMined(long payoutId, String reason, long reverseTransferId) {
        return jdbc.sql("UPDATE payout SET status = 'FAILED', failure_reason = :r, reverse_transfer_id = :t, updated_at = now() WHERE id = :id AND status = 'MINED'")
                .param("id", payoutId).param("r", reason).param("t", reverseTransferId).update() == 1;
    }

    /** 改尝试状态，带期望的当前状态；返回 false = 别的实例先改了。 */
    public boolean moveAttempt(long attemptId, String from, String to) {
        return jdbc.sql("UPDATE payout_tx SET status = :to, updated_at = now() WHERE id = :id AND status = :from")
                .param("id", attemptId).param("from", from).param("to", to).update() == 1;
    }

    public java.util.Optional<String> findStatus(long payoutId) {
        return jdbc.sql("SELECT status FROM payout WHERE id = :id").param("id", payoutId).query(String.class).optional();
    }

    /** 人工拒绝：PENDING_APPROVAL → REJECTED，带原因与解冻转账（CHECK 约束要求两者同在）。 */
    public boolean markRejected(long payoutId, String reason, long reverseTransferId) {
        return jdbc.sql("UPDATE payout SET status = 'REJECTED', failure_reason = :r, reverse_transfer_id = :t, updated_at = now() "
                        + "WHERE id = :id AND status = 'PENDING_APPROVAL'")
                .param("id", payoutId).param("r", reason).param("t", reverseTransferId).update() == 1;
    }

    /** 锁住这笔提现并读它现在的状态：两个实例同时想给同一笔定结局时，后到的要等先到的提交，然后看到已经定过。 */
    public String lockStatus(long payoutId) {
        return jdbc.sql("SELECT status FROM payout WHERE id = :id FOR UPDATE").param("id", payoutId).query(String.class).single();
    }

    /** 还没结局的编号数：分出去了、但没有任何一次尝试上链的那些编号（同一编号的多次尝试只算一个）。 */
    public long unfinishedNonces(String hotWallet) {
        return jdbc.sql("""
                        SELECT count(DISTINCT nonce) FROM payout_tx
                        WHERE hot_wallet = :w
                          AND nonce NOT IN (SELECT nonce FROM payout_tx WHERE hot_wallet = :w AND status = 'MINED')
                        """)
                .param("w", hotWallet).query(Long.class).single();
    }

    /** 排队中的提现直接判失败（估 gas 就 revert 之类）：状态、原因、解冻转账一起写，CHECK 约束要求三者同时成立。 */
    public void markFailed(long payoutId, String reason, long reverseTransferId) {
        int rows = jdbc.sql("UPDATE payout SET status = 'FAILED', failure_reason = :r, reverse_transfer_id = :t, updated_at = now() WHERE id = :id AND status = 'QUEUED'")
                .param("id", payoutId).param("r", reason).param("t", reverseTransferId).update();
        if (rows != 1) {
            throw new IllegalStateException("提现 " + payoutId + " 不在 QUEUED，判不了失败");
        }
    }
}
