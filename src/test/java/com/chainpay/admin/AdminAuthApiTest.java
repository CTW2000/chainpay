package com.chainpay.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.security.filter.AdminAuthFilter;
import com.chainpay.support.AbstractPostgresTest;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 控制面的门是管理员会话：登录拿令牌 → 带令牌调管理接口 → 敏感操作要再认证 → 每次调用留审计行；已删除的静态令牌头一律不认。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("管理员会话接口")
class AdminAuthApiTest extends AbstractPostgresTest {

    static final String PASSWORD = "ops-test-password-123!";

    @LocalServerPort
    private int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE admin_action, admin_session, admin_user CASCADE").update();
        // 只清本测试建的商户：全套里别的类会留下带凭证的商户，整表 DELETE 会撞外键
        jdbc.sql("DELETE FROM api_credential WHERE merchant_id IN (SELECT id FROM merchant WHERE code = 'acme')").update();
        jdbc.sql("DELETE FROM merchant WHERE code = 'acme'").update();
    }

    @Test
    @DisplayName("★ 登录 → 令牌只在这一次响应里；带令牌能调；退出后 401；旧的静态令牌头一律 401")
    void loginUseLogout() {
        adminAuth.createUser("ops", PASSWORD);
        HttpResponse<String> login = post("/admin/v1/auth/login", "{\"username\":\"ops\",\"password\":\"" + PASSWORD + "\"}", null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        JsonNode body = json.readTree(login.body()).get("data");
        String token = body.get("token").asString();
        assertThat(body.get("username").asString()).isEqualTo("ops");
        assertThat(body.get("expiresAt").asString()).isNotBlank();

        assertThat(get("/admin/v1/indexer", token).statusCode()).isEqualTo(200);
        HttpResponse<String> me = get("/admin/v1/auth/me", token);
        assertThat(json.readTree(me.body()).get("data").get("reauthFresh").asBoolean()).isTrue();

        assertThat(get("/admin/v1/indexer", null).statusCode()).as("没令牌").isEqualTo(401);
        assertThat(get("/admin/v1/indexer", "not-a-real-token").statusCode()).as("假令牌").isEqualTo(401);
        HttpResponse<String> legacy = send(HttpRequest.newBuilder().uri(url("/admin/v1/indexer")).header("X-CP-ADMIN-TOKEN", "chainpay-test-admin-token-not-for-prod").GET());
        assertThat(legacy.statusCode()).as("静态令牌头彻底不认").isEqualTo(401);

        assertThat(post("/admin/v1/auth/logout", "", token).statusCode()).isEqualTo(200);
        assertThat(get("/admin/v1/indexer", token).statusCode()).as("退出后").isEqualTo(401);
    }

    @Test
    @DisplayName("★ 错口令 401 且信封不说是哪个错；登录接口本身也只认回环 + 无代理头")
    void loginFailures() {
        adminAuth.createUser("ops", PASSWORD);
        HttpResponse<String> wrong = post("/admin/v1/auth/login", "{\"username\":\"ops\",\"password\":\"nope-nope-nope-nope\"}", null);
        HttpResponse<String> noUser = post("/admin/v1/auth/login", "{\"username\":\"ghost\",\"password\":\"nope-nope-nope-nope\"}", null);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).isEqualTo(noUser.body());
        HttpResponse<String> proxied = send(HttpRequest.newBuilder().uri(url("/admin/v1/auth/login")).header("Content-Type", "application/json")
                .header("X-Forwarded-For", "203.0.113.9").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"ops\",\"password\":\"" + PASSWORD + "\"}")));
        assertThat(proxied.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("★ 敏感操作（发凭证、建商户）要最近 5 分钟内再认证：把 reauth_at 拨回 6 分钟前 → 403 + 3002；POST reauth 后放行")
    void sensitiveOperationsAreGatedByReauth() {
        adminAuth.createUser("ops", PASSWORD);
        String token = adminSessionToken("ops", PASSWORD);
        jdbc.sql("UPDATE admin_session SET reauth_at = now() - interval '6 minutes'").update();

        HttpResponse<String> gated = post("/admin/v1/merchants", "{\"code\":\"acme\",\"name\":\"Acme\"}", token);
        assertThat(gated.statusCode()).isEqualTo(403);
        assertThat(gated.body()).contains("\"code\":\"3002\"");
        assertThat(jdbc.sql("SELECT count(*) FROM merchant WHERE code = 'acme'").query(Long.class).single()).as("被拦住就什么都没建").isZero();
        assertThat(get("/admin/v1/indexer", token).statusCode()).as("只读接口不受再认证影响").isEqualTo(200);

        assertThat(post("/admin/v1/auth/reauth", "{\"password\":\"" + PASSWORD + "\"}", token).statusCode()).isEqualTo(200);
        assertThat(post("/admin/v1/merchants", "{\"code\":\"acme\",\"name\":\"Acme\"}", token).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("★ 每次管理调用都在 admin_action 里留一行：谁、什么方法、哪条路径、回了几、从哪来")
    void everyAdminCallIsRecorded() {
        adminAuth.createUser("ops", PASSWORD);
        String token = adminSessionToken("ops", PASSWORD);
        get("/admin/v1/indexer", token);
        get("/admin/v1/indexer", "bad-token");
        var rows = jdbc.sql("SELECT username, method, path, status, remote_addr FROM admin_action WHERE path = '/admin/v1/indexer' ORDER BY id").query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("username", "ops").containsEntry("method", "GET").containsEntry("status", 200);
        assertThat(rows.get(1)).containsEntry("status", 401);
        assertThat(rows.get(1).get("username")).isNull();
    }

    private HttpResponse<String> get(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(url(path)).GET();
        if (token != null) {
            b.header(AdminAuthFilter.HEADER_ADMIN_SESSION, token);
        }
        return send(b);
    }

    private HttpResponse<String> post(String path, String body, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(url(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header(AdminAuthFilter.HEADER_ADMIN_SESSION, token);
        }
        return send(b);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 请求失败", e);
        }
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
