package com.chainpay.chain.indexer;

import com.chainpay.chain.erc20.Erc20Transfer;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 事件表的写入与重组相关的两个操作。 */
@Repository
public class TransferLogRepository {

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
     * </ul>
     * 返回写入 + 复活的条数。CHECK 违反照常抛出——那不是重复，是坏数据，要让整批回滚。
     */
    public int recordCanonical(List<Erc20Transfer> transfers) {
        int written = 0;
        for (Erc20Transfer t : transfers) {
            written += jdbc.sql("""
                            INSERT INTO chain_transfer_log
                                (token, from_address, to_address, value,
                                 block_number, block_hash, tx_hash, log_index)
                            VALUES (:token, :from, :to, :value,
                                    :blockNumber, :blockHash, :txHash, :logIndex)
                            ON CONFLICT (block_hash, log_index)
                            DO UPDATE SET status = 'CANONICAL'
                            WHERE chain_transfer_log.status = 'ORPHANED'
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
        }
        return written;
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
}
