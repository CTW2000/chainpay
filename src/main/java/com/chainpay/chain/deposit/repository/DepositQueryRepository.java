package com.chainpay.chain.deposit.repository;

import com.chainpay.chain.erc20.TokenAmounts;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 商户视角的只读查询。走应用连接，必须在 {@code asMerchant} 作用域里调：
 * deposit_address 与 deposit 有 RLS，别人的行根本查不出来；链表没有 RLS，但每条查询都经 deposit_address 连接，
 * 所以只会带出自己地址上的转账。「不存在」和「不是你的」在这里同样是一个结果：空。
 */
@Repository
public class DepositQueryRepository {

    /** 列表里的一行：已处理的入账行，或还没有 deposit 行的「在路上」的钱（status = PENDING，amount 由服务层换算）。 */
    public record DepositRow(String token, String symbol, int decimals, String address, BigInteger rawValue, BigDecimal amount,
                             String status, String level, long confirmations, long blockNumber, String txHash, int logIndex,
                             Instant occurredAt, Instant creditedAt) {}

    public record AddressRow(String address, String token, String symbol, String status) {}

    private static final RowMapper<DepositRow> ROW = (rs, i) -> new DepositRow(
            rs.getString("token"), rs.getString("symbol"), rs.getInt("decimals"), rs.getString("address"),
            rs.getBigDecimal("raw_value").toBigIntegerExact(), rs.getBigDecimal("amount"), rs.getString("status"),
            rs.getString("level"), rs.getLong("confirmations"), rs.getLong("block_number"), rs.getString("tx_hash"),
            rs.getInt("log_index"), instant(rs.getObject("occurred_at", OffsetDateTime.class)),
            instant(rs.getObject("credited_at", OffsetDateTime.class)));

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private final JdbcClient jdbc;

    public DepositQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AddressRow> listAddresses() {
        return jdbc.sql("""
                        SELECT da.address, da.token, t.symbol, da.status
                        FROM deposit_address da JOIN chain_token t ON t.address = da.token
                        ORDER BY da.derivation_index
                        """)
                .query((rs, i) -> new AddressRow(rs.getString("address"), rs.getString("token"), rs.getString("symbol"), rs.getString("status")))
                .list();
    }

    /**
     * 上半段：视图里到我们地址的转账，还没有 deposit 行 = 在路上（SEEN / SAFE / 已 FINAL 但任务还没跑到）。
     * 下半段：已处理的入账行，level 与 confirmations 仍从视图取（被抛弃的行视图里没有，那时按 FINAL 算——它记账时就是 FINAL）。
     */
    public List<DepositRow> listDeposits(String token, String status, int limit) {
        return jdbc.sql("""
                        SELECT * FROM (
                            SELECT c.token, t.symbol, t.decimals, c.to_address AS address, c.value AS raw_value,
                                   NULL::numeric AS amount, 'PENDING' AS status, c.level, c.confirmations,
                                   c.block_number, c.tx_hash, c.log_index, NULL::timestamptz AS occurred_at, NULL::timestamptz AS credited_at
                            FROM chain_transfer_confirmation c
                            JOIN deposit_address da ON da.address = c.to_address AND da.token = c.token
                            JOIN chain_token t ON t.address = c.token
                            WHERE NOT EXISTS (SELECT 1 FROM deposit d WHERE d.transfer_log_id = c.id)
                            UNION ALL
                            SELECT d.token, t.symbol, t.decimals, d.address, d.raw_value, d.amount, d.status,
                                   COALESCE(c.level, 'FINAL'), COALESCE(c.confirmations, 0),
                                   d.block_number, l.tx_hash, l.log_index, d.occurred_at, d.credited_at
                            FROM deposit d
                            JOIN chain_transfer_log l ON l.id = d.transfer_log_id
                            LEFT JOIN chain_transfer_confirmation c ON c.id = l.id
                            JOIN chain_token t ON t.address = d.token
                        ) x
                        WHERE (CAST(:token AS text) IS NULL OR x.token = :token)
                          AND (CAST(:status AS text) IS NULL OR x.status = :status)
                        ORDER BY x.block_number DESC, x.log_index DESC
                        LIMIT :limit
                        """)
                .param("token", token, Types.VARCHAR).param("status", status, Types.VARCHAR).param("limit", limit)
                .query(ROW).list();
    }

    /** 该商户在这种币上的账本余额（available）；还没申请过地址 = 没有账户 = 空。 */
    /** 本商户在这种币上冻着的钱（M4-④，账户 user:<code>:<SYM>:frozen）；没有冻结账户 = 0。 */
    public BigDecimal frozenBalance(String symbol) {
        return jdbc.sql("SELECT a.balance FROM account a JOIN merchant m ON m.id = a.merchant_id WHERE a.code = 'user:' || m.code || ':' || :s || ':frozen'")
                .param("s", symbol).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO.setScale(TokenAmounts.LEDGER_SCALE));
    }

    public Optional<BigDecimal> availableBalance(String token) {
        return jdbc.sql("SELECT a.balance FROM deposit_address da JOIN account a ON a.id = da.account_id WHERE da.token = :t")
                .param("t", token).query(BigDecimal.class).optional();
    }

    /** 在路上的钱的原始单位合计（还没有 deposit 行的转账），换算由服务层做一次。 */
    public BigInteger pendingRaw(String token) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(c.value), 0)
                        FROM chain_transfer_confirmation c
                        JOIN deposit_address da ON da.address = c.to_address AND da.token = c.token
                        WHERE c.token = :t AND NOT EXISTS (SELECT 1 FROM deposit d WHERE d.transfer_log_id = c.id)
                        """)
                .param("t", token).query(BigDecimal.class).single().toBigIntegerExact();
    }

    public Optional<TokenRow> findToken(String token) {
        return jdbc.sql("SELECT symbol, decimals, status FROM chain_token WHERE address = :t")
                .param("t", token)
                .query((rs, i) -> new TokenRow(rs.getString("symbol"), rs.getInt("decimals"), rs.getString("status")))
                .optional();
    }

    public record TokenRow(String symbol, int decimals, String status) {}
}
