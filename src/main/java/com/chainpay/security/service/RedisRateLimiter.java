package com.chainpay.security.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 跨实例共享的限流计数，存在 Redis 里。
 *
 * <p><b>为什么需要它：</b>进程内的 {@code AtomicLong} 在单实例下完全正确，
 * 但两个实例各自允许 120 次/分钟，商户实际能打 240 次。
 * 计数必须放在所有实例都能看到的地方。
 *
 * <p><b>为什么必须用 Lua 脚本，而不是两条命令：</b>
 *
 * <pre>
 *   INCR   key          ← 计数加一
 *   EXPIRE key 60       ← 设置过期
 * </pre>
 *
 * 这两条之间如果进程崩溃、网络断开、或者 Redis 恰好在此刻主从切换，
 * 这个 key 就<b>永远不会过期</b> —— 那个商户会被永久限流，
 * 而且没有任何日志会提示原因。
 *
 * <p>Redis 保证<b>单个 Lua 脚本原子执行</b>，中途不会插入其他命令。
 * 把两条命令合成一个脚本，那个窗口就不存在了。
 *
 * <p><b>这是 check-then-act 在分布式环境的又一次现身。</b>
 * 本项目已经在四个地方遇到同一个形状：
 * 账本余额、幂等键、进程内限流计数、以及这里的「计数与过期之间的窗口」。
 * 每次的解法都是同一句话：<b>把「两步」变成「一步」。</b>
 */
@Component
public class RedisRateLimiter {

    /**
     * 计数 + 首次设置过期，原子执行。
     *
     * <p>返回窗口内的当前计数。只在 {@code current == 1}（也就是这个窗口的第一次）
     * 时设置 TTL —— 每次都设的话窗口会被不断延长，变成「只要一直有请求就永不重置」，
     * 那不是固定窗口，是个永远关不上的闸门。
     */
    private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 计数加一并返回当前值。
     *
     * <p>返回空 {@link Optional} 表示 <b>Redis 不可用</b> ——
     * 调用方据此决定降级策略，而不是在这里替它决定。
     * 「无法计数」和「计数为 0」是两件事，混成同一个返回值会让调用方无从分辨。
     */
    public Optional<Long> increment(String key) {
        try {
            Long count = redis.execute(INCREMENT_WITH_TTL, List.of(key),
                    String.valueOf(WINDOW.toSeconds()));
            return Optional.ofNullable(count);
        } catch (RuntimeException e) {
            // 不打印堆栈：Redis 抖动时这里会被高频触发，堆栈会淹没日志。
            // 调用方负责记录降级事件。
            return Optional.empty();
        }
    }

    /** 距该键的窗口结束还剩多少秒；取不到时返回整个窗口长度。 */
    public long secondsUntilReset(String key) {
        try {
            Long ttl = redis.getExpire(key);
            return ttl == null || ttl <= 0 ? WINDOW.toSeconds() : ttl;
        } catch (RuntimeException e) {
            return WINDOW.toSeconds();
        }
    }

    /** 删除某个键的计数。认证成功后用来清掉该来源的失败记录。 */
    public void clear(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            // 清不掉不影响正确性：那条计数会在 TTL 到期后自己消失。
            // 最坏情况是该来源在这一分钟内被多算了几次失败。
        }
    }

    /** Redis 当前是否可用。用于测试与健康检查。 */
    public boolean isAvailable() {
        try {
            var factory = redis.getConnectionFactory();
            if (factory == null) {
                return false;
            }
            try (var connection = factory.getConnection()) {
                return "PONG".equalsIgnoreCase(connection.ping());
            }
        } catch (RuntimeException e) {
            return false;
        }
    }
}
