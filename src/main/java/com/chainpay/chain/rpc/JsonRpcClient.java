package com.chainpay.chain.rpc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 最薄的 JSON-RPC 2.0 客户端：JDK 的 {@link HttpClient} + 已在类路径上的 Jackson 3。
 *
 * <p><b>为什么不用 web3j：</b>它 5.0.3 的直接依赖里有 OkHttp、RxJava2、WebSocket、
 * jnr-unixsocket、tuweni、AWS KMS SDK——为解码一个 Transfer 事件背这些，
 * 违背 M0 定下的「抽象越薄越好」。索引器是账本的上游，它出错就是账本出错，
 * 每一行都要看得见。
 *
 * <p><b>JSON-RPC 的失败长什么样（和我们自己的 API 相反）：</b>
 * HTTP 状态码照样是 200，失败信息在响应体的 {@code error} 对象里。
 * 只看状态码的客户端会把节点报错当成功——drpc 的「chain is not available on free plan」
 * 就是 HTTP 200 + {@code error.code = 35}。
 */
public class JsonRpcClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    /** 每个请求一个新 id，响应必须原样带回——这是并发时对上「哪个答案是哪个问题的」的唯一手段。 */
    private final AtomicLong nextId = new AtomicLong(1);

    public JsonRpcClient(URI endpoint) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * 调一个方法，返回 {@code result} 节点（十六进制在这一层<b>不</b>转换，交给 {@link EthRpc}）。
     *
     * @throws JsonRpcException 节点返回 error 对象、HTTP 非 2xx、响应不是 JSON、id 不匹配、网络失败
     */
    public JsonNode call(String method, Object... params) {
        long id = nextId.getAndIncrement();
        String body = json.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", List.of(params)));

        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(endpoint)
                            .timeout(REQUEST_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JsonRpcException("节点不可达：" + endpoint.getHost() + " · " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JsonRpcException("请求被中断：" + method, e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new JsonRpcException(null, "HTTP " + response.statusCode() + " · " + method);
        }

        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new JsonRpcException("响应不是 JSON · " + method, e);
        }

        // ★ 先看 error，再看 result ★ —— HTTP 200 不代表成功
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            Integer code = error.has("code") ? error.get("code").asInt() : null;
            String message = error.has("message") ? error.get("message").asString() : error.toString();
            throw new JsonRpcException(code, method + " 失败：" + message);
        }

        JsonNode echoedId = root.get("id");
        if (echoedId == null || echoedId.asLong() != id) {
            throw new JsonRpcException(null, "响应的 id 与请求不符：期望 " + id + "，收到 " + echoedId);
        }

        return root.get("result");
    }
}
