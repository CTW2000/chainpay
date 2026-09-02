package com.chainpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.ledger.service.LedgerException;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * V2 三个建模缺口的守卫。
 *
 * <p>{@code LedgerInvariantTest} 验证的是<b>行为</b>（幂等、原子、不超支）。
 * 这个类验证的是<b>建模</b> —— 它守的东西，行为测试一个都抓不到，
 * 这也正是那三个缺口能一直藏到对照官方文档时才被发现的原因。
 */
@DisplayName("M0 · 建模契约（V2）")
class LedgerModelingTest extends AbstractPostgresTest {

    private static final String USDT = "USDT";

    private long mint;
    private long alice;

    @BeforeEach
    void seedAccounts() {
        mint = createAccount("house:mint:USDT", USDT, "EQUITY", true);
        alice = createAccount("user:alice:USDT", USDT, "LIABILITY");
    }

    // ------------------------------------------------------------------
    // 缺口 1 · 余额不变量必须由数据库守，不能只由 Java 守
    // ------------------------------------------------------------------

    @Test
    @DisplayName("绕过 LedgerService 直接改余额为负 —— 数据库必须拒绝")
    void databaseRejectsNegativeBalanceEvenWhenServiceIsBypassed() {
        // 这里故意不走 ledger.transfer()，而是直接发 SQL。
        // 模拟的是：将来某个新接口、某个运维脚本、某条手工 SQL —— 任何绕过服务层的路径。
        //
        // 如果不变量只写在 Java 的 `if (balance < amount) throw` 里，这一步会成功，
        // 账本被写坏且没有任何报错。account_balance_ck 才是真正的最后一道防线。
        assertThatThrownBy(() ->
                jdbc.sql("UPDATE account SET balance = -1 WHERE id = :id")
                        .param("id", alice)
                        .update())
                .as("allow_negative=false 的账户，数据库必须在 SQL 层就拒绝负余额")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("account_balance_ck");
    }

    @Test
    @DisplayName("allow_negative=true 的资金来源账户可以为负")
    void fundingAccountsMayGoNegative() {
        ledger.transfer(new TransferCommand("t-seed", USDT, new BigDecimal("100"), mint, alice,
                TransferCode.SEED, null));

        // 复式记账里钱不能凭空出现：alice 多出来的 100，必然是 mint 少掉的 100。
        assertThat(ledger.balanceOf(mint)).isEqualByComparingTo("-100");
        assertThat(ledger.balanceOf(alice)).isEqualByComparingTo("100");
    }

    // ------------------------------------------------------------------
    // 新判官本身也要被验证：它得真的能抓到漂移
    // ------------------------------------------------------------------

    @Test
    @DisplayName("物化余额被人为改脏 —— balanceDrift 必须抓到")
    void balanceDriftDetectsTamperedMaterializedBalance() {
        ledger.transfer(new TransferCommand("t-seed", USDT, new BigDecimal("100"), mint, alice,
                TransferCode.SEED, null));
        assertThat(balanceDrift()).as("正常状态下不应有漂移").isZero();

        // 只改物化列，不动分录 —— 制造一次"缓存和事实不一致"。
        // 选 mint 是因为它 allow_negative=true，CHECK 约束不会先一步拦下来，
        // 这样才能验证 balance_consistency 这个判官本身有没有用。
        jdbc.sql("UPDATE account SET balance = balance + 999 WHERE id = :id")
                .param("id", mint)
                .update();

        assertThat(balanceDrift())
                .as("一个从不会失败的判官等于没有判官 —— 这里必须能抓到")
                .isEqualTo(1);

        // 还原，否则基类的 @AfterEach 会（正确地）报警
        jdbc.sql("UPDATE account SET balance = balance - 999 WHERE id = :id")
                .param("id", mint)
                .update();
        assertThat(balanceDrift()).isZero();
    }

    // ------------------------------------------------------------------
    // 缺口 2 / 3 · 业务类型（为什么）与业务发生时间（何时）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("业务类型与业务发生时间必须原样落库")
    void businessCodeAndOccurredAtArePersisted() {
        // 链上充值：区块时间在过去，写库时间是现在，两者必须分开记。
        Instant blockTime = Instant.parse("2026-01-15T08:30:00Z");

        ledger.transfer(new TransferCommand(
                "t-deposit", USDT, new BigDecimal("100"), mint, alice,
                TransferCode.DEPOSIT, blockTime));

        String code = jdbc.sql("SELECT code FROM transfer WHERE idempotency_key = 't-deposit'")
                .query(String.class).single();
        OffsetDateTime occurredAt =
                jdbc.sql("SELECT occurred_at FROM transfer WHERE idempotency_key = 't-deposit'")
                        .query(OffsetDateTime.class).single();
        OffsetDateTime createdAt =
                jdbc.sql("SELECT created_at FROM transfer WHERE idempotency_key = 't-deposit'")
                        .query(OffsetDateTime.class).single();

        assertThat(code).isEqualTo("DEPOSIT");
        assertThat(occurredAt.toInstant())
                .as("业务发生时间必须原样保留，不能被写库时间覆盖")
                .isEqualTo(blockTime);
        assertThat(createdAt.toInstant())
                .as("记账时间是现在，和业务发生时间不是一回事")
                .isAfter(blockTime);
    }

