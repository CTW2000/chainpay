package com.chainpay.api.auth;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 记住刚用过的签名，挡住原样重放。
 *
 * <p><b>为什么时间窗不够：</b>验签只保证「这个请求是持有 secret 的人签的、
 * 且签发时间在 5 秒内」。它<b>不保证这是第一次收到</b> ——
 * 截获一个请求在 5 秒内原样重发，签名照样有效。
 *
 * <p>之前挡住损失的是账本的幂等键：重放会命中同一个 clientTransferId，
 * 不重复扣款。但那是<b>账本层</b>的保护，只覆盖走账本的操作。
 * 将来任何一个不幂等的接口（发通知、触发结算、导出报表）都会中招。
 *
 * <p><b>这是 check-then-act 在本项目的第六次现身。</b>
 * 「先查这个签名用过没有，再记下来」在并发下必然失败：
 * 两个线程可以同时查到「没用过」。
 * 解法还是那一句：<b>把两步变成一步</b> —— Redis 的 {@code SET key NX EX}
 * 在一条命令里完成「不存在才写入」并告诉你是不是你写进去的。
 */
@Component
public class ReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(ReplayGuard.class);

    /**
     * 签名记多久。
     *
     * <p>必须<b>严格大于</b>验签的时钟容差（5 秒），否则会出现一段真空：
     * 签名记录已经过期，而时间窗还没关上 —— 那几毫秒里重放是通的。
     * 取两倍容差留足余量：一个签名最多只可能在 ±5 秒内被接受，
     * 记满 10 秒就一定覆盖住它的整个有效期。
     */
    private static final Duration REMEMBER = Duration.ofSeconds(10);

    private static final String KEY_PREFIX = "cp:nonce:";

    private final StringRedisTemplate redis;

    public ReplayGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 登记一个 nonce。返回 {@code true} 表示这是<b>第一次</b>见到它。
     *
     * <p><b>为什么记 nonce 而不是记签名：</b>
     * 签名是「谁签的」的证明，nonce 是「哪一次」的编号 —— 记后者语义才对上。
     * 而且 nonce 已经参与了签名计算，攻击者改不了它（改了签名就对不上），
     * 所以用它当键和用签名当键一样安全，但键更短、含义更清楚。
     *
     * <p>键按 {@code apiKey} 分域：不同商户的 nonce 互不干扰，
     * 一个商户也无法通过抢占 nonce 来阻塞另一个商户。
     *
     * <p>Redis 不可用时返回 {@code true}（放行）。理由和限流降级一致：
     * <b>fail-open 还是 fail-closed，取决于这个检查是不是最终关卡。</b>
     * 重放防护<b>不是</b>最终关卡 —— 账本的幂等键才是。
     * 它是一层加固，不该有能力在 Redis 抖动时让整个支付 API 停摆。
     *
     * <p><b>但这个判断有前提，前提哪天不成立就必须改：</b>
     * 它成立是因为目前所有会改状态的操作都走账本、都有幂等键。
     * 一旦出现一个「不幂等且不可撤销」的操作（打款指令、发短信、调外部接口），
     * 重放防护对它就是最终关卡，那时这里必须改成 fail-closed，
     * 或者给那个操作单独加幂等。
     */
    public boolean isFirstUse(String apiKey, String nonce) {
        String key = KEY_PREFIX + apiKey + ":" + nonce;
        try {
            // setIfAbsent = SET key value NX EX ttl，一条命令、原子。
            // 拆成 exists + set 两条就又是 check-then-act 了。
            Boolean written = redis.opsForValue()
                    .setIfAbsent(key, "1", REMEMBER.toSeconds(), TimeUnit.SECONDS);
            return written == null || written;
        } catch (RuntimeException e) {
            // 不打堆栈：Redis 抖动时这里会被高频触发，堆栈会淹没日志。
            log.warn("重放防护不可用，本次放行（幂等键仍在兜底）");
            return true;
        }
    }
}
