package com.chainpay.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.audit.domain.AuditFinding;
import com.chainpay.audit.domain.AuditKind;
import com.chainpay.audit.domain.AuditResult;
import com.chainpay.audit.service.AuditService;
import com.chainpay.chain.deposit.service.AbstractDepositPostingTest;
import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 对账是判官：站在两个节点都认的 finalized 块上，把链上事实和库内记录逐项比对。
 * 钱从 M3 的真实路径进来（索引 → FINAL → 入账），然后往库里或链上动手脚，看哪条检查响。
 */
@SpringBootTest
@DisplayName("M5 · 对账：三种差异都要浮出来，没跑完也要能看出来")
class AuditServiceTest extends AbstractDepositPostingTest {

    static final String HOT = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";          // Hardhat #2：本测试里的热钱包
    static final long F = 50;                                                      // indexUpTo(100, 90, 50) 之后 finalized = 50

    @AfterEach
    void cleanAuditTables() {
        jdbc.sql("TRUNCATE audit_finding, audit_run, payout_tx, payout, payout_address, payout_limit, hot_wallet CASCADE").update();
    }

    /** 10 LINK 走 M3 真实路径入账；链上两个节点在 F 的余额都等于事件累计；热钱包登记、余额 0。 */
    private void fundedAndPosted() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, F);
        poster().postOnce();
        assertThat(depositStatus(5)).startsWith("CREDITED");
        balancesAt(F, TEN_LINK, BigInteger.ZERO);
        jdbc.sql("INSERT INTO hot_wallet (address, chain, next_nonce) VALUES (:a, 'test', 0)").param("a", HOT).update();
    }

    private void balancesAt(long block, BigInteger acme, BigInteger hot) {
        for (FakeChain c : List.of(chain, audit)) {
            c.defineBalanceAt(LINK, ACME_ADDRESS, block, acme);
            c.defineBalanceAt(LINK, HOT, block, hot);
        }
    }

    @Test
    @DisplayName("★ 外部注资：登记前是 CUSTODY_TOTAL 的「链上多」；登记后等式平了 → OK（M6-②）")
    void externalFundingIsAFindingUntilRegistered() {
        chain.addTransfer(LINK, 30, ALICE, HOT, ONE_LINK, FUNDING_TX);            // 运营往热钱包充了 1
        fundedAndPosted();
        balancesAt(F, TEN_LINK, ONE_LINK);

        AuditResult before = auditor().runOnce();
        assertThat(kinds(before, "CUSTODY_TOTAL")).containsExactly(AuditKind.MISSING_IN_LEDGER);

        new com.chainpay.chain.payout.service.HotWalletFundingService(systemLedger).register(FUNDING_TX, null, "演练充币");
        AuditResult after = auditor().runOnce();
        assertThat(after.status()).as(after.detail() + " " + after.findings()).isEqualTo("OK");
    }

    @Test
    @DisplayName("★ 登记过的注资，日志后来被重组翻掉：CUSTODY_TOTAL 报「登记的注资链上不认」")
    void registeredFundingWhoseLogWasOrphanedIsMissingOnChain() {
        chain.addTransfer(LINK, 30, ALICE, HOT, ONE_LINK, FUNDING_TX);
        fundedAndPosted();
        balancesAt(F, TEN_LINK, BigInteger.ZERO);                                  // 链上热钱包其实是 0：那笔充币不在主分支
        new com.chainpay.chain.payout.service.HotWalletFundingService(systemLedger).register(FUNDING_TX, null, null);
        jdbc.sql("UPDATE chain_transfer_log SET status = 'ORPHANED' WHERE tx_hash = :h").param("h", FUNDING_TX).update();

        AuditResult r = auditor().runOnce();

        assertThat(r.findings()).filteredOn(f -> f.check().equals("CUSTODY_TOTAL")).extracting(AuditFinding::kind)
                .contains(AuditKind.MISSING_ON_CHAIN);
        assertThat(r.findings()).extracting(AuditFinding::detail).anyMatch(d -> d.contains("登记的注资"));
    }

    static final String FUNDING_TX = "0x" + "f".repeat(64);

    private AuditService auditor() {
        return new AuditService(systemLedger, chain, audit, 10);
    }

    private static List<AuditKind> kinds(AuditResult r, String check) {
        return r.findings().stream().filter(f -> f.check().equals(check)).map(AuditFinding::kind).toList();
    }

    // ---------------------------------------------------------------- 干净

    @Test
    @DisplayName("★ 账链一致：OK、零差异；audit_run 落一行，记着站在哪个 finalized 块上")
    void cleanStateIsOk() {
        fundedAndPosted();

        AuditResult r = auditor().runOnce();

        assertThat(r.status()).as(r.detail() + " " + r.findings()).isEqualTo("OK");
        assertThat(r.findings()).isEmpty();
        assertThat(r.finalizedNumber()).isEqualTo(F);
        assertThat(jdbc.sql("SELECT status || ' ' || finalized_number || ' ' || findings FROM audit_run").query(String.class).single()).isEqualTo("OK 50 0");
    }

    // ---------------------------------------------------------------- 入账三种差异

    @Test
    @DisplayName("★ 库内有、链上无：记了账的入账，它的日志被标成不在主分支 → MISSING_ON_CHAIN；地址余额也对不上")
    void creditedDepositWhoseLogLeftTheCanonicalChainIsMissingOnChain() {
        fundedAndPosted();
        jdbc.sql("UPDATE chain_transfer_log SET status = 'ORPHANED' WHERE to_address = :a").param("a", ACME_ADDRESS).update();

        AuditResult r = auditor().runOnce();

        assertThat(r.status()).isEqualTo("DIFF");
        assertThat(kinds(r, "DEPOSIT_LEDGER")).contains(AuditKind.MISSING_ON_CHAIN);
        assertThat(kinds(r, "ADDRESS_BALANCE")).as("链上 10、库里事件累计 0：链上多").contains(AuditKind.MISSING_IN_LEDGER);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_finding").query(Long.class).single()).isEqualTo(r.findings().size());
    }

    @Test
    @DisplayName("★ 链上有、库内无：入账行被删掉，FINAL 的收款日志没有入账行 → MISSING_IN_LEDGER")
    void deletedDepositRowIsMissingInLedger() {
        fundedAndPosted();
        jdbc.sql("DELETE FROM deposit").update();

        AuditResult r = auditor().runOnce();

        assertThat(kinds(r, "DEPOSIT_LEDGER")).contains(AuditKind.MISSING_IN_LEDGER);
        assertThat(r.findings()).extracting(AuditFinding::subject).anyMatch(s -> s.contains(FakeChain.hashOf(5)));
    }

    @Test
    @DisplayName("★ 两边都有、金额不符：入账行的金额被改小 → AMOUNT_MISMATCH，期望与实际都写出来")
    void shrunkDepositAmountIsAMismatch() {
        fundedAndPosted();
        jdbc.sql("UPDATE deposit SET amount = amount - 1").update();

        AuditResult r = auditor().runOnce();

        AuditFinding f = r.findings().stream().filter(x -> x.check().equals("DEPOSIT_LEDGER") && x.kind() == AuditKind.AMOUNT_MISMATCH).findFirst().orElseThrow();
        assertThat(f.expected()).contains("10");
        assertThat(f.actual()).contains("9");
    }

    // ---------------------------------------------------------------- 地址余额与两个节点

    @Test
    @DisplayName("★ 合约说的余额比事件累计少：MISSING_ON_CHAIN；托管总量也报差额")
    void chainBalanceBelowEvidenceIsMissingOnChain() {
        fundedAndPosted();
        balancesAt(F, TEN_LINK.subtract(ONE_LINK), BigInteger.ZERO);

        AuditResult r = auditor().runOnce();

        assertThat(kinds(r, "ADDRESS_BALANCE")).containsExactly(AuditKind.MISSING_ON_CHAIN);
        assertThat(kinds(r, "CUSTODY_TOTAL")).contains(AuditKind.MISSING_ON_CHAIN);
    }

    @Test
    @DisplayName("★ 审计节点说的余额和主节点不同：DISPUTED，不下结论")
    void auditNodeDisagreementIsDisputed() {
        fundedAndPosted();
        audit.defineBalanceAt(LINK, ACME_ADDRESS, F, TEN_LINK.subtract(ONE_LINK));

        AuditResult r = auditor().runOnce();

        assertThat(kinds(r, "ADDRESS_BALANCE")).containsExactly(AuditKind.DISPUTED);
    }

    // ---------------------------------------------------------------- 出账

    @Test
    @DisplayName("★ 热钱包发出了一笔链上转账，库里没有对应的尝试 → MISSING_IN_LEDGER（有人在别处用了这把钥匙）")
    void hotWalletOutflowWithoutAnAttemptIsMissingInLedger() {
        pay(5, TEN_LINK);
        chain.addTransfer(LINK, 30, ALICE, HOT, ONE_LINK);                         // 有人给热钱包充了 1
        chain.addTransfer(LINK, 40, HOT, BOB, ONE_LINK);                           // 热钱包发出了 1，库里没有提现
        indexUpTo(100, 90, F);
        poster().postOnce();
        balancesAt(F, TEN_LINK, BigInteger.ZERO);
        jdbc.sql("INSERT INTO hot_wallet (address, chain, next_nonce) VALUES (:a, 'test', 0)").param("a", HOT).update();

        AuditResult r = auditor().runOnce();

        assertThat(kinds(r, "PAYOUT_LEDGER")).contains(AuditKind.MISSING_IN_LEDGER);
        assertThat(r.findings()).extracting(AuditFinding::detail).anyMatch(d -> d.contains("钥匙"));
    }

    @Test
    @DisplayName("★ CONFIRMED 的提现在链上找不到它的转账日志 → MISSING_ON_CHAIN；日志金额不对 → AMOUNT_MISMATCH")
    void confirmedPayoutIsCheckedAgainstItsOnChainLog() {
        pay(5, TEN_LINK);
        chain.addTransfer(LINK, 30, ALICE, HOT, ONE_LINK.multiply(BigInteger.TWO));   // 热钱包有 2
        String good = FakeChain.txHashOf(40, 7);
        String wrong = FakeChain.txHashOf(41, 7);
        chain.addTransfer(LINK, 40, HOT, BOB, ONE_LINK, good);                    // 提现 A 的日志：对
        chain.addTransfer(LINK, 41, HOT, BOB, ONE_LINK.divide(BigInteger.TWO), wrong);   // 提现 B 的日志：金额少了一半
        indexUpTo(100, 90, F);
        poster().postOnce();
        balancesAt(F, TEN_LINK, ONE_LINK.divide(BigInteger.TWO));
        jdbc.sql("INSERT INTO hot_wallet (address, chain, next_nonce) VALUES (:a, 'test', 3)").param("a", HOT).update();
        confirmedPayout("a", new BigDecimal("1"), 0, good, 40);
        confirmedPayout("b", new BigDecimal("1"), 1, wrong, 41);
        confirmedPayout("c", new BigDecimal("1"), 2, FakeChain.txHashOf(42, 7), 42);   // 提现 C：链上根本没有它的日志

        AuditResult r = auditor().runOnce();

        assertThat(kinds(r, "PAYOUT_LEDGER")).contains(AuditKind.AMOUNT_MISMATCH, AuditKind.MISSING_ON_CHAIN);
        assertThat(r.findings().stream().filter(f -> f.check().equals("PAYOUT_LEDGER")).map(AuditFinding::subject))
                .noneMatch(s -> s.contains("提现 " + payoutId("a")));
    }

    // ---------------------------------------------------------------- 对账自己

    @Test
    @DisplayName("★ 节点不可达：这一轮 FAILED，原因写进 audit_run，零差异——「没跑完」和「跑了没事」分得开")
    void nodeOutageMakesTheRunFailed() {
        fundedAndPosted();
        chain.beforeBlock(n -> { throw new JsonRpcException(null, "节点不可达"); });

        AuditResult r = auditor().runOnce();

        assertThat(r.failed()).isTrue();
        assertThat(r.detail()).contains("节点不可达");
        assertThat(jdbc.sql("SELECT status FROM audit_run").query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_finding").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("★ 两个节点对 finalized 那一块的哈希意见不同：拒绝下结论（FAILED），在分叉上对账对出来的差异是假的")
    void forkAtTheFinalizedBlockRefusesToJudge() {
        fundedAndPosted();
        audit.reorgFrom(F, "another-branch");

        AuditResult r = auditor().runOnce();

        assertThat(r.failed()).isTrue();
        assertThat(r.detail()).contains("意见不同");
    }

    @Test
    @DisplayName("★ stale：从没跑过 = stale；刚跑过 = 不 stale；上次成功距今超过两个周期 = stale")
    void staleTellsWhetherTheJudgeHasBeenSilentTooLong() {
        fundedAndPosted();
        assertThat(auditor().status(Duration.ofHours(1)).stale()).isTrue();
        auditor().runOnce();
        assertThat(auditor().status(Duration.ofHours(1)).stale()).isFalse();
        jdbc.sql("UPDATE audit_run SET finished_at = now() - interval '3 hours'").update();
        assertThat(auditor().status(Duration.ofHours(1)).stale()).isTrue();
        assertThat(auditor().status(Duration.ofHours(1)).lastRun()).isPresent();
    }

    @Test
    @DisplayName("★ 索引器还没追到 finalized：站在书签上对账（证据只到书签），结论写明站在哪；不在没证据的块上报假差异")
    void standsOnTheCursorWhenTheIndexerIsBehind() {
        fundedAndPosted();                                                        // 书签 100，finalized 50
        for (FakeChain c : List.of(chain, audit)) {
            c.withBlocks(130);
            c.defineBalanceAt(LINK, ACME_ADDRESS, 100, TEN_LINK);                  // 只定义书签那一块的余额；块 120 上没有答案
            c.defineBalanceAt(LINK, HOT, 100, BigInteger.ZERO);
        }
        jdbc.sql("UPDATE chain_head SET latest_number = 130, latest_hash = :lh, safe_number = 125, safe_hash = :sh, finalized_number = 120, finalized_hash = :fh")
                .param("lh", FakeChain.hashOf(130)).param("sh", FakeChain.hashOf(125)).param("fh", FakeChain.hashOf(120)).update();
        confirmedPayout("beyond", new BigDecimal("1"), 0, FakeChain.txHashOf(110, 1), 110);   // 上链块 110 > 书签 100：证据还没索到，这一轮不看它

        AuditResult r = auditor().runOnce();

        assertThat(r.status()).as(r.detail()).isEqualTo("OK");
        assertThat(r.finalizedNumber()).as("站在书签 100，不是 finalized 120").isEqualTo(100);
        assertThat(r.detail()).contains("书签");
        assertThat(r.findings()).as("截至书签的镜像 = 入账 10 − 结算 0；块 110 的提现与它的结算都在视野之外").isEmpty();
    }

    // ---------------------------------------------------------------- 助手

    /** 直接造一笔已 CONFIRMED 的提现：冻结、结算两笔账本转账 + payout 行 + 一次 MINED 尝试。 */
    private void confirmedPayout(String key, BigDecimal amount, long nonce, String txHash, long block) {
        long frozen = jdbc.sql("INSERT INTO account(code, currency, kind, merchant_id) VALUES ('user:acme:LINK:frozen', 'LINK', 'LIABILITY', :m) ON CONFLICT (code) DO NOTHING RETURNING id")
                .param("m", acmeId).query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("SELECT id FROM account WHERE code = 'user:acme:LINK:frozen'").query(Long.class).single());
        long custody = jdbc.sql("SELECT id FROM account WHERE code = 'chain:custody:LINK'").query(Long.class).single();
        long freeze = ledger.transfer(new TransferCommand("withdrawal:" + key + ":freeze", "LINK", amount, acmeAccount, frozen, TransferCode.WITHDRAWAL_FREEZE, null));
        long id = jdbc.sql("INSERT INTO payout (merchant_id, idempotency_key, token, to_address, amount, raw_value, status, freeze_transfer_id) "
                        + "VALUES (:m, :k, :t, :to, :amt, :raw, 'MINED', :f) RETURNING id")
                .param("m", acmeId).param("k", key).param("t", LINK).param("to", BOB).param("amt", amount)
                .param("raw", new BigDecimal(amount.movePointRight(18).toBigIntegerExact())).param("f", freeze).query(Long.class).single();
        long settle = ledger.transfer(new TransferCommand("withdrawal:" + id + ":settle", "LINK", amount, frozen, custody, TransferCode.WITHDRAWAL, null));
        jdbc.sql("UPDATE payout SET status = 'CONFIRMED', settle_transfer_id = :s, confirmed_at = now() WHERE id = :id").param("s", settle).param("id", id).update();
        jdbc.sql("INSERT INTO payout_tx (payout_id, hot_wallet, nonce, tx_hash, raw_tx, gas_limit, max_fee_per_gas, max_priority_fee_per_gas, status, block_number, block_hash, reverted) "
                        + "VALUES (:p, :w, :n, :h, '0x02', 60000, 1, 1, 'MINED', :b, :bh, false)")
                .param("p", id).param("w", HOT).param("n", nonce).param("h", txHash).param("b", block).param("bh", FakeChain.hashOf(block)).update();
    }

    private long payoutId(String key) {
        return jdbc.sql("SELECT id FROM payout WHERE idempotency_key = :k").param("k", key).query(Long.class).single();
    }
}
