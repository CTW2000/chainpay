package com.chainpay.chain.deposit.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chainpay.chain.deposit.domain.DepositCandidate;
import com.chainpay.chain.deposit.domain.PostingResult;
import com.chainpay.chain.deposit.repository.DepositRepository;
import com.chainpay.chain.indexer.service.BlockIndexer;
import com.chainpay.chain.indexer.service.ChainHeadTracker;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.rpc.RpcAuthException;
import com.chainpay.chain.support.FakeChain;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 入账：已 FINAL 的链上转账 → 账本。先占坑再动钱，网络在事务外，两个节点都点头才记，金额只经 TokenAmounts。
 *
 * <p>链是 FakeChain，但日志是用真的 BlockIndexer 索引进真 PostgreSQL 的，链头由真的 ChainHeadTracker 刷新，
 * 收款地址由真的 DepositAddressService 分配（Hardhat xpub，序号 0 = Hardhat 第一个地址）。
 * 入账任务处理的是和生产一模一样的表。
 */
@SpringBootTest
@DisplayName("入账")
class DepositPosterTest extends AbstractDepositPostingTest {

    @Test
    @DisplayName("★ 已 FINAL 的一笔 10 LINK：占坑、记账、挂 transfer_id；镜像账户 −10、商户 +10；幂等键是链上坐标，occurred_at 是区块时间")
    void creditsAFinalDeposit() {
        pay(5, TEN_LINK);
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
        pay(5, TEN_LINK);
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
        pay(5, TEN_LINK);
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
        pay(5, TEN_LINK);
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
        pay(5, TEN_LINK);
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
        pay(5, TEN_LINK);
        pay(6, TEN_LINK);
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
    @DisplayName("★ 审计节点的 finalized 慢半拍（落后 46 块）：这一轮延后、不占坑；它追上来之后自己记上")
    void defersWhileTheAuditNodeCatchesUpOnFinality() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        audit.reportFinalized(4);                     // 块 5 它有，只是自己的 finalized 标签还没推进

        PostingResult first = poster().postOnce();

        assertThat(first.held()).as("「还没到」不是「意见不同」，不能判 HELD").isZero();
        assertThat(first.deferred()).as("算作延后").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).as("不占坑：占了就再也回不到队列").isZero();
        assertThat(transferCount()).isZero();

        audit.reportFinalized(50);

