package com.chainpay.audit.repository;

import com.chainpay.audit.domain.AuditFinding;
import com.chainpay.audit.domain.AuditKind;
import com.chainpay.audit.domain.AuditResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 对账用到的读与写（系统连接：要看全部商户的行）。只追加：结论与差异写进去就不改。 */
public class AuditRepository {

    /** 脚下的块：号、哈希、它是 finalized 还是索引书签（书签落后时站在书签上：证据只到那里）。 */
    public record Head(long number, String hash, String basis) {}
    public record Token(String address, String symbol, int decimals) {}
    /** 一笔记了账的入账，连同它的链上证据与账本转账。 */
    public record CreditedDeposit(long id, String token, BigDecimal amount, BigInteger rawValue, long logId, String logStatus, long logBlock,
                                  String blockHash, int logIndex, BigDecimal transferAmount) {}
    /** 一条收款方是白名单里 ACTIVE 收款地址、代币 ACTIVE 的主分支日志。 */
    public record Inflow(long logId, String token, String toAddress, long block, String blockHash, int logIndex, BigInteger value) {}
    /** 一笔 CONFIRMED 的提现，连同它的 MINED 尝试与链上日志（可能没有）。 */
    public record ConfirmedPayout(long id, String token, String toAddress, BigDecimal amount, BigInteger rawValue, BigDecimal settleAmount,
                                  Long attemptId, String txHash, Long attemptBlock, Boolean reverted, int minedAttempts,
                                  String logStatus, String logTo, BigInteger logValue, Long logBlock) {}
    /** 一条从热钱包发出的主分支日志。 */
    public record Outflow(long logId, String token, String from, String to, BigInteger value, long block, String blockHash, int logIndex, String txHash) {}

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 读：脚下的块

    /**
     * 脚下的块：库里的 finalized 与索引书签里较小的那个。索引器追赶时书签落后于 finalized，链上余额是 finalized 的、
     * 库里日志只到书签，直接比就会把「还没索到」报成差异；站在书签上，两边的证据才是同一个时刻的。
     */
    public Optional<Head> standingBlock() {
        Optional<Head> finalized = jdbc.sql("SELECT finalized_number, finalized_hash FROM chain_head")
                .query((rs, i) -> new Head(rs.getLong("finalized_number"), rs.getString("finalized_hash"), "finalized")).optional();
        Optional<Head> cursor = jdbc.sql("SELECT last_block_number, last_block_hash FROM indexer_cursor ORDER BY last_block_number LIMIT 1")
                .query((rs, i) -> new Head(rs.getLong("last_block_number"), rs.getString("last_block_hash"), "书签")).optional();
        if (finalized.isEmpty()) {
            return Optional.empty();
        }
        if (cursor.isPresent() && cursor.get().number() < finalized.get().number()) {
            return cursor;
        }
        return finalized;
    }

    public List<Token> activeTokens() {
        return jdbc.sql("SELECT address, symbol, decimals FROM chain_token WHERE status = 'ACTIVE' ORDER BY address")
                .query((rs, i) -> new Token(rs.getString("address"), rs.getString("symbol"), rs.getInt("decimals"))).list();
    }

    public List<String> depositAddresses(String token) {
        return jdbc.sql("SELECT address FROM deposit_address WHERE token = :t AND status = 'ACTIVE' ORDER BY address").param("t", token).query(String.class).list();
    }

    public List<String> hotWallets() {
        return jdbc.sql("SELECT address FROM hot_wallet ORDER BY address").query(String.class).list();
    }

    // ---------------------------------------------------------------- 读：入账

