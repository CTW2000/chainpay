package com.chainpay.api.auth;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 固定窗口计数限流。
 *
 * <p><b>★ 已知限制：这是进程内的，多实例部署时形同虚设 ★</b>
 *
 * <p>两个实例各自允许 120 次/分钟，商户实际能打 240 次。真正的解法是把计数放到
 * 所有实例共享的地方（Redis 的 {@code INCR} + {@code EXPIRE}）。
 * 现在只有一个实例，所以这一版是正确的；<b>扩容到两个实例的那一刻它就不正确了</b>。
 * 这个前提哪天不成立，这段注释就是提醒。
 *
 * <p><b>为什么计数必须原子 —— flow-pay 的 9f22aac 就栽在这里：</b>
 *
 * <pre>
 *   线程 A: GET count → 4
 *   线程 B: GET count → 4        ← 两个都读到 4
 *   线程 A: SET count = 5，放行
 *   线程 B: SET count = 5，放行   ← 第 6 次也被放行了
 * </pre>
 *
 * 「先读、再判断、再写」在并发下必然失败 —— 这就是 check-then-act，
 * 本项目已经在账本余额、幂等键、限频三个地方遇到同一个形状。
 * 这里用 {@link AtomicLong#incrementAndGet()}：读和写是<b>一条不可分割的指令</b>。
 *
 * <p><b>固定窗口的已知弱点</b>：窗口边界可以被双倍突破 ——
 * 在 12:00:59 打满配额、12:01:00 再打满一次，两秒内实际放行了两倍。
 * 更严的做法是滑动窗口或令牌桶。固定窗口的优点是实现简单、内存占用小，
 * 对「防止资源耗尽」这个主要目的足够；<b>但用它做「防暴力破解」就不够</b>，
 * 所以下面对认证失败单独用了严得多的阈值。
 */
@Component
public class RateLimiter {

    /** 认证通过后，每个 API key 每分钟的请求配额。 */
    private static final int REQUESTS_PER_MINUTE = 120;

    /**
     * 认证失败的容忍次数，按来源 IP 计。
     *
     * <p>比正常配额严得多，因为它防的是<b>暴力破解</b>：
     * 攻击者拿一把 key 反复猜签名。正常客户端几乎不会连续认证失败 ——
     * 失败十次说明要么在攻击，要么客户端签名实现坏了，两种都该被拦。
     *
     * <p>对应 OWASP Transaction_Authorization 2.4：
     * <i>"After a set number of failed authorization attempts,
     * the entire transaction authorization process should be restarted."</i>
     */
    private static final int AUTH_FAILURES_PER_MINUTE = 10;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Window> requestWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> failureWindows = new ConcurrentHashMap<>();

    /** 一个固定时间窗内的计数。 */
    private static final class Window {
        private final AtomicLong count = new AtomicLong();
        private volatile long startedAtMillis = System.currentTimeMillis();

        /**
         * 计数加一，返回窗口内的当前值。
         *
         * <p>窗口过期时重置。这里的重置存在一个<b>良性竞态</b>：
         * 两个线程可能同时判定窗口已过期、都执行重置，导致丢掉一两次计数。
         * 我们接受它 —— 修掉需要加锁，而代价（每个请求一把锁）远大于收益
         * （极低概率下多放行一两个请求）。
         *
         * <p><b>把「已知的、可接受的不精确」写下来，和把它藏起来是两回事。</b>
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

    /** 该 API key 是否还在配额内。返回 false 表示应当拒绝。 */
    public boolean allowRequest(String apiKey) {
        return within(requestWindows, apiKey, REQUESTS_PER_MINUTE);
    }

    /**
     * 记一次认证失败，返回该来源是否还被允许继续尝试。
     *
     * <p>按 IP 而不是按 api key 计：认证失败时我们<b>还不知道调用方是谁</b> ——
     * 它给的 key 可能根本不存在。按一个攻击者可以随意伪造的字段限流，等于没限。
     */
    public boolean recordAuthFailure(String clientIp) {
        return within(failureWindows, clientIp, AUTH_FAILURES_PER_MINUTE);
    }

    /** 认证成功后清掉该来源的失败计数，避免正常用户被历史失败拖累。 */
    public void clearAuthFailures(String clientIp) {
        failureWindows.remove(clientIp);
    }

    /** 距当前窗口结束还有多少秒 —— 用于 Retry-After 响应头。 */
    public long secondsUntilWindowReset(String key) {
        Window window = requestWindows.get(key);
        if (window == null) {
            return WINDOW.toSeconds();
        }
        long elapsed = System.currentTimeMillis() - window.startedAtMillis;
        return Math.max(1, (WINDOW.toMillis() - elapsed) / 1000);
    }

    private boolean within(Map<String, Window> windows, String key, int limit) {
        if (key == null || key.isBlank()) {
            return false;
        }
        // computeIfAbsent 保证同一个 key 只会有一个 Window 实例，
        // 即使多个线程同时第一次访问它。
        Window window = windows.computeIfAbsent(key, k -> new Window());
        return window.incrementWithinWindow(WINDOW.toMillis()) <= limit;
    }

    /**
     * 清空全部计数。<b>仅供测试使用</b> ——
     * 测试之间必须互不影响，否则前一个测试打满配额会让后一个莫名其妙地失败。
     */
    public void resetAll() {
        requestWindows.clear();
        failureWindows.clear();
    }
}
