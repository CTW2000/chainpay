package com.chainpay.chain.indexer.service;

import com.chainpay.chain.erc20.Erc20Transfer;
import com.chainpay.chain.indexer.domain.BlockReconciliation;
import com.chainpay.chain.indexer.repository.ReconcileRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LogReconciler} 的两段事务：差异确认后的「补录、标废、记审计」，
 * 以及回执解不了时的「本块记为 disputed」。两个节点的回执比对全在 LogReconciler；这里只有数据库。类与方法都不能加 final。
 */
@Component
public class ReconcileWriter {

    private final TransferLogRepository transferLogs;
    private final ReconcileRepository reconciles;

    public ReconcileWriter(TransferLogRepository transferLogs, ReconcileRepository reconciles) {
        this.transferLogs = transferLogs;
        this.reconciles = reconciles;
    }

    /** 两个节点都点过头的差异：补录缺的、标废多的，并记一行审计。 */
    @Transactional
    public BlockReconciliation apply(long blockNumber, String blockHash, int expected, int found,
                                     List<Erc20Transfer> toRepair, List<Erc20Transfer> toOrphan, int disputed) {
        int repaired = transferLogs.recordCanonical(toRepair);
        int orphaned = 0;
        for (Erc20Transfer t : toOrphan) {
            orphaned += transferLogs.orphanOne(t.blockHash(), t.logIndex());
        }
        BlockReconciliation result = new BlockReconciliation(blockNumber, blockHash, expected, found, repaired, orphaned, disputed);
        reconciles.record(result);
        return result;
    }

    /** 回执里有一条解不了的日志：本块记为 disputed 等人看，不动任何行。 */
    @Transactional
    public BlockReconciliation recordUndecodable(long blockNumber, String blockHash, int found) {
        BlockReconciliation result = new BlockReconciliation(blockNumber, blockHash, 0, found, 0, 0, 1);
        reconciles.record(result);
        return result;
    }
}
