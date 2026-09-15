package com.chainpay.chain.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.deposit.service.AbstractDepositPostingTest;
import com.chainpay.security.filter.AdminAuthFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 管理接口：登记一笔注资、列出来；没令牌 401；不存在的哈希 404 带信封。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("M6-② · 注资登记管理接口")
class HotWalletFundingApiTest extends AbstractDepositPostingTest {

    static final String ADMIN_TOKEN = "chainpay-test-admin-token-not-for-prod";
    static final String HOT = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";
    static final String TX = "0x" + "a".repeat(64);

    @LocalServerPort
    private int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE hot_wallet CASCADE").update();
    }

    @Test
    @DisplayName("★ POST 登记（金额从日志来）→ GET 列出；再 POST 同一笔仍是同一行；没令牌 401；没有的哈希 404")
    void registerAndList() {
        chain.addTransfer(LINK, 30, ALICE, HOT, ONE_LINK, TX);
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        jdbc.sql("INSERT INTO hot_wallet (address, chain, next_nonce) VALUES (:a, 'test', 0)").param("a", HOT).update();

        HttpResponse<String> created = adminPost("/admin/v1/hot-wallet/fundings", "{\"txHash\":\"" + TX + "\",\"note\":\"演练\"}");
        HttpResponse<String> again = adminPost("/admin/v1/hot-wallet/fundings", "{\"txHash\":\"" + TX + "\"}");
        HttpResponse<String> listed = adminGet("/admin/v1/hot-wallet/fundings");
        HttpResponse<String> missing = adminPost("/admin/v1/hot-wallet/fundings", "{\"txHash\":\"0x" + "9".repeat(64) + "\"}");
        HttpResponse<String> anonymous = send(HttpRequest.newBuilder().uri(url("/admin/v1/hot-wallet/fundings")).GET());

        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        assertThat(created.body()).contains("\"rawValue\":\"1000000000000000000\"").contains("\"blockNumber\":30").contains("\"note\":\"演练\"");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(TX);
        assertThat(listed.body().split("\"txHash\"").length - 1).as("同一笔只有一行").isEqualTo(1);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":\"2011\"");
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> adminGet(String path) {
        return send(HttpRequest.newBuilder().uri(url(path)).header(AdminAuthFilter.HEADER_ADMIN_TOKEN, ADMIN_TOKEN).GET());
    }

    private HttpResponse<String> adminPost(String path, String json) {
        return send(HttpRequest.newBuilder().uri(url(path)).header(AdminAuthFilter.HEADER_ADMIN_TOKEN, ADMIN_TOKEN)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)));
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
