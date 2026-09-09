package com.chainpay.chain.deposit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.security.crypto.SecretCipher;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 商户的收款接口（M3-④）：真 Tomcat、真签名、真 RLS。
 * 形状照币安 / OKX：地址接口幂等；入账列表把「在路上」的钱也列出来（PENDING + level + confirmations）；余额分 available 与 pending；
 * 金额一律字符串；HELD 的原因不外露；别人的地址与入账结构上查不到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("M3-④ · 收款接口")
class DepositApiTest extends AbstractDepositPostingTest {

    static final String ACME_CHECKSUMMED = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String EVILCO_CHECKSUMMED = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    @LocalServerPort
    private int port;

    @Autowired
    private SecretCipher cipher;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private String acmeSecret;
    private String evilSecret;

    @BeforeEach
    void seedCredentials() {
        acmeSecret = cipher.generateSecret();
        evilSecret = cipher.generateSecret();
        createCredential(acmeId, "ak_acme", acmeSecret);
        createCredential(evilcoId, "ak_evilco", evilSecret);
    }

    @Test
    @DisplayName("★ 申请收款地址：200，地址是 EIP-55 写法；再申请还是它；另一个商户拿到下一个序号的地址；列表能看到")
    void allocatesAnAddressIdempotently() {
        HttpResponse<String> first = signedPost(acmeSecret, "ak_acme", "/api/v1/deposit-addresses", "{\"token\":\"" + LINK + "\"}");
        HttpResponse<String> again = signedPost(acmeSecret, "ak_acme", "/api/v1/deposit-addresses", "{\"token\":\"" + LINK + "\"}");
        HttpResponse<String> other = signedPost(evilSecret, "ak_evilco", "/api/v1/deposit-addresses", "{\"token\":\"" + LINK.toUpperCase().replace("0X", "0x") + "\"}");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"code\":\"0\"").contains("\"address\":\"" + ACME_CHECKSUMMED + "\"").contains("\"symbol\":\"LINK\"");
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(other.body()).contains(EVILCO_CHECKSUMMED);
        assertThat(signedGet(acmeSecret, "ak_acme", "/api/v1/deposit-addresses").body())
                .contains(ACME_CHECKSUMMED).doesNotContain(EVILCO_CHECKSUMMED);
    }

    @Test
    @DisplayName("★ 代币不在白名单 → 400 + 2008；地址不成形 → 400 + 2001；没有凭证 → 401")
    void rejectsBadTokensAndAnonymousCallers() {
        HttpResponse<String> unknown = signedPost(acmeSecret, "ak_acme", "/api/v1/deposit-addresses", "{\"token\":\"0xcccccccccccccccccccccccccccccccccccccccc\"}");
        HttpResponse<String> malformed = signedPost(acmeSecret, "ak_acme", "/api/v1/deposit-addresses", "{\"token\":\"LINK\"}");
        HttpResponse<String> anonymous = send(HttpRequest.newBuilder().uri(url("/api/v1/deposit-addresses")).GET());

        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("\"code\":\"2008\"");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.body()).contains("\"code\":\"2001\"");
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("★ 入账列表：在路上的钱以 PENDING 列出并带 level 与 confirmations；记账后变 CREDITED；别的商户看不到")
    void listsPendingAndCreditedDeposits() {
        pay(5, TEN_LINK);                               // 会 FINAL
        pay(80, TEN_LINK);                              // SAFE
        pay(95, new BigInteger("5000000000000000000")); // SEEN
        indexUpTo(100, 90, 50);

        String before = signedGet(acmeSecret, "ak_acme", "/api/v1/deposits").body();
        assertThat(count(before, "\"status\":\"PENDING\"")).isEqualTo(3);
        assertThat(before).contains("\"level\":\"FINAL\"").contains("\"level\":\"SAFE\"").contains("\"level\":\"SEEN\"");
        assertThat(before).contains("\"confirmations\":96").contains("\"confirmations\":21").contains("\"confirmations\":6");
        assertThat(before).contains("\"amount\":\"10.000000000000000000\"").contains("\"amount\":\"5.000000000000000000\"");
        assertThat(before).contains("\"address\":\"" + ACME_CHECKSUMMED + "\"");

        poster().postOnce();
        String after = signedGet(acmeSecret, "ak_acme", "/api/v1/deposits").body();
        assertThat(count(after, "\"status\":\"PENDING\"")).isEqualTo(2);
        assertThat(count(after, "\"status\":\"CREDITED\"")).isEqualTo(1);
        assertThat(after).contains("\"blockNumber\":5");
        assertThat(after).containsPattern("\"status\":\"CREDITED\"[^}]*\"creditedAt\":\"20");   // 记账时间写上了

        assertThat(signedGet(evilSecret, "ak_evilco", "/api/v1/deposits").body()).contains("\"data\":[]");
        assertThat(signedGet(acmeSecret, "ak_acme", "/api/v1/deposits?status=CREDITED").body()).doesNotContain("PENDING");
        assertThat(signedGet(acmeSecret, "ak_acme", "/api/v1/deposits?token=0xcccccccccccccccccccccccccccccccccccccccc").body()).contains("\"data\":[]");
    }

    @Test
    @DisplayName("★ 余额：available 是账本余额，pending 是在路上的合计；记账前后各是各的")
    void balanceSplitsAvailableAndPending() {
        pay(5, TEN_LINK);
        pay(80, TEN_LINK);
        indexUpTo(100, 90, 50);

        String before = signedGet(acmeSecret, "ak_acme", "/api/v1/deposits/balance?token=" + LINK).body();
        assertThat(before).contains("\"available\":\"0.000000000000000000\"").contains("\"pending\":\"20.000000000000000000\"").contains("\"symbol\":\"LINK\"");

        poster().postOnce();
        String after = signedGet(acmeSecret, "ak_acme", "/api/v1/deposits/balance?token=" + LINK).body();
        assertThat(after).contains("\"available\":\"10.000000000000000000\"").contains("\"pending\":\"10.000000000000000000\"");

        assertThat(signedGet(evilSecret, "ak_evilco", "/api/v1/deposits/balance?token=" + LINK).body())
                .contains("\"available\":\"0").contains("\"pending\":\"0");
    }

    @Test
    @DisplayName("★ HELD 的入账只露状态，不露原因（原因里有节点与哈希细节）")
    void heldDepositsExposeStatusButNotTheReason() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        audit.tamperHash(5, com.chainpay.chain.support.FakeChain.hashOf(999));
        poster().postOnce();

        String body = signedGet(acmeSecret, "ak_acme", "/api/v1/deposits").body();

        assertThat(body).contains("\"status\":\"HELD_NODE_DISAGREE\"");
        assertThat(body).doesNotContain("审计").doesNotContain("hold_reason").doesNotContain("holdReason");
    }

    // ------------------------------------------------------------------ 脚手架

    @Test
    @DisplayName("★ 查询参数类型不对或缺失 —— 400，不是 500")
    void badQueryParametersAreBadRequests() {
        var badLimit = signedGet(acmeSecret, "ak_acme", "/api/v1/deposits?limit=abc");
        assertThat(badLimit.statusCode()).as(badLimit.body()).isEqualTo(400);
        assertThat(badLimit.body()).contains("\"code\":\"2001\"");

        var missingToken = signedGet(acmeSecret, "ak_acme", "/api/v1/deposits/balance");
        assertThat(missingToken.statusCode()).as(missingToken.body()).isEqualTo(400);
        assertThat(missingToken.body()).contains("\"code\":\"2001\"");
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
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
                .header("X-CP-API-SIGN", SignedRequests.sign(secret, ts, nonce, "GET", path, ""))
                .GET());
    }

    private HttpResponse<String> signedPost(String secret, String apiKey, String path, String body) {
        long ts = System.currentTimeMillis();
        String nonce = SignedRequests.newNonce();
        return send(HttpRequest.newBuilder().uri(url(path))
                .header("Content-Type", "application/json")
                .header("X-CP-API-KEY", apiKey).header("X-CP-API-TIMESTAMP", String.valueOf(ts)).header("X-CP-API-NONCE", nonce)
                .header("X-CP-API-SIGN", SignedRequests.sign(secret, ts, nonce, "POST", path, body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
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
