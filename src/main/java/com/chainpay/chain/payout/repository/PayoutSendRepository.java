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

    private static final RowMapper<PayoutAttempt> ATTEMPT = (rs, i) -> new PayoutAttempt(
            rs.getLong("id"), rs.getLong("payout_id"), rs.getString("hot_wallet"), rs.getLong("nonce"),
            rs.getString("tx_hash"), rs.getString("raw_tx"), rs.getString("status"));

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
        return jdbc.sql("SELECT id, payout_id, hot_wallet, nonce, tx_hash, raw_tx, status FROM payout_tx WHERE hot_wallet = :w AND status = :s ORDER BY nonce, id")
                .param("w", hotWallet).param("s", status).query(ATTEMPT).list();
    }

    /** 改尝试状态，带期望的当前状态；返回 false = 别的实例先改了。 */
    public boolean moveAttempt(long attemptId, String from, String to) {
        return jdbc.sql("UPDATE payout_tx SET status = :to, updated_at = now() WHERE id = :id AND status = :from")
                .param("id", attemptId).param("from", from).param("to", to).update() == 1;
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
