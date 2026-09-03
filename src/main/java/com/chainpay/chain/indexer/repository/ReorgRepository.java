package com.chainpay.chain.indexer.repository;

import com.chainpay.chain.indexer.domain.HeadRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 审计表 chain_reorg：只增。depth 是库里的生成列，这里不写。 */
@Repository
public class ReorgRepository {

    private final JdbcClient jdbc;

    public ReorgRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(HeadRef cursor, HeadRef ancestor, int orphanedLogs) {
        jdbc.sql("""
                        INSERT INTO chain_reorg
                            (cursor_block, cursor_hash, ancestor_block, ancestor_hash, orphaned_logs)
                        VALUES (:cursorBlock, :cursorHash, :ancestorBlock, :ancestorHash, :orphaned)
                        """)
                .param("cursorBlock", cursor.number())
                .param("cursorHash", cursor.hash())
                .param("ancestorBlock", ancestor.number())
                .param("ancestorHash", ancestor.hash())
                .param("orphaned", orphanedLogs)
                .update();
    }
}
