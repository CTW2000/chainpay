package com.chainpay.chain.rpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 *
 * <p><b>读法的两个洞（M2-⑤ 补上）：</b>JDK 的 {@code HttpRequest.timeout} 只管到响应头到达，
 * 正文滴流它不管；正文没有上限，坏节点可以一直发到我们内存耗尽。
 * 这里对「发出到正文读完」整段计时，正文按 {@link #MAX_BODY_BYTES} 封顶。
 */
public class JsonRpcClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    /** 正文上限。100 块的 getLogs、一个块的全部回执都远在这之下；超过它的只会是坏节点或错请求。 */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private final URI endpoint;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final ObjectMapper json = new ObjectMapper();
    /** 每个请求一个新 id，响应必须原样带回——这是并发时对上「哪个答案是哪个问题的」的唯一手段。 */
    private final AtomicLong nextId = new AtomicLong(1);

    /** 状态码 + 读到上限为止的正文。 */
    private record Raw(int status, byte[] body) {}

    public JsonRpcClient(URI endpoint) {
        this(endpoint, DEFAULT_REQUEST_TIMEOUT);
    }

    /** @param requestTimeout 一次调用的总时限：从发出到<b>正文读完</b>。 */
    public JsonRpcClient(URI endpoint, Duration requestTimeout) {
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * 调一个方法，返回 {@code result} 节点（十六进制在这一层<b>不</b>转换，交给 {@link EthRpc}）。
     *
     * @throws JsonRpcException 节点返回 error 对象、HTTP 非 2xx、响应不是 JSON、id 不匹配、网络失败、超时、正文超限
     */
    public JsonNode call(String method, Object... params) {
        long id = nextId.getAndIncrement();
        String body = json.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", List.of(params)));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        Raw raw = exchange(request, method);
        if (raw.status() / 100 != 2) {
            throw new JsonRpcException(null, "HTTP " + raw.status() + " · " + method);
        }

        JsonNode root;
        try {
            root = json.readTree(raw.body());
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

    /**
     * 发出请求并把正文读完：整段不超过 requestTimeout，正文不超过 MAX_BODY_BYTES。
     *
     * <p>异步发送，在同一个 future 里把正文读到上限（多读一个字节，超没超一比就知道），
     * 然后对整个 future 等一个时限。超时后把流关掉，让还在读的那个线程立刻结束，
     * 而不是留一个挂着的线程和一条占着的连接。
     */
    private Raw exchange(HttpRequest request, String method) {
        AtomicReference<InputStream> open = new AtomicReference<>();
        CompletableFuture<Raw> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    open.set(response.body());
                    try (InputStream in = response.body()) {
                        return new Raw(response.statusCode(), in.readNBytes(MAX_BODY_BYTES + 1));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        try {
            Raw raw = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (raw.body().length > MAX_BODY_BYTES) {
                throw new JsonRpcException(null, "响应正文超过上限 " + MAX_BODY_BYTES + " 字节 · " + method);
            }
            return raw;
        } catch (TimeoutException e) {
            future.cancel(true);
            closeQuietly(open.get());
            throw new JsonRpcException("超时（" + requestTimeout.toMillis() + " ms，含正文）· " + method, e);
        } catch (ExecutionException e) {
            throw new JsonRpcException("节点不可达：" + endpoint.getHost() + " · " + method, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            closeQuietly(open.get());
            throw new JsonRpcException("请求被中断：" + method, e);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 已经在超时路径上，关流失败没有更多能做的
        }
    }
}
