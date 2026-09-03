package com.chainpay.chain;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.erc20.TransferLogDecoder;
import com.chainpay.chain.indexer.domain.BatchOutcome;
import com.chainpay.chain.indexer.domain.BatchResult;
import com.chainpay.chain.indexer.domain.ChainHead;
import com.chainpay.chain.indexer.domain.IndexerCursor;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.indexer.service.BlockIndexer;
import com.chainpay.chain.indexer.service.ChainHeadTracker;
import com.chainpay.chain.rpc.EthRpc;
import com.chainpay.chain.rpc.JsonRpcClient;
import com.chainpay.support.AbstractPostgresTest;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M2-② 的落库探针：对着真实的 Sepolia，把最近几百个区块的 LINK 转账索引进真的 PostgreSQL。
 *
 * <p><b>默认不跑</b>：设了 CHAINPAY_SEPOLIA_RPC 才启用（理由同 {@link SepoliaProbeTest}）。
 *
 * <pre>
 *   CHAINPAY_SEPOLIA_RPC=https://ethereum-sepolia-rpc.publicnode.com mvn test -Dtest=SepoliaIndexProbeTest
 * </pre>
 *
 * <p>它验证的是 M2 验收标准里的「对账」雏形：索引过的范围内，链上有几条日志，库里就得有几条。
 */
@DisplayName("M2-② · Sepolia 落库探针（需网络）")
@EnabledIfEnvironmentVariable(named = "CHAINPAY_SEPOLIA_RPC", matches = ".+")
class SepoliaIndexProbeTest extends AbstractPostgresTest {

    static final String LINK_SEPOLIA = "0x779877A7B0D9E8603169DdbD7836e478b4624789";
    static final String CURSOR = "probe:sepolia:link";
    static final int BLOCKS_BACK = 300;
    static final int MAX_BATCHES = 20;

    @Autowired
    private IndexerCursorRepository cursors;

    @Autowired
    private TransferLogRepository transferLogs;

    @Autowired
    private ChainHeadRepository heads;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @DisplayName("最近 300 块的 LINK 转账落库后，库内条数等于链上条数，书签哈希等于链上哈希")
    void indexesRecentBlocksAndReconcilesAgainstTheChain() {
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor, chain_head").update();
        var chain = new EthRpc(new JsonRpcClient(URI.create(System.getenv("CHAINPAY_SEPOLIA_RPC"))));
        long startBlock = chain.blockNumber() - BLOCKS_BACK;
        var indexer = new BlockIndexer(chain, cursors, transferLogs, new TransactionTemplate(txManager),
                CURSOR, LINK_SEPOLIA, 100);
        indexer.start(startBlock);

        int batches = 0;
        BatchResult r;
        do {
            r = indexer.indexNextBatch();
            batches++;
            System.out.printf(">>> 批 %d：%s  %d..%d  看到 %d 条，写入 %d 条%n",
                    batches, r.outcome(), r.fromBlock(), r.toBlock(), r.logsSeen(), r.logsInserted());
        } while (r.outcome() != BatchOutcome.UP_TO_DATE && batches < MAX_BATCHES);

        IndexerCursor cursor = cursors.find(CURSOR).orElseThrow();
        long onChain = chain.logs(startBlock + 1, cursor.lastBlockNumber(),
                LINK_SEPOLIA, TransferLogDecoder.TRANSFER_TOPIC0).size();
        long inDb = jdbc.sql("SELECT COUNT(*) FROM chain_transfer_log WHERE block_number BETWEEN :a AND :b")
                .param("a", startBlock + 1).param("b", cursor.lastBlockNumber())
                .query(Long.class).single();
        System.out.printf(">>> 书签 %d %s；范围 %d..%d 链上 %d 条，库里 %d 条%n",
                cursor.lastBlockNumber(), cursor.lastBlockHash(), startBlock + 1, cursor.lastBlockNumber(),
                onChain, inDb);

        ChainHead head = new ChainHeadTracker(chain, heads, new TransactionTemplate(txManager), "sepolia").refresh();
        System.out.printf(">>> 链头 latest=%d safe=%d (-%d) finalized=%d (-%d)%n",
                head.latest().number(), head.safe().number(), head.latest().number() - head.safe().number(),
                head.finalized().number(), head.latest().number() - head.finalized().number());
        jdbc.sql("SELECT level, COUNT(*) FROM chain_transfer_confirmation GROUP BY level ORDER BY level")
                .query((rs, i) -> rs.getString(1) + " = " + rs.getLong(2)).list()
                .forEach(line -> System.out.println(">>> 等级 " + line));

        assertThat(inDb).isEqualTo(onChain);
        assertThat(cursor.lastBlockHash()).isEqualTo(chain.block(cursor.lastBlockNumber()).hash());
    }
}
