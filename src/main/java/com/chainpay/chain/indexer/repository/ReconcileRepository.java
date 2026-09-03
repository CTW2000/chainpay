package com.chainpay.chain.indexer.repository;

import com.chainpay.chain.indexer.domain.BlockReconciliation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 审计表 chain_reconcile：只增，只记有差异的检查。 */
@Repository
public class ReconcileRepository {

    private final JdbcClient jdbc;

    public ReconcileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(BlockReconciliation r) {
        jdbc.sql("""
                        INSERT INTO chain_reconcile
                            (block_number, block_hash, expected, found, repaired, orphaned, disputed)
                        VALUES (:block, :hash, :expected, :found, :repaired, :orphaned, :disputed)
                        """)
                .param("block", r.blockNumber())
                .param("hash", r.blockHash())
                .param("expected", r.expected())
                .param("found", r.found())
                .param("repaired", r.repaired())
                .param("orphaned", r.orphaned())
                .param("disputed", r.disputed())
                .update();
    }
}
