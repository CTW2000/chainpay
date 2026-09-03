package com.chainpay.chain.indexer.repository;

import com.chainpay.chain.indexer.domain.IndexerCursor;
import com.chainpay.chain.indexer.service.BlockIndexer;
import com.chainpay.chain.indexer.service.ReorgRecovery;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 书签的几个操作。SQL 在这里，事务边界在 {@link BlockIndexer} 和 {@link ReorgRecovery}。
 *
 * <p>「书签只从期望值出发改」由两道保险共同守着，都在这个类里能看见：
 * {@link #lock} 之后的重读（第一道），和 {@link #advance} / {@link #rewind} 的 WHERE 里带着期望值（第二道）。
 */
@Repository
public class IndexerCursorRepository {

    private static final RowMapper<IndexerCursor> ROW = (rs, i) -> new IndexerCursor(
            rs.getString("name"), rs.getLong("last_block_number"), rs.getString("last_block_hash"));

    private final JdbcClient jdbc;

    public IndexerCursorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 不加锁地读。索引器在事务外用它决定这一批的范围。 */
    public Optional<IndexerCursor> find(String name) {
        return jdbc.sql("SELECT name, last_block_number, last_block_hash FROM indexer_cursor WHERE name = :name")
                .param("name", name)
                .query(ROW)
                .optional();
    }

    /** 书签放下时的起点。抽样对账不抽它之前的块。 */
    public Optional<Long> startBlock(String name) {
        return jdbc.sql("SELECT start_block FROM indexer_cursor WHERE name = :name")
                .param("name", name)
                .query(Long.class)
                .optional();
    }

    /** 建书签；已存在就不动它（幂等）。返回这次是否真的插入了。 */
    public boolean insertIfAbsent(String name, long blockNumber, String blockHash) {
        return jdbc.sql("""
                        INSERT INTO indexer_cursor (name, last_block_number, last_block_hash, start_block)
                        VALUES (:name, :number, :hash, :number)
                        ON CONFLICT (name) DO NOTHING
                        """)
                .param("name", name)
                .param("number", blockNumber)
                .param("hash", blockHash)
                .update() == 1;
    }

    /**
     * 锁住书签这一行并重读。<b>必须在事务里调</b>，锁持有到事务结束。
     *
     * <p>两个实例同时到这里：后到的等先到的提交，然后读到的已是被推走的书签——
     * 这就是账本第 ④ 步「锁账户行」的同一堵承重墙：互斥在数据库里发生，
     * 因为它是所有实例唯一共享的东西。
     */
    public IndexerCursor lock(String name) {
        return jdbc.sql("SELECT name, last_block_number, last_block_hash FROM indexer_cursor "
                        + "WHERE name = :name FOR UPDATE")
                .param("name", name)
                .query(ROW)
                .optional()
                .orElseThrow(() -> new IllegalStateException("书签不存在：" + name));
    }

    /** 往前推。只能从 {@code expectedLast} 出发改，见 {@link #move}。 */
    public boolean advance(String name, long expectedLast, long newLast, String newHash) {
        return move(name, expectedLast, newLast, newHash);
    }

    /** 退回祖先（重组恢复）。同一条 SQL、同一个守卫。 */
    public boolean rewind(String name, long expectedLast, long newLast, String newHash) {
        return move(name, expectedLast, newLast, newHash);
    }

    /**
     * WHERE 里带上期望值：值已不是它就一行都不改，返回 false。
     * 就算调用方算错了范围，也不可能把书签改成别的起点。
     */
    private boolean move(String name, long expectedLast, long newLast, String newHash) {
        return jdbc.sql("""
                        UPDATE indexer_cursor
                        SET last_block_number = :newLast, last_block_hash = :newHash, updated_at = now()
                        WHERE name = :name AND last_block_number = :expectedLast
                        """)
                .param("name", name)
                .param("expectedLast", expectedLast)
                .param("newLast", newLast)
                .param("newHash", newHash)
                .update() == 1;
    }
}
