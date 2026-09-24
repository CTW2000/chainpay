package com.chainpay.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.audit.config.AuditProperties;
import com.chainpay.audit.service.AuditService;
import com.chainpay.chain.deposit.service.AbstractDepositPostingTest;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.ledger.system.SystemLedger;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 管理接口：没跑过 = stale；POST run 立刻跑一轮并把结论回给人；没令牌 401。对账服务由测试装配（主节点在测试里是 FakeChain）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuditApiTest.FakeAudit.class)
@DisplayName("对账管理接口")
class AuditApiTest extends AbstractDepositPostingTest {

    /** 静态的假节点：bean 在上下文启动时建，测试方法里再往它上面造链。 */
    @TestConfiguration
    static class FakeAudit {
        static final FakeChain PRIMARY = new FakeChain();
        static final FakeChain AUDIT = new FakeChain();

        @Bean
        AuditService auditService(SystemLedger system) {
            return new AuditService(system, PRIMARY, AUDIT, 10);
        }

        @Bean
        AuditProperties auditProperties() {
            return new AuditProperties(Duration.ofHours(1), 10);
        }
    }

    @LocalServerPort
    private int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void cleanAuditTables() {
        jdbc.sql("TRUNCATE audit_finding, audit_run CASCADE").update();
    }

    @Test
    @DisplayName("★ 从没跑过：GET 报 stale、没有上次；POST run 跑一轮回 OK；再 GET 不 stale；没令牌 401")
    void statusAndRunOnDemand() {
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        poster().postOnce();
        for (FakeChain c : java.util.List.of(FakeAudit.PRIMARY, FakeAudit.AUDIT)) {
            c.withBlocks(100);
            c.defineBalanceAt(LINK, ACME_ADDRESS, 50, TEN_LINK);
        }

        HttpResponse<String> before = adminGet("/admin/v1/audit");
        HttpResponse<String> run = adminPost("/admin/v1/audit/run");
        HttpResponse<String> after = adminGet("/admin/v1/audit");
        HttpResponse<String> anonymous = send(HttpRequest.newBuilder().uri(url("/admin/v1/audit")).GET());

        assertThat(before.statusCode()).isEqualTo(200);
        assertThat(before.body()).contains("\"stale\":true").contains("\"lastRun\":null");
        assertThat(run.statusCode()).as(run.body()).isEqualTo(200);
        assertThat(run.body()).contains("\"status\":\"OK\"").contains("\"finalizedNumber\":50").contains("\"findings\":[]");
        assertThat(after.body()).contains("\"stale\":false").contains("\"status\":\"OK\"");
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> adminGet(String path) {
        return send(HttpRequest.newBuilder().uri(url(path)).header(AdminAuthFilter.HEADER_ADMIN_SESSION, adminSessionToken()).GET());
    }

    private HttpResponse<String> adminPost(String path) {
        return send(HttpRequest.newBuilder().uri(url(path)).header(AdminAuthFilter.HEADER_ADMIN_SESSION, adminSessionToken()).POST(HttpRequest.BodyPublishers.noBody()));
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
