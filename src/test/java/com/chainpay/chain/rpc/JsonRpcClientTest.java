package com.chainpay.chain.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON-RPC 客户端的契约。用 JDK 自带的 HttpServer 假扮节点，默认测试集不碰网络。
 *
 * <p>最要紧的一条：<b>JSON-RPC 的失败是 HTTP 200 里带一个 error 对象</b>。
 * 只看 HTTP 状态码的客户端会把节点报错当成成功——这和我们自己 API 的设计
 * （状态码与信封一起动）正好相反，写的时候很容易按习惯写错。
 *
 * <p>M2-⑤ 加的两条守的是「读法的洞」：JDK 的请求超时只管到响应头到达，正文滴流它不管；
 * 正文没有上限，坏节点可以一直发到我们内存耗尽——M1.5 在鉴权过滤器里堵过的同一个坑。
 */
@DisplayName("M2 · JSON-RPC 客户端")
class JsonRpcClientTest {

    private enum Mode { CANNED, TRICKLE, ENDLESS }

    private static final int ENDLESS_CAP_BYTES = 24 * 1024 * 1024;   // 假节点最多发这么多，比客户端上限大

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final CountDownLatch stall = new CountDownLatch(1);
    private volatile Mode mode = Mode.CANNED;
    private volatile String cannedResponse;
    private volatile int cannedStatus = 200;

    @BeforeEach
    void startFakeNode() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            switch (mode) {
                case CANNED -> {
                    byte[] body = cannedResponse.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(cannedStatus, body.length);
                    exchange.getResponseBody().write(body);
                }
                case TRICKLE -> {                                   // 头到了，正文只来一个字节，然后按住
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream out = exchange.getResponseBody();
                    out.write('{');
                    out.flush();
                    try {
                        stall.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                case ENDLESS -> {                                   // 头到了，正文一直发，直到对方挂断
                    exchange.sendResponseHeaders(200, 0);
                    byte[] chunk = new byte[64 * 1024];
                    Arrays.fill(chunk, (byte) 'x');
                    try (OutputStream out = exchange.getResponseBody()) {
                        for (int sent = 0; sent < ENDLESS_CAP_BYTES; sent += chunk.length) {
                            out.write(chunk);
                        }
                    } catch (IOException clientHungUp) {
                        // 正是我们想要的：客户端读够上限就断开
                    }
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeNode() {
        stall.countDown();
        server.stop(0);
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private JsonRpcClient client() {
        return new JsonRpcClient(endpoint());
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
    @DisplayName("★ HTTP 400 + JSON-RPC error 对象（Alchemy 免费档就这么报上限）—— 必须带着 code 抛出，不能当成传输失败")
    void surfacesTheErrorCodeEvenWhenHttpStatusIsNot2xx() {
        // 2026-09-03 实测：Alchemy 免费档 eth_getLogs 超过 10 块，HTTP 400 + error.code=-32600。
        // 先看状态码的客户端会把 code 丢掉，对半分永远不会触发，只会每 12 秒「瞬时失败」。
        cannedStatus = 400;
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32600,\"message\":\"Under the Free tier plan, you can make eth_getLogs requests with up to a 10 block range\"}}";

        assertThatThrownBy(() -> client().call("eth_getLogs"))
                .isInstanceOf(JsonRpcException.class)
                .satisfies(e -> {
                    var rpc = (JsonRpcException) e;
                    assertThat(rpc.code()).isEqualTo(-32600);
                    assertThat(rpc.getMessage()).contains("10 block");
                });
    }

    @Test
    @DisplayName("★ HTTP 429 限流（Alchemy 也带 JSON error 对象）—— 是瞬时失败，code 必须为空，不能触发对半分")
    void treatsRateLimitingAsTransientEvenWithAnErrorBody() {
        cannedStatus = 429;
        cannedResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":429,\"message\":\"Your app has exceeded its compute units per second capacity\"}}";

        assertThatThrownBy(() -> client().call("eth_getLogs"))
                .isInstanceOf(JsonRpcException.class)
                .satisfies(e -> assertThat(((JsonRpcException) e).code()).isNull())
                .hasMessageContaining("429");
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

    @Test
    @DisplayName("★ 正文滴流：头到了正文不来，必须在总时限内失败（JDK 的请求超时只管到头）")
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void timesOutWhenTheBodyTrickles() {
        mode = Mode.TRICKLE;
        JsonRpcClient client = new JsonRpcClient(endpoint(), Duration.ofMillis(500));
        long started = System.nanoTime();

        assertThatThrownBy(() -> client.call("eth_blockNumber"))
                .isInstanceOf(JsonRpcException.class)
                .hasMessageContaining("超时");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("★ 正文无限：超过上限就拒绝，不把整条流读进内存")
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rejectsAnOversizedBody() {
        mode = Mode.ENDLESS;

        assertThatThrownBy(() -> client().call("eth_blockNumber"))
                .isInstanceOf(JsonRpcException.class)
                .hasMessageContaining("上限");
    }
}
