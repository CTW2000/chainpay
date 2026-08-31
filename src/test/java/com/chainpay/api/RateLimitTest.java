package com.chainpay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.api.auth.ApiCredentialService;
import com.chainpay.api.auth.RateLimiter;
import com.chainpay.api.auth.RedisRateLimiter;
import com.chainpay.api.auth.SecretCipher;
import com.chainpay.support.AbstractPostgresTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 限流的守卫。
 *
 * <p>限流器有两条独立的闸门，防的是两件不同的事：
 * <ul>
 *   <li><b>请求配额</b>（按 API key，认证通过后计）—— 防资源耗尽</li>
 *   <li><b>认证失败次数</b>（按来源 IP，认证之前计）—— 防暴力破解</li>
 * </ul>
 *
 * <p>两者的阈值差一个数量级，因为正常客户端几乎不会连续认证失败：
 * 失败十次说明要么在攻击、要么客户端签名实现坏了，两种都该被拦。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("M1 · 限流契约")
class RateLimitTest extends AbstractPostgresTest {

    private static final int REQUESTS_PER_MINUTE = 120;
    private static final int AUTH_FAILURES_PER_MINUTE = 10;

    @LocalServerPort
    private int port;

    @Autowired
    private SecretCipher cipher;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String acmeSecret;
    private long acmeAccount;

    @BeforeEach
    void seedMerchant() {
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();

        long acme = jdbc.sql("INSERT INTO merchant(code,name) VALUES ('acme','Acme') RETURNING id")
                .query(Long.class).single();
        acmeSecret = cipher.generateSecret();
        jdbc.sql("""
                        INSERT INTO api_credential(merchant_id, api_key, secret_encrypted)
                        VALUES (:m, 'ak_acme', :s)
                        """)
                .param("m", acme).param("s", cipher.encrypt(acmeSecret))
                .update();
        acmeAccount = jdbc.sql("""
                        INSERT INTO account(code, currency, kind, merchant_id)
                        VALUES ('user:acme:USDT','USDT','LIABILITY',:m) RETURNING id
                        """)
                .param("m", acme).query(Long.class).single();
    }

    // ==================================================================
    // 请求配额
    // ==================================================================

