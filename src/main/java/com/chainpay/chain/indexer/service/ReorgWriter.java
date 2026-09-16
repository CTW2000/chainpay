package com.chainpay.chain.indexer.service;

import com.chainpay.chain.indexer.domain.HeadRef;
import com.chainpay.chain.indexer.domain.IndexerCursor;
import com.chainpay.chain.indexer.domain.ReorgResult;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.ReorgRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReorgRecovery} 的事务那一段（2026-09-15 由手工模板改为注解，用户选）：锁书签、核对号和哈希、标废、退书签、记审计。
 * 这几件事必须同生同死——崩在「标废」和「退书签」之间，重放永远不会发生，那几笔转账就静默丢了（ReorgRecoveryTest 钉住）。
 * 找祖先要问节点，全在 ReorgRecovery；这里只有数据库。类与方法都不能加 final。
 */
@Component
public class ReorgWriter {

    private final IndexerCursorRepository cursors;
    private final TransferLogRepository transferLogs;
    private final ReorgRepository reorgs;

    public ReorgWriter(IndexerCursorRepository cursors, TransferLogRepository transferLogs, ReorgRepository reorgs) {
        this.cursors = cursors;
        this.transferLogs = transferLogs;
        this.reorgs = reorgs;
    }

    /**
     * @param cursor   发现接不上时书签指着的块（号 + 哈希）
     * @param ancestor 能证明和链上一致的最高一块：它之上的行标废，书签退到它
     */
    @Transactional
    public ReorgResult rollback(String cursorName, HeadRef cursor, HeadRef ancestor) {
        IndexerCursor locked = cursors.lock(cursorName);
        boolean untouched = locked.lastBlockNumber() == cursor.number()
                && locked.lastBlockHash().equalsIgnoreCase(cursor.hash());
        if (!untouched) {
            // 别的实例已经恢复过（甚至已重放到同一个号的新分支：号相同、哈希不同）。核对哈希，不只核对号
            return ReorgResult.skipped(cursor.number());
        }
        int orphaned = transferLogs.orphanAbove(ancestor.number());
        if (!cursors.rewind(cursorName, cursor.number(), cursor.hash(), ancestor.number(), ancestor.hash())) {
            throw new IllegalStateException("书签在锁内被改动，不应发生：" + cursorName);
        }
        reorgs.record(cursor, ancestor, orphaned);
        return new ReorgResult(true, cursor.number(), ancestor.number(), orphaned);
    }
}
