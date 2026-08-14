package com.chainpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M0 的三个判官。现在全是红的 —— 你的任务是把它们变绿。
 *
 * <p><b>但不要直接改实现。</b>先按 {@code LEARNING-PATH.md} 第四节走第 ① 步：
 * 在 {@code docs/retro/M0-before.md} 里写下你认为这个账本会怎么坏。
 * 那份清单才是这个里程碑真正的产出。
 */
@DisplayName("M0 · 账本核心契约")
class LedgerInvariantTest extends AbstractPostgresTest {

    private static final String USDT = "USDT";

    @Autowired
    private LedgerService ledger;

    /** 资金来源账户。复式记账里钱不能凭空出现，注资时它是那个变负的对手方。 */
    private long mint;
    private long alice;
    private long bob;

    @BeforeEach
    void seedAccounts() {
        mint = createAccount("house:mint:USDT", USDT, "EQUITY", true);
        alice = createAccount("user:alice:USDT", USDT, "LIABILITY");
        bob = createAccount("user:bob:USDT", USDT, "LIABILITY");
    }

    // ------------------------------------------------------------------
    // 判官 1 · 不变量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("一笔转账之后：账本平、余额对")
    void singleTransferKeepsLedgerBalanced() {
        ledger.transfer(new TransferCommand("t-1", USDT, new BigDecimal("100"), mint, alice));

        assertThat(invariantViolations())
                .as("每种币的分录之和必须恒为 0")
                .isZero();
        assertThat(ledger.balanceOf(alice)).isEqualByComparingTo("100");
        assertThat(ledger.balanceOf(mint)).isEqualByComparingTo("-100");
    }

    @Test
    @DisplayName("余额不足必须拒绝，且不留下任何痕迹")
    void insufficientBalanceIsRejectedAtomically() {
        ledger.transfer(new TransferCommand("t-seed", USDT, new BigDecimal("10"), mint, alice));

        assertThat(catchTransfer("t-toomuch", new BigDecimal("11"), alice, bob))
                .as("余额 10 却要转 11，必须失败")
                .isTrue();

        // 失败的转账不能留下半条记录 —— 原子性
        assertThat(transferCount()).isEqualTo(1);
        assertThat(invariantViolations()).isZero();
        assertThat(illegalNegativeBalances()).isZero();
        assertThat(ledger.balanceOf(alice)).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------
    // 判官 2 · 幂等
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一个幂等键并发提交 50 次，只能产生 1 笔")
    void sameIdempotencyKeyProducesExactlyOneTransfer() {
        ledger.transfer(new TransferCommand("t-seed", USDT, new BigDecimal("1000"), mint, alice));

        final int attempts = 50;
        AtomicInteger succeeded = new AtomicInteger();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    // 同一个 key、同样的参数 —— 模拟客户端网络超时后的重试风暴
                    if (!catchTransfer("same-key", new BigDecimal("1"), alice, bob)) {
                        succeeded.incrementAndGet();
                    }
                });
            }
        }

        assertThat(transferCount())
                .as("幂等键 same-key 只能对应一笔转账，加上种子那笔共 2 笔")
                .isEqualTo(2);
        assertThat(ledger.balanceOf(bob))
                .as("bob 只应该收到 1，不是 50")
                .isEqualByComparingTo("1");
        assertThat(succeeded.get())
                .as("幂等意味着重复调用应当成功返回同一结果，而不是报错")
                .isEqualTo(attempts);
        assertThat(invariantViolations()).isZero();
    }

    // ------------------------------------------------------------------
    // 判官 3 · 并发（最狠的一个）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("余额 500，并发发起 1000 笔 1 元转账 —— 必须恰好成功 500 笔")
    void concurrentTransfersCannotOverdraw() {
        ledger.transfer(new TransferCommand("t-seed", USDT, new BigDecimal("500"), mint, alice));

        final int threads = 100;
        final int perThread = 10;   // 共 1000 次尝试，但只有 500 元可用
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                final int threadNo = t;
                pool.submit(() -> {
                    for (int j = 0; j < perThread; j++) {
                        String key = "c-" + threadNo + "-" + j;
                        if (catchTransfer(key, BigDecimal.ONE, alice, bob)) {
                            rejected.incrementAndGet();
                        } else {
                            ok.incrementAndGet();
                        }
                    }
                });
            }
        }

        // 这三条是本项目最重要的三条断言。
        //
        // 「先查余额，够就扣」的天真实现会在这里崩：
        // 100 个线程同时查到"余额够"，然后同时扣款 —— alice 会变成负数。
        // 这就是 check-then-act，flow-pay 里 Redis 验证码计数、
        // MySQL 付款单状态、账本余额三处踩的是同一个坑。
        assertThat(illegalNegativeBalances())
                .as("用户余额永远不能为负")
                .isZero();
        assertThat(ledger.balanceOf(alice))
                .as("500 元被花光，一分不多一分不少")
                .isEqualByComparingTo("0");
        assertThat(ok.get())
                .as("恰好 500 笔成功，另外 500 笔必须被拒")
                .isEqualTo(500);

        assertThat(rejected.get()).isEqualTo(threads * perThread - 500);
        assertThat(invariantViolations()).isZero();
    }

    // ------------------------------------------------------------------

    /** 执行一次转账，返回「是否抛异常」。true = 被拒绝。 */
    private boolean catchTransfer(String key, BigDecimal amount, long from, long to) {
        try {
            ledger.transfer(new TransferCommand(key, USDT, amount, from, to));
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
