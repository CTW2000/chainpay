package com.chainpay.chain.deposit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.deposit.domain.DepositAddress;
import com.chainpay.chain.deposit.repository.DepositAddressRepository;
import com.chainpay.chain.indexer.repository.ChainTokenRepository;
import com.chainpay.chain.wallet.DepositAddressDeriver;
import com.chainpay.support.AbstractPostgresTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分配收款地址：一户一币一址，序号来自序列，唯一性由约束裁决，地址表是租户边界。
 *
 * <p>xpub 是 Hardhat 公开助记词的账户层 xpub（测试基类钉入），所以序号 0、1、2 派出的地址是公开常数：
 * 从「商户申请地址」到「库里那一行」整条链路都有已知答案。
 */
@SpringBootTest
@DisplayName("M3-①b · 收款地址分配")
class DepositAddressServiceTest extends AbstractPostgresTest {

    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String UNKNOWN_TOKEN = "0xcccccccccccccccccccccccccccccccccccccccc";
    static final String HARDHAT_0 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266".toLowerCase(Locale.ROOT);
    static final String HARDHAT_1 = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8".toLowerCase(Locale.ROOT);

    @Autowired
    private DepositAddressService service;

    @Autowired
    private JdbcClient appJdbc;

    @Autowired
    private DepositAddressDeriver deriver;

    @Autowired
    private ChainTokenRepository tokens;

    @Autowired
    private PlatformTransactionManager txManager;

    private long acmeId;
    private long evilcoId;

    @BeforeEach
    void seedMerchants() {
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();
        acmeId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('acme', 'Acme') RETURNING id").query(Long.class).single();
        evilcoId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('evilco', 'Evil') RETURNING id").query(Long.class).single();
        jdbc.sql("ALTER SEQUENCE deposit_address_index_seq RESTART WITH 0").update();
        jdbc.sql("DELETE FROM chain_token WHERE address <> :link").param("link", LINK).update();
        jdbc.sql("UPDATE chain_token SET status = 'ACTIVE' WHERE address = :link").param("link", LINK).update();
    }

