package com.chainpay.chain.deposit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.deposit.domain.DepositCandidate;
import com.chainpay.chain.deposit.domain.PostingResult;
import com.chainpay.chain.deposit.repository.DepositRepository;
import com.chainpay.chain.rpc.JsonRpcException;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * 策略与例外（M3-③）：最小入账额、balanceOf 第二意见、意外异常的分类、HELD 的人工路径。
 * 信合约做的（balanceOf），不信合约说的（Transfer 事件）。
 */
@DisplayName("M3-③ · 入账策略与例外")
class DepositPolicyTest extends AbstractDepositPostingTest {

    @Test
    @DisplayName("★ 合约做的 = 合约说的：余额等于累计转入，照记")
    void creditsWhenTheBalanceMatchesTheEvents() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);

        assertThat(poster().postOnce().credited()).isEqualTo(1);
        assertThat(depositStatus(5)).startsWith("CREDITED");
    }

    @Test
    @DisplayName("★ 转账扣费的代币：事件说 10，链上到账 9.8 → HELD_BALANCE_MISMATCH，不记；下一笔（余额对得上）照记")
    void holdsWhenTheContractDeliveredLessThanItSaid() {
        pay(5, TEN_LINK);
        fundedAt(5, new BigInteger("9800000000000000000"));
        pay(6, TEN_LINK);
        fundedAt(6, new BigInteger("9800000000000000000").add(TEN_LINK));   // 第二笔按第一笔实际到账累计——但事件累计是 20，仍对不上
        indexUpTo(100, 90, 50);

        PostingResult result = poster().postOnce();

        assertThat(depositStatus(5)).startsWith("HELD_BALANCE_MISMATCH ").contains("10000000000000000000").contains("9800000000000000000");
        assertThat(depositStatus(6)).startsWith("HELD_BALANCE_MISMATCH ");
        assertThat(result.held()).isEqualTo(2);
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("★ 审计节点给的余额和主节点不同：HELD，原因写明是审计节点")
    void holdsWhenTheAuditNodeReportsADifferentBalance() {
        pay(5, TEN_LINK);
        audit.defineBalanceAt(LINK, ACME_ADDRESS, 5, ONE_LINK);
        indexUpTo(100, 90, 50);

        assertThat(poster().postOnce().held()).isEqualTo(1);
        assertThat(depositStatus(5)).startsWith("HELD_BALANCE_MISMATCH ").contains("审计");
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("★ 余额问不到（合约 revert / 节点没有那一块的状态）：不猜，HELD，原因写明问不到")
    void holdsWhenTheBalanceCannotBeRead() {
        chain.addTransfer(LINK, 5, ALICE, ACME_ADDRESS, TEN_LINK);   // 不用 pay：两条链上都没定义余额，FakeChain 会 revert
        indexUpTo(100, 90, 50);

        assertThat(poster().postOnce().held()).isEqualTo(1);
        assertThat(depositStatus(5)).startsWith("HELD_BALANCE_MISMATCH ").contains("问不到");
        assertThat(transferCount()).isZero();
    }

    @Test
    @DisplayName("★ 问余额时节点瞬时失败：这一轮提前结束，什么都不写；下一轮照记")
    void transientFailureWhileReadingTheBalanceRetriesLater() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        chain.beforeCall(() -> {
            if (failOnce.getAndSet(false)) {
                throw new JsonRpcException(null, "超时（20000 ms，含正文）· eth_call");
            }
        });
        DepositPoster poster = poster();

        PostingResult first = poster.postOnce();
        assertThat(first.retryLater()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).isZero();

        assertThat(poster.postOnce().credited()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 最小入账额：0.5 LINK 低于 1 LINK → REJECTED_DUST 记录不入账；正好 1 LINK 照记；阈值 0 = 不限")
    void rejectsDustBelowTheTokenMinimum() {
        jdbc.sql("UPDATE chain_token SET min_deposit = 1 WHERE address = :l").param("l", LINK).update();
        pay(5, new BigInteger("500000000000000000"));
        pay(6, ONE_LINK);
        indexUpTo(100, 90, 50);

        PostingResult result = poster().postOnce();

        assertThat(depositStatus(5)).startsWith("REJECTED_DUST ").contains("1");
        assertThat(depositStatus(6)).startsWith("CREDITED");
        assertThat(result.credited()).isEqualTo(1);
        assertThat(transferCount()).isEqualTo(1);
        assertThat(balanceOf("chain:custody:LINK")).isEqualByComparingTo("-1");
    }

    @Test
    @DisplayName("★ 结构性异常（记账后改状态时炸了）：这一笔 HELD_ERROR 带异常原文，不卡队列，下一笔照记")
    void holdsWithTheErrorWhenPostingFailsUnexpectedly() {
        pay(5, TEN_LINK);
        pay(6, TEN_LINK);
        indexUpTo(100, 90, 50);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        DepositPoster poster = new DepositPoster(systemLedger, chain, audit, 50, jdbcClient -> new DepositRepository(jdbcClient) {
            @Override
            public void credit(long depositId, long transferId) {
                if (failOnce.getAndSet(false)) {
                    throw new IllegalStateException("模拟：账本约束违反之类的结构性错误");
                }
                super.credit(depositId, transferId);
            }
        });

        PostingResult result = poster.postOnce();

        assertThat(depositStatus(5)).startsWith("HELD_ERROR ").contains("结构性错误");
        assertThat(depositStatus(6)).startsWith("CREDITED");
        assertThat(result.held()).isEqualTo(1);
        assertThat(result.credited()).isEqualTo(1);
        assertThat(transferCount()).as("炸掉的那笔连账本都回滚了").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 数据库瞬时失败（超时、连接断）：这一轮提前结束，不记 HELD，下一轮照记")
    void transientDatabaseFailureRetriesLater() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        DepositPoster poster = new DepositPoster(systemLedger, chain, audit, 50, jdbcClient -> new DepositRepository(jdbcClient) {
            @Override
            public long ensureCustodyAccount(String symbol) {
                if (failOnce.getAndSet(false)) {
                    throw new QueryTimeoutException("模拟：语句超时");
                }
                return super.ensureCustodyAccount(symbol);
            }
        });

        PostingResult first = poster.postOnce();
        assertThat(first.retryLater()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM deposit").query(Long.class).single()).isZero();

        assertThat(poster.postOnce().credited()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 人工路径：HELD 的那笔人复核后改成 APPROVED，下一轮用同一套占坑与幂等键记上，不再核对；人不碰账本表")
    void approvedHeldDepositIsPostedOnTheNextRound() {
        pay(5, TEN_LINK);
        fundedAt(5, new BigInteger("9800000000000000000"));
        indexUpTo(100, 90, 50);
        DepositPoster poster = poster();
        assertThat(poster.postOnce().held()).isEqualTo(1);

        jdbc.sql("UPDATE deposit SET status = 'APPROVED', hold_reason = hold_reason || ' | 人工核准：运营 A 核对过链上，2026-09-07' WHERE block_number = 5").update();
        PostingResult result = poster.postOnce();

        assertThat(result.credited()).isEqualTo(1);
        assertThat(depositStatus(5)).startsWith("CREDITED ").contains("人工核准");
        assertThat(transferCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT idempotency_key FROM transfer").query(String.class).single()).isEqualTo("deposit:" + chain.block(5).hash() + ":0");
        assertThat(balanceOf("chain:custody:LINK")).isEqualByComparingTo("-10");
    }

    @Test
    @DisplayName("APPROVED 的行两个实例同时来记：占坑是 UPDATE … WHERE status = 'APPROVED'，只有一个记得上")
    void approvedRowIsClaimedAtomically() {
        pay(5, TEN_LINK);
        fundedAt(5, new BigInteger("9800000000000000000"));
        indexUpTo(100, 90, 50);
        DepositPoster poster = poster();
        poster.postOnce();
        jdbc.sql("UPDATE deposit SET status = 'APPROVED', hold_reason = hold_reason || ' | 人工核准' WHERE block_number = 5").update();
        List<DepositCandidate> stale = poster.candidates();

        assertThat(poster.postOnce().credited()).isEqualTo(1);
        PostingResult late = poster.post(stale);

        assertThat(late.skipped()).isEqualTo(1);
        assertThat(transferCount()).isEqualTo(1);
    }
}
