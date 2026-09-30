package com.chainpay.chain.payout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.payout.domain.SendResult;
import com.chainpay.chain.payout.service.PayoutSigningGate.Refusal;
import com.chainpay.chain.payout.service.PayoutSigningGate.Refused;
import com.chainpay.chain.payout.service.PayoutSigningGate.Result;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 签名闸口的复核（进程拆分 ③ 的 8a）：以应用角色直接写库，模拟被攻破的 web——库守放行的写法它都能做
 * （冻结一笔钱、插一行「放行」或「待核准」、往白名单里加地址），闸口要么照规矩签，要么按原因处置：
 * 像被攻破的 → 整把钱包停发；商户停用了地址 → 判失败解冻；超限没人核准 → 退回待核准。
 * 判失败与人工拒绝会按这一行的金额解冻，所以它们之前同样要核冻结分录。
 */
@SpringBootTest
@DisplayName("签名闸口：不信 web 写下的行——签名、判失败、拒绝之前都复核")
class PayoutSigningGateTest extends AbstractPayoutSendingTest {

    @Autowired
    private PayoutApprovalService approvals;

    /** 费率由发送任务在事务外问好再递进来；闸口只管签。 */
    private static final FeePolicy.Fees FEES = new FeePolicy.Fees(GWEI, GWEI.multiply(BigInteger.valueOf(21)), 60_000);

    private int forged;