    @Test
    @DisplayName("★ 超出每分钟配额 —— 429，且带 Retry-After")
    void exceedingQuotaReturns429WithRetryAfter() {
        for (int i = 0; i < REQUESTS_PER_MINUTE; i++) {
            assertThat(signedGet().statusCode())
                    .as("配额内第 %d 次应当放行", i + 1)
                    .isEqualTo(200);
        }

        var rejected = signedGet();

        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.body()).contains("RATE_LIMITED");
        // 不带 Retry-After 的话，客户端不知道该等多久，会立刻重试 ——
        // 限流反而制造了更多请求。
        assertThat(rejected.headers().firstValue("Retry-After"))
                .as("429 必须告诉客户端等多久")
                .isPresent();
        assertThat(Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow()))
                .isPositive();
    }

    @Test
    @DisplayName("★ 并发打满配额 —— 放行数必须恰好等于配额，不能被并发绕过")
    void concurrentRequestsCannotExceedQuota() {
        // 这是 flow-pay 9f22aac 的形状：错误计数 get+set 非原子，
        // 两个线程同时读到 4、同时写 5，第 6 次也被放行。
        // 计数必须原子 —— incrementAndGet，而不是 get 之后再 set。
        final int attempts = REQUESTS_PER_MINUTE + 40;
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    int status = signedGet().statusCode();
                    if (status == 200) {
                        allowed.incrementAndGet();
                    } else if (status == 429) {
                        limited.incrementAndGet();
                    }
                });
            }
        }

        assertThat(allowed.get())
                .as("放行数必须恰好等于配额；多出来就说明计数不是原子的")
                .isEqualTo(REQUESTS_PER_MINUTE);
        assertThat(limited.get()).isEqualTo(attempts - REQUESTS_PER_MINUTE);
    }

    // ==================================================================
    // 计数到底存在哪里 —— 这两个测试防的是「静默降级」
    // ==================================================================

    @Test
    @DisplayName("★ 计数必须真的走 Redis，而不是悄悄退回本地内存")
    void countingActuallyGoesThroughRedis() {
        // 没有这个断言的话，Redis 连不上时限流器会静默降级到进程内计数，
        // 而所有限流测试照样全绿 —— 我们会以为跨实例限流生效了，实际没有。
        // 静默降级比不降级更危险：系统看起来一切正常，保护却弱了 N 倍。
        signedGet();
        signedGet();
        signedGet();

        assertThat(rateLimiter.isDegraded())
                .as("Redis 可用时不应处于降级状态")
                .isFalse();
        assertThat(redisTemplate.opsForValue().get("cp:rate:ak_acme"))
                .as("计数必须出现在 Redis 里")
                .isEqualTo("3");
        assertThat(redisTemplate.getExpire("cp:rate:ak_acme"))
                .as("必须设了 TTL，否则这个 key 永不过期，商户会被永久限流")
                .isPositive();
    }

    @Test
    @DisplayName("★ Redis 挂掉时降级到本地计数，而不是放行全部或拒绝全部")
    void fallsBackToLocalCountingWhenRedisIsUnavailable() {
        // 用一个「永远连不上」的 RedisRateLimiter 构造限流器，
        // 而不去停掉共享的测试容器 —— 那会连累其他测试。
        var alwaysDown = new RedisRateLimiter(null) {
            @Override
            public Optional<Long> increment(String key) {
                return Optional.empty();   // 模拟 Redis 不可用
            }

            @Override
            public void clear(String key) {
                // Redis 挂了，清不掉也不该抛异常
            }
        };
        var degradedLimiter = new RateLimiter(alwaysDown);

        // 关键断言：降级之后限流仍然生效，不是 fail-open 全部放行
        for (int i = 0; i < REQUESTS_PER_MINUTE; i++) {
            assertThat(degradedLimiter.allowRequest("ak_test"))
                    .as("降级后配额内的第 %d 次仍应放行", i + 1)
                    .isTrue();
        }
        assertThat(degradedLimiter.allowRequest("ak_test"))
                .as("降级后超出配额仍应拒绝 —— 保护变弱了，但没有消失")
                .isFalse();

        assertThat(degradedLimiter.isDegraded())
                .as("降级状态必须能被观测到，否则运维不知道保护已经弱了")
                .isTrue();
    }

    // ==================================================================
    // 认证失败次数
    // ==================================================================

    @Test
    @DisplayName("★ 反复认证失败 —— 达到阈值后从 401 变成 429")
    void repeatedAuthFailuresAreThrottled() {
        // 对应 OWASP Transaction_Authorization 2.4：
        // 失败达到上限后，整个授权流程必须重来，而不只是拒绝这一次。
        for (int i = 0; i < AUTH_FAILURES_PER_MINUTE; i++) {
            assertThat(badSignatureGet().statusCode())
                    .as("阈值内的第 %d 次失败仍回 401", i + 1)
                    .isEqualTo(401);
        }

        assertThat(badSignatureGet().statusCode())
                .as("超过阈值后不再回 401，直接 429 —— 连验签的成本都不再付")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("认证成功会清掉该来源的失败计数")
    void successfulAuthClearsFailureCounter() {
        // 正常用户不该被自己历史上的几次失败拖累：
        // 客户端时钟偶尔偏一下、重启时签名算错几次，都是常态。
        for (int i = 0; i < AUTH_FAILURES_PER_MINUTE - 1; i++) {
            assertThat(badSignatureGet().statusCode()).isEqualTo(401);
        }

        assertThat(signedGet().statusCode()).as("正确签名应当放行").isEqualTo(200);

        // 计数已清零，可以重新失败满一整轮而不被 429
        for (int i = 0; i < AUTH_FAILURES_PER_MINUTE; i++) {
            assertThat(badSignatureGet().statusCode())
                    .as("失败计数应已被成功认证清零")
                    .isEqualTo(401);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private HttpResponse<String> signedGet() {
        String path = "/api/v1/accounts/" + acmeAccount + "/balance";
        long ts = System.currentTimeMillis();
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-CP-API-KEY", "ak_acme")
                .header("X-CP-API-TIMESTAMP", String.valueOf(ts))
                .header("X-CP-API-SIGN", ApiCredentialService.sign(ts + "GET" + path, acmeSecret))
                .GET());
    }

    private HttpResponse<String> badSignatureGet() {
        String path = "/api/v1/accounts/" + acmeAccount + "/balance";
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-CP-API-KEY", "ak_acme")
                .header("X-CP-API-TIMESTAMP", String.valueOf(System.currentTimeMillis()))
                .header("X-CP-API-SIGN", "this-is-not-a-valid-signature")
                .GET());
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 请求失败", e);
        }
    }
}
