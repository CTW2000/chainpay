package com.chainpay.chain.payout.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 商户连接上的提现数据访问（M4-④）：每条语句都在 asMerchant 的事务里跑，RLS 把 payout / payout_address / account 限在本商户。
 * 状态只由系统身份改（应用角色对 payout 没有 UPDATE），这里只有插与读。
 */
public class WithdrawalRepository {

    public record TokenRow(String address, String symbol, int decimals, String status) {}

    public record AddressRow(long id, String address, String label, String status, Instant createdAt) {}

    public record Limit(BigDecimal perTxMax, BigDecimal dailyMax) {}

    public record WithdrawalRow(long id, String idempotencyKey, String token, String symbol, String toAddress, BigDecimal amount,
                                BigInteger rawValue, String status, String failureReason, String txHash, Instant createdAt, Instant updatedAt) {}

    private static final RowMapper<AddressRow> ADDRESS = (rs, i) -> new AddressRow(rs.getLong("id"), rs.getString("address"),
            rs.getString("label"), rs.getString("status"), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());

    private static final String WITHDRAWAL_COLUMNS = """
            p.id, p.idempotency_key, p.token, t.symbol, p.to_address, p.amount, p.raw_value, p.status, p.failure_reason, p.created_at, p.updated_at,
            (SELECT x.tx_hash FROM payout_tx x WHERE x.payout_id = p.id
               ORDER BY (x.status = 'MINED') DESC, (x.status = 'BROADCAST') DESC, x.id DESC LIMIT 1) AS tx_hash
            """;
    private static final RowMapper<WithdrawalRow> WITHDRAWAL = (rs, i) -> new WithdrawalRow(rs.getLong("id"), rs.getString("idempotency_key"),
            rs.getString("token"), rs.getString("symbol"), rs.getString("to_address"), rs.getBigDecimal("amount"),
            rs.getBigDecimal("raw_value").toBigIntegerExact(), rs.getString("status"), rs.getString("failure_reason"), rs.getString("tx_hash"),
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(), rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public WithdrawalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TokenRow> findToken(String token) {
        return jdbc.sql("SELECT address, symbol, decimals, status FROM chain_token WHERE address = :a").param("a", token)
                .query((rs, i) -> new TokenRow(rs.getString("address"), rs.getString("symbol"), rs.getInt("decimals"), rs.getString("status"))).optional();
    }

    /** 登记白名单地址：已有就返回已有的（幂等），不改它的状态。 */
    public AddressRow upsertAddress(long merchantId, String address, String label) {
        jdbc.sql("INSERT INTO payout_address (merchant_id, address, label) VALUES (:m, :a, :l) ON CONFLICT (merchant_id, address) DO NOTHING")
                .param("m", merchantId).param("a", address).param("l", label).update();
        return jdbc.sql("SELECT id, address, label, status, created_at FROM payout_address WHERE address = :a").param("a", address).query(ADDRESS).single();
    }

    public List<AddressRow> listAddresses() {
        return jdbc.sql("SELECT id, address, label, status, created_at FROM payout_address ORDER BY id").query(ADDRESS).list();
    }

    public Optional<AddressRow> findAddress(String address) {
        return jdbc.sql("SELECT id, address, label, status, created_at FROM payout_address WHERE address = :a").param("a", address).query(ADDRESS).optional();
    }

    /** 停用：RLS 让别人的行改不动（0 行）。 */
    public boolean disableAddress(long id) {
        return jdbc.sql("UPDATE payout_address SET status = 'DISABLED' WHERE id = :id AND status = 'ACTIVE'").param("id", id).update() == 1;
    }

    /** 锁住本商户那一行直到事务结束：同一商户的申请串行化，「当日汇总 + 插行」之间没有缝。返回商户 code。 */
    public String lockMerchant(long merchantId) {
        return jdbc.sql("SELECT code FROM merchant WHERE id = :id FOR UPDATE").param("id", merchantId).query(String.class).single();
    }

    public long ensureUserAccount(long merchantId, String merchantCode, String symbol) {
        String code = "user:" + merchantCode + ":" + symbol;
        jdbc.sql("INSERT INTO account (code, currency, kind, merchant_id) VALUES (:c, :s, 'LIABILITY', :m) ON CONFLICT (code) DO NOTHING")
                .param("c", code).param("s", symbol).param("m", merchantId).update();
        return jdbc.sql("SELECT id FROM account WHERE code = :c").param("c", code).query(Long.class).single();
    }

    public Optional<WithdrawalRow> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT " + WITHDRAWAL_COLUMNS + " FROM payout p JOIN chain_token t ON t.address = p.token WHERE p.idempotency_key = :k")
                .param("k", key).query(WITHDRAWAL).optional();
    }

    public Optional<Limit> findLimit(String token) {
        return jdbc.sql("SELECT per_tx_max, daily_max FROM payout_limit WHERE token = :t").param("t", token)
                .query((rs, i) -> new Limit(rs.getBigDecimal("per_tx_max"), rs.getBigDecimal("daily_max"))).optional();
    }

    /**
     * 本商户这种代币当日（UTC）已<b>自动放行</b>的合计：等人核准的不算（人核准时看得到全部），退了钱的不算。
     * 当日上限管的是「一天里不经人手能出去多少」。RLS 已把行限在本商户。
     */
    public BigDecimal sumToday(String token) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM payout
                        WHERE token = :t AND status NOT IN ('PENDING_APPROVAL', 'FAILED', 'REJECTED')
                          AND created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                        """)
                .param("t", token).query(BigDecimal.class).single();
    }

    public long insert(long merchantId, String idempotencyKey, String token, String toAddress, BigDecimal amount, BigInteger rawValue,
                       String status, long freezeTransferId) {
        return jdbc.sql("""
                        INSERT INTO payout (merchant_id, idempotency_key, token, to_address, amount, raw_value, status, freeze_transfer_id)
                        VALUES (:m, :k, :t, :to, :amt, :raw, :st, :f) RETURNING id
                        """)
                .param("m", merchantId).param("k", idempotencyKey).param("t", token).param("to", toAddress).param("amt", amount)
                .param("raw", new BigDecimal(rawValue)).param("st", status).param("f", freezeTransferId).query(Long.class).single();
    }

    public WithdrawalRow findById(long id) {
        return jdbc.sql("SELECT " + WITHDRAWAL_COLUMNS + " FROM payout p JOIN chain_token t ON t.address = p.token WHERE p.id = :id")
                .param("id", id).query(WITHDRAWAL).single();
    }

    public List<WithdrawalRow> list(String token, String status, int limit) {
        return jdbc.sql("SELECT " + WITHDRAWAL_COLUMNS + " FROM payout p JOIN chain_token t ON t.address = p.token "
                        + "WHERE (:t::text IS NULL OR p.token = :t) AND (:s::text IS NULL OR p.status = :s) ORDER BY p.id DESC LIMIT :n")
                .param("t", token).param("s", status).param("n", limit).query(WITHDRAWAL).list();
    }

    /** 本商户在这种币上冻着的钱（没有冻结账户 = 0）。 */
    public BigDecimal frozenBalance(String symbol) {
        return jdbc.sql("""
                        SELECT a.balance FROM account a JOIN merchant m ON m.id = a.merchant_id
                        WHERE a.code = 'user:' || m.code || ':' || :s || ':frozen'
                        """)
                .param("s", symbol).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }
}
