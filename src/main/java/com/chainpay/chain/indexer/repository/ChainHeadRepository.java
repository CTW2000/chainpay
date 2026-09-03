package com.chainpay.chain.indexer.repository;

import com.chainpay.chain.indexer.domain.ChainHead;
import com.chainpay.chain.indexer.domain.HeadRef;
import com.chainpay.chain.indexer.service.ChainHeadTracker;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 单行表 chain_head 的读写。规则（只进不退）在 {@link ChainHeadTracker}。 */
@Repository
public class ChainHeadRepository {

    private static final String COLUMNS =
            "latest_number, latest_hash, safe_number, safe_hash, finalized_number, finalized_hash";

    private static final RowMapper<ChainHead> ROW = (rs, i) -> new ChainHead(
            new HeadRef(rs.getLong("latest_number"), rs.getString("latest_hash")),
            new HeadRef(rs.getLong("safe_number"), rs.getString("safe_hash")),
            new HeadRef(rs.getLong("finalized_number"), rs.getString("finalized_hash")));

    private final JdbcClient jdbc;

    public ChainHeadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChainHead> find() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM chain_head").query(ROW).optional();
    }

    /** 锁住这一行并重读。必须在事务里调；两个实例同时刷新时后到的等先到的提交。 */
    public Optional<ChainHead> lock() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM chain_head FOR UPDATE").query(ROW).optional();
    }

    public void insert(String chain, ChainHead head) {
        jdbc.sql("""
                        INSERT INTO chain_head
                            (chain, latest_number, latest_hash, safe_number, safe_hash,
                             finalized_number, finalized_hash)
                        VALUES (:chain, :latestNumber, :latestHash, :safeNumber, :safeHash,
                                :finalizedNumber, :finalizedHash)
                        """)
                .param("chain", chain)
                .params(params(head))
                .update();
    }

    public void update(ChainHead head) {
        jdbc.sql("""
                        UPDATE chain_head
                        SET latest_number = :latestNumber, latest_hash = :latestHash,
                            safe_number = :safeNumber, safe_hash = :safeHash,
                            finalized_number = :finalizedNumber, finalized_hash = :finalizedHash,
                            observed_at = now()
                        WHERE singleton
                        """)
                .params(params(head))
                .update();
    }

    private static java.util.Map<String, Object> params(ChainHead head) {
        return java.util.Map.of(
                "latestNumber", head.latest().number(), "latestHash", head.latest().hash(),
                "safeNumber", head.safe().number(), "safeHash", head.safe().hash(),
                "finalizedNumber", head.finalized().number(), "finalizedHash", head.finalized().hash());
    }
}