    @Test
    @DisplayName("occurredAt 传 null 时，不拿 created_at 顶替")
    void nullOccurredAtIsStoredAsNullNotBackfilled() {
        ledger.transfer(new TransferCommand("t-plain", USDT, new BigDecimal("10"), mint, alice,
                TransferCode.INTERNAL, null));

        String code = jdbc.sql("SELECT code FROM transfer WHERE idempotency_key = 't-plain'")
                .query(String.class).single();
        Long withOccurredAt = jdbc.sql("""
                        SELECT COUNT(*) FROM transfer
                        WHERE idempotency_key = 't-plain' AND occurred_at IS NOT NULL
                        """)
                .query(Long.class).single();

        assertThat(code).isEqualTo("INTERNAL");
        // NULL 在这里有明确含义：「没有独立的业务时间」。
        // 拿 created_at 去填它就是编造数据 —— 事后没人分得清哪个是真的。
        assertThat(withOccurredAt).as("occurred_at 应保持 NULL，不要用 created_at 顶替").isZero();
    }

    // ------------------------------------------------------------------
    // 加锁顺序 · 双向并发不能死锁
    // ------------------------------------------------------------------

    /**
     * A→B 与 B→A 同时高并发发生，必须全部成功，一笔都不能因死锁失败。
     *
     * <p><b>这个测试守的是 {@code lockBothInIdOrder} 里的那个排序。</b>
     * 在它存在之前，「按 id 升序加锁」这行代码没有任何测试证明它在防什么 ——
     * 把排序去掉，原有的全部测试照样绿，因为它们只有 alice→bob 一个方向。
     *
     * <p>失效场景：V2 让步骤 ⑦ 更新两行 balance，于是每个事务先后持有两把行锁。
     * 不排序时，A 持 alice 等 bob、B 持 bob 等 alice —— 循环等待，
     * PostgreSQL 在 deadlock_timeout 后杀掉其中一个，那笔转账失败。
     *
     * <p>这是「防护措施存在但无人验证」的修复 —— 和 {@code balanceDrift()}
     * 曾经无人调用是同一类问题的另一种形态。
     */
    @Test
    @DisplayName("A→B 与 B→A 并发 —— 按 id 升序加锁必须避免死锁")
    void bidirectionalTransfersMustNotDeadlock() {
        long bob = createAccount("user:bob:USDT", USDT, "LIABILITY");
        ledger.transfer(new TransferCommand("seed-alice", USDT, new BigDecimal("1000"), mint, alice,
                TransferCode.SEED, null));
        ledger.transfer(new TransferCommand("seed-bob", USDT, new BigDecimal("1000"), mint, bob,
                TransferCode.SEED, null));

        final int rounds = 60;
        AtomicInteger failed = new AtomicInteger();
        List<String> reasons = Collections.synchronizedList(new ArrayList<>());

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < rounds; i++) {
                final int n = i;
                // 两个方向同时发，制造互相持有对方所需锁的机会
                pool.submit(() -> attempt("ab-" + n, alice, bob, failed, reasons));
                pool.submit(() -> attempt("ba-" + n, bob, alice, failed, reasons));
            }
        }

        // 只报去重后的失败类型，不报每一条。
        // 死锁会连锁耗尽连接池（本项目实测：一次死锁引发近百条
        // CannotCreateTransactionException），全部打印出来的失败信息没法读，
        // 而真正有信息量的是「出现了哪几种失败」。
        assertThat(failed.get())
                .as("双向并发不应产生任何失败；出现过的失败类型：%s",
                        reasons.stream().distinct().limit(5).toList())
                .isZero();
        // 2 笔注资 + 2×rounds 笔互转，全部落库
        assertThat(transferCount()).isEqualTo(2L + 2L * rounds);
        // 一来一回金额相等，两边余额都回到 1000
        assertThat(ledger.balanceOf(alice)).isEqualByComparingTo("1000");
        assertThat(ledger.balanceOf(bob)).isEqualByComparingTo("1000");
    }

    private void attempt(String key, long from, long to, AtomicInteger failed, List<String> reasons) {
        try {
            ledger.transfer(new TransferCommand(
                    key, USDT, BigDecimal.ONE, from, to, TransferCode.INTERNAL, null));
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            // 只留异常类型和首行，完整堆栈对判断「是不是死锁」没有额外价值
            String firstLine = e.getMessage() == null ? "" : e.getMessage().split("\n")[0];
            reasons.add(e.getClass().getSimpleName() + ": " + firstLine);
        }
    }

    @Test
    @DisplayName("业务类型缺失必须被拒绝")
    void missingTransferCodeIsRejected() {
        assertThatThrownBy(() -> ledger.transfer(new TransferCommand(
                "t-nocode", USDT, new BigDecimal("10"), mint, alice, null, null)))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("业务类型");
    }
}
