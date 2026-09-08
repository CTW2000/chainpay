package com.chainpay.chain.deposit.repository;

import com.chainpay.chain.deposit.domain.DepositCandidate;
import com.chainpay.chain.deposit.domain.DepositStatus;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * deposit 表的 SQL，以及入账时依赖的镜像账户。
 *
 * <p><b>不是 Spring bean</b>：它跑在系统连接上，由入账任务在 {@code SystemLedger.inTransaction} 的回调里
 * 用会话的 JdbcClient 现造一个——同 SystemLedger 的纪律，系统身份的 SQL 只在系统事务里出现。
 */
public class DepositRepository {

    private static final RowMapper<DepositCandidate> CANDIDATE = (rs, i) -> new DepositCandidate(
            rs.getLong("id"), rs.getLong("block_number"), rs.getString("block_hash"), rs.getInt("log_index"),
            rs.getString("token"), rs.getString("symbol"), rs.getInt("decimals"), rs.getString("to_address"),
            rs.getBigDecimal("value").toBigIntegerExact(), rs.getLong("merchant_id"), rs.getLong("account_id"));

    private final JdbcClient jdbc;

    public DepositRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 入账队列：已 FINAL（视图按 chain_head 算出来的）、收款方是 ACTIVE 的收款地址、代币 ACTIVE、还没有 deposit 行的日志，按链上顺序。
     * 「谁的钱」由 deposit_address 那一行决定，不接受任何调用方递进来的商户 id（M3-before 第 20 问）。
     */
    public List<DepositCandidate> findFinalUnposted(int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.block_number, c.block_hash, c.log_index, c.token, t.symbol, t.decimals,
                               c.to_address, c.value, da.merchant_id, da.account_id
                        FROM chain_transfer_confirmation c
                        JOIN deposit_address da ON da.address = c.to_address AND da.token = c.token AND da.status = 'ACTIVE'
                        JOIN chain_token t ON t.address = c.token AND t.status = 'ACTIVE'
                        WHERE c.level = 'FINAL'
                          AND NOT EXISTS (SELECT 1 FROM deposit d WHERE d.transfer_log_id = c.id)
                        ORDER BY c.block_number, c.log_index
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(CANDIDATE).list();
    }

    /**
     * 镜像账户 chain:custody:&lt;SYMBOL&gt;：链上托管地址里的币在账本里的影子。入账时它是变负的对手方，
     * 余额的绝对值 = 所有托管地址链上应有的余额之和（M5 对账的判官）。不存在就建，唯一性由 account_code_uk 裁决。
     */
    public long ensureCustodyAccount(String symbol) {
        String code = "chain:custody:" + symbol;
        jdbc.sql("""
                        INSERT INTO account (code, currency, kind, allow_negative, merchant_id)
                        VALUES (:code, :currency, 'ASSET', TRUE, NULL)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", code).param("currency", symbol)
                .update();
        return jdbc.sql("SELECT id FROM account WHERE code = :code").param("code", code)
                .query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("镜像账户 " + code + " 建不出来也读不到"));
    }

    /** 占坑：INSERT … ON CONFLICT (transfer_log_id) DO NOTHING。返回 deposit id；这条日志已被处理过就返回空。 */
    public Optional<Long> claim(DepositCandidate c, DepositStatus status, BigDecimal amount, String holdReason, Instant occurredAt) {
        return jdbc.sql("""
                        INSERT INTO deposit (transfer_log_id, address, merchant_id, token, raw_value, amount, status,
                                             hold_reason, block_number, block_hash, occurred_at)
                        VALUES (:log, :address, :merchant, :token, :raw, :amount, :status,
                                :reason, :block, :hash, :occurredAt)
                        ON CONFLICT (transfer_log_id) DO NOTHING
                        RETURNING id
                        """)
                .param("log", c.logId()).param("address", c.toAddress()).param("merchant", c.merchantId())
                .param("token", c.token()).param("raw", new BigDecimal(c.rawValue()))
                .param("amount", amount, Types.NUMERIC).param("status", status.name())
                .param("reason", holdReason, Types.VARCHAR).param("block", c.blockNumber()).param("hash", c.blockHash())
                .param("occurredAt", occurredAt == null ? null : occurredAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class).optional();
    }

    /** 记账之后把占坑行改成 CREDITED 并挂上 transfer_id。只允许从 POSTING 改，改不到就是状态机被绕过了。 */
    public void credit(long depositId, long transferId) {
        int rows = jdbc.sql("""
                        UPDATE deposit SET status = 'CREDITED', transfer_id = :transfer, credited_at = now()
                        WHERE id = :id AND status = 'POSTING'
                        """)
                .param("transfer", transferId).param("id", depositId)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("入账行 " + depositId + " 不在 POSTING 状态，无法记成 CREDITED");
        }
    }
}
