package com.chainpay.chain.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.erc20.Abi;
import com.chainpay.chain.erc20.Erc20Transfer;
import com.chainpay.chain.erc20.TransferLogDecoder;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 翻译层的契约测试：把<b>真实节点的实际输出</b>喂给我们的解析器。
 *
 * <p>夹具是 2026-09-04 从 Sepolia 公共网关录下来的 JSON-RPC 响应（块 11627476，里面有一笔 LINK 转账），
 * 期望值从夹具里手抄，不经过被测代码。之前 {@code EthRpc} 只被两条默认不跑的探针碰过：
 * 服务层的测试全走 {@code FakeChain}，外部 JSON 进入系统的唯一那一层在默认测试集里零执行（质询扫描 5.9 / 10.7）。
 */
@DisplayName("M2-⑥ 补丁 3 · EthRpc 翻译层（真实响应夹具）")
class EthRpcTest {

    static final String FIXTURES = "/rpc/sepolia-11627476/";
    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final long BLOCK = 11627476L;
    static final String BLOCK_HASH = "0x09b98b97f76b16247a04f2d892858cdd76450bc4b14446c12e3d3588cbfb89a0";
    static final String PARENT_HASH = "0xb35793a1f8b1bba3f34001b77a8860a3a10ffcbf245229ea89e48eed6121055f";
    static final String TX_HASH = "0xe73fc5d1816b53ac891b50d32cac25fc35ab2235fd32cf9f055002be6e0b69e4";

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private HttpServer server;

    @BeforeEach
    void startFakeNode() throws IOException {
        answers.put("eth_blockNumber", fixture("blockNumber.json"));
        answers.put("eth_getBlockByNumber", fixture("block.json"));
        answers.put("eth_getLogs", fixture("logs.json"));
        answers.put("eth_getBlockReceipts", fixture("receipts.json"));
        answers.put("eth_call", fixture("call.json"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
            String canned = answers.get(request.get("method").asString());
            ObjectNode reply = (ObjectNode) json.readTree(canned);
            reply.put("id", request.get("id").asLong());                    // 客户端会核对 id：假节点照实回显
            byte[] body = json.writeValueAsBytes(reply);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeNode() {
        server.stop(0);
    }

    @Test
    @DisplayName("区块头：number / hash / parentHash / timestamp 逐字段翻译，十六进制变成 long")
    void translatesABlockHeader() {
        BlockHeader header = rpc().block(BLOCK);

        assertThat(header.number()).isEqualTo(BLOCK);
        assertThat(header.hash()).isEqualTo(BLOCK_HASH);
        assertThat(header.parentHash()).isEqualTo(PARENT_HASH);
        assertThat(header.timestamp()).isEqualTo(1788449724L);
    }

    @Test
    @DisplayName("★ eth_getLogs：真实的 LINK 转账逐字段翻译，再经 TransferLogDecoder 解出双方与金额")
    void translatesLogsFieldByField() {
        List<RawLog> logs = rpc().logs(BLOCK, BLOCK, LINK, TransferLogDecoder.TRANSFER_TOPIC0);

        assertThat(logs).hasSize(1);
        RawLog log = logs.get(0);
        assertThat(log.address()).isEqualTo(LINK);
        assertThat(log.blockNumber()).isEqualTo("0xb16bd4");
        assertThat(log.blockHash()).isEqualTo(BLOCK_HASH);
        assertThat(log.transactionHash()).isEqualTo(TX_HASH);
        assertThat(log.transactionIndex()).isEqualTo("0x6");
        assertThat(log.logIndex()).isEqualTo("0x10");
        assertThat(log.removed()).isFalse();
        assertThat(log.topics()).hasSize(3);

        Erc20Transfer transfer = TransferLogDecoder.decode(log);
        assertThat(transfer.from()).isEqualTo("0xbed29ae053d9da2a7012272f3e625fffa9c626ec");
        assertThat(transfer.to()).isEqualTo("0x1f08c0b284d5480652c896584015657493e1f70e");
        assertThat(transfer.value()).isEqualTo(new BigInteger("13500000000000000"));
        assertThat(transfer.blockNumber()).isEqualTo(BLOCK);
        assertThat(transfer.logIndex()).isEqualTo(16);
    }

    @Test
    @DisplayName("★ eth_getBlockReceipts：回执里的日志全部摊平；同地址的另一个事件不会被当成 Transfer")
    void flattensReceiptLogs() {
        List<RawLog> logs = rpc().blockReceipts(BLOCK);

        assertThat(logs).hasSize(4);
        List<RawLog> link = logs.stream().filter(l -> l.address().equalsIgnoreCase(LINK)).toList();
        assertThat(link).extracting(RawLog::logIndex).containsExactly("0x10", "0x11");
        List<RawLog> transfers = link.stream()
                .filter(l -> l.topics().get(0).equalsIgnoreCase(TransferLogDecoder.TRANSFER_TOPIC0)).toList();
        assertThat(transfers).extracting(RawLog::logIndex).containsExactly("0x10");
    }

    @Test
    @DisplayName("eth_call 与 eth_blockNumber：原样的十六进制交给上层")
    void passesHexThrough() {
        assertThat(Abi.decodeUint(rpc().call(LINK, Abi.DECIMALS, "latest"))).isEqualTo(BigInteger.valueOf(18));
        long expected = Long.parseLong(json.readTree(fixture("blockNumber.json")).get("result").asString().substring(2), 16);
        assertThat(rpc().blockNumber()).isEqualTo(expected);
    }

    @Test
    @DisplayName("★ 节点少给一个字段：指名道姓地拒绝，不是一个不知所云的空指针")
    void namesTheMissingField() {
        answers.put("eth_getLogs", without("logs.json", "result", 0, "blockHash"));
        assertThatThrownBy(() -> rpc().logs(BLOCK, BLOCK, LINK, TransferLogDecoder.TRANSFER_TOPIC0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockHash")
                .hasMessageContaining("eth_getLogs");

        answers.put("eth_getBlockByNumber", without("block.json", "result", -1, "parentHash"));
        assertThatThrownBy(() -> rpc().block(BLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parentHash");
    }

    @Test
    @DisplayName("result 为 null 的区块：节点说没有这个块，按传输层失败抛出")
    void nullBlockIsNotFound() {
        answers.put("eth_getBlockByNumber", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");

        assertThatThrownBy(() -> rpc().block(BLOCK))
                .isInstanceOf(JsonRpcException.class)
                .hasMessageContaining("区块不存在");
    }

    // ------------------------------------------------------------------ 脚手架

    private EthRpc rpc() {
        return new EthRpc(new JsonRpcClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort())));
    }

    private static String fixture(String name) {
        try (InputStream in = EthRpcTest.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IllegalStateException("夹具不存在：" + FIXTURES + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 把夹具里 result（或 result 的第 index 项）的某个字段删掉，造一个「节点少给字段」的响应。 */
    private String without(String name, String container, int index, String field) {
        ObjectNode root = (ObjectNode) json.readTree(fixture(name));
        JsonNode target = index < 0 ? root.get(container) : root.get(container).get(index);
        ((ObjectNode) target).remove(field);
        return json.writeValueAsString(root);
    }
}
