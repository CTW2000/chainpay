package com.chainpay.chain.deposit.service;

import com.chainpay.chain.indexer.domain.BatchOutcome;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.indexer.service.BlockIndexer;
import com.chainpay.chain.indexer.service.ChainHeadTracker;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 入账测试的脚手架：真的 PostgreSQL、真的索引器与链头追踪、真的地址分配（Hardhat xpub），只有链是 FakeChain。
 * 主链与审计链是两个独立的 FakeChain，同样的块；{@link #pay} 往主链挂一笔到 acme 地址的转账，并在两条链上定义该块的余额
 * （= 到这一块为止转入的累计），让「合约说的」和「合约做的」默认一致，要造分歧的测试再单独覆盖余额。
 */
public abstract class AbstractDepositPostingTest extends AbstractPostgresTest {

    protected static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    protected static final String ALICE = "0x4281ecf07378ee595c564a59048801330f3084ee";
    protected static final String BOB = "0x5e97b169613aff0c40a1910e597e9736c3a5ebc3";
    protected static final String ACME_ADDRESS = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266".toLowerCase(Locale.ROOT);
    protected static final String CURSOR = "test:link:transfer";
    protected static final BigInteger TEN_LINK = new BigInteger("10000000000000000000");
    protected static final BigInteger ONE_LINK = new BigInteger("1000000000000000000");

    @Autowired
    protected DepositAddressService addressService;

    @Autowired
    protected IndexerCursorRepository cursors;

    @Autowired
    protected TransferLogRepository transferLogs;

    @Autowired
    protected ChainHeadRepository heads;

    @Autowired
    protected PlatformTransactionManager txManager;

    @Autowired
    protected JdbcClient appJdbc;

    @Autowired
    protected LedgerService appLedger;

    protected long acmeId;
    protected long evilcoId;
    protected long acmeAccount;
    protected FakeChain chain;
    protected FakeChain audit;
    private BigInteger acmeOnChain = BigInteger.ZERO;

    @BeforeEach
    void seedDepositScaffolding() {
        jdbc.sql("TRUNCATE payout_tx, payout, payout_address, payout_limit, hot_wallet, chain_transfer_log, indexer_cursor, chain_head, chain_reorg, chain_reconcile, deposit CASCADE").update();
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();
        jdbc.sql("ALTER SEQUENCE deposit_address_index_seq RESTART WITH 0").update();
        jdbc.sql("UPDATE chain_token SET status = 'ACTIVE' WHERE address = :l").param("l", LINK).update();
        acmeId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('acme', 'Acme') RETURNING id").query(Long.class).single();
        evilcoId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('evilco', 'Evil') RETURNING id").query(Long.class).single();
        acmeAccount = tenantScope.asMerchant(acmeId, () -> addressService.allocate(acmeId, LINK)).accountId();
        chain = new FakeChain().withBlocks(100);      // 先有块才能往块上挂日志：addTransfer 用的是该块当前的哈希
        audit = new FakeChain().withBlocks(100);
        acmeOnChain = BigInteger.ZERO;
    }

    /** 往 acme 的收款地址转一笔，并在两条链上定义该块之后的余额（累计转入）。 */
    protected void pay(long block, BigInteger value) {
        chain.addTransfer(LINK, block, ALICE, ACME_ADDRESS, value);
        acmeOnChain = acmeOnChain.add(value);
        fundedAt(block, acmeOnChain);
    }

    /** 两条链上 acme 地址在某一块的余额。要造「合约做的 ≠ 合约说的」，在 pay 之后再调它覆盖。 */
    protected void fundedAt(long block, BigInteger balance) {
        chain.defineBalanceAt(LINK, ACME_ADDRESS, block, balance);
        audit.defineBalanceAt(LINK, ACME_ADDRESS, block, balance);
    }

    protected DepositPoster poster() {
        return new DepositPoster(systemLedger, chain, audit, 50);
    }

    /** 两条链同样的块，主链上有日志；真的索引进库、真的刷新链头。 */
    protected void indexUpTo(long head, long safe, long finalized) {
        for (FakeChain c : List.of(chain, audit)) {
            c.withBlocks(head);
            c.reportSafe(safe);
            c.reportFinalized(finalized);
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        BlockIndexer indexer = new BlockIndexer(chain, cursors, transferLogs, tx, CURSOR, LINK, 100);
        if (!indexer.hasCursor()) {
            indexer.start(0);
        }
        while (indexer.indexNextBatch().outcome() != BatchOutcome.UP_TO_DATE) {
            // 追平
        }
        new ChainHeadTracker(chain, heads, tx, "test").refresh();
    }

    protected BigDecimal balanceOf(String code) {
        return jdbc.sql("SELECT balance FROM account WHERE code = :c").param("c", code).query(BigDecimal.class).single();
    }

    protected String depositStatus(long block) {
        return jdbc.sql("SELECT status || ' ' || coalesce(hold_reason, '') FROM deposit WHERE block_number = :b")
                .param("b", block).query(String.class).single();
    }
}
