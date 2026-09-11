package com.chainpay.chain.payout.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.deposit.service.AbstractDepositPostingTest;
import com.chainpay.chain.payout.service.FeePolicy;
import com.chainpay.chain.payout.service.PayoutSender;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.chain.wallet.EthAddress;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.security.crypto.SecretCipher;
import com.chainpay.security.filter.AdminAuthFilter;
import com.chainpay.support.SignedRequests;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 商户提现接口：先登记白名单，再申请；申请 = 冻结 + 插行同一事务；超限或没定过限额进 PENDING_APPROVAL，管理接口核准或拒绝。
 * 钱从 M3 的真实路径进来（索引 → FINAL → 入账），再从这里申请出去。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("M4-④ · 提现接口：白名单、限额、申请、核准")
class WithdrawalApiTest extends AbstractDepositPostingTest {

    static final String DEST = "0x90f79bf6eb2c4f870365e785982e1f101e93b906";       // Hardhat #3：白名单里的收款人
    static final String ADMIN_TOKEN = "chainpay-test-admin-token-not-for-prod";
    static final String HOT_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    @LocalServerPort
    private int port;
    @Autowired
    private SecretCipher cipher;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String acmeSecret;
    private String evilSecret;

    @BeforeEach
    void seedCredentialsAndMoney() {
        acmeSecret = cipher.generateSecret();
        evilSecret = cipher.generateSecret();
        createCredential(acmeId, "ak_acme", acmeSecret);
        createCredential(evilcoId, "ak_evilco", evilSecret);
        pay(5, TEN_LINK);                                   // 10 LINK 走 M3 的真实路径进来：索引 → FINAL → 入账
        indexUpTo(100, 90, 50);
        poster().postOnce();
        assertThat(balanceOf("user:acme:LINK")).isEqualByComparingTo("10");
    }

    /** 提现表引用 merchant：别的测试类清场用 DELETE FROM merchant，这里留下的行会挡住它们。自己收拾。 */
    @AfterEach
    void cleanPayoutTables() {
        jdbc.sql("TRUNCATE payout_tx, payout, payout_address, payout_limit, hot_wallet CASCADE").update();
    }

    // ---------------------------------------------------------------- 白名单