    public List<CreditedDeposit> creditedDeposits() {
        return jdbc.sql("""
                        SELECT d.id, d.token, d.amount, d.raw_value, l.id AS log_id, l.status AS log_status, l.block_number, l.block_hash, l.log_index,
                               t.amount AS transfer_amount
                        FROM deposit d
                        JOIN chain_transfer_log l ON l.id = d.transfer_log_id
                        LEFT JOIN transfer t ON t.id = d.transfer_id
                        WHERE d.status = 'CREDITED' ORDER BY d.id
                        """)
                .query((rs, i) -> new CreditedDeposit(rs.getLong("id"), rs.getString("token"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("raw_value").toBigIntegerExact(), rs.getLong("log_id"), rs.getString("log_status"), rs.getLong("block_number"),
                        rs.getString("block_hash"), rs.getInt("log_index"), rs.getBigDecimal("transfer_amount"))).list();
    }

    /** 块 ≤ upTo、收款方是 ACTIVE 收款地址、代币 ACTIVE、主分支上、却没有任何入账行（任何状态）的日志：入账任务没处理。 */
    public List<Inflow> finalInflowsWithoutDeposit(long upTo) {
        return jdbc.sql("""
                        SELECT l.id, l.token, l.to_address, l.block_number, l.block_hash, l.log_index, l.value
                        FROM chain_transfer_log l
                        JOIN deposit_address a ON a.address = l.to_address AND a.token = l.token AND a.status = 'ACTIVE'
                        JOIN chain_token c ON c.address = l.token AND c.status = 'ACTIVE'
                        WHERE l.status = 'CANONICAL' AND l.block_number <= :b
                          AND NOT EXISTS (SELECT 1 FROM deposit d WHERE d.transfer_log_id = l.id)
                        ORDER BY l.block_number, l.log_index
                        """)
                .param("b", upTo)
                .query((rs, i) -> new Inflow(rs.getLong("id"), rs.getString("token"), rs.getString("to_address"), rs.getLong("block_number"),
                        rs.getString("block_hash"), rs.getInt("log_index"), rs.getBigDecimal("value").toBigIntegerExact())).list();
    }

    // ---------------------------------------------------------------- 读：出账

    public List<ConfirmedPayout> confirmedPayouts() {
        return jdbc.sql("""
                        SELECT p.id, p.token, p.to_address, p.amount, p.raw_value, s.amount AS settle_amount,
                               x.id AS attempt_id, x.tx_hash, x.block_number AS attempt_block, x.reverted,
                               (SELECT count(*) FROM payout_tx y WHERE y.payout_id = p.id AND y.status = 'MINED') AS mined_attempts,
                               l.status AS log_status, l.to_address AS log_to, l.value AS log_value, l.block_number AS log_block
                        FROM payout p
                        LEFT JOIN transfer s ON s.id = p.settle_transfer_id
                        LEFT JOIN payout_tx x ON x.payout_id = p.id AND x.status = 'MINED'
                        LEFT JOIN chain_transfer_log l ON l.tx_hash = x.tx_hash AND l.token = p.token AND l.from_address = x.hot_wallet
                        WHERE p.status = 'CONFIRMED' ORDER BY p.id
                        """)
                .query((rs, i) -> new ConfirmedPayout(rs.getLong("id"), rs.getString("token"), rs.getString("to_address"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("raw_value").toBigIntegerExact(), rs.getBigDecimal("settle_amount"),
                        rs.getObject("attempt_id", Long.class), rs.getString("tx_hash"), rs.getObject("attempt_block", Long.class), rs.getObject("reverted", Boolean.class),
                        rs.getInt("mined_attempts"), rs.getString("log_status"), rs.getString("log_to"),
                        rs.getBigDecimal("log_value") == null ? null : rs.getBigDecimal("log_value").toBigIntegerExact(), rs.getObject("log_block", Long.class))).list();
    }

    /** 从热钱包发出、主分支上、块 ≤ upTo、却没有任何一次尝试的哈希与之对应的日志：不是我们签的。 */
    public List<Outflow> hotWalletOutflowsWithoutAttempt(long upTo) {
        return jdbc.sql("""
                        SELECT l.id, l.token, l.from_address, l.to_address, l.value, l.block_number, l.block_hash, l.log_index, l.tx_hash
                        FROM chain_transfer_log l
                        JOIN hot_wallet w ON w.address = l.from_address
                        WHERE l.status = 'CANONICAL' AND l.block_number <= :b
                          AND NOT EXISTS (SELECT 1 FROM payout_tx x WHERE x.tx_hash = l.tx_hash)
                        ORDER BY l.block_number, l.log_index
                        """)
                .param("b", upTo)
                .query((rs, i) -> new Outflow(rs.getLong("id"), rs.getString("token"), rs.getString("from_address"), rs.getString("to_address"),
                        rs.getBigDecimal("value").toBigIntegerExact(), rs.getLong("block_number"), rs.getString("block_hash"), rs.getInt("log_index"),
                        rs.getString("tx_hash"))).list();
    }

    // ---------------------------------------------------------------- 读：托管总量

    /**
     * 截至块 F 的镜像（原始单位）：已记账的入账（日志块 ≤ F）之和 − 已结算的提现（上链块 ≤ F）之和。
     * 不用账户此刻的余额：余额是「现在」的，链上余额是「F 时刻」的，两者不同刻，索引器落后时会把时间差报成差额。
     */
    public BigInteger mirrorAsOf(String token, long upTo) {
        BigInteger credited = jdbc.sql("""
                        SELECT COALESCE(SUM(d.raw_value), 0) FROM deposit d JOIN chain_transfer_log l ON l.id = d.transfer_log_id
                        WHERE d.status = 'CREDITED' AND d.token = :t AND l.block_number <= :b
                        """).param("t", token).param("b", upTo).query(BigDecimal.class).single().toBigIntegerExact();
        BigInteger settled = jdbc.sql("""
                        SELECT COALESCE(SUM(p.raw_value), 0) FROM payout p JOIN payout_tx x ON x.payout_id = p.id AND x.status = 'MINED'
                        WHERE p.status = 'CONFIRMED' AND p.token = :t AND x.block_number <= :b
                        """).param("t", token).param("b", upTo).query(BigDecimal.class).single().toBigIntegerExact();
        return credited.subtract(settled);
    }

    /** 已在主分支、块 ≤ upTo、打到 ACTIVE 收款地址、但还没记成 CREDITED 的转入合计（在路上的钱，原始单位）。 */
    public BigInteger uncreditedInflows(String token, long upTo) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(l.value), 0) FROM chain_transfer_log l
                        JOIN deposit_address a ON a.address = l.to_address AND a.token = l.token AND a.status = 'ACTIVE'
                        WHERE l.token = :t AND l.status = 'CANONICAL' AND l.block_number <= :b
                          AND NOT EXISTS (SELECT 1 FROM deposit d WHERE d.transfer_log_id = l.id AND d.status = 'CREDITED')
                        """)
                .param("t", token).param("b", upTo).query(BigDecimal.class).single().toBigIntegerExact();
    }

    /** 已上链（块 ≤ upTo、未 revert）但提现还没 CONFIRMED 的合计：链上已经出去、账本还冻着（在路上的钱，原始单位）。 */
    public BigInteger unsettledOutflows(String token, long upTo) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(p.raw_value), 0) FROM payout p
                        JOIN payout_tx x ON x.payout_id = p.id AND x.status = 'MINED' AND x.reverted = false
                        WHERE p.token = :t AND x.block_number <= :b AND p.status <> 'CONFIRMED'
                        """)
                .param("t", token).param("b", upTo).query(BigDecimal.class).single().toBigIntegerExact();
    }

    /** 已登记、日志仍在主分支、块 ≤ upTo 的注资合计（原始单位）：托管等式里「账本之外、但有人签字认了」的那一项。 */
    public BigInteger registeredFundings(String token, long upTo) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(f.raw_value), 0) FROM hot_wallet_funding f JOIN chain_transfer_log l ON l.id = f.transfer_log_id
                        WHERE f.token = :t AND l.status = 'CANONICAL' AND l.block_number <= :b
                        """)
                .param("t", token).param("b", upTo).query(BigDecimal.class).single().toBigIntegerExact();
    }

    /** 登记过、但日志已不在主分支的注资：登记时它是 finalized 的，之后被翻掉只能是深重组或手工改库——报出来。 */
    public record OrphanedFunding(long id, String token, String txHash, int logIndex, BigInteger rawValue, String logStatus) {}

    public List<OrphanedFunding> orphanedFundings() {
        return jdbc.sql("""
                        SELECT f.id, f.token, f.tx_hash, l.log_index, f.raw_value, l.status FROM hot_wallet_funding f
                        JOIN chain_transfer_log l ON l.id = f.transfer_log_id WHERE l.status <> 'CANONICAL' ORDER BY f.id
                        """)
                .query((rs, i) -> new OrphanedFunding(rs.getLong("id"), rs.getString("token"), rs.getString("tx_hash"), rs.getInt("log_index"),
                        rs.getBigDecimal("raw_value").toBigIntegerExact(), rs.getString("status"))).list();
    }

    public List<Map<String, Object>> judge() {
        return jdbc.sql("SELECT check_name, subject, detail FROM ledger_judge()").query().listOfRows();
    }

    // ---------------------------------------------------------------- 写

    public long insertRun(Instant startedAt, String status, Long finalizedNumber, String finalizedHash, int findings, String detail) {
        return jdbc.sql("INSERT INTO audit_run (started_at, status, finalized_number, finalized_hash, findings, detail) VALUES (:s, :st, :n, :h, :c, :d) RETURNING id")
                .param("s", OffsetDateTime.ofInstant(startedAt, java.time.ZoneOffset.UTC)).param("st", status).param("n", finalizedNumber).param("h", finalizedHash)
                .param("c", findings).param("d", detail).query(Long.class).single();
    }

    public void insertFindings(long runId, List<AuditFinding> findings) {
        for (AuditFinding f : findings) {
            jdbc.sql("INSERT INTO audit_finding (run_id, check_name, kind, subject, expected, actual, detail) VALUES (:r, :c, :k, :s, :e, :a, :d)")
                    .param("r", runId).param("c", f.check()).param("k", f.kind().name()).param("s", f.subject()).param("e", f.expected()).param("a", f.actual())
                    .param("d", f.detail()).update();
        }
    }

    public Optional<AuditResult> lastRun() {
        return jdbc.sql("SELECT id, status, finalized_number, started_at, finished_at, detail FROM audit_run ORDER BY id DESC LIMIT 1")
                .query((rs, i) -> new AuditResult(rs.getLong("id"), rs.getString("status"), rs.getObject("finalized_number", Long.class),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(), rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
                        findingsOf(rs.getLong("id")), rs.getString("detail"))).optional();
    }

    /** 上一次给出结论（OK 或 DIFF）的时刻；FAILED 不算「给出过结论」。 */
    public Optional<Instant> lastVerdictAt() {
        return jdbc.sql("SELECT finished_at FROM audit_run WHERE status IN ('OK', 'DIFF') ORDER BY id DESC LIMIT 1")
                .query((rs, i) -> rs.getObject("finished_at", OffsetDateTime.class).toInstant()).optional();
    }

    public List<AuditFinding> findingsOf(long runId) {
        return jdbc.sql("SELECT check_name, kind, subject, expected, actual, detail FROM audit_finding WHERE run_id = :r ORDER BY id").param("r", runId)
                .query((rs, i) -> new AuditFinding(rs.getString("check_name"), AuditKind.valueOf(rs.getString("kind")), rs.getString("subject"),
                        rs.getString("expected"), rs.getString("actual"), rs.getString("detail"))).list();
    }
}
