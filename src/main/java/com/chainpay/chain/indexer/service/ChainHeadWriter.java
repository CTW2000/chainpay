package com.chainpay.chain.indexer.service;

import com.chainpay.chain.indexer.domain.ChainHead;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ChainHeadTracker} 的事务那一段：锁链头行、按「只进不退」合并、落库。
 * 网络（问节点三个头、审计节点核对 finalized）全在 ChainHeadTracker，这里只有数据库。类与方法都不能加 final。
 */
@Component
public class ChainHeadWriter {

    private final ChainHeadRepository heads;

    public ChainHeadWriter(ChainHeadRepository heads) {
        this.heads = heads;
    }

    /** 第一次见到这条链就插入；否则锁住、合并、更新。合并规则（finalized 倒退或换哈希 = 停下叫人）见 {@link ChainHeadTracker#merge}。 */
    @Transactional
    public ChainHead store(String chainName, ChainHead observed) {
        Optional<ChainHead> current = heads.lock();
        if (current.isEmpty()) {
            heads.insert(chainName, observed);
            return observed;
        }
        ChainHead merged = ChainHeadTracker.merge(current.get(), observed);
        heads.update(merged);
        return merged;
    }
}