    @Test
    @DisplayName("★ 第一次分配：序号 0 → Hardhat 的第一个地址；账本账户 user:acme:LINK 顺手建好并挂在行上")
    void allocatesTheFirstAddress() {
        DepositAddress allocated = tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, LINK));

        assertThat(allocated.address()).isEqualTo(HARDHAT_0);
        assertThat(allocated.derivationIndex()).isZero();
        assertThat(allocated.isActive()).isTrue();
        assertThat(allocated.merchantId()).isEqualTo(acmeId);
        assertThat(jdbc.sql("SELECT code || ' ' || currency || ' ' || kind || ' ' || merchant_id FROM account WHERE id = :id")
                .param("id", allocated.accountId()).query(String.class).single())
                .isEqualTo("user:acme:LINK LINK LIABILITY " + acmeId);
        assertThat(jdbc.sql("SELECT count(*) FROM deposit_address").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 幂等：同一商户同一代币再申请，还是那个地址、还是一行；序号没有被多消耗，下一个商户拿到序号 1")
    void isIdempotentPerMerchantAndToken() {
        DepositAddress first = tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, LINK));
        DepositAddress again = tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, LINK));
        DepositAddress other = tenantScope.asMerchant(evilcoId, () -> service.allocate(evilcoId, LINK));

        assertThat(again).isEqualTo(first);
        assertThat(other.address()).isEqualTo(HARDHAT_1);
        assertThat(other.derivationIndex()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM deposit_address").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM account WHERE code = 'user:acme:LINK'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 并发：八个线程同时为同一商户申请，全部拿到同一个地址，表里恰好一行（check-then-act 第 9 次候选位）")
    void concurrentAllocationsYieldExactlyOneAddress() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, LINK)).address());
            }
            List<String> results = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(tasks)) {
                results.add(f.get());
            }
            assertThat(results).containsOnly(HARDHAT_0);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM deposit_address").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 插队（确定性）：A 查过「没有」并取走序号 0 后停住，B 先插入并提交；A 的插入被 ON CONFLICT 吞掉，读回 B 的地址，序号 0 作废")
    void loserOfTheRaceReadsBackTheWinnersAddress() throws Exception {
        // 预先建好账户：否则 A 未提交的账户行会让 B 的 ON CONFLICT (code) 等 A 提交，而 A 在等 B——死锁
        jdbc.sql("INSERT INTO account(code, currency, kind, merchant_id) VALUES ('user:acme:LINK', 'LINK', 'LIABILITY', :m)")
                .param("m", acmeId).update();
        CountDownLatch aTookItsIndex = new CountDownLatch(1);
        CountDownLatch bCommitted = new CountDownLatch(1);
        DepositAddressRepository pausingAfterNextIndex = new DepositAddressRepository(appJdbc) {
            @Override
            public long nextIndex() {
                long index = super.nextIndex();
                aTookItsIndex.countDown();
                await(bCommitted);
                return index;
            }
        };
        DepositAddressService slowService = new DepositAddressService(deriver, pausingAfterNextIndex, tokens, new TransactionTemplate(txManager));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<DepositAddress> a = pool.submit(() -> tenantScope.asMerchant(acmeId, () -> slowService.allocate(acmeId, LINK)));
            assertThat(aTookItsIndex.await(10, TimeUnit.SECONDS)).as("A 已过「先查」并取走序号").isTrue();

            DepositAddress b = tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, LINK));
            bCommitted.countDown();
            DepositAddress aResult = a.get(10, TimeUnit.SECONDS);

            assertThat(b.derivationIndex()).as("A 拿走了 0，B 拿到 1").isEqualTo(1);
            assertThat(b.address()).isEqualTo(HARDHAT_1);
            assertThat(aResult).as("输的一方读回赢家的行").isEqualTo(b);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM deposit_address").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT derivation_index FROM deposit_address").query(Long.class).single()).as("序号 0 作废，跳号无害").isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("★ 地址表是租户边界：别的商户查不到、也写不进别人的地址；不进作用域一行都看不到")
    void rowLevelSecurityGuardsTheAddressTable() {
        tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, LINK));

        long seenByEvilco = tenantScope.asMerchant(evilcoId, () ->
                appJdbc.sql("SELECT count(*) FROM deposit_address").query(Long.class).single());
        long seenByAcme = tenantScope.asMerchant(acmeId, () ->
                appJdbc.sql("SELECT count(*) FROM deposit_address").query(Long.class).single());
        long seenOutsideScope = appJdbc.sql("SELECT count(*) FROM deposit_address").query(Long.class).single();

        assertThat(seenByAcme).isEqualTo(1);
        assertThat(seenByEvilco).isZero();
        assertThat(seenOutsideScope).isZero();
        long acmeAccount = jdbc.sql("SELECT account_id FROM deposit_address").query(Long.class).single();
        assertThatThrownBy(() -> tenantScope.asMerchant(evilcoId, () -> appJdbc.sql("""
                        INSERT INTO deposit_address(address, merchant_id, token, account_id, derivation_index)
                        VALUES ('0x1111111111111111111111111111111111111111', :acme, :link, :account, 777)
                        """).param("acme", acmeId).param("link", LINK).param("account", acmeAccount).update()))
                .as("WITH CHECK：写别人的行被策略拒绝").isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("★ 代币不在白名单或已停用：拒绝，不分配、不建账户")
    void rejectsUnregisteredOrDisabledToken() {
        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, UNKNOWN_TOKEN)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("未登记");
        jdbc.sql("UPDATE chain_token SET status = 'DISABLED' WHERE address = :link").param("link", LINK).update();
        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, LINK)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("停用");
        assertThat(jdbc.sql("SELECT count(*) FROM deposit_address").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM account").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("停用的商户不分配地址")
    void rejectsSuspendedMerchant() {
        jdbc.sql("UPDATE merchant SET status = 'SUSPENDED' WHERE id = :id").param("id", acmeId).update();

        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, LINK)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("停用");
    }

    @Test
    @DisplayName("★ 派生出的地址已被占用：不是「再取一个序号」，是 xpub 配错或序号重用，必须报出来")
    void detectsAnOccupiedAddress() {
        long evilcoAccount = jdbc.sql("INSERT INTO account(code, currency, kind, merchant_id) VALUES ('user:evilco:LINK', 'LINK', 'LIABILITY', :m) RETURNING id")
                .param("m", evilcoId).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO deposit_address(address, merchant_id, token, account_id, derivation_index)
                        VALUES (:address, :m, :link, :account, 999)
                        """).param("address", HARDHAT_0).param("m", evilcoId).param("link", LINK).param("account", evilcoAccount).update();

        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> service.allocate(acmeId, LINK)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("xpub");
    }

    @Test
    @DisplayName("★ 白名单里 ACTIVE 代币的 symbol 唯一（账本币种名用 symbol）；停用的可以重名")
    void activeSymbolsAreUniqueInTheWhitelist() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO chain_token(address, symbol, decimals) VALUES ('0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'LINK', 18)").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("INSERT INTO chain_token(address, symbol, decimals, status) VALUES ('0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'LINK', 18, 'DISABLED')").update();
        assertThat(jdbc.sql("SELECT count(*) FROM chain_token WHERE symbol = 'LINK'").query(Long.class).single()).isEqualTo(2);
    }
}
