package com.chainpay.chain.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.indexer.domain.ChainHead;
import com.chainpay.chain.indexer.domain.HeadRef;
import com.chainpay.chain.indexer.repository.ChainHeadRepository;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.security.filter.AdminAuthFilter;
import com.chainpay.support.AbstractPostgresTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 「停下叫人」需要一个能被问到的地方。这个接口只读，挂在 /admin/ 下，和其它管理接口同一道门。
 * 测试里没配节点，所以进程视角是 NOT_CONFIGURED；表里的状态、书签、链头照样要给全。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("M2-⑥ 补丁 3 · 索引器状态接口")
class IndexerStatusEndpointTest extends AbstractPostgresTest {

    private static final String ADMIN_TOKEN = "chainpay-test-admin-token-not-for-prod";
    /** application.yml 里的 cursor-name。 */
    static final String CURSOR = "sepolia:link:transfer";

    @LocalServerPort
    private int port;

    @Autowired
    private ChainHeadRepository heads;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetChainTables() {
        jdbc.sql("TRUNCATE chain_transfer_log, indexer_cursor, chain_head, chain_reconcile, indexer_state").update();
    }

    @Test
    @DisplayName("★ 没有管理员令牌：401，和其它管理接口同一道门")
    void requiresTheAdminToken() {
        assertThat(get(null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("★ 一次给全：表里的状态与原因、书签、三个头、落后块数、争议块数；没配节点时进程视角是 NOT_CONFIGURED")
    void reportsPersistedState() {
        jdbc.sql("INSERT INTO indexer_cursor (name, last_block_number, last_block_hash, start_block) VALUES (:n, 100, :h, 0)")
                .param("n", CURSOR).param("h", FakeChain.hashOf(100)).update();
        heads.insert("test", new ChainHead(new HeadRef(130, FakeChain.hashOf(130)),
                new HeadRef(120, FakeChain.hashOf(120)), new HeadRef(110, FakeChain.hashOf(110))));
        jdbc.sql("INSERT INTO chain_reconcile (block_number, block_hash, expected, found, repaired, orphaned, disputed) VALUES (50, :h, 1, 1, 0, 0, 1)")
                .param("h", FakeChain.hashOf(50)).update();
        jdbc.sql("INSERT INTO indexer_state (name, status, reason) VALUES (:n, 'HALTED', '两个节点对 finalized 块 110 意见不同')")
                .param("n", CURSOR).update();

        HttpResponse<String> response = get(ADMIN_TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = json.readTree(response.body()).get("data");
        assertThat(data.get("status").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(data.get("persistedStatus").asString()).isEqualTo("HALTED");
        assertThat(data.get("reason").asString()).contains("意见不同");
        assertThat(data.get("cursorName").asString()).isEqualTo(CURSOR);
        assertThat(data.get("cursorBlock").asLong()).isEqualTo(100);
        assertThat(data.get("latestBlock").asLong()).isEqualTo(130);
        assertThat(data.get("finalizedBlock").asLong()).isEqualTo(110);
        assertThat(data.get("lagBlocks").asLong()).isEqualTo(30);
        assertThat(data.get("disputedBlocks").asLong()).isEqualTo(1);
        assertThat(data.get("auditMode").asString()).isNotBlank();
    }

    private HttpResponse<String> get(String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/v1/indexer")).GET();
        if (token != null) {
            builder.header(AdminAuthFilter.HEADER_ADMIN_TOKEN, token);
        }
        try {
            return http.send(builder.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 请求失败", e);
        }
    }
}
