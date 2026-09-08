package com.chainpay.chain.deposit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.deposit.domain.DepositCandidate;
import com.chainpay.chain.deposit.domain.PostingResult;
import com.chainpay.chain.deposit.repository.DepositRepository;
import com.chainpay.chain.indexer.domain.BatchOutcome;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.indexer.repository.IndexerCursorRepository;
import com.chainpay.chain.indexer.repository.TransferLogRepository;
import com.chainpay.chain.indexer.service.BlockIndexer;
import com.chainpay.chain.indexer.service.ChainHeadTracker;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.system.SystemLedger;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 入账：已 FINAL 的链上转账 → 账本。先占坑再动钱，网络在事务外，两个节点都点头才记，金额只经 TokenAmounts。
 *
 * <p>链是 FakeChain，但日志是用真的 BlockIndexer 索引进真 PostgreSQL 的，链头由真的 ChainHeadTracker 刷新，
 * 收款地址由真的 DepositAddressService 分配（Hardhat xpub，序号 0 = Hardhat 第一个地址）。
 * 入账任务处理的是和生产一模一样的表。
 */
@SpringBootTest
@DisplayName("M3-② · 入账")
class DepositPosterTest extends AbstractPostgresTest {

    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String ALICE = "0x4281ecf07378ee595c564a59048801330f3084ee";
    static final String BOB = "0x5e97b169613aff0c40a1910e597e9736c3a5ebc3";
    static final String ACME_ADDRESS = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266".toLowerCase(Locale.ROOT);
    static final String CURSOR = "test:link:transfer";
    static final BigInteger TEN_LINK = new BigInteger("10000000000000000000");

    @Autowired
    private SystemLedger systemLedger;

    @Autowired
    private DepositAddressService addressService;

    @Autowired
    private IndexerCursorRepository cursors;

    @Autowired
    private TransferLogRepository transferLogs;

    @Autowired
    private ChainHeadRepository heads;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcClient appJdbc;

    @Autowired
    private LedgerService appLedger;

