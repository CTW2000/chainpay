package com.chainpay.chain.indexer.service;

import com.chainpay.chain.erc20.Erc20Transfer;
import com.chainpay.chain.indexer.domain.BatchOutcome;
import com.chainpay.chain.indexer.domain.BatchResult;
import com.chainpay.chain.indexer.domain.IndexerCursor;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.rpc.BlockHeader;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BlockIndexer} 的第 ⑦ 步：锁书签、重读、写事件、推书签，在一个事务里。
 *
 * <p>单独成类，是因为注解的边界是「一个方法」：留在 BlockIndexer 里，要么整个 indexNextBatch 进事务、握着连接等网络，
 * 要么自己调自己绕过代理、悄悄没有事务。网络全在 BlockIndexer，这里只有数据库。
 * 类与方法都不能加 final；测试里 new 出来的实例要用 TransactionalProxy 套上代理才有事务（IndexerWritersTransactionalTest 钉住容器里的那个）。
 */
@Component
public class BatchWriter {

    private final IndexerCursorRepository cursors;
    private final TransferLogRepository transferLogs;

    public BatchWriter(IndexerCursorRepository cursors, TransferLogRepository transferLogs) {
        this.cursors = cursors;
        this.transferLogs = transferLogs;
    }

    /**
     * @param expected ① 读到的书签（号 + 哈希）：锁内重读必须仍是它，否则这批作废
     * @param last     这批最后一块的头：书签推到它
     */
    @Transactional
    public BatchResult persist(String cursorName, IndexerCursor expected, List<Erc20Transfer> transfers,
                               BlockHeader last, long from, long to) {
        IndexerCursor locked = cursors.lock(cursorName);
        boolean untouched = locked.lastBlockNumber() == expected.lastBlockNumber()
                && locked.lastBlockHash().equalsIgnoreCase(expected.lastBlockHash());
        if (!untouched) {
            // 别的实例在我们取数据期间推走了书签，或者重组恢复后重放到了同一个号的另一条分支。
            // 书签的身份是「号 + 哈希」：同号不同哈希是另一个世界的这一块，按旧分支算的这批作废。
            return BatchResult.skipped(from, to, transfers.size());
        }
        int inserted = transferLogs.recordCanonical(transfers);
        if (!cursors.advance(cursorName, expected.lastBlockNumber(), expected.lastBlockHash(), to, last.hash())) {
            throw new IllegalStateException("书签在锁内被改动，不应发生：" + cursorName);
        }
        return new BatchResult(BatchOutcome.INDEXED, from, to, transfers.size(), inserted);
    }
}
