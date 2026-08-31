package com.chainpay.api.auth;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 限流门面：<b>优先用 Redis（跨实例共享），Redis 不可用时降级到进程内计数</b>。
 *
 * <p><b>为什么是「降级」而不是「二选一」—— 这是本类最重要的设计决定：</b>
 *
 * <p>引入 Redis 之后，它就成了每个 API 请求的必经之路。于是必须回答：
 * <b>Redis 挂了怎么办？</b>
 *
 * <pre>
 *   Fail-closed（拒绝所有请求）  Redis 一抖，整个支付 API 停摆。
 *                              为了一个保护措施把主业务干掉。
 *   Fail-open（全部放行）        限流完全失效：可被打爆，暴力破解无人拦。
 *   降级到本地（本类的选择）      N 个实例 = N 倍配额，但远好过完全没有。
 * </pre>
 *
 * <p>判断依据是本项目反复用到的那条：<b>fail-open 还是 fail-closed，
 * 取决于这个检查是不是最终关卡。</b>
 * 限流<b>不是</b>最终关卡（账本的余额检查和数据库约束才是），
 * 所以它不该有能力让整个系统停摆 —— 但「降级」比「放弃」好。
 *
 * <p><b>降级必须被看见。</b>静默降级最危险：系统看起来一切正常，
 * 实际保护已经弱了 N 倍，而没有人知道。所以状态切换会打日志，
 * 并通过 {@link #isDegraded()} 暴露给监控。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** 认证通过后，每个 API key 每分钟的请求配额。 */
    private static final int REQUESTS_PER_MINUTE = 120;

    /**
     * 认证失败的容忍次数，按来源 IP 计。
     *
     * <p>比正常配额严得多，因为它防的是<b>暴力破解</b>。正常客户端几乎不会
     * 连续认证失败 —— 失败十次说明要么在攻击，要么客户端签名实现坏了，两种都该被拦。
     *
     * <p>对应 OWASP Transaction_Authorization 2.4：
     * <i>"After a set number of failed authorization attempts,
     * the entire transaction authorization process should be restarted."</i>
     */
    private static final int AUTH_FAILURES_PER_MINUTE = 10;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Redis 键前缀。加前缀是为了和将来放进同一个 Redis 的其他数据分开。 */
    private static final String KEY_REQUEST = "cp:rate:";
    private static final String KEY_AUTH_FAILURE = "cp:authfail:";

    private final RedisRateLimiter redis;

    /** 降级时用的进程内计数。平时是空的，不占内存。 */
    private final Map<String, Window> localWindows = new ConcurrentHashMap<>();

    /** 当前是否处于降级状态。用 AtomicBoolean 是为了只在状态<b>切换</b>时打一次日志。 */
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    public RateLimiter(RedisRateLimiter redis) {
        this.redis = redis;
    }

    /** 该 API key 是否还在配额内。返回 false 表示应当拒绝。 */
    public boolean allowRequest(String apiKey) {
        return within(KEY_REQUEST + apiKey, REQUESTS_PER_MINUTE);
    }

    /**
     * 记一次认证失败，返回该来源是否还被允许继续尝试。
     *
     * <p>按 IP 而不是按 api key 计：认证失败时我们<b>还不知道调用方是谁</b> ——
     * 它给的 key 可能根本不存在。按一个攻击者可以随意伪造的字段限流，等于没限。
     */
    public boolean recordAuthFailure(String clientIp) {
        return within(KEY_AUTH_FAILURE + clientIp, AUTH_FAILURES_PER_MINUTE);
    }

    /** 认证成功后清掉该来源的失败计数，避免正常用户被历史失败拖累。 */
    public void clearAuthFailures(String clientIp) {
        redis.clear(KEY_AUTH_FAILURE + clientIp);
        localWindows.remove(KEY_AUTH_FAILURE + clientIp);
    }

    /** 距当前窗口结束还有多少秒 —— 用于 Retry-After 响应头。 */
    public long secondsUntilWindowReset(String apiKey) {
        String key = KEY_REQUEST + apiKey;
        if (!degraded.get()) {
            return redis.secondsUntilReset(key);
        }
        Window window = localWindows.get(key);
        if (window == null) {
            return WINDOW.toSeconds();
        }
        long elapsed = System.currentTimeMillis() - window.startedAtMillis;
        return Math.max(1, (WINDOW.toMillis() - elapsed) / 1000);
    }

    /** 是否正处于降级（Redis 不可用）状态。供监控和测试使用。 */
    public boolean isDegraded() {
        return degraded.get();
    }

    /**
     * 清空全部计数。<b>仅供测试使用</b> ——
     * 测试之间必须互不影响，否则前一个测试打满配额会让后一个莫名其妙地失败。
     */
    public void resetAll() {
        localWindows.clear();
        degraded.set(false);
    }

    // ==================================================================

    private boolean within(String key, int limit) {
        if (key == null || key.isBlank()) {
            return false;
        }

        Optional<Long> count = redis.increment(key);
        if (count.isPresent()) {
            recoverFromDegradation();
            return count.get() <= limit;
        }

        enterDegradation();
        return withinLocally(key, limit);
    }

    /**
     * 降级路径：进程内固定窗口计数。
     *
     * <p>这段代码就是引入 Redis 之前的实现。保留它而不是删掉，
     * 正是因为「Redis 挂了」是一定会发生的事，而那时它是我们唯一还有的保护。
     */
    private boolean withinLocally(String key, int limit) {
        Window window = localWindows.computeIfAbsent(key, k -> new Window());
        return window.incrementWithinWindow(WINDOW.toMillis()) <= limit;
    }

    private void enterDegradation() {
        // compareAndSet 保证只在「从正常切到降级」的那一刻打一次日志，
        // 而不是每个请求都打 —— Redis 挂掉时请求量不会变少，
        // 每请求一条 ERROR 会在几秒内把磁盘写满。
        if (degraded.compareAndSet(false, true)) {
            log.error("Redis 不可用，限流降级为进程内计数。"
                    + "多实例部署下实际配额会变成 N 倍，请尽快恢复 Redis");
        }
    }

    private void recoverFromDegradation() {
        if (degraded.compareAndSet(true, false)) {
            log.warn("Redis 已恢复，限流回到跨实例模式");
            // 清掉降级期间的本地计数：它和 Redis 里的计数是两套账，
            // 留着会让恢复后的第一个窗口被两边重复计数。
            localWindows.clear();
        }
    }

    /** 降级时用的进程内固定窗口计数。 */
    private static final class Window {
        private final AtomicLong count = new AtomicLong();
        private volatile long startedAtMillis = System.currentTimeMillis();

        /**
         * 计数加一，返回窗口内的当前值。
         *
         * <p>用 {@link AtomicLong#incrementAndGet()} 而不是「读 → 判断 → 写」：
         * 后者在并发下必然失败 —— 两个线程同时读到 119、同时写 120，第 121 次也被放行。
         * 这是 flow-pay 9f22aac 栽过的坑。
         *
         * <p>窗口重置存在一个<b>良性竞态</b>：两个线程可能同时判定窗口已过期、
         * 都执行重置，丢掉一两次计数。我们接受它 —— 修掉需要每请求一把锁，
         * 代价远大于收益。<b>把「已知的、可接受的不精确」写下来，
         * 和把它藏起来是两回事。</b>
         */
        long incrementWithinWindow(long windowMillis) {
            long now = System.currentTimeMillis();
            if (now - startedAtMillis >= windowMillis) {
                startedAtMillis = now;
                count.set(0);
            }
            return count.incrementAndGet();
        }
    }
}
