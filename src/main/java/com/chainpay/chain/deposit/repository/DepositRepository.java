package com.chainpay.chain.deposit.repository;

import com.chainpay.chain.deposit.domain.DepositCandidate;
import com.chainpay.chain.deposit.domain.DepositStatus;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * deposit 表的 SQL，以及入账时依赖的镜像账户与「事件累计」。
 *
 * <p><b>不是 Spring bean</b>：它跑在系统连接上，由入账任务在 {@code SystemLedger.inTransaction} 的回调里
 * 用会话的 JdbcClient 现造一个——同 SystemLedger 的纪律，系统身份的 SQL 只在系统事务里出现。
 */
public class DepositRepository {

    private static final String CANDIDATE_COLUMNS = """
            c.id, c.block_number, c.block_hash, c.log_index, c.token, t.symbol, t.decimals, t.min_deposit,
            c.to_address, c.value, da.merchant_id, da.account_id""";

    private static RowMapper<DepositCandidate> candidate(boolean approved) {
        return (rs, i) -> new DepositCandidate(
                rs.getLong("id"), rs.getLong("block_number"), rs.getString("block_hash"), rs.getInt("log_index"),
                rs.getString("token"), rs.getString("symbol"), rs.getInt("decimals"), rs.getString("to_address"),
                rs.getBigDecimal("value").toBigIntegerExact(), rs.getLong("merchant_id"), rs.getLong("account_id"),
                rs.getBigDecimal("min_deposit"), approved ? rs.getLong("deposit_id") : null);
    }

    private final JdbcClient jdbc;

    public DepositRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 入账队列：已 FINAL（视图按 chain_head 算出来的）、收款方是 ACTIVE 的收款地址、代币 ACTIVE、还没有 deposit 行的日志，按链上顺序。
     * 「谁的钱」由 deposit_address 那一行决定，不接受任何调用方递进来的商户 id（M3-before 第 20 问）。
     */
    public List<DepositCandidate> findFinalUnposted(int limit) {
        return jdbc.sql("SELECT " + CANDIDATE_COLUMNS + """

                        FROM chain_transfer_confirmation c
                        JOIN deposit_address da ON da.address = c.to_address AND da.token = c.token AND da.status = 'ACTIVE'
                        JOIN chain_token t ON t.address = c.token AND t.status = 'ACTIVE'
                        WHERE c.level = 'FINAL'
                          AND NOT EXISTS (SELECT 1 FROM deposit d WHERE d.transfer_log_id = c.id)
                        ORDER BY c.block_number, c.log_index
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(candidate(false)).list();
    }

    /** 人复核后改成 APPROVED 的旧行：重新记账，不再核对。按行 id 顺序。 */
    public List<DepositCandidate> findApproved(int limit) {
        return jdbc.sql("SELECT " + CANDIDATE_COLUMNS + """
                        , d.id AS deposit_id
                        FROM deposit d
                        JOIN chain_transfer_log c ON c.id = d.transfer_log_id
                        JOIN deposit_address da ON da.address = d.address
                        JOIN chain_token t ON t.address = d.token
                        WHERE d.status = 'APPROVED'
                        ORDER BY d.id
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(candidate(true)).list();
    }

    /**
     * 「合约说的」累计：到某一块为止，这个地址在这种代币上的转入减转出（原始单位，CANONICAL 的日志）。
     * 入账前拿它和「合约做的」（balanceOf）比。M3 没有归集，等式应精确成立。
     */
    public BigInteger netTransfersUpTo(String address, String token, long blockNumber) {
        BigDecimal net = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN to_address = :a THEN value ELSE 0 END), 0)
                             - COALESCE(SUM(CASE WHEN from_address = :a THEN value ELSE 0 END), 0)
                        FROM chain_transfer_log
                        WHERE token = :t AND status = 'CANONICAL' AND block_number <= :b
                          AND (to_address = :a OR from_address = :a)
                        """)
                .param("a", address).param("t", token).param("b", blockNumber)
                .query(BigDecimal.class).single();
        return net.toBigIntegerExact();
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

    /** 人核准的行重新占坑：UPDATE … WHERE status = 'APPROVED'，两个实例只有一个改得到。 */
    public Optional<Long> claimApproved(long depositId, BigDecimal amount, Instant occurredAt) {
        int rows = jdbc.sql("""
                        UPDATE deposit SET status = 'POSTING', amount = :amount, occurred_at = :occurredAt
                        WHERE id = :id AND status = 'APPROVED'
                        """)
                .param("id", depositId).param("amount", amount, Types.NUMERIC)
                .param("occurredAt", occurredAt == null ? null : occurredAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return rows == 1 ? Optional.of(depositId) : Optional.empty();
    }

    /** 人核准的行记不上（金额装不下、结构性异常）：改回 HELD_ERROR 带原因，等人再看。 */
    public boolean holdApproved(long depositId, String reason) {
        return jdbc.sql("UPDATE deposit SET status = 'HELD_ERROR', hold_reason = :reason WHERE id = :id AND status = 'APPROVED'")
                .param("reason", reason).param("id", depositId)
                .update() == 1;
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
