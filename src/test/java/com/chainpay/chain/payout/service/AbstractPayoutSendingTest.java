package com.chainpay.chain.payout.service;

import com.chainpay.chain.support.FakeChain;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import com.chainpay.support.AbstractPostgresTest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 发送任务的脚手架：一个商户、25 LINK 可用余额、一把公开的测试热钱包（Hardhat #0）、一条有内存池的假链。
 * {@link #queued} 像 ④ 的接口那样先冻结再插申请行（同一事务的形状由 ④ 负责，这里分两步只是为了造数据）。
 */
abstract class AbstractPayoutSendingTest extends AbstractPostgresTest {

    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String DEST = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    static final String HOT_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    static final String HOT = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    static final long SEPOLIA = 11_155_111L;
    static final BigInteger GWEI = BigInteger.TEN.pow(9);

    @Autowired
    protected JdbcClient appJdbc;

    @Autowired
    protected LedgerService appLedger;

    protected FakeChain chain;
    protected HotWalletSigner signer;
    protected long acmeId;
    protected long userAccount;
    protected long frozenAccount;
    protected long custodyAccount;
    private int requests;

    @BeforeEach
    void seedPayoutScaffolding() {
        jdbc.sql("TRUNCATE payout_tx, payout, payout_address, hot_wallet, deposit, deposit_address CASCADE").update();
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();
        jdbc.sql("INSERT INTO chain_token(address, symbol, decimals) VALUES (:a, 'LINK', 18) ON CONFLICT (address) DO NOTHING").param("a", LINK).update();
        jdbc.sql("UPDATE chain_token SET status = 'ACTIVE' WHERE address = :a").param("a", LINK).update();
        acmeId = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('acme', 'Acme') RETURNING id").query(Long.class).single();
        custodyAccount = account("chain:custody:LINK", "ASSET", true, null);
        userAccount = account("user:acme:LINK", "LIABILITY", false, acmeId);
        frozenAccount = account("user:acme:LINK:frozen", "LIABILITY", false, acmeId);
        ledger.transfer(new TransferCommand("seed:acme", "LINK", new BigDecimal("25"), custodyAccount, userAccount, TransferCode.SEED, null));
        chain = new FakeChain().withBlocks(100);
        chain.quoteFees(GWEI.multiply(BigInteger.TEN), GWEI.multiply(BigInteger.valueOf(3)).divide(BigInteger.TWO));   // 基础费 10 gwei，小费 1.5 gwei
        chain.answerEstimateGas(52_000);
        signer = HotWalletSigner.fromHex(HOT_KEY);
        requests = 0;
    }

    /** 像 ④ 那样：先冻结，再插一条 QUEUED 的申请。 */
    protected long queued(String amount) {
        String key = "req-" + (++requests);
        BigDecimal value = new BigDecimal(amount);
        long freeze = tenantScope.asMerchant(acmeId, () -> new PayoutLedger(appJdbc, appLedger)
                .freeze(key, "LINK", value, userAccount, frozenAccount, Instant.now()));
        return jdbc.sql("""
                        INSERT INTO payout (merchant_id, idempotency_key, token, to_address, amount, raw_value, status, freeze_transfer_id)
                        VALUES (:m, :k, :t, :to, :amount, :raw, 'QUEUED', :f)
                        RETURNING id
                        """)
                .param("m", acmeId).param("k", key).param("t", LINK).param("to", DEST).param("amount", value)
                .param("raw", new BigDecimal(value.movePointRight(18).toBigIntegerExact())).param("f", freeze)
                .query(Long.class).single();
    }

    protected PayoutSender sender() {
        return new PayoutSender(systemLedger, chain, chain, signer, new FeePolicy(GWEI, GWEI.multiply(BigInteger.valueOf(50)), 200_000), SEPOLIA, 10);
    }

    /** 卡住多久算卡住由测试定；Duration.ZERO = 广播过的一律算卡住。 */
    protected PayoutSender sender(java.time.Duration stuckAfter) {
        return new PayoutSender(systemLedger, chain, chain, signer, new FeePolicy(GWEI, GWEI.multiply(BigInteger.valueOf(50)), 200_000),
                SEPOLIA, "sepolia", 10, stuckAfter);
    }

    /** 追踪任务：主节点与审计节点都是同一个假节点。 */
    protected PayoutTracker tracker() {
        return new PayoutTracker(systemLedger, chain, chain);
    }

    protected PayoutTracker tracker(FakeChain audit) {
        return new PayoutTracker(systemLedger, chain, audit);
    }

    protected long transfersWithKey(String idempotencyKey) {
        return jdbc.sql("SELECT count(*) FROM transfer WHERE idempotency_key = :k").param("k", idempotencyKey).query(Long.class).single();
    }

    protected String payoutStatus(long id) {
        return jdbc.sql("SELECT status FROM payout WHERE id = :id").param("id", id).query(String.class).single();
    }

    protected List<Map<String, Object>> attempts() {
        return jdbc.sql("SELECT id, payout_id, nonce, tx_hash, status, raw_tx FROM payout_tx ORDER BY nonce, id").query().listOfRows();
    }

    protected Map<String, Object> wallet() {
        return jdbc.sql("SELECT address, next_nonce, status, halt_reason FROM hot_wallet WHERE address = :a").param("a", HOT).query().singleRow();
    }

    protected BigDecimal balance(String code) {
        return jdbc.sql("SELECT balance FROM account WHERE code = :c").param("c", code).query(BigDecimal.class).single();
    }

    private long account(String code, String kind, boolean allowNegative, Long merchantId) {
        return jdbc.sql("INSERT INTO account(code, currency, kind, allow_negative, merchant_id) VALUES (:c, 'LINK', :k, :n, :m) RETURNING id")
                .param("c", code).param("k", kind).param("n", allowNegative).param("m", merchantId)
                .query(Long.class).single();
    }
}