        assertThat(poster().postOnce().credited()).as("追上来就自己记上，不用人核准").isEqualTo(1);
        assertThat(tenantScope.asMerchant(acmeId, () -> appLedger.balanceOf(acmeAccount))).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("★ 审计节点卡住不动（落后 496 块，超出容忍）：等不回来了，HELD 叫人去看节点")
    void holdsWhenTheAuditNodeIsStuckFarBehind() {
        chain.withBlocks(600);
        audit.withBlocks(600);
        pay(5, TEN_LINK);
        indexUpTo(600, 550, 500);
        audit.reportFinalized(4);

        PostingResult result = poster().postOnce();

        assertThat(result.held()).isEqualTo(1);
        assertThat(depositStatus(5)).startsWith("HELD_NODE_DISAGREE").contains("去看节点");
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("★ 库里说 FINAL、两个节点都还没到（超前 100 块）：索引器错了也过不了这道门，HELD")
    void holdsWhenTheViewIsAheadOfBothNodes() {
        chain.withBlocks(600);
        audit.withBlocks(600);
        pay(200, TEN_LINK);
        indexUpTo(600, 550, 500);
        chain.reportFinalized(100);
        audit.reportFinalized(100);

        PostingResult result = poster().postOnce();

        assertThat(result.held()).isEqualTo(1);
        assertThat(result.deferred()).isZero();
        assertThat(depositStatus(200)).startsWith("HELD_NODE_DISAGREE").contains("去看节点");
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("容忍值是配置项：调成 0 之后慢半拍也立刻 HELD（证明用的是配置，不是写死的 64）")
    void theToleranceComesFromConfiguration() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        audit.reportFinalized(4);

        PostingResult result = new DepositPoster(systemLedger, chain, audit, 50, 0).postOnce();

        assertThat(result.held()).isEqualTo(1);
        assertThat(result.deferred()).isZero();
        assertThat(depositStatus(5)).contains("超出 0 块的容忍");
    }

    @Test
    @DisplayName("★ 主节点现在给的块哈希和库里那行不一样（索引之后换了说法）：HELD，不记")
    void holdsWhenThePrimaryNodeChangesItsStory() {
        pay(5, TEN_LINK);
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
        pay(5, TEN_LINK);
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
        pay(5, BigInteger.TEN.pow(40));
        pay(6, TEN_LINK);
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
        pay(5, BigInteger.ZERO);
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
    @DisplayName("地址已 DISABLED 后来的钱：不是候选（怎么处理还没定，这里先钉住「不记」）")
    void disabledAddressIsNotACandidate() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        jdbc.sql("UPDATE deposit_address SET status = 'DISABLED'").update();

        assertThat(poster().postOnce().examined()).isZero();
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("★ 崩在记账与改状态之间：账本、镜像账户一起回滚；这一笔记成 HELD_ERROR 等人，队列不卡；人核准后照记")
    void crashAfterTheLedgerPostingLeavesNothingBehind() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        DepositPoster crashing = new DepositPoster(systemLedger, chain, audit, 50, 64, jdbcClient -> new DepositRepository(jdbcClient) {
            @Override
            public void credit(long depositId, long transferId) {
                throw new IllegalStateException("模拟：记账之后、改状态之前崩溃");
            }
        });

        PostingResult result = crashing.postOnce();

        assertThat(result.held()).isEqualTo(1);
        assertThat(depositStatus(5)).as("占坑行只能以 HELD_ERROR 的样子留下，不能是 POSTING").startsWith("HELD_ERROR ").contains("崩溃");
        assertThat(transferCount()).as("账本那笔不能单独留下").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM account WHERE code = 'chain:custody:LINK'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM deposit WHERE status = 'POSTING'").query(Long.class).single()).isZero();

        jdbc.sql("UPDATE deposit SET status = 'APPROVED', hold_reason = hold_reason || ' | 人工核准' WHERE block_number = 5").update();
        assertThat(poster().postOnce().credited()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 落库崩了那一笔：HELD 日志播报的是库里实际写的 HELD_ERROR 与异常原文，不是判决阶段的打算")
    void theHeldLogLineReportsWhatWasWritten() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        DepositPoster crashing = new DepositPoster(systemLedger, chain, audit, 50, 64, jdbcClient -> new DepositRepository(jdbcClient) {
            @Override
            public void credit(long depositId, long transferId) {
                throw new IllegalStateException("模拟：记账之后、改状态之前崩溃");
            }
        });

        List<String> warnings = warningsOf(crashing::postOnce);

        assertThat(warnings).as("HELD 那行要说出库里实际写进去的状态与原因")
                .anySatisfy(line -> assertThat(line).contains("入账 HELD：").contains("HELD_ERROR").contains("崩溃"));
        assertThat(warnings).as("不能播报判决阶段的打算（CREDITED / null）")
                .noneMatch(line -> line.startsWith("入账 HELD：") && (line.contains("CREDITED") || line.contains("null")));
        assertThat(depositStatus(5)).as("库里写的就是日志说的那个状态").startsWith("HELD_ERROR ");
    }

    /** 抓 DepositPoster 这一个 logger 的 WARN 行。出事时人读的就是这一行，所以它的内容也是契约。 */
    private static List<String> warningsOf(Runnable work) {
        Logger logger = (Logger) LoggerFactory.getLogger(DepositPoster.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            work.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("★ 商户只看得到自己的入账：deposit 表有 RLS")
    void merchantsSeeOnlyTheirOwnDeposits() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        poster().postOnce();

        assertThat(tenantScope.asMerchant(acmeId, () -> appJdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single())).isEqualTo(1);
        assertThat(tenantScope.asMerchant(evilcoId, () -> appJdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single())).isZero();
        assertThat(appJdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("★ 记账时连接池拿不到连接：这一轮退避、下一轮再来；不能把这一笔记成 HELD_ERROR 等人")
    void databaseOutageMakesTheRoundRetryLaterNotHeld() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        DepositPoster outage = new DepositPoster(systemLedger, chain, audit, 50, 64, jdbcClient -> new DepositRepository(jdbcClient) {
            @Override
            public void credit(long depositId, long transferId) {
                throw new CannotGetJdbcConnectionException("模拟：连接池拿不到连接");
            }
        });

        PostingResult first = outage.postOnce();

        assertThat(first.retryLater()).as(first.detail()).isTrue();
        assertThat(first.held()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).as("占坑随事务一起回滚").isZero();
        assertThat(poster().postOnce().credited()).isEqualTo(1);
        assertThat(depositStatus(5)).startsWith("CREDITED");
    }

    @Test
    @DisplayName("★ 节点撤销了我们的 key：这一轮 halted、叫人换 key，不能混进「下一轮自己会好」那一桶")
    void revokedCredentialsHaltTheRound() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        chain.beforeBlock(n -> {
            throw new RpcAuthException(401, "eth_getBlockByNumber");
        });

        PostingResult result = poster().postOnce();

        assertThat(result.halted()).as(result.detail()).isTrue();
        assertThat(result.retryLater()).as("被撤销的 key 不会自己好").isFalse();
        assertThat(result.detail()).contains("凭证");
        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).as("什么都不写").isZero();
    }

    @Test
    @DisplayName("★ 问余额时节点给了不认识的错误码（后端落后）：只把这一笔延后、不占坑，排在后面的入账照常记；节点好了它自己记上，不用人核准")
    void unknownCodedBalanceErrorsDeferOnlyThatDeposit() {
        pay(5, TEN_LINK);
        pay(7, TEN_LINK);
        indexUpTo(100, 90, 50);
        AtomicInteger calls = new AtomicInteger();
        chain.beforeCall(() -> {
            if (calls.getAndIncrement() == 0) {                       // 只有第一次问余额（块 5 那一笔）答不上来
                throw new JsonRpcException(-32000, "header not found");
            }
        });
        DepositPoster poster = poster();

        PostingResult first = poster.postOnce();

        assertThat(first.retryLater()).as("一笔答不上来不该拖住整轮").isFalse();
        assertThat(first.deferred()).as("块 5 那一笔延后").isEqualTo(1);
        assertThat(first.credited()).as("块 7 那一笔照常记账").isEqualTo(1);
        assertThat(depositStatus(7)).startsWith("CREDITED");
        assertThat(jdbc.sql("SELECT count(*) FROM deposit WHERE block_number = 5").query(Long.class).single()).as("不占坑：占了就再也回不到队列").isZero();

        assertThat(poster.postOnce().credited()).as("节点好了就自己记上").isEqualTo(1);
        assertThat(depositStatus(5)).startsWith("CREDITED");
    }

    @Test
    @DisplayName("★ 不认识的错误码一直不好：重试预算用完那一轮才 HELD，原因写明重试过几轮")
    void unknownCodedBalanceErrorsHoldAfterTheRetryBudget() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        chain.beforeCall(() -> {
            throw new JsonRpcException(-32000, "missing trie node");
        });
        DepositPoster poster = poster();

        for (int round = 1; round < DepositPoster.UNKNOWN_BALANCE_ROUNDS; round++) {
            PostingResult r = poster.postOnce();
            assertThat(r.retryLater()).as("第 " + round + " 轮：只延后这一笔，整轮照常走完").isFalse();
            assertThat(r.deferred()).as("第 " + round + " 轮还在等").isEqualTo(1);
        }
        PostingResult last = poster.postOnce();

        assertThat(last.held()).as(last.detail()).isEqualTo(1);
        assertThat(depositStatus(5)).startsWith("HELD_BALANCE_MISMATCH ").contains("missing trie node").contains("重试");
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("★ 停下之后不再碰节点：HALTED 那一轮被记住，之后每轮直接回它，一次节点都不再问；换 key 后重启才会再试")
    void theSchedulerStopsCallingTheNodeAfterHalted() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        AtomicInteger blockCalls = new AtomicInteger();
        chain.beforeBlock(n -> {
            blockCalls.incrementAndGet();
            throw new RpcAuthException(401, "eth_getBlockByNumber");
        });
        DepositPostingScheduler scheduler = new DepositPostingScheduler(poster());

        assertThat(scheduler.tick().halted()).isTrue();
        int callsWhenHalted = blockCalls.get();
        assertThat(callsWhenHalted).isGreaterThan(0);

        PostingResult again = scheduler.tick();

        assertThat(again.halted()).isTrue();
        assertThat(scheduler.halted()).isTrue();
        assertThat(blockCalls.get()).as("停下之后一次节点都没再问").isEqualTo(callsWhenHalted);
        assertThat(scheduler.lastTick().orElseThrow().halted()).as("健康项读到的依据仍是 HALTED = DOWN").isTrue();
        assertThat(scheduler.lastTickAt()).isNotNull();
    }

    @Test
    @DisplayName("★ 调度器记得连续几轮没跑完（健康项据此判 DEGRADED）：跑完一轮就清零")
    void theSchedulerCountsUnfinishedRounds() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        AtomicBoolean broken = new AtomicBoolean(true);
        chain.beforeCall(() -> {
            if (broken.get()) {
                throw new JsonRpcException(null, "节点不可达：网关");
            }
        });
        DepositPostingScheduler scheduler = new DepositPostingScheduler(poster());

        scheduler.tick();
        scheduler.tick();

        assertThat(scheduler.consecutiveFailures()).isEqualTo(2);
        assertThat(scheduler.lastTick().orElseThrow().retryLater()).isTrue();
        assertThat(scheduler.lastTickAt()).isNotNull();

        broken.set(false);
        scheduler.tick();

        assertThat(scheduler.consecutiveFailures()).as("跑完一轮就清零").isZero();
        assertThat(scheduler.lastTick().orElseThrow().credited()).isEqualTo(1);
    }
}
