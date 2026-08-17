package com.chainpay.api.auth;

import com.chainpay.api.auth.ApiCredentialService.SignedRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    public ApiKeyAuthFilter(ApiCredentialService credentials, RateLimiter rateLimiter) {
        this.credentials = credentials;
        this.rateLimiter = rateLimiter;
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

        var merchant = credentials.authenticate(new SignedRequest(
                apiKey,
                parseTimestamp(request.getHeader(HEADER_TIMESTAMP)),
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
        writeJson(response, HttpStatus.UNAUTHORIZED,
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"签名校验失败\"}");
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
        writeJson(response, HttpStatus.TOO_MANY_REQUESTS,
                "{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁\"}");
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, String json)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 响应体里不含任何内部细节：不说是哪一步失败的，也不回显收到的 key。
        response.getWriter().write(json);
    }
}