    @Test
    @DisplayName("★ 冻结分录对不上（冻 1 LINK，行里写 5 LINK）：不签，整把钱包停发；这一笔原地不动，钱还冻着")
    void aForgedFreezeHaltsTheWallet() {
        long id = webWrites("5", "1", DEST);

        SendResult r = sender().sendOnce();

        assertThat(r.haltReason()).contains("冻结分录对不上").contains(String.valueOf(id));
        assertThat(wallet().get("status")).isEqualTo("HALTED");
        assertThat(payoutStatus(id)).isEqualTo("QUEUED");
        assertThat(attempts()).as("一笔都没签").isEmpty();
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("★ 收款地址是平台自己的（热钱包）：web 绕过申请接口直接把它加进白名单、插一行放行 → 不签，整把钱包停发")
    void aPlatformAddressTargetHaltsTheWallet() {
        tenantScope.asMerchant(acmeId, () -> appJdbc.sql("INSERT INTO payout_address (merchant_id, address) VALUES (:m, :a)")
                .param("m", acmeId).param("a", HOT).update());
        long id = webWrites("1", "1", HOT);

        SendResult r = sender().sendOnce();                                   // 这一轮先对账，热钱包那一行就在库里了

        assertThat(r.haltReason()).contains("是平台自己的地址").contains(String.valueOf(id));
        assertThat(payoutStatus(id)).isEqualTo("QUEUED");
        assertThat(attempts()).isEmpty();
    }

    @Test
    @DisplayName("★ 白名单里根本没有（外键被人拆了的兜底）：不签，整把钱包停发——闸口不把安全全押在外键上")
    void aTargetMissingFromTheWhitelistHaltsTheWallet() throws SQLException {
        String stranger = "0x4444444444444444444444444444444444444444";
        long freeze = tenantScope.asMerchant(acmeId, () -> new PayoutLedger(appJdbc, appLedger)
                .freeze("no-fk", "LINK", BigDecimal.ONE, userAccount, frozenAccount, Instant.now()));
        long id;
        try (Connection c = DriverManager.getConnection(jdbcUrl(), ownerUsername(), ownerPassword());
             Statement st = c.createStatement();
             PreparedStatement insert = c.prepareStatement("""
                     INSERT INTO payout (merchant_id, idempotency_key, token, to_address, amount, raw_value, status, freeze_transfer_id)
                     VALUES (?, 'no-fk', ?, ?, 1, 1000000000000000000, 'QUEUED', ?) RETURNING id
                     """)) {
            st.execute("SET session_replication_role = replica");             // 这条连接上外键的触发器不跑：模拟外键不在了
            insert.setLong(1, acmeId);
            insert.setString(2, LINK);
            insert.setString(3, stranger);
            insert.setLong(4, freeze);
            try (var rs = insert.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }

        SendResult r = sender().sendOnce();

        assertThat(r.haltReason()).contains("不在这个商户的白名单里").contains(String.valueOf(id));
        assertThat(attempts()).isEmpty();
    }

    @Test
    @DisplayName("★ 超过单笔上限却写成「放行」：不签，退回待核准；钱包不停，同一轮后面那笔正常的照签")
    void anOverLimitRowGoesBackToApprovalAndTheQueueMovesOn() {
        limits("2", "10");
        long tooBig = webWrites("5", "5", DEST);
        long normal = queued("1");

        SendResult r = sender().sendOnce();

        assertThat(r.haltReason()).isNull();
        assertThat(payoutStatus(tooBig)).isEqualTo("PENDING_APPROVAL");
        assertThat(payoutStatus(normal)).isEqualTo("BROADCAST");
        assertThat(wallet().get("status")).isEqualTo("ACTIVE");
        assertThat(balance("user:acme:LINK:frozen")).as("退回待核准不解冻：等人决定").isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("★ 有人核准就不看限额：退回的那一笔经管理员核准（核准标记写下是谁）后照签；它也不占当天的自动额度，后面的小额照样自动放行")
    void anApprovedRowIsSignedDespiteTheLimit() {
        limits("2", "3");
        long id = webWrites("5", "5", DEST);
        sender().sendOnce();
        assertThat(payoutStatus(id)).isEqualTo("PENDING_APPROVAL");

        approvals.approve(id, "ops");
        long small = queued("2");
        sender().sendOnce();

        assertThat(payoutStatus(id)).isEqualTo("BROADCAST");
        assertThat(jdbc.sql("SELECT approved_by FROM payout WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("ops");
        assertThat(payoutStatus(small)).as("核准过的 5 不算自动放行：当天自动放行 0 + 2 ≤ 3").isEqualTo("BROADCAST");
    }

    @Test
    @DisplayName("★ 同一商户两笔「放行」合起来撞穿当日额度：先到的签，后到的退回待核准（按商户串行核对，没有并发裂隙）")
    void theDailyLimitCountsWhatThisMerchantAlreadyReleased() {
        limits("2", "3");
        long first = queued("2");
        long second = queued("2");                                            // web 那边两笔各自都在额度内，合起来超了

        sender().sendOnce();

        assertThat(payoutStatus(first)).isEqualTo("BROADCAST");
        assertThat(payoutStatus(second)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("★ 商户在排队期间停用了收款地址：这一笔判失败、解冻、写明原因；钱包不停")
    void aDisabledAddressFailsThePayoutAndUnfreezes() {
        long id = queued("1");
        jdbc.sql("UPDATE payout_address SET status = 'DISABLED' WHERE merchant_id = :m AND address = :a").param("m", acmeId).param("a", DEST).update();

        SendResult r = sender().sendOnce();

        assertThat(r.failed()).isEqualTo(1);
        assertThat(payoutStatus(id)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_reason FROM payout WHERE id = :id").param("id", id).query(String.class).single()).contains("停用");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("0");
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("25");
        assertThat(wallet().get("status")).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ 判失败与拒绝：解冻的金额也来自 web 写的行

    @Test
    @DisplayName("★ 发不出去也先复核：一行「放行、金额 5、只冻了 1」估 gas 就 revert → 不解冻，整把钱包停发（不核的话多解冻 4，挪的是同商户在途提现的冻结款）")
    void aForgedRowThatCannotBeSentUnfreezesNothing() {
        queued("10");
        sender().sendOnce();                                                  // 同一商户一笔正常的在途提现：冻结账户里有 10
        long id = webWrites("5", "1", DEST);
        chain.failEstimateGas(3, "execution reverted: ERC20: transfer amount exceeds balance");   // 金额写得比热钱包余额大，估 gas 必然 revert

        SendResult r = sender().sendOnce();

        assertThat(r.haltReason()).contains("冻结分录对不上").contains(String.valueOf(id));
        assertThat(payoutStatus(id)).isEqualTo("QUEUED");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("11");
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("14");
    }

    @Test
    @DisplayName("★ 拒绝也不信 web 写的金额：一行「待核准、金额 5、只冻了 1」被人工拒绝 → 报错、不解冻，这一行原样留着")
    void rejectingAForgedRowUnfreezesNothing() {
        queued("10");
        long id = webWrites("5", "1", DEST, "PENDING_APPROVAL");

        assertThatThrownBy(() -> approvals.reject(id, "看着可疑"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("冻结分录对不上");

        assertThat(payoutStatus(id)).isEqualTo("PENDING_APPROVAL");
        assertThat(balance("user:acme:LINK:frozen")).isEqualByComparingTo("11");
        assertThat(balance("user:acme:LINK")).isEqualByComparingTo("14");
    }

    // ------------------------------------------------------------------ 锁：复核在钱包锁外，额度按商户排队

    @Test
    @DisplayName("★ 复核不等热钱包：热钱包被别的事务锁着，超限的这一笔照样当场退回待核准（系统连接等锁 1 秒就放弃——复核要是在钱包锁里，这里会抛）")
    void verificationDoesNotWaitForTheWallet() throws Exception {
        hotWalletRow();
        limits("2", "10");
        long id = webWrites("5", "5", DEST);

        Result r;
        try (AutoCloseable held = holdExclusiveLock("hot_wallet", Duration.ofSeconds(5))) {
            r = gate().sign(id, FEES);
        }

        assertThat(r).isInstanceOfSatisfying(Refused.class, refused -> assertThat(refused.kind()).isEqualTo(Refusal.BACK_TO_APPROVAL));
        assertThat(payoutStatus(id)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("★ 同一商户两笔同时复核：额度核对按商户排队，不会两笔都看见「当天还没放行过」而一起签出去")
    void sameMerchantVerificationsQueueUpOnTheMerchant() throws Exception {
        hotWalletRow();
        limits("2", "3");
        long first = queued("2");
        long second = queued("2");                                            // 各自都在额度内，合起来 4 > 3
        PayoutSigningGate g = gate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> a;
            Future<Result> b;
            AutoCloseable held = holdExclusiveLock("hot_wallet", Duration.ofSeconds(5));
            try {
                a = pool.submit(() -> g.sign(first, FEES));
                b = pool.submit(() -> g.sign(second, FEES));
                // 两个都卡住才放：一个复核完卡在热钱包，另一个卡在商户的额度锁上。
                // 没有额度锁的话，两个都复核通过（都看见当天放行过 0）、都卡在热钱包，放开后先后都签
                awaitSystemLockWaiters(2);
            } finally {
                held.close();
            }
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(List.of(payoutStatus(first), payoutStatus(second))).containsExactlyInAnyOrder("SIGNED", "PENDING_APPROVAL");
        assertThat(attempts()).hasSize(1);
    }

    // ------------------------------------------------------------------

    /** 被攻破的 web 能做的：以应用角色冻结一笔（冻结本身合法），再直接插一行「放行」——金额与收款地址由它写。 */
    private long webWrites(String amount, String frozen, String to) {
        return webWrites(amount, frozen, to, "QUEUED");
    }

    private long webWrites(String amount, String frozen, String to, String status) {
        String key = "forged-" + (++forged);
        long freeze = tenantScope.asMerchant(acmeId, () -> new PayoutLedger(appJdbc, appLedger)
                .freeze(key, "LINK", new BigDecimal(frozen), userAccount, frozenAccount, Instant.now()));
        BigDecimal value = new BigDecimal(amount);
        return tenantScope.asMerchant(acmeId, () -> appJdbc.sql("""
                        INSERT INTO payout (merchant_id, idempotency_key, token, to_address, amount, raw_value, status, freeze_transfer_id)
                        VALUES (:m, :k, :t, :to, :amount, :raw, :s, :f)
                        RETURNING id
                        """)
                .param("m", acmeId).param("k", key).param("t", LINK).param("to", to).param("amount", value)
                .param("raw", new BigDecimal(value.movePointRight(18).toBigIntegerExact())).param("s", status).param("f", freeze)
                .query(Long.class).single());
    }

    /** 热钱包那一行（平时由发送任务第一轮对账插入）。 */
    private void hotWalletRow() {
        jdbc.sql("INSERT INTO hot_wallet (address, chain, next_nonce) VALUES (:a, 'sepolia', 0)").param("a", HOT).update();
    }

    /** 等到系统角色有 n 条连接在等锁。系统连接等锁 1 秒就放弃，所以这里只等 800 毫秒。 */
    private void awaitSystemLockWaiters(int n) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(800).toNanos();
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE usename = 'chainpay_system' AND wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (waiting >= n) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("800 毫秒内没等到 " + n + " 条系统连接在等锁");
    }

    private void limits(String perTx, String daily) {
        jdbc.sql("UPDATE payout_limit SET per_tx_max = :p, daily_max = :d WHERE token = :t")
                .param("p", new BigDecimal(perTx)).param("d", new BigDecimal(daily)).param("t", LINK).update();
    }
}
