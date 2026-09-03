package com.chainpay.chain.indexer;

import com.chainpay.chain.erc20.Erc20Transfer;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 事件表的写入。 */
@Repository
public class TransferLogRepository {

    private final JdbcClient jdbc;

    public TransferLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 逐条写入，撞上 (block_hash, log_index) 唯一约束的静默跳过。返回真正写进去的条数。
     *
     * <p>这就是「至少一次的投递 + 幂等的写入 = 效果上恰好一次」里的后一半：
     * 重放、两个实例、补扫，同一条日志来多少次都只记一次。靠的是约束，不是「先查有没有」。
     *
     * <p>CHECK 违反（地址、哈希不成形，value 为负）照常抛出——那不是重复，是坏数据，
     * 要让整批回滚而不是悄悄少一条。
     */
    public int insertIgnoringDuplicates(List<Erc20Transfer> transfers) {
        int inserted = 0;
        for (Erc20Transfer t : transfers) {
            inserted += jdbc.sql("""
                            INSERT INTO chain_transfer_log
                                (token, from_address, to_address, value,
                                 block_number, block_hash, tx_hash, log_index)
                            VALUES (:token, :from, :to, :value,
                                    :blockNumber, :blockHash, :txHash, :logIndex)
                            ON CONFLICT (block_hash, log_index) DO NOTHING
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
        return inserted;
    }
}
