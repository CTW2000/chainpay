package com.chainpay.chain.indexer.repository;

import com.chainpay.chain.erc20.Erc20Transfer;
import com.chainpay.chain.indexer.domain.HeadRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 事件表的写入与重组相关的两个操作。 */
@Repository
public class TransferLogRepository {

    private static final String PAYLOAD_COLUMNS =
            "token, from_address, to_address, value, block_number, block_hash, tx_hash, log_index";
    private static final RowMapper<Erc20Transfer> ROW = (rs, i) -> new Erc20Transfer(
            rs.getString("token"), rs.getString("from_address"), rs.getString("to_address"),
            rs.getBigDecimal("value").toBigInteger(), rs.getLong("block_number"),
            rs.getString("block_hash"), rs.getString("tx_hash"), rs.getInt("log_index"));

    private final JdbcClient jdbc;

    public TransferLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 逐条写入。撞上 (block_hash, log_index) 唯一约束时：
     * <ul>
     *   <li>那行是 CANONICAL → 一个字节不动（重放、两个实例、补扫：至少一次 + 幂等 = 效果上恰好一次）</li>
     *   <li>那行是 ORPHANED  → <b>复活</b>成 CANONICAL，同一行、同一个 id。链翻回原分支时被丢弃的块
     *       又是正经的了，它上面的日志再来一次；DO NOTHING 会让它们永远停在 ORPHANED，一笔存款就没了</li>
     *   <li>那行的<b>内容</b>（代币、付款人、收款人、金额）和这次来的不同 → 抛出，整批回滚。块哈希承诺了内容，
     *       同一坐标两种内容不可能都对；原来的 upsert 只改 status 列，会让第一次写入的说法永远留下（2026-09-03 补丁）</li>
     * </ul>
     * 返回写入 + 复活的条数。CHECK 违反照常抛出——那不是重复，是坏数据，要让整批回滚。
     */
    public int recordCanonical(List<Erc20Transfer> transfers) {
        int written = 0;
        for (Erc20Transfer t : transfers) {
            int changed = jdbc.sql("""
                            INSERT INTO chain_transfer_log
                                (token, from_address, to_address, value,
                                 block_number, block_hash, tx_hash, log_index)
                            VALUES (:token, :from, :to, :value,
                                    :blockNumber, :blockHash, :txHash, :logIndex)
                            ON CONFLICT (block_hash, log_index)
                            DO UPDATE SET status = 'CANONICAL'
                            WHERE chain_transfer_log.status = 'ORPHANED'
                              AND chain_transfer_log.token = EXCLUDED.token
                              AND chain_transfer_log.from_address = EXCLUDED.from_address
                              AND chain_transfer_log.to_address = EXCLUDED.to_address
                              AND chain_transfer_log.value = EXCLUDED.value
                            """)
                    .param("token", t.token())
                    .param("from", t.from())
                    .param("to", t.to())
                    // BigInteger 没有 JDBC 类型，NUMERIC 走 BigDecimal；scale 为 0，不丢任何东西
                    .param("value", new BigDecimal(t.value()))
                    .param("blockNumber", t.blockNumber())
                    .param("blockHash", t.blockHash())
                    .param("txHash", t.transactionHash())
                    .param("logIndex", t.logIndex())
                    .update();
            if (changed == 0) {
                requireSamePayload(t);                              // 没动：要么本来就是 CANONICAL，要么同坐标不同内容
            }
            written += changed;
        }
        return written;
    }

    /** 同一坐标已有一行且内容不同：两者不可能都对，抛出让整批回滚，停下叫人。 */
    private void requireSamePayload(Erc20Transfer t) {
        Optional<Erc20Transfer> stored = jdbc.sql("SELECT " + PAYLOAD_COLUMNS
                        + " FROM chain_transfer_log WHERE block_hash = :h AND log_index = :i")
                .param("h", t.blockHash())
                .param("i", t.logIndex())
                .query(ROW)
                .optional();
        if (stored.isPresent() && !stored.get().samePayloadAs(t)) {
            throw new IllegalStateException("同一坐标 (" + t.blockHash() + ", " + t.logIndex() + ") 已有不同内容的记录：库里 "
                    + describe(stored.get()) + "，链上现在 " + describe(t) + "。块哈希承诺了内容，两者不可能都对，停下叫人");
        }
    }

    private static String describe(Erc20Transfer t) {
        return t.token() + " " + t.from() + " → " + t.to() + " " + t.value();
    }

    /**
     * 找共同祖先的候选：开区间 (above, below) 内、仍在链上的日志块，按高度降序。
     * 这些是我们手里除了书签和 finalized 头之外，唯一知道哈希的块。
     */
    public List<HeadRef> canonicalBlocksBetween(long aboveExclusive, long belowExclusive) {
        return jdbc.sql("""
                        SELECT DISTINCT block_number, block_hash
                        FROM chain_transfer_log
                        WHERE status = 'CANONICAL' AND block_number > :above AND block_number < :below
                        ORDER BY block_number DESC
                        """)
                .param("above", aboveExclusive)
                .param("below", belowExclusive)
                .query((rs, i) -> new HeadRef(rs.getLong("block_number"), rs.getString("block_hash")))
                .list();
    }

    /**
     * 把祖先之上（不含）所有 CANONICAL 的行标成 ORPHANED，返回标了几行。
     * 不删：账本的上游证据永远留着，重组翻回来时还能复活。
     */
    public int orphanAbove(long ancestorBlock) {
        return jdbc.sql("""
                        UPDATE chain_transfer_log SET status = 'ORPHANED'
                        WHERE status = 'CANONICAL' AND block_number > :ancestor
                        """)
                .param("ancestor", ancestorBlock)
                .update();
    }

    /** 该块里 CANONICAL 的行，整条载荷：对账时和回执比的是内容，不只是坐标。 */
    public List<Erc20Transfer> canonicalLogsInBlock(long blockNumber) {
        return jdbc.sql("SELECT " + PAYLOAD_COLUMNS
                        + " FROM chain_transfer_log WHERE status = 'CANONICAL' AND block_number = :n ORDER BY log_index")
                .param("n", blockNumber)
                .query(ROW)
                .list();
    }

    /** 标废一条幻影（两个节点都说它不存在）。返回 1 = 标了，0 = 那行已不是 CANONICAL。 */
    public int orphanOne(String blockHash, int logIndex) {
        return jdbc.sql("""
                        UPDATE chain_transfer_log SET status = 'ORPHANED'
                        WHERE status = 'CANONICAL' AND block_hash = :h AND log_index = :i
                        """)
                .param("h", blockHash)
                .param("i", logIndex)
                .update();
    }
}
