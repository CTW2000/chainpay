package com.chainpay.security.filter;

import com.chainpay.security.service.ApiCredentialService;
import com.chainpay.security.service.RateLimiter;
import com.chainpay.security.service.ReplayGuard;

import com.chainpay.common.web.ErrorCode;
import com.chainpay.common.web.ErrorResponseWriter;
import com.chainpay.security.service.ApiCredentialService.SignedRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个请求进业务代码之前，先在这里限频 + 验签名。
 *
 * <p><b>两道闸门的顺序是关键：</b>
 *
 * <pre>
 *   ① 认证失败次数限流（按 IP）  —— 挡住暴力破解，在验签之前就拒
 *   ② 验签名                    —— 计算 HMAC、查库、解密，有真实成本
 *   ③ 请求配额限流（按 API key） —— 认证通过后才知道是谁，才能按 key 计
 * </pre>
 *
 * <p>为什么 ① 必须在 ② 之前：验签要算 HMAC、要查数据库、要解密，
 * <b>这些成本攻击者不用付，我们要付</b>。先按 IP 拦住反复失败的来源，
 * 攻击流量就打不到昂贵的那一步上。
 *
 * <p>为什么 ③ 必须在 ② 之后：认证成功之前<b>我们不知道调用方是谁</b> ——
 * 它给的 api key 可能根本不存在。按一个攻击者能随意伪造的字段限流，等于没限。
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_API_KEY = "X-CP-API-KEY";
    public static final String HEADER_TIMESTAMP = "X-CP-API-TIMESTAMP";
    public static final String HEADER_SIGNATURE = "X-CP-API-SIGN";
    /** 一次性随机串，客户端每个请求生成一个新的。参与签名，所以改不了。 */
    public static final String HEADER_NONCE = "X-CP-API-NONCE";

    /**
     * nonce 的长度，必须<b>正好</b>这么多个十六进制字符（16 字节随机数）。
     *
     * <p><b>两个理由，两个都是承重的：</b>
     *
     * <p><b>① 上限：防止拿 nonce 打我们。</b>不限长的话，攻击者每个请求塞一个
     * 1MB 的 nonce，Redis 内存几分钟就被吃光 —— 这个「保护措施」本身
     * 变成了一条攻击通道。和 {@link #MAX_BODY_BYTES} 是同一个道理：
     * <b>加一层防护时要问一句，这层防护本身能不能被用来打我。</b>
     *
     * <p><b>② 定长：签名拼接不能有歧义。</b>prehash 是各段直接拼起来的，
     * 不加分隔符。nonce 若可变长，{@code "ab"+"c"} 和 {@code "a"+"bc"}
     * 会拼出同一个串、算出同一个签名，攻击者能据此构造
     * 「不同的请求、相同的签名」。定长把这个洞焊死。
     *
     * <p>16 字节 = 128 位随机。同一个商户在 10 秒窗口内偶然撞出两个相同 nonce 的
     * 概率约为 2 的负 128 次方级别 —— 比硬件出错的概率低得多。
     */
    private static final int NONCE_HEX_LENGTH = 32;

    /** 认证成功后，商户 id 放在这个请求属性里，供控制器读取。 */
    public static final String ATTR_MERCHANT_ID = "chainpay.merchantId";
    public static final String ATTR_MERCHANT_CODE = "chainpay.merchantCode";

    /**
     * 请求体大小上限。
     *
     * <p>为了算签名，整个请求体必须先读进内存。<b>没有上限的话，
     * 一个几 GB 的请求体就能把服务的内存吃光</b> —— 而且这发生在认证<b>之前</b>，
     * 不需要任何凭证。
     *
     * <p>这是「为了做安全检查而引入新攻击面」的典型例子：
     * 加一层防护时要问一句，这层防护本身能不能被用来打我。
     */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final ApiCredentialService credentials;
    private final RateLimiter rateLimiter;
    private final ReplayGuard replayGuard;
    private final ErrorResponseWriter errors;

    public ApiKeyAuthFilter(ApiCredentialService credentials, RateLimiter rateLimiter,
                            ReplayGuard replayGuard, ErrorResponseWriter errors) {
        this.credentials = credentials;
        this.rateLimiter = rateLimiter;
        this.replayGuard = replayGuard;
        this.errors = errors;
    }

    /**
     * 只保护 {@code /api/} 开头的路径。
     *
     * <p>写法是<b>默认拦截</b>，而不是「列出需要保护的接口」。
     * 后者每加一个新接口都要记得登记，忘一次就是一个裸奔的接口 —— 而人一定会忘。
     * 默认拦截忘了配的后果是「接口打不开」，会立刻被发现；
     * 反过来的后果是「接口没人管」，可能几个月都没人发现。
     *
     * <p><b>让失误的方向指向「立刻暴露」，而不是「悄悄地错」。</b>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String clientIp = clientIp(request);

        // 先读 body —— 它是被签名的内容之一。
        // 读完必须用 CachedBodyHttpServletRequest 包一层，否则控制器读到的是空流：
        // HttpServletRequest 的输入流只能读一次。
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        if (body.length > MAX_BODY_BYTES) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request, body);

        String apiKey = request.getHeader(HEADER_API_KEY);

        String nonce = request.getHeader(HEADER_NONCE);

        var merchant = credentials.authenticate(new SignedRequest(
                apiKey,
                parseTimestamp(request.getHeader(HEADER_TIMESTAMP)),
                nonce == null ? "" : nonce,
                request.getMethod(),
                fullPath(request),
                cached.bodyAsString(),
                request.getHeader(HEADER_SIGNATURE)));

        if (merchant.isEmpty()) {
            // 认证失败按 IP 计数。超过阈值后连 401 都不再回，直接 429 ——
            // 对应 OWASP Transaction_Authorization 2.4：失败达上限后整个流程重来。
            if (!rateLimiter.recordAuthFailure(clientIp)) {
                tooManyRequests(response, 60);
                return;
            }
            // ★ 所有认证失败给同一个回答 ★
            // 不能分别回「缺少请求头」「key 不存在」「签名不对」「时间戳过期」——
            // 那等于告诉攻击者「你猜的 key 是真的，只是签名错了」，可用于枚举；
            // 「时间戳过期」还会泄露服务器的时钟。
            unauthorized(response);
            return;
        }

        // 认证通过，清掉该来源的失败计数：正常用户不该被自己历史上的几次失败拖累。
        rateLimiter.clearAuthFailures(clientIp);

        // ★ 重放检查放在验签之后 ★
        // 放在之前的话，任何人拿一个瞎编的 nonce 就能往 Redis 里塞垃圾，
        // 这个「保护措施」本身会变成一条不需要凭证的内存耗尽通道。
        // 放在之后，登记表里只会有**验证过的**请求。
        //
        // ★ 长度校验必须在这里：验签之后、碰 Redis 之前 ★
        //
        // 不能放在验签**之前**：那样没有凭证的人也能触发它，
        // 而任何在认证之前就执行的逻辑都是一条免费的攻击面。
        //
        // 也不能省掉：验签**拦不住**超长 nonce。
        // nonce 是签名串的一部分，所以持有 secret 的调用方
        // 完全可以拿一个 1MB 的 nonce 算出**完全正确**的签名 ——
        // 验签会通过。挡住它的只有这里这一行。
        // 也就是说这道检查防的不是外部攻击者，而是**有合法凭证的调用方**
        // （被盗用的商户凭证、或者我们自己写错的客户端）。
        //
        // ApiContractTest.nonceLengthIsEnforced 断言的正是这一点：
        // 「签名算对了也不行」。把这行删掉，那条测试立刻变红 —— 已实测。
        if (nonce == null || nonce.length() != NONCE_HEX_LENGTH) {
            unauthorized(response);
            return;
        }
        if (!replayGuard.isFirstUse(apiKey, nonce)) {
            replayed(response);
            return;
        }

        if (!rateLimiter.allowRequest(apiKey)) {
            tooManyRequests(response, rateLimiter.secondsUntilWindowReset(apiKey));
            return;
        }

        cached.setAttribute(ATTR_MERCHANT_ID, merchant.get().merchantId());
        cached.setAttribute(ATTR_MERCHANT_CODE, merchant.get().merchantCode());

        // 往下传的是包装后的请求，控制器才能读到 body
        chain.doFilter(cached, response);
    }

    /**
     * 取客户端 IP。
     *
     * <p><b>⚠️ X-Forwarded-For 是客户端可以随便伪造的请求头。</b>
     * 只有在「请求一定经过我们自己的反向代理、且代理会覆写这个头」时才可信。
     * 直接暴露在公网的服务读这个头做限流，等于让攻击者每次换一个假 IP 绕过限流。
     *
     * <p>这里读它是因为我们的部署形态是 nginx 反代（见 M6）。
     * <b>如果哪天这个前提变了，这里必须改。</b>
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 该头可能是逗号分隔的链路，第一段是最初的客户端
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 路径要连查询串一起签。
     *
     * <p>只签路径不签查询串的话，{@code /accounts/2/balance} 的签名
     * 可以被拿去请求 {@code /accounts/2/balance?debug=true} —— 参数没被保护。
     */
    private String fullPath(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    /** 时间戳解析失败返回 0，会被时间窗校验拒掉 —— 不单独报错，避免泄露失败原因。 */
    private long parseTimestamp(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException | NullPointerException e) {
            return 0L;
        }
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        errors.write(response, HttpStatus.UNAUTHORIZED,
                ErrorCode.UNAUTHORIZED, "签名校验失败");
    }

    /**
     * 重放同样回 401：它和「签名无效」在客户端看来都是「这个请求不能再用了」。
     *
     * <p>码不同（1002 vs 1001）是为了让客户端知道<b>该怎么办</b> ——
     * 1002 明确告诉它「换一个新 nonce 重发即可；上一次可能已经成功了，
     * 保持同一个 clientTransferId 就不会重复扣款」。
     *
     * <p><b>已知的一处不理想</b>：很多通用 HTTP 客户端遇到 401 的默认反应是
     * 「去刷新凭证再重试」，而这里凭证是好的，需要的只是换 nonce 重发。
     * 保留 401 是因为它确实是「这次认证不被接受」；
     * 正确的引导靠错误码和文档，不靠状态码。
     */
    private void replayed(HttpServletResponse response) throws IOException {
        errors.write(response, HttpStatus.UNAUTHORIZED,
                ErrorCode.REPLAYED, "该 nonce 已使用过，请换一个新 nonce 重新签名后重发");
    }

    /**
     * 429 必须带 {@code Retry-After}。
     *
     * <p>不带的话，客户端只知道「被拒了」，不知道该等多久，
     * 于是它会立刻重试 —— <b>限流反而制造了更多请求</b>。
     * OWASP REST Security 把 429 单列出来，正是因为它是给机器读的指令。
     */
    private void tooManyRequests(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        errors.write(response, HttpStatus.TOO_MANY_REQUESTS,
                ErrorCode.RATE_LIMITED, "请求过于频繁");
    }
}
