package com.chainpay.security.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 跨实例共享的限流计数，存在 Redis 里：进程内计数下两个实例各自允许 120 次/分钟，商户实际能打 240 次。
 *
 * <p><b>为什么必须用 Lua 脚本，而不是 INCR + EXPIRE 两条命令：</b>两条之间如果进程崩溃、网络断开、
 * 或者 Redis 恰好主从切换，这个 key 就<b>永远不会过期</b>——那个商户会被永久限流，而且没有任何日志提示原因。
 * Redis 保证<b>单个 Lua 脚本原子执行</b>，中途不会插入其他命令：把「两步」变成「一步」，那个窗口就不存在了。
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
}
