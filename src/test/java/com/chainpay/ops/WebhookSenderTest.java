package com.chainpay.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.ops.alert.Alert;
import com.chainpay.ops.alert.WebhookSender;
import com.chainpay.ops.alert.WebhookSender.Format;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** webhook 出口：真的 POST 到一个本地假接收端；四种载荷形状；非 2xx 算没送到；没配地址只打日志；日志与描述里只有主机名。 */
@DisplayName("M6-③ · webhook 出口")
class WebhookSenderTest {

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger respondWith = new AtomicInteger(200);
    private final ObjectMapper json = new ObjectMapper();
    private final Alert alert = new Alert("indexer", "UP", "DOWN", Map.of("reason", "书签块哈希与两个节点都不同"), Instant.parse("2026-09-14T12:00:00Z"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            received.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(respondWith.get(), -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook?token=SECRET-TOKEN");
    }

    @Test
    @DisplayName("★ generic：结构化 JSON，有 component / from / to / details / at 和一行 text")
    void genericPayload() {
        assertThat(new WebhookSender(Optional.of(url()), Format.GENERIC).send(alert)).isTrue();
        JsonNode body = json.readTree(received.getFirst());
        assertThat(body.get("component").asString()).isEqualTo("indexer");
        assertThat(body.get("from").asString()).isEqualTo("UP");
        assertThat(body.get("to").asString()).isEqualTo("DOWN");
        assertThat(body.get("details").get("reason").asString()).contains("书签");
        assertThat(body.get("text").asString()).contains("indexer").contains("DOWN");
    }

    @Test
    @DisplayName("slack / dingtalk / feishu 各是各的形状，正文里都有那一行 text")
    void chatShapes() {
        new WebhookSender(Optional.of(url()), Format.SLACK).send(alert);
        new WebhookSender(Optional.of(url()), Format.DINGTALK).send(alert);
        new WebhookSender(Optional.of(url()), Format.FEISHU).send(alert);
        JsonNode slack = json.readTree(received.get(0));
        JsonNode ding = json.readTree(received.get(1));
        JsonNode feishu = json.readTree(received.get(2));
        assertThat(slack.get("text").asString()).contains("indexer");
        assertThat(ding.get("msgtype").asString()).isEqualTo("text");
        assertThat(ding.get("text").get("content").asString()).contains("indexer");
        assertThat(feishu.get("msg_type").asString()).isEqualTo("text");
        assertThat(feishu.get("content").get("text").asString()).contains("indexer");
    }

    @Test
    @DisplayName("★ 非 2xx 或连不上 = 没送到（返回 false，交给策略下一轮再叫）")
    void failuresAreNotDelivered() {
        respondWith.set(500);
        assertThat(new WebhookSender(Optional.of(url()), Format.GENERIC).send(alert)).isFalse();
        assertThat(new WebhookSender(Optional.of(URI.create("http://127.0.0.1:1/hook")), Format.GENERIC).send(alert)).isFalse();
    }

    @Test
    @DisplayName("没配地址：不发 HTTP，只打日志，算送到（否则策略会每轮重试一个不存在的出口）")
    void unconfiguredLogsOnly() {
        assertThat(new WebhookSender(Optional.empty(), Format.GENERIC).send(alert)).isTrue();
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("★ 地址按密码对待：描述里只有主机，没有路径和查询串")
    void describesHostOnly() {
        assertThat(new WebhookSender(Optional.of(url()), Format.SLACK).describe()).contains("127.0.0.1").doesNotContain("SECRET-TOKEN").doesNotContain("/hook");
        assertThat(new WebhookSender(Optional.empty(), Format.GENERIC).describe()).contains("只打日志");
    }

    @Test
    @DisplayName("恢复的那条是 🟢，出事的是 🔴")
    void textMarksRecovery() {
        Alert recovered = new Alert("indexer", "DOWN", "UP", Map.of(), Instant.parse("2026-09-14T12:05:00Z"));
        new WebhookSender(Optional.of(url()), Format.SLACK).send(alert);
        new WebhookSender(Optional.of(url()), Format.SLACK).send(recovered);
        assertThat(json.readTree(received.get(0)).get("text").asString()).startsWith("🔴");
        assertThat(json.readTree(received.get(1)).get("text").asString()).startsWith("🟢");
    }
}
