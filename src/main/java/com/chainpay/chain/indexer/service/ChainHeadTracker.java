package com.chainpay.chain.indexer.service;

import com.chainpay.chain.indexer.domain.ChainHead;
import com.chainpay.chain.indexer.domain.HeadRef;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 刷新链头：问节点三个头，按「只进不退」合并进 chain_head。
 *
 * <p>三个头的规矩不一样，因为它们的含义不一样：
 * <pre>
 *   finalized  倒退、或同一个号换了哈希 → {@link FinalityViolationException}，停下叫人。
 *              它是协议意义上的不可逆；看到它变，要么节点坏了，要么链上出了灾难
 *   safe / latest  倒退 → 保留旧值。节点落后、负载均衡切到旧节点，都是日常
 * </pre>
 *
 * <p>和 {@link BlockIndexer} 同一个形状：网络在事务外，事务里只做锁、比较、写。
 */
public final class ChainHeadTracker {

    private final ChainReader chain;
    private final ChainHeadRepository heads;
    private final TransactionTemplate tx;
    private final String chainName;

    public ChainHeadTracker(ChainReader chain, ChainHeadRepository heads, TransactionTemplate tx, String chainName) {
        this.chain = chain;
        this.heads = heads;
        this.tx = tx;
        this.chainName = chainName;
    }

    /** 问节点、合并、落库；返回落库后的头。 */
    public ChainHead refresh() {
        ChainHead observed = new ChainHead(
                ref(chain.block("latest")), ref(chain.block("safe")), ref(chain.block("finalized")));
        requireOrdered(observed);

        return tx.execute(status -> {
            Optional<ChainHead> current = heads.lock();
            if (current.isEmpty()) {
                heads.insert(chainName, observed);
                return observed;
            }
            ChainHead merged = merge(current.get(), observed);
            heads.update(merged);
            return merged;
        });
    }

    /** 协议保证 finalized ≤ safe ≤ latest。节点给出别的顺序，是节点坏了，不合并。 */
    static void requireOrdered(ChainHead h) {
        boolean ordered = h.finalized().number() <= h.safe().number()
                && h.safe().number() <= h.latest().number();
        if (!ordered) {
            throw new IllegalStateException("节点给出的三个头顺序不对（应 finalized ≤ safe ≤ latest）：latest="
                    + h.latest().number() + " safe=" + h.safe().number() + " finalized=" + h.finalized().number());
        }
    }

    static ChainHead merge(ChainHead current, ChainHead observed) {
        HeadRef finalized = observed.finalized();
        if (finalized.number() < current.finalized().number()) {
            throw new FinalityViolationException("finalized 倒退：记录的是 " + current.finalized().number()
                    + "，节点现在说 " + finalized.number() + "。这不是重组，停下叫人");
        }
        if (finalized.number() == current.finalized().number()
                && !finalized.hash().equalsIgnoreCase(current.finalized().hash())) {
            throw new FinalityViolationException("finalized 区块 " + finalized.number() + " 换了哈希：记录的是 "
                    + current.finalized().hash() + "，节点现在说 " + finalized.hash() + "。停下叫人");
        }
        HeadRef safe = observed.safe().number() >= current.safe().number() ? observed.safe() : current.safe();
        HeadRef latest = observed.latest().number() >= current.latest().number() ? observed.latest() : current.latest();
        return new ChainHead(latest, safe, finalized);
    }

    private static HeadRef ref(BlockHeader header) {
        return new HeadRef(header.number(), header.hash());
    }
}
