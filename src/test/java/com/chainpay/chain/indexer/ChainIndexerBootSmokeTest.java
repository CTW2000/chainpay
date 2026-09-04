package com.chainpay.chain.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.indexer.config.ChainReaders;
import com.chainpay.chain.indexer.domain.TickOutcome;
import com.chainpay.chain.indexer.service.ChainIndexerScheduler;
import com.chainpay.security.filter.AdminAuthFilter;
import com.chainpay.support.AbstractPostgresTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 启动冒烟：让 Spring 容器<b>真的</b>把索引器装配一次，并证明调度注解真的起作用。
 *
 * <p>其它测试全部手工 new，从没执行过 {@code @ConditionalOnProperty}、属性绑定和 {@code @Scheduled} 这一层：
 * 条件注解的属性名、fixedDelayString 里的占位符、绑定的字段名任何一个写错，只有真实启动会发现（质询扫描 5.9）。
 * 假节点对什么都回 503，所以每次轮询都是瞬时失败——这里验证的是装配与调度，不是索引。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@DisplayName("M2-⑥ 补丁 3 · 启动冒烟：索引器真的被容器装配并调度")
class ChainIndexerBootSmokeTest extends AbstractPostgresTest {

    private static final String ADMIN_TOKEN = "chainpay-test-admin-token-not-for-prod";
    private static final HttpServer STUB;

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        STUB.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        STUB.start();
        System.setProperty(TEST_RPC_URL_PROPERTY, "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @DynamicPropertySource
    static void pollFast(DynamicPropertyRegistry r) {
        r.add("chainpay.chain.poll-interval", () -> "200ms");
    }

    @AfterAll
    static void stopStub() {
        System.clearProperty(TEST_RPC_URL_PROPERTY);
        STUB.stop(0);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ObjectProvider<ChainIndexerScheduler> scheduler;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("★ 容器装配出调度器、@Scheduled 注册了 tick、第一次轮询真的跑了、状态接口能报出来")
    void schedulerIsAssembledScheduledAndObservable() throws Exception {
        ChainIndexerScheduler running = scheduler.getIfAvailable();
        assertThat(running).as("配了 rpc-url 就该装配").isNotNull();
        assertThat(context.getBean(ChainReaders.class).auditMode()).contains("单节点");

        var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
        // Spring 7 把方法包成 Task.OutcomeTrackingRunnable，toString 仍是「类名.方法名」
        assertThat(tasks).extracting(Object::toString).anySatisfy(name -> assertThat(name).endsWith("ChainIndexerScheduler.tick"));

        for (int i = 0; i < 100 && running.lastTick().isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(running.lastTick()).as("调度器该已经跑过至少一轮").isPresent();
        assertThat(running.lastTick().get().outcome()).isEqualTo(TickOutcome.RETRY_LATER);

        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/admin/v1/indexer"))
                .header(AdminAuthFilter.HEADER_ADMIN_TOKEN, ADMIN_TOKEN)
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = new ObjectMapper().readTree(response.body()).get("data");
        assertThat(data.get("status").asString()).isEqualTo("RUNNING");
        assertThat(data.get("lastTickOutcome").asString()).isEqualTo("RETRY_LATER");
        assertThat(data.get("consecutiveFailures").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(data.get("auditMode").asString()).contains("单节点");
    }
}
