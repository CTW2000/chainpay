package com.chainpay.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.support.AbstractPostgresTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M6-⓪ · 探针真的在回环的管理端口上答，主端口上没有；不要令牌；细节里没有密钥。
 * 这里用真 HTTP 打真 Tomcat：分组、探针、端口这些都是配置，只有真实启动才验得到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("M6-⓪ · 健康探针")
class HealthProbesTest extends AbstractPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private Environment env;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    private int managementPort() {
        return Integer.parseInt(env.getRequiredProperty("local.management.port"));
    }

    private HttpResponse<String> get(int onPort, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + onPort + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("liveness：进程在就 UP，不要令牌")
    void livenessIsUpWithoutAToken() throws Exception {
        HttpResponse<String> r = get(managementPort(), "/actuator/health/liveness");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json.readTree(r.body()).get("status").asString()).isEqualTo("UP");
    }

    @Test
    @DisplayName("readiness：两个连接池都通才 UP，能看到 db 与 systemDb 两个部件")
    void readinessListsBothPools() throws Exception {
        HttpResponse<String> r = get(managementPort(), "/actuator/health/readiness");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(r.body());
        assertThat(body.get("status").asString()).isEqualTo("UP");
        assertThat(body.get("components").get("db").get("status").asString()).isEqualTo("UP");
        assertThat(body.get("components").get("systemDb").get("status").asString()).isEqualTo("UP");
        assertThat(body.get("components").get("systemDb").get("details").get("pool").asString()).isEqualTo("chainpay-system");
    }

    @Test
    @DisplayName("work：索引器、热钱包、判官（没配 = UNKNOWN）与 Redis（UP）并列，整组 UP")
    void workGroupListsTheWorkers() throws Exception {
        HttpResponse<String> r = get(managementPort(), "/actuator/health/work");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode c = json.readTree(r.body()).get("components");
        assertThat(c.get("indexer").get("status").asString()).isEqualTo("UNKNOWN");
        assertThat(c.get("hotWallet").get("status").asString()).isEqualTo("UNKNOWN");
        assertThat(c.get("audit").get("status").asString()).isEqualTo("UNKNOWN");
        assertThat(c.get("redis").get("status").asString()).isEqualTo("UP");
    }

    @Test
    @DisplayName("★ 主端口上没有 /actuator：探针不带令牌，所以它不能出现在对外的端口上")
    void nothingOnTheMainPort() throws Exception {
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(port, "/actuator/health/liveness").statusCode()).isEqualTo(404);
        assertThat(get(port, "/actuator/metrics").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("管理端口只绑回环地址；主端口默认也只绑回环（M6-②：容器里由 CHAINPAY_BIND_ADDRESS 放开）")
    void managementPortBindsLoopbackOnly() {
        assertThat(env.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        assertThat(env.getProperty("server.address")).as("宿主上直接跑 jar 时不该对所有网卡开放").isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("★ 细节里没有密码、没有连接串")
    void detailsLeakNoSecrets() throws Exception {
        String body = get(managementPort(), "/actuator/health").body();
        assertThat(body).doesNotContain("chainpay_app_dev").doesNotContain("chainpay_system_dev").doesNotContain("jdbc:postgresql")
                .doesNotContainIgnoringCase("password");
    }

    @Test
    @DisplayName("两个池的指标都在：hikaricp.connections 带 pool 标签能分出主池与系统池")
    void bothPoolsAreMetered() throws Exception {
        HttpResponse<String> r = get(managementPort(), "/actuator/metrics/hikaricp.connections");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("chainpay-system");
        assertThat(get(managementPort(), "/actuator/metrics/hikaricp.connections?tag=pool:chainpay-system").statusCode()).isEqualTo(200);
    }
}
