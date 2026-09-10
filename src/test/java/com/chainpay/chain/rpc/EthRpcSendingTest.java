package com.chainpay.chain.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * M4-② 新增的五个方法的翻译层。诚实说明：这里的响应不是真实录制，是按 JSON-RPC 规范的形状手写的；
 * 真实节点的回答在 M4-⑤ 的演练里核对。测的是：参数怎么发出去、十六进制怎么读回来、错误怎么翻译。
 */
@DisplayName("M4-② · EthRpc 翻译层：计数、估 gas、费率、广播、按哈希查")
class EthRpcSendingTest {

    static final String HOT = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    static final String HASH = "0x" + "ab".repeat(32);

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> lastParams = new ConcurrentHashMap<>();
    private HttpServer server;
    private EthRpc rpc;

    @BeforeEach
    void startFakeNode() throws IOException {
        answers.put("eth_getTransactionCount", "{\"jsonrpc\":\"2.0\",\"result\":\"0x1a\"}");
        answers.put("eth_estimateGas", "{\"jsonrpc\":\"2.0\",\"result\":\"0xcb5c\"}");
        answers.put("eth_maxPriorityFeePerGas", "{\"jsonrpc\":\"2.0\",\"result\":\"0x59682f00\"}");
        answers.put("eth_getBlockByNumber", "{\"jsonrpc\":\"2.0\",\"result\":{\"number\":\"0xb1e6d1\",\"hash\":\"" + HASH + "\",\"parentHash\":\"" + HASH + "\",\"timestamp\":\"0x68c0\",\"baseFeePerGas\":\"0x2540be400\"}}");
        answers.put("eth_sendRawTransaction", "{\"jsonrpc\":\"2.0\",\"result\":\"" + HASH + "\"}");
        answers.put("eth_getTransactionByHash", "{\"jsonrpc\":\"2.0\",\"result\":null}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.get("method").asString();
            lastParams.put(method, request.get("params"));
            ObjectNode reply = (ObjectNode) json.readTree(answers.get(method));
            reply.put("id", request.get("id").asLong());
            byte[] body = json.writeValueAsBytes(reply);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        rpc = new EthRpc(new JsonRpcClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort())));
    }

    @AfterEach
    void stopFakeNode() {
        server.stop(0);
    }

    @Test
    @DisplayName("★ 交易计数：十六进制读成整数，地址与口径原样发出")
    void transactionCountParsesHexAndPassesTheTag() {
        assertThat(rpc.transactionCount(HOT, "latest")).isEqualTo(BigInteger.valueOf(26));
        assertThat(lastParams.get("eth_getTransactionCount").get(0).asString()).isEqualTo(HOT);
        assertThat(lastParams.get("eth_getTransactionCount").get(1).asString()).isEqualTo("latest");
    }

    @Test
    @DisplayName("估 gas：from / to / data 装进一个调用对象")
    void estimateGasSendsACallObject() {
        assertThat(rpc.estimateGas(HOT, "0x" + "11".repeat(20), "0xa9059cbb")).isEqualTo(BigInteger.valueOf(52_060));
        JsonNode call = lastParams.get("eth_estimateGas").get(0);
        assertThat(call.get("from").asString()).isEqualTo(HOT);
        assertThat(call.get("data").asString()).isEqualTo("0xa9059cbb");
    }

    @Test
    @DisplayName("费率：基础费从最新块头读，小费问节点")
    void feeQuoteReadsBaseFeeFromTheLatestHeaderAndTipFromTheNode() {
        FeeQuote quote = rpc.feeQuote();

        assertThat(quote.baseFeePerGas()).isEqualTo(BigInteger.valueOf(10_000_000_000L));
        assertThat(quote.maxPriorityFeePerGas()).isEqualTo(BigInteger.valueOf(1_500_000_000L));
        assertThat(lastParams.get("eth_getBlockByNumber").get(0).asString()).isEqualTo("latest");
    }

    @Test
    @DisplayName("★ 广播：原文按 0x 十六进制发出、回哈希；节点的 already known 是带 code 的错，交给调用方分类")
    void sendRawTransactionReturnsTheHashAndSurfacesCodedErrors() {
        assertThat(rpc.sendRawTransaction(new byte[] {0x02, (byte) 0xf8})).isEqualTo(HASH);
        assertThat(lastParams.get("eth_sendRawTransaction").get(0).asString()).isEqualTo("0x02f8");

        answers.put("eth_sendRawTransaction", "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"already known\"}}");
        assertThatThrownBy(() -> rpc.sendRawTransaction(new byte[] {0x02}))
                .isInstanceOf(JsonRpcException.class)
                .satisfies(e -> assertThat(((JsonRpcException) e).code()).isEqualTo(-32000))
                .hasMessageContaining("already known");
    }

    @Test
    @DisplayName("按哈希查：result 为 null = 节点不知道这笔；有对象 = 知道")
    void transactionKnownFollowsTheResult() {
        assertThat(rpc.transactionKnown(HASH)).isFalse();
        answers.put("eth_getTransactionByHash", "{\"jsonrpc\":\"2.0\",\"result\":{\"hash\":\"" + HASH + "\",\"nonce\":\"0x0\"}}");
        assertThat(rpc.transactionKnown(HASH)).isTrue();
    }
}
