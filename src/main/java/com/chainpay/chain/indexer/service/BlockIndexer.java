package com.chainpay.chain.indexer.service;

import com.chainpay.chain.erc20.Erc20Transfer;
import com.chainpay.chain.erc20.TransferLogDecoder;
import com.chainpay.chain.indexer.domain.BatchOutcome;
import com.chainpay.chain.indexer.domain.BatchResult;
import com.chainpay.chain.indexer.domain.IndexerCursor;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.rpc.BlockHeader;
import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.rpc.RawLog;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M2-② 的核心：一批一批地把链上的 Transfer 写进库，并推进书签。
 *
 * <p><b>一次 {@link #indexNextBatch()} 的形状，顺序不能换：</b>
 * <pre>
 *   ① 读书签（不加锁）                      cursor = 100
 *   ② 问链头，算范围                        from = 101, to = min(100 + batch, head)
 *   ③ 网络：取 block(from)、block(to)、logs  ← 在事务外面，可能要好几秒
 *   ④ 校验 block(from).parentHash == cursor.hash
 *                                           ← 不等就停：重组。M2-② 只检测，回滚是 M2-④
 *   ⑤ 解码                                  ← 解不了就停，整批不写
 *   ⑥ BEGIN
 *        锁书签行、重读：必须仍是 100，否则这批作废
 *        INSERT 事件 ON CONFLICT：CANONICAL 不动，ORPHANED 复活（M2-④）
 *        UPDATE 书签 WHERE last_block_number = 100
 *      COMMIT
 * </pre>
 *
 * <p><b>为什么事件和书签在同一个事务里：</b>崩在两步中间的两种坏法不对称——
 * 先写事件再推书签，崩了是重复，唯一约束会尖叫；先推书签再写事件，崩了是丢失，静默。
 * 放进同一个事务，崩在任何位置，重启后书签和事件永远一致（M2-before 第 8 问）。
 *
 * <p><b>为什么网络在事务外面：</b>事务开着时那条连接被占着、那行锁被握着。一次 RPC 最长 20 秒，
 * 放进事务就是握着锁等网络：另一个实例干等，连接池少一条。事务要短，网络在外面。
 * 代价是两个实例可能都取了同一段数据，慢的那个在 ⑥ 发现书签已动、白取一次——账是对的。
 *
 * <p><b>为什么停下而不是跳过：</b>④ 和 ⑤ 的失败都让整批不写、书签不动、抛出。
 * 一条被跳过的日志就是一笔静默丢失的入账；停下来的索引器是一个报警，往前走的是定时炸弹。
 *
 * <p>它不是 Spring bean：装配在 {@code ChainIndexerConfig}（配了 RPC 地址才装），
 * 测试里直接 new，把 {@link ChainReader} 换成内存里的链。
 */
public final class BlockIndexer {

    private final ChainReader chain;
    private final IndexerCursorRepository cursors;
    private final TransferLogRepository transferLogs;
    private final TransactionTemplate tx;
    private final String cursorName;
    private final String token;
    private final int batchBlocks;
    /** 当前 eth_getLogs 的窗口：撞上限减半，成功翻倍回到 batchBlocks。 */
    private final java.util.concurrent.atomic.AtomicInteger window;

    public BlockIndexer(ChainReader chain,
                        IndexerCursorRepository cursors,
                        TransferLogRepository transferLogs,
                        TransactionTemplate tx,
                        String cursorName,
                        String token,
                        int batchBlocks) {
        if (batchBlocks < 1) {
            throw new IllegalArgumentException("batchBlocks 必须 >= 1：" + batchBlocks);
        }
        this.chain = chain;
        this.cursors = cursors;
        this.transferLogs = transferLogs;
        this.tx = tx;
        this.cursorName = cursorName;
        this.token = requireAddress(token);
        this.batchBlocks = batchBlocks;
        this.window = new java.util.concurrent.atomic.AtomicInteger(batchBlocks);
    }

    /**
     * 放书签：从 {@code fromBlock} 之后开始索引（该块本身视为已处理）。
     * 书签已存在就不动它——重启时调它是安全的。
     */
    public IndexerCursor start(long fromBlock) {
        BlockHeader header = chain.block(fromBlock);
        cursors.insertIfAbsent(cursorName, header.number(), header.hash());
        return cursors.find(cursorName).orElseThrow();
    }

    public int currentWindow() {
        return window.get();
    }

    /**
     * 合约地址必须是 0x + 40 位十六进制，构造时就拒绝。
     * 不这么做的后果实测过：YAML 把不加引号的地址转成十进制数，应用起来了、书签放了、链头刷新了，
     * 然后每一次 eth_getLogs 都是 Invalid params，窗口一路减到 1 块再停机——错误离它的原因隔了四层。
     */
    static String requireAddress(String address) {
        if (address == null || !address.matches("0x[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("合约地址必须是 0x + 40 位十六进制，收到：" + address
                    + "（YAML 里的地址要加引号，否则会被当成整数）");
        }
        return address.toLowerCase();
    }

    /** 书签在不在。轮询用它决定要不要先放书签。 */
    public boolean hasCursor() {
        return cursors.find(cursorName).isPresent();
    }

    /** 处理下一批。见类注释里的 ①～⑥。 */
    public BatchResult indexNextBatch() {
        // ① 读书签，不加锁
        IndexerCursor cursor = cursors.find(cursorName)
                .orElseThrow(() -> new IllegalStateException("书签不存在，先调 start()：" + cursorName));

        // ② 链头。比书签旧（节点落后、负载均衡切到旧节点）就什么都不做——书签永远不倒退
        long head = chain.blockNumber();
        if (head <= cursor.lastBlockNumber()) {
            return BatchResult.upToDate(cursor.lastBlockNumber());
        }
        long from = cursor.lastBlockNumber() + 1;

        // ③ 网络，事务外
        BlockHeader first = chain.block(from);
        // ④ 这一批必须接在书签上
        if (!first.parentHash().equalsIgnoreCase(cursor.lastBlockHash())) {
            throw new ReorgDetectedException(from, cursor.lastBlockHash(), first.parentHash());
        }
        // ⑤ 取日志。撞上提供商的上限（带 code 的错）就对半分重试，成功后翻倍回到 batchBlocks；
        //    减到一块还失败就停下——那不是范围问题。传输失败（code 为空）是瞬时的，不缩窗口、原样抛出
        long to;
        List<RawLog> raw;
        while (true) {
            to = Math.min(cursor.lastBlockNumber() + window.get(), head);
            try {
                raw = chain.logs(from, to, token, TransferLogDecoder.TRANSFER_TOPIC0);
                break;
            } catch (JsonRpcException e) {
                if (e.code() == null) {
                    throw e;
                }
                int current = window.get();
                if (current <= 1 || to == from) {
                    throw new IllegalStateException("单块 " + from + " 的日志也取不到（节点说：" + e.getMessage()
                            + "）：不是范围问题，换提供商或检查它的归档范围");
                }
                window.set(Math.max(1, current / 2));
            }
        }
        window.set(Math.min(batchBlocks, window.get() * 2));
        BlockHeader last = to == from ? first : chain.block(to);
        if (last.number() != to) {
            throw new IllegalStateException("节点返回了错误的区块：要 " + to + "，给了 " + last.number());
        }
        // ⑥ 解码。任何一条解不了，整批不写
        List<Erc20Transfer> transfers = raw.stream().map(TransferLogDecoder::decode).toList();

        // ⑦ 事务：锁、重读、写、推
        long batchEnd = to;                                          // 循环里改过的变量进不了 lambda
        return tx.execute(status -> persist(cursor, transfers, last, from, batchEnd));
    }

    private BatchResult persist(IndexerCursor expected, List<Erc20Transfer> transfers,
                                BlockHeader last, long from, long to) {
        IndexerCursor locked = cursors.lock(cursorName);
        if (locked.lastBlockNumber() != expected.lastBlockNumber()) {
            // 别的实例在我们取数据期间推走了书签。我们手里这批是按旧书签算的，作废
            return BatchResult.skipped(from, to, transfers.size());
        }
        int inserted = transferLogs.recordCanonical(transfers);
        if (!cursors.advance(cursorName, expected.lastBlockNumber(), to, last.hash())) {
            throw new IllegalStateException("书签在锁内被改动，不应发生：" + cursorName);
        }
        return new BatchResult(BatchOutcome.INDEXED, from, to, transfers.size(), inserted);
    }
}
