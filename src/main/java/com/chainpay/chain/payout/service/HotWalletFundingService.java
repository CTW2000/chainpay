package com.chainpay.chain.payout.service;

import com.chainpay.common.web.ErrorCode;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注资登记：运营往热钱包充的币，账本不记（不是任何商户的钱），但对账的托管等式要能解释它，否则每一轮对账都报「链上多」。
 * 运营只指认「是哪一笔」（交易哈希，同一笔里多条时再给 logIndex）；金额、块、代币全部从索引器已经记下的日志里读——数字不由人填。
 * 门按这个顺序：txHash 的形状（400）；库里有这笔转给热钱包的日志（404——收款方不是热钱包的那是入账，同样 404）；
 * 一笔交易里有多条时要给 logIndex（400）；登记过的直接返回同一行；日志在主分支（409）；块 ≤ finalized（409，会被重组翻掉的钱不进等式）；
 * 发起方不是平台自己的地址（收款地址或热钱包，那是归集，400）。
 * 全段走系统身份：hot_wallet_funding 只有系统角色能写，deposit_address 要跨全部商户看。
 */
@Service
public class HotWalletFundingService {

    private static final Logger log = LoggerFactory.getLogger(HotWalletFundingService.class);

    public record Funding(long id, String hotWallet, String token, BigInteger rawValue, long blockNumber, String txHash, int logIndex, String note,
                          Instant registeredAt) {}

    private record Candidate(long logId, String token, String from, String to, BigInteger value, long block, int logIndex, String status) {}

    private final JdbcClient jdbc;

    public HotWalletFundingService(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc) {
        this.jdbc = systemJdbc;
    }

    @Transactional(SystemLedger.QUALIFIER)
    public Funding register(String txHash, Integer logIndex, String note) {
        String hash = normalize(txHash);
        List<Candidate> candidates = jdbc.sql("""
                        SELECT l.id, l.token, l.from_address, l.to_address, l.value, l.block_number, l.log_index, l.status
                        FROM chain_transfer_log l JOIN hot_wallet w ON w.address = l.to_address
                        WHERE l.tx_hash = :h AND (:i::int IS NULL OR l.log_index = :i) ORDER BY l.log_index
                        """)
                .param("h", hash).param("i", logIndex)
                .query((rs, i) -> new Candidate(rs.getLong("id"), rs.getString("token"), rs.getString("from_address"), rs.getString("to_address"),
                        rs.getBigDecimal("value").toBigIntegerExact(), rs.getLong("block_number"), rs.getInt("log_index"), rs.getString("status"))).list();
        if (candidates.isEmpty()) {
            throw new FundingRejectedException(HttpStatus.NOT_FOUND, ErrorCode.LOG_NOT_FOUND,
                    "库里没有交易 " + hash + (logIndex == null ? "" : " 第 " + logIndex + " 条") + " 转给热钱包的日志：哈希错了、还没索到，或收款方不是热钱包");
        }
        if (candidates.size() > 1) {
            throw new FundingRejectedException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "交易 " + hash + " 里有 " + candidates.size() + " 条转给热钱包的日志（logIndex " + candidates.stream().map(c -> String.valueOf(c.logIndex())).toList() + "），指定 logIndex");
        }
        Candidate c = candidates.getFirst();
        Optional<Funding> existing = findByLog(jdbc, c.logId());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!"CANONICAL".equals(c.status())) {
            throw new FundingRejectedException(HttpStatus.CONFLICT, ErrorCode.NOT_FINALIZED, "这条日志已不在主分支（" + c.status() + "）：不登记");
        }
        long finalized = jdbc.sql("SELECT finalized_number FROM chain_head").query(Long.class).optional().orElse(-1L);
        if (c.block() > finalized) {
            throw new FundingRejectedException(HttpStatus.CONFLICT, ErrorCode.NOT_FINALIZED,
                    "块 " + c.block() + " 还没 finalized（现在 " + finalized + "）：会被重组翻掉的钱不进等式，等一会儿再来");
        }
        boolean internal = jdbc.sql("SELECT EXISTS (SELECT 1 FROM deposit_address WHERE address = :a) OR EXISTS (SELECT 1 FROM hot_wallet WHERE address = :a)")
                .param("a", c.from()).query(Boolean.class).single();
        if (internal) {
            throw new FundingRejectedException(HttpStatus.BAD_REQUEST, ErrorCode.INTERNAL_ADDRESS,
                    "发起方 " + c.from() + " 是平台自己的地址：这是内部挪动（归集），不是注资，不改变托管总量");
        }
        long id = jdbc.sql("""
                        INSERT INTO hot_wallet_funding (transfer_log_id, hot_wallet, token, raw_value, block_number, tx_hash, note)
                        VALUES (:l, :w, :t, :v, :b, :h, :n) RETURNING id
                        """)
                .param("l", c.logId()).param("w", c.to()).param("t", c.token()).param("v", new BigDecimal(c.value())).param("b", c.block())
                .param("h", hash).param("n", note).query(Long.class).single();
        log.info("注资登记 {}：热钱包 {} 收到 {}（原始单位）于块 {}，交易 {}", id, c.to(), c.value(), c.block(), hash);
        return findByLog(jdbc, c.logId()).orElseThrow();
    }

    public List<Funding> list() {
        return jdbc.sql(SELECT + " ORDER BY f.id DESC LIMIT 200").query(this::map).list();
    }

    private static final String SELECT = """
            SELECT f.id, f.hot_wallet, f.token, f.raw_value, f.block_number, f.tx_hash, l.log_index, f.note, f.registered_at
            FROM hot_wallet_funding f JOIN chain_transfer_log l ON l.id = f.transfer_log_id""";

    private Optional<Funding> findByLog(JdbcClient jdbc, long logId) {
        return jdbc.sql(SELECT + " WHERE f.transfer_log_id = :l").param("l", logId).query(this::map).optional();
    }

    private Funding map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Funding(rs.getLong("id"), rs.getString("hot_wallet"), rs.getString("token"), rs.getBigDecimal("raw_value").toBigIntegerExact(),
                rs.getLong("block_number"), rs.getString("tx_hash"), rs.getInt("log_index"), rs.getString("note"),
                rs.getObject("registered_at", OffsetDateTime.class).toInstant());
    }

    private static String normalize(String txHash) {
        if (txHash == null || !txHash.matches("0[xX][0-9a-fA-F]{64}")) {
            throw new FundingRejectedException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "txHash 必须是 0x 开头的 64 位十六进制");
        }
        return "0x" + txHash.substring(2).toLowerCase(Locale.ROOT);
    }
}
