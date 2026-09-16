package com.chainpay.support;

import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.ReconcileRepository;
import com.chainpay.chain.indexer.repository.ReorgRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.indexer.service.BatchWriter;
import com.chainpay.chain.indexer.service.ChainHeadWriter;
import com.chainpay.chain.indexer.service.ReconcileWriter;
import com.chainpay.chain.indexer.service.ReorgWriter;
import org.springframework.transaction.TransactionManager;

/** 测试里造索引器用的四个写入类：new 出来再套上事务代理（{@link TransactionalProxy}），和容器里的行为一致。 */
public final class IndexerWriters {

    private IndexerWriters() {
    }

    public static BatchWriter batch(IndexerCursorRepository cursors, TransferLogRepository transferLogs, TransactionManager tm) {
        return TransactionalProxy.of(new BatchWriter(cursors, transferLogs), tm);
    }

    public static ChainHeadWriter head(ChainHeadRepository heads, TransactionManager tm) {
        return TransactionalProxy.of(new ChainHeadWriter(heads), tm);
    }

    public static ReorgWriter reorg(IndexerCursorRepository cursors, TransferLogRepository transferLogs, ReorgRepository reorgs,
                                    TransactionManager tm) {
        return TransactionalProxy.of(new ReorgWriter(cursors, transferLogs, reorgs), tm);
    }

    public static ReconcileWriter reconcile(TransferLogRepository transferLogs, ReconcileRepository reconciles, TransactionManager tm) {
        return TransactionalProxy.of(new ReconcileWriter(transferLogs, reconciles), tm);
    }
}
