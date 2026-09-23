package com.chainpay.chain.indexer.service;

import com.chainpay.chain.indexer.domain.HeadRef;
import com.chainpay.chain.indexer.domain.ReorgResult;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.JsonRpcException;
import java.util.ArrayList;
import java.util.List;

/**
 * 重组恢复：找共同祖先、标废、退书签、记审计。之后索引器从祖先之后正常重放。
 *
 * <p><b>我们手里知道哈希的块只有三类</b>：书签那一块、有日志的块、chain_head 里存下的 finalized 头。
 * 中间没有日志的块，我们不知道它们的哈希——所以祖先是「能证明和链上一致的最高一块」，
 * 可能比真正的分叉点低。多退不伤（重放是幂等的），少退要命（会留下一行属于被丢弃区块的记录）。
 *
 * <p><b>走法：</b>书签之下、finalized 之上所有有日志的块，按高度降序逐个问链「这个高度现在的哈希是什么」，
 * 第一个和我们记的一致的就是祖先；一个都不一致，就看 finalized 头。
 * 走过的每个日志块都对不上，所以「标废祖先之上的全部行」恰好就是精确的答案，不是近似。
 *
 * <p><b>地板是 finalized。</b>连它都对不上，不是重组，是 {@link FinalityViolationException}：停下叫人。
 * 不用 Envio 那种 200 块的魔法数字，以太坊 PoS 把这个数字交给了协议。
 *
 * <p>形状和 {@link BlockIndexer} 一样：网络在事务外；事务那一段（锁、核对、标废、退书签、记审计，同生同死）在 {@link ReorgWriter}。
 */
public final class ReorgRecovery {

    private final ChainReader chain;
    private final TransferLogRepository transferLogs;
    private final ChainHeadRepository heads;
    private final ReorgWriter writer;
    private final String cursorName;

    public ReorgRecovery(ChainReader chain,
                         TransferLogRepository transferLogs,
                         ChainHeadRepository heads,
                         ReorgWriter writer,
                         String cursorName) {
        this.chain = chain;
        this.transferLogs = transferLogs;
        this.heads = heads;
        this.writer = writer;
        this.cursorName = cursorName;
    }

    /**
     * @param cursorBlock 发现接不上时书签指着的块
     * @param cursorHash  书签记的那块的哈希
     */
    public ReorgResult recover(long cursorBlock, String cursorHash) {
        // ① 书签那块还对不对。对的话不是重组：下一块接不上只能是节点前后不一致（负载均衡混了节点）
        BlockHeader atCursor = chain.block(cursorBlock);
        if (atCursor.hash().equalsIgnoreCase(cursorHash)) {
            throw new JsonRpcException(null, "书签块 " + cursorBlock
                    + " 的哈希仍与链上一致，只是下一块接不上：节点前后不一致，稍后再试");
        }

        // ② 地板：chain_head 里存下的 finalized 头
        HeadRef finalized = heads.find()
                .orElseThrow(() -> new IllegalStateException("没有链头记录，无法确定回滚的地板：先刷新链头"))
                .finalized();
        if (cursorBlock <= finalized.number()) {
            throw new FinalityViolationException("书签块 " + cursorBlock + " 不高于 finalized 头 "
                    + finalized.number() + "，却和链上对不上：不是重组，停下叫人");
        }

        // ③ 找祖先。网络在事务外
        List<HeadRef> candidates = new ArrayList<>(transferLogs.canonicalBlocksBetween(finalized.number(), cursorBlock));
        candidates.add(finalized);
        HeadRef ancestor = null;
        for (HeadRef candidate : candidates) {
            if (chain.block(candidate.number()).hash().equalsIgnoreCase(candidate.hash())) {
                ancestor = candidate;
                break;
            }
        }
        if (ancestor == null) {
            throw new FinalityViolationException("重组深过 finalized：finalized 块 " + finalized.number()
                    + " 的哈希也和链上对不上（记录的是 " + finalized.hash() + "）。停下叫人");
        }

        // ④ 事务：锁书签、核对号和哈希、标废、退书签、记审计（ReorgWriter，经代理进事务）
        return writer.rollback(cursorName, new HeadRef(cursorBlock, cursorHash), ancestor);
    }
}