    @Test
    @DisplayName("★ 登记白名单地址：200、EIP-55 写法、ACTIVE；再登记还是它；列表只看到自己的")
    void registersAnAddressIdempotently() {
        HttpResponse<String> first = signedPost(acmeSecret, "ak_acme", "/api/v1/withdrawal-addresses", "{\"address\":\"" + DEST + "\",\"label\":\"冷钱包\"}");
        HttpResponse<String> again = signedPost(acmeSecret, "ak_acme", "/api/v1/withdrawal-addresses", "{\"address\":\"" + DEST.toUpperCase().replace("0X", "0x") + "\"}");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"address\":\"" + EthAddress.checksummed(DEST) + "\"").contains("\"status\":\"ACTIVE\"").contains("冷钱包");
        assertThat(field(again.body(), "id")).isEqualTo(field(first.body(), "id"));
        assertThat(signedGet(acmeSecret, "ak_acme", "/api/v1/withdrawal-addresses").body()).contains(EthAddress.checksummed(DEST));
        assertThat(signedGet(evilSecret, "ak_evilco", "/api/v1/withdrawal-addresses").body()).doesNotContain(DEST.substring(2, 12));
    }

    @Test
    @DisplayName("★ 平台自己的收款地址不能进白名单 → 400 + 2010；地址不成形 → 400 + 2001")
    void rejectsPlatformAndMalformedAddresses() {
        HttpResponse<String> platform = signedPost(acmeSecret, "ak_acme", "/api/v1/withdrawal-addresses", "{\"address\":\"" + ACME_ADDRESS + "\"}");
        HttpResponse<String> malformed = signedPost(acmeSecret, "ak_acme", "/api/v1/withdrawal-addresses", "{\"address\":\"0x1234\"}");

        assertThat(platform.statusCode()).isEqualTo(400);
        assertThat(platform.body()).contains("\"code\":\"2010\"");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.body()).contains("\"code\":\"2001\"");
    }

    // ---------------------------------------------------------------- 申请

    @Test
    @DisplayName("★ 申请提现：200 + QUEUED；可用 → 冻结；余额接口分 available / frozen")
    void requestsAWithdrawalAndFreezesTheMoney() {
        whitelist(DEST);
        setLimit("10", "20");

        HttpResponse<String> r = withdraw(acmeSecret, "ak_acme", DEST, "1.5", "w-1");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"status\":\"QUEUED\"").contains("\"amount\":\"1.500000000000000000\"").contains("\"symbol\":\"LINK\"")
                .contains("\"toAddress\":\"" + EthAddress.checksummed(DEST) + "\"");
        assertThat(balanceOf("user:acme:LINK")).isEqualByComparingTo("8.5");
        assertThat(balanceOf("user:acme:LINK:frozen")).isEqualByComparingTo("1.5");
        assertThat(signedGet(acmeSecret, "ak_acme", "/api/v1/deposits/balance?token=" + LINK).body())
                .contains("\"available\":\"8.500000000000000000\"").contains("\"frozen\":\"1.500000000000000000\"");
    }

    @Test
    @DisplayName("★ 同幂等键重发得到同一笔；同键不同金额 → 409 + 4003；两次申请只冻一次")
    void idempotencyKeyReturnsTheSameWithdrawal() {
        whitelist(DEST);
        setLimit("10", "20");
        HttpResponse<String> first = withdraw(acmeSecret, "ak_acme", DEST, "1", "w-1");
        HttpResponse<String> replay = withdraw(acmeSecret, "ak_acme", DEST, "1", "w-1");
        HttpResponse<String> conflict = withdraw(acmeSecret, "ak_acme", DEST, "2", "w-1");

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(field(replay.body(), "id")).isEqualTo(field(first.body(), "id"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("\"code\":\"4003\"");
        assertThat(balanceOf("user:acme:LINK:frozen")).isEqualByComparingTo("1");
        assertThat(jdbc.sql("SELECT count(*) FROM payout").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 目标不在白名单 → 400 + 2009；停用后再申请 → 2009；平台收款地址 → 2010；钱一分没冻")
    void rejectsTargetsOutsideTheWhitelist() {
        setLimit("10", "20");
        HttpResponse<String> unlisted = withdraw(acmeSecret, "ak_acme", DEST, "1", "w-1");
        long id = Long.parseLong(field(whitelist(DEST), "id"));
        assertThat(withdraw(acmeSecret, "ak_acme", DEST, "1", "w-ok").statusCode()).isEqualTo(200);
        HttpResponse<String> disable = signedPost(acmeSecret, "ak_acme", "/api/v1/withdrawal-addresses/" + id + "/disable", "");
        HttpResponse<String> disabled = withdraw(acmeSecret, "ak_acme", DEST, "1", "w-2");
        HttpResponse<String> platform = withdraw(acmeSecret, "ak_acme", ACME_ADDRESS, "1", "w-3");

        assertThat(unlisted.statusCode()).isEqualTo(400);
        assertThat(unlisted.body()).contains("\"code\":\"2009\"");
        assertThat(disable.statusCode()).isEqualTo(200);
        assertThat(disabled.body()).contains("\"code\":\"2009\"");
        assertThat(platform.body()).contains("\"code\":\"2010\"");
        assertThat(balanceOf("user:acme:LINK:frozen")).as("只有 w-ok 那笔冻了").isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("★ 余额不够 → 4001；小数位超过代币的 decimals → 2004；都不插行")
    void rejectsUnaffordableAndTooPreciseAmounts() {
        whitelist(DEST);
        setLimit("100", "200");
        HttpResponse<String> poor = withdraw(acmeSecret, "ak_acme", DEST, "11", "w-1");
        HttpResponse<String> precise = withdraw(acmeSecret, "ak_acme", DEST, "1.0000000000000000001", "w-2");

        assertThat(poor.body()).contains("\"code\":\"4001\"");
        assertThat(precise.statusCode()).isEqualTo(400);
        assertThat(precise.body()).contains("\"code\":\"200");                      // 2001（形状）或 2004（金额）都是拒绝，不是 500
        assertThat(jdbc.sql("SELECT count(*) FROM payout").query(Long.class).single()).isZero();
        assertThat(balanceOf("user:acme:LINK")).isEqualByComparingTo("10");
    }

    // ---------------------------------------------------------------- 限额与核准

    @Test
    @DisplayName("★ 超单笔上限、超当日上限、没定过限额：都进 PENDING_APPROVAL，钱照样冻着")
    void limitsRouteToApproval() {
        whitelist(DEST);
        HttpResponse<String> noLimit = withdraw(acmeSecret, "ak_acme", DEST, "1", "w-0");
        assertThat(noLimit.body()).as("没定过限额 = 一律人工").contains("\"status\":\"PENDING_APPROVAL\"");

        setLimit("3", "4.5");
        HttpResponse<String> perTx = withdraw(acmeSecret, "ak_acme", DEST, "3.5", "w-1");
        HttpResponse<String> a = withdraw(acmeSecret, "ak_acme", DEST, "2", "w-2");
        HttpResponse<String> b = withdraw(acmeSecret, "ak_acme", DEST, "2", "w-3");
        HttpResponse<String> overDaily = withdraw(acmeSecret, "ak_acme", DEST, "1", "w-4");

        assertThat(perTx.body()).contains("\"status\":\"PENDING_APPROVAL\"");
        assertThat(a.body()).contains("\"status\":\"QUEUED\"");
        assertThat(b.body()).contains("\"status\":\"QUEUED\"");
        assertThat(overDaily.body()).as("当日已自动放行 2 + 2 = 4，再来 1 超过 4.5 → 转人工；等核准的 1 与 3.5 不占自动额度").contains("\"status\":\"PENDING_APPROVAL\"");
        assertThat(balanceOf("user:acme:LINK:frozen")).as("五笔无论排队还是待核准都冻着：1 + 3.5 + 2 + 2 + 1").isEqualByComparingTo("9.5");
    }

    @Test
    @DisplayName("★ 管理接口：待核准列表；核准 → QUEUED；拒绝 → REJECTED + 解冻 + 原因；再核准 → 409 + 4004；没令牌 → 401")
    void adminApprovesOrRejects() {
        whitelist(DEST);
        long approveMe = Long.parseLong(field(withdraw(acmeSecret, "ak_acme", DEST, "1", "w-1").body(), "id"));
        long rejectMe = Long.parseLong(field(withdraw(acmeSecret, "ak_acme", DEST, "2", "w-2").body(), "id"));
        assertThat(adminGet("/admin/v1/payouts/pending").body()).contains("\"id\":" + approveMe).contains("\"id\":" + rejectMe);

        HttpResponse<String> approved = adminPost("/admin/v1/payouts/" + approveMe + "/approve", "");
        HttpResponse<String> rejected = adminPost("/admin/v1/payouts/" + rejectMe + "/reject", "{\"reason\":\"收款人可疑\"}");
        HttpResponse<String> twice = adminPost("/admin/v1/payouts/" + rejectMe + "/approve", "");
        HttpResponse<String> anonymous = send(HttpRequest.newBuilder().uri(url("/admin/v1/payouts/" + approveMe + "/approve")).POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(payoutStatus(approveMe)).isEqualTo("QUEUED");
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(payoutStatus(rejectMe)).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT failure_reason FROM payout WHERE id = :id").param("id", rejectMe).query(String.class).single()).contains("收款人可疑");
        assertThat(balanceOf("user:acme:LINK:frozen")).as("拒绝的那 2 已解冻，核准的 1 还冻着").isEqualByComparingTo("1");
        assertThat(balanceOf("user:acme:LINK")).isEqualByComparingTo("9");
        assertThat(twice.statusCode()).isEqualTo(409);
        assertThat(twice.body()).contains("\"code\":\"4004\"");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(signedGet(acmeSecret, "ak_acme", "/api/v1/withdrawals?status=REJECTED").body()).contains("收款人可疑");
    }

    // ---------------------------------------------------------------- 列表与隔离

    @Test
    @DisplayName("★ 列表带链上状态与哈希：发送任务广播之后看到 BROADCAST 与 txHash；别的商户看不到")
    void listShowsChainProgressAndIsTenantScoped() {
        whitelist(DEST);
        setLimit("10", "20");
        long id = Long.parseLong(field(withdraw(acmeSecret, "ak_acme", DEST, "1", "w-1").body(), "id"));
        FakeChain node = new FakeChain().withBlocks(5);
        HotWalletSigner signer = HotWalletSigner.fromHex(HOT_KEY);
        new PayoutSender(systemLedger, node, node, signer, new FeePolicy(BigInteger.TEN.pow(9), BigInteger.TEN.pow(9).multiply(BigInteger.valueOf(50)), 200_000), 11_155_111L, 10).sendOnce();

        String mine = signedGet(acmeSecret, "ak_acme", "/api/v1/withdrawals?token=" + LINK).body();
        String theirs = signedGet(evilSecret, "ak_evilco", "/api/v1/withdrawals").body();

        assertThat(payoutStatus(id)).isEqualTo("BROADCAST");
        assertThat(mine).contains("\"status\":\"BROADCAST\"").contains("\"txHash\":\"0x");
        assertThat(theirs).doesNotContain("\"id\":" + id).contains("\"data\":[]");
    }

    // ---------------------------------------------------------------- 助手

    private String whitelist(String address) {
        HttpResponse<String> r = signedPost(acmeSecret, "ak_acme", "/api/v1/withdrawal-addresses", "{\"address\":\"" + address + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.body();
    }

    private void setLimit(String perTx, String daily) {
        HttpResponse<String> r = adminPut("/admin/v1/payout-limits/" + LINK, "{\"perTxMax\":\"" + perTx + "\",\"dailyMax\":\"" + daily + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    private HttpResponse<String> withdraw(String secret, String apiKey, String to, String amount, String key) {
        return signedPost(secret, apiKey, "/api/v1/withdrawals",
                "{\"token\":\"" + LINK + "\",\"toAddress\":\"" + to + "\",\"amount\":\"" + amount + "\",\"idempotencyKey\":\"" + key + "\"}");
    }

    private String payoutStatus(long id) {
        return jdbc.sql("SELECT status FROM payout WHERE id = :id").param("id", id).query(String.class).single();
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"?([^,\"}]+)").matcher(json);
        assertThat(m.find()).as("字段 " + name + " 在 " + json).isTrue();
        return m.group(1);
    }

    private void createCredential(long merchantId, String apiKey, String secret) {
        jdbc.sql("INSERT INTO api_credential(merchant_id, api_key, secret_encrypted) VALUES (:m, :k, :s)")
                .param("m", merchantId).param("k", apiKey).param("s", cipher.encrypt(secret)).update();
    }

    private HttpResponse<String> signedGet(String secret, String apiKey, String path) {
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        return send(HttpRequest.newBuilder().uri(url(path))
                .header("X-CP-API-KEY", apiKey).header("X-CP-API-TIMESTAMP", String.valueOf(ts)).header("X-CP-API-NONCE", nonce)
                .header("X-CP-API-SIGN", SignedRequests.sign(secret, ts, nonce, "GET", path, "")).GET());
    }

    private HttpResponse<String> signedPost(String secret, String apiKey, String path, String body) {
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        return send(HttpRequest.newBuilder().uri(url(path)).header("Content-Type", "application/json")
                .header("X-CP-API-KEY", apiKey).header("X-CP-API-TIMESTAMP", String.valueOf(ts)).header("X-CP-API-NONCE", nonce)
                .header("X-CP-API-SIGN", SignedRequests.sign(secret, ts, nonce, "POST", path, body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> adminPost(String path, String body) {
        return send(HttpRequest.newBuilder().uri(url(path)).header("Content-Type", "application/json")
                .header(AdminAuthFilter.HEADER_ADMIN_TOKEN, ADMIN_TOKEN).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> adminPut(String path, String body) {
        return send(HttpRequest.newBuilder().uri(url(path)).header("Content-Type", "application/json")
                .header(AdminAuthFilter.HEADER_ADMIN_TOKEN, ADMIN_TOKEN).PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> adminGet(String path) {
        return send(HttpRequest.newBuilder().uri(url(path)).header(AdminAuthFilter.HEADER_ADMIN_TOKEN, ADMIN_TOKEN).GET());
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 请求失败", e);
        }
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
