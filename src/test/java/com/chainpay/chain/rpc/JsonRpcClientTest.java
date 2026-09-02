package com.chainpay.chain.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON-RPC 客户端的契约。用 JDK 自带的 HttpServer 假扮节点，默认测试集不碰网络。
 *
 * <p>最要紧的一条：<b>JSON-RPC 的失败是 HTTP 200 里带一个 error 对象</b>。
 * 只看 HTTP 状态码的客户端会把节点报错当成成功——这和我们自己 API 的设计
 * （状态码与信封一起动）正好相反，写的时候很容易按习惯写错。
 */
@DisplayName("M2 · JSON-RPC 客户端")
class JsonRpcClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private volatile String cannedResponse;
    private volatile int cannedStatus = 200;

    @BeforeEach
    void startFakeNode() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = cannedResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(cannedStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeNode() {
        server.stop(0);
    }

    private JsonRpcClient client() {
        return new JsonRpcClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @Test
    @DisplayName("请求信封：jsonrpc=2.0、method、params、id 一个都不能少")
    void sendsAWellFormedEnvelope() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0xb147ec\"}";

        client().call("eth_blockNumber");

        JsonNode sent = new ObjectMapper().readTree(lastRequestBody.get());
        assertThat(sent.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(sent.get("method").asString()).isEqualTo("eth_blockNumber");
        assertThat(sent.get("params").isArray()).isTrue();
        assertThat(sent.has("id")).isTrue();
    }

    @Test
    @DisplayName("result 原样返回给调用方（十六进制不在这一层转换）")
    void returnsTheResultNode() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0xb147ec\"}";

        JsonNode result = client().call("eth_blockNumber");

        assertThat(result.asString()).isEqualTo("0xb147ec");
    }

    @Test
    @DisplayName("★ HTTP 200 + error 对象 —— 必须抛出，带上节点给的 code 和 message")
    void treatsJsonRpcErrorAsFailureEvenWithHttp200() {
        // 这正是 drpc 那种「chain is not available on free plan」的形状：HTTP 200，error.code=35
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"query returned more than 10000 results\"}}";

        assertThatThrownBy(() -> client().call("eth_getLogs"))
                .isInstanceOf(JsonRpcException.class)
                .satisfies(e -> {
                    var rpc = (JsonRpcException) e;
                    assertThat(rpc.code()).isEqualTo(-32602);
                    assertThat(rpc.getMessage()).contains("10000");
                });
    }

    @Test
    @DisplayName("★ 响应的 id 和请求对不上 —— 必须抛出，不能把别人的答案当自己的")
    void rejectsMismatchedId() {
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":\"0x1\"}";

        assertThatThrownBy(() -> client().call("eth_blockNumber"))
                .isInstanceOf(JsonRpcException.class)
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("HTTP 层失败（5xx / 非 JSON）—— 抛出，code 为空表示不是节点的业务错误")
    void wrapsTransportFailures() {
        cannedStatus = 502;
        cannedResponse = "<html>Bad Gateway</html>";

        assertThatThrownBy(() -> client().call("eth_blockNumber"))
                .isInstanceOf(JsonRpcException.class)
                .satisfies(e -> assertThat(((JsonRpcException) e).code()).isNull());
    }
}
