package com.chainpay.chain.indexer.repository;

import com.chainpay.chain.indexer.domain.IndexerState;
import com.chainpay.chain.indexer.domain.IndexerStatus;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 状态表 indexer_state：一枚书签一行。 */
@Repository
public class IndexerStateRepository {

    /** reason 列的 CHECK 上限；异常信息可能很长，截断保存，不让状态写入本身失败。 */
    static final int MAX_REASON_LENGTH = 2000;

    private static final RowMapper<IndexerState> ROW = (rs, i) -> new IndexerState(
            rs.getString("name"),
            IndexerStatus.valueOf(rs.getString("status")),
            rs.getString("reason"),
            rs.getObject("since", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public IndexerStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IndexerState> find(String name) {
        return jdbc.sql("SELECT name, status, reason, since FROM indexer_state WHERE name = :name")
                .param("name", name)
                .query(ROW)
                .optional();
    }

    /** 写状态。状态没变时 since 不动（它记的是「从什么时候起」），变了才重置。 */
    public void set(String name, IndexerStatus status, String reason) {
        String clipped = reason != null && reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
        jdbc.sql("""
                        INSERT INTO indexer_state (name, status, reason)
                        VALUES (:name, :status, :reason)
                        ON CONFLICT (name) DO UPDATE
                            SET status = EXCLUDED.status,
                                reason = EXCLUDED.reason,
                                since = CASE WHEN indexer_state.status = EXCLUDED.status
                                             THEN indexer_state.since ELSE now() END,
                                updated_at = now()
                        """)
                .param("name", name)
                .param("status", status.name())
                .param("reason", clipped)
                .update();
    }
}