    private long acmeId;
    private long evilcoId;
    private long acmeAccount;
    private FakeChain chain;
    private FakeChain audit;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor, chain_head, chain_reorg, chain_reconcile, deposit CASCADE").update();
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();
        jdbc.sql("ALTER SEQUENCE deposit_address_index_seq RESTART WITH 0").update();
        jdbc.sql("UPDATE chain_token SET status = 'ACTIVE' WHERE address = :l").param("l", LINK).update();
        acmeId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('acme', 'Acme') RETURNING id").query(Long.class).single();
        evilcoId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('evilco', 'Evil') RETURNING id").query(Long.class).single();
        acmeAccount = tenantScope.asMerchant(acmeId, () -> addressService.allocate(acmeId, LINK)).accountId();
        chain = new FakeChain().withBlocks(100);      // 先有块才能往块上挂日志：addTransfer 用的是该块当前的哈希
        audit = new FakeChain().withBlocks(100);
    }

    @Test
    @DisplayName("★ 已 FINAL 的一笔 10 LINK：占坑、记账、挂 transfer_id；镜像账户 −10、商户 +10；幂等键是链上坐标，occurred_at 是区块时间")
    void creditsAFinalDeposit() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);

        PostingResult result = poster().postOnce();

        assertThat(result.credited()).isEqualTo(1);
        assertThat(result.retryLater()).isFalse();
        assertThat(jdbc.sql("SELECT status || ' ' || (transfer_id IS NOT NULL) || ' ' || amount FROM deposit").query(String.class).single())
                .isEqualTo("CREDITED true 10.000000000000000000");
        assertThat(jdbc.sql("SELECT idempotency_key || ' ' || code || ' ' || currency || ' ' || amount || ' ' || extract(epoch FROM occurred_at)::bigint FROM transfer")
                .query(String.class).single())
                .isEqualTo("deposit:" + FakeChain.hashOf(5) + ":0 DEPOSIT LINK 10.000000000000000000 " + (1_700_000_000L + 5 * 12));
        assertThat(balanceOf("chain:custody:LINK")).isEqualByComparingTo("-10");
        assertThat(jdbc.sql("SELECT allow_negative || ' ' || (merchant_id IS NULL) FROM account WHERE code = 'chain:custody:LINK'").query(String.class).single())
                .isEqualTo("true true");
        assertThat(tenantScope.asMerchant(acmeId, () -> appLedger.balanceOf(acmeAccount))).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("★ 没到 FINAL 不记：SAFE 的钱不进余额；finalized 追上来之后才记")
    void waitsForFinality() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 3);

        assertThat(poster().postOnce().examined()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).isZero();
        assertThat(transferCount()).isZero();

        indexUpTo(100, 90, 50);
        assertThat(poster().postOnce().credited()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 重放无害：再跑一轮什么都不写")
    void replayIsHarmless() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);
        DepositPoster poster = poster();

        assertThat(poster.postOnce().credited()).isEqualTo(1);
        PostingResult again = poster.postOnce();

        assertThat(again.examined()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).isEqualTo(1);
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 另一个实例拿着过期的候选列表：占坑失败，不记账，不抛")
    void staleCandidatesCannotDoublePost() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);
        DepositPoster poster = poster();
        List<DepositCandidate> stale = poster.candidates();
        assertThat(poster.postOnce().credited()).isEqualTo(1);

        PostingResult late = poster.post(stale);

        assertThat(late.skipped()).isEqualTo(1);
        assertThat(late.credited()).isZero();
        assertThat(transferCount()).isEqualTo(1);
        assertThat(balanceOf("chain:custody:LINK")).isEqualByComparingTo("-10");
    }

    @Test
    @DisplayName("★ 另一个实例已把这笔判成 HELD，我们拿着过期候选再来：占坑失败就一分钱不记（先占坑再动钱的顺序）")
    void doesNotCreditWhatAnotherInstanceAlreadyHeld() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);
        DepositPoster poster = poster();
        List<DepositCandidate> stale = poster.candidates();
        long logId = jdbc.sql("SELECT id FROM chain_transfer_log").query(Long.class).single();
        long depositAddressAccount = jdbc.sql("SELECT account_id FROM deposit_address").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO deposit (transfer_log_id, address, merchant_id, token, raw_value, status, hold_reason, block_number, block_hash)
                        VALUES (:log, :address, :m, :token, :raw, 'HELD_NODE_DISAGREE', '另一个实例：审计节点意见不同', 5, :hash)
                        """).param("log", logId).param("address", ACME_ADDRESS).param("m", acmeId).param("token", LINK)
                .param("raw", new BigDecimal(TEN_LINK)).param("hash", FakeChain.hashOf(5)).update();

        PostingResult result = poster.post(stale);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.credited()).isZero();
        assertThat(transferCount()).as("占坑失败之后账本不能动").isZero();
        assertThat(depositAddressAccount).isEqualTo(acmeAccount);
    }

    @Test
    @DisplayName("★ 审计节点对那一块的哈希意见不同：这笔 HELD_NODE_DISAGREE 不记账，队列不卡，下一笔照记")
    void holdsWhenTheAuditNodeDisagrees() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        chain.addTransfer(LINK, 6, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);
        audit.tamperHash(5, FakeChain.hashOf(999));

        PostingResult result = poster().postOnce();

        assertThat(result.held()).isEqualTo(1);
        assertThat(result.credited()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || ' ' || coalesce(hold_reason, '') FROM deposit WHERE block_number = 5").query(String.class).single())
                .startsWith("HELD_NODE_DISAGREE ").contains("审计");
        assertThat(jdbc.sql("SELECT status FROM deposit WHERE block_number = 6").query(String.class).single()).isEqualTo("CREDITED");
        assertThat(transferCount()).isEqualTo(1);
        assertThat(tenantScope.asMerchant(acmeId, () -> appLedger.balanceOf(acmeAccount))).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("★ 主节点现在给的块哈希和库里那行不一样（索引之后换了说法）：HELD，不记")
    void holdsWhenThePrimaryNodeChangesItsStory() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);
        chain.tamperHash(5, FakeChain.hashOf(999));

        PostingResult result = poster().postOnce();

        assertThat(result.held()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT hold_reason FROM deposit").query(String.class).single()).contains("主节点");
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("★ 核对时节点瞬时失败：这一轮提前结束、什么都不写，下一轮照记")
    void transientRpcFailureRetriesLater() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        chain.beforeBlock(n -> {
            if (failOnce.getAndSet(false)) {
                throw new JsonRpcException(null, "超时（20000 ms，含正文）· eth_getBlockByNumber");
            }
        });
        DepositPoster poster = poster();

        PostingResult first = poster.postOnce();
        assertThat(first.retryLater()).isTrue();
        assertThat(first.detail()).contains("超时");
        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).isZero();
        assertThat(transferCount()).isZero();

        assertThat(poster.postOnce().credited()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 装不进账本的金额（10^40 原始单位）：HELD_OVERFLOW，不记，不卡队列")
    void holdsAnAmountTheLedgerCannotStore() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, BigInteger.TEN.pow(40));
        chain.addTransfer(LINK, 6, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);

        PostingResult result = poster().postOnce();

        assertThat(result.held()).isEqualTo(1);
        assertThat(result.credited()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || ' ' || (amount IS NULL) FROM deposit WHERE block_number = 5").query(String.class).single())
                .isEqualTo("HELD_OVERFLOW true");
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("零值转账：EIP-20 说合法，账本说没意义——IGNORED_ZERO，记录不入账")
    void ignoresZeroValueTransfers() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, BigInteger.ZERO);
        indexUpTo(100, 90, 50);

        PostingResult result = poster().postOnce();

        assertThat(result.ignored()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM deposit").query(String.class).single()).isEqualTo("IGNORED_ZERO");
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("收款方不是我们的地址：不是候选，什么都不记")
    void ignoresTransfersToStrangers() {
        chain.addTransfer(LINK, 5, ALICE, BOB, TEN_LINK);
        indexUpTo(100, 90, 50);

        assertThat(poster().postOnce().examined()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("地址已 DISABLED 后来的钱：不是候选（怎么处理是 M3-before 第 5 问，这里先钉住「不记」）")
    void disabledAddressIsNotACandidate() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);
        jdbc.sql("UPDATE deposit_address SET status = 'DISABLED'").update();

        assertThat(poster().postOnce().examined()).isZero();
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("★ 崩在占坑与记账之间：整个事务回滚，deposit、transfer、镜像账户一样都不留；重启后照记")
    void crashAfterTheLedgerPostingLeavesNothingBehind() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);
        DepositPoster crashing = new DepositPoster(systemLedger, chain, audit, 50, jdbcClient -> new DepositRepository(jdbcClient) {
            @Override
            public void credit(long depositId, long transferId) {
                throw new IllegalStateException("模拟：记账之后、改状态之前崩溃");
            }
        });

        assertThatThrownBy(crashing::postOnce).isInstanceOf(IllegalStateException.class).hasMessageContaining("崩溃");

        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).as("占坑行不能单独留下").isZero();
        assertThat(transferCount()).as("账本那笔不能单独留下").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM account WHERE code = 'chain:custody:LINK'").query(Long.class).single()).isZero();
        assertThat(poster().postOnce().credited()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 商户只看得到自己的入账：deposit 表有 RLS")
    void merchantsSeeOnlyTheirOwnDeposits() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);
        indexUpTo(100, 90, 50);
        poster().postOnce();

        assertThat(tenantScope.asMerchant(acmeId, () -> appJdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single())).isEqualTo(1);
        assertThat(tenantScope.asMerchant(evilcoId, () -> appJdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single())).isZero();
        assertThat(appJdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------ 脚手架

    private DepositPoster poster() {
        return new DepositPoster(systemLedger, chain, audit, 50);
    }

    /** 两条链同样的块，主链上有日志；真的索引进库、真的刷新链头。 */
    private void indexUpTo(long head, long safe, long finalized) {
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

    private BigDecimal balanceOf(String code) {
        return jdbc.sql("SELECT balance FROM account WHERE code = :c").param("c", code).query(BigDecimal.class).single();
    }
}
