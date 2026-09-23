package com.chainpay.security.filter;

import java.util.regex.Pattern;
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
 * 每个 {@code /api/} 请求进业务代码之前，先在这里验签、查重放、限频。
 *
 * <pre>
 *   ① 请求体上限、nonce 格式      —— 只看长度与请求头，不碰任何外部系统
 *   ② 验签名                      —— 计算 HMAC、查库、解密，有真实成本；
 *                                    失败按来源 IP 计数，超过阈值回 429 而不是 401
 *   ③ 重放登记（Redis）            —— 只登记验证过的请求
 *   ④ 请求配额限流（按 API key）   —— 认证通过后才知道是谁，才能按 key 计
 * </pre>
 *
 * <p>配额必须在验签之后：认证成功之前<b>我们不知道调用方是谁</b>——它给的 api key 可能根本不存在，
 * 按一个攻击者能随意伪造的字段限流，等于没限。
 *
 * <p>注意按 IP 的失败计数是在验签<b>失败之后</b>才记、才查：它把失败的回答从 401 换成 429，
 * 但不省下验签的成本，也不拦验签通过的请求。
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_API_KEY = "X-CP-API-KEY";
    public static final String HEADER_TIMESTAMP = "X-CP-API-TIMESTAMP";
    public static final String HEADER_SIGNATURE = "X-CP-API-SIGN";
    /** 一次性随机串，客户端每个请求生成一个新的。参与签名，所以改不了。 */
    public static final String HEADER_NONCE = "X-CP-API-NONCE";

    /**
     * nonce 的长度，必须<b>正好</b>这么多个十六进制字符（16 字节 = 128 位随机，同一商户 10 秒内撞车的概率可忽略）。
     *
     * <p><b>① 上限：防止拿 nonce 打我们。</b>不限长的话，攻击者每个请求塞一个 1MB 的 nonce，
     * Redis 内存几分钟就被吃光——这个「保护措施」本身变成了一条攻击通道（和 {@link #MAX_BODY_BYTES} 同一个道理）。
     *
     * <p><b>② 只含十六进制：</b>nonce 里不可能出现换行，CP2 规范串的分段才无歧义（见 {@code ApiCredentialService.prehash}）；
     * 定长还让 Redis 里的重放键大小可预期。
     */
    private static final int NONCE_HEX_LENGTH = 32;
    /** 恰好 32 个十六进制字符。格式在**验签之前**检查，见 doFilterInternal 里的说明。 */
    private static final Pattern NONCE_PATTERN = Pattern.compile("[0-9a-fA-F]{" + NONCE_HEX_LENGTH + "}");

    /** 认证成功后，商户 id 放在这个请求属性里，供控制器读取。 */
    public static final String ATTR_MERCHANT_ID = "chainpay.merchantId";
    public static final String ATTR_MERCHANT_CODE = "chainpay.merchantCode";

    /**
     * 请求体大小上限。
     *
     * <p>为了算签名，整个请求体必须先读进内存，而且发生在认证<b>之前</b>、不需要任何凭证：
     * <b>没有上限的话，一个几 GB 的请求体就能把服务的内存吃光</b>。上限必须是<b>读的过程中的边界</b>，
     * 不能是读完之后量一下（见 doFilterInternal 第 ② 步）。
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
     * 只保护 {@code /api/} 开头的路径，写法是<b>默认拦截</b>，而不是「列出需要保护的接口」：
     * 后者每加一个新接口都要记得登记，忘一次就是一个没人管的接口，可能几个月都没人发现；
     * 默认拦截忘了配的后果是「接口打不开」，立刻就会被发现。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String clientIp = clientIp(request);

        // ① Content-Length 快路径：诚实声明了超大长度的请求，一个字节都不读。
        //    它**不能单独存在**——chunked 编码没有 Content-Length，② 才是防线。
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            payloadTooLarge(response);
            return;
        }

        // ② 有界读取：最多读 MAX + 1 字节就停手。
        //
        // 不能先读完再量：那样几 GB 的请求体早已进堆，一条永不结束的流会让 OutOfMemoryError 带走整个 JVM——
        // 而这发生在验签之前（读 body 是验签的前提），不需要任何凭证。
        // readNBytes(n) 最多读 n 字节就返回，第 n+1 个字节之后的内容永远不进堆。
        // +1 是承重的：readNBytes(MAX) 读满后无从知道后面还有没有，只能把截断了的超长请求当合法请求处理——静默失效。
        // 多读一个字节，才分得清「恰好 1 MB」和「超过 1 MB」。
        //
        // 读完必须用 CachedBodyHttpServletRequest 包一层，否则控制器读到的是空流：
        // HttpServletRequest 的输入流只能读一次。
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            payloadTooLarge(response);
            return;
        }
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request, body);

        String apiKey = request.getHeader(HEADER_API_KEY);

        String nonce = request.getHeader(HEADER_NONCE);

        // ★ nonce 的格式在验签之前检查 ★
        // CP2 规范串靠「每段不含换行」保证无歧义，「nonce 只含十六进制」这个前提必须在**算签名之前**就成立。
        // 它只看请求头：不分配、不落库、不碰任何外部系统，和上面的 Content-Length 检查同一性质；
        // 碰 Redis 的重放登记则仍在验签之后（见下）。
        if (nonce == null || !NONCE_PATTERN.matcher(nonce).matches()) {
            unauthorized(response);
            return;
        }

        var merchant = credentials.authenticate(new SignedRequest(
                apiKey,
                parseTimestamp(request.getHeader(HEADER_TIMESTAMP)),
                nonce,
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
            // 分别回「缺少请求头」「key 不存在」「签名不对」「时间戳过期」，等于告诉攻击者
            // 「你猜的 key 是真的，只是签名错了」，可用于枚举；「时间戳过期」还会泄露服务器的时钟。
            unauthorized(response);
            return;
        }

        // ★ 认证通过**不**清失败计数 ★
        // 否则持有一把合法凭证的攻击者用自己的 key 成功一次，就能把同一 IP 上探测别人 key 的失败记录整桶清掉。
        // 固定窗口计数器只该有一条重置路径：TTL 到期——攻击者控制不了时间。
        // 诚实客户端连续失败 10 次说明签名实现坏了，429 + Retry-After 正是对它有用的信号。

        // ★ 重放检查放在验签之后 ★
        // 放在之前的话，任何人拿一个瞎编的 nonce 就能往 Redis 里塞垃圾，
        // 这个「保护措施」本身会变成一条不需要凭证的内存耗尽通道。
        // 放在之后，登记表里只会有**验证过的**请求（长度与字符集已由 NONCE_PATTERN 限定）。
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
     * 取客户端 IP —— <b>只信 TCP 对端，不解析任何转发头</b>。
     *
     * <p>转发头是客户端自己能填的：读 X-Forwarded-For 的第一段的话，每个请求换一个假 IP 就是一个新桶，
     * 认证失败限流对攻击者不存在（常见的 {@code $proxy_add_x_forwarded_for} 是<b>追加</b>不是覆写，第一段仍是客户端填的）。
     *
     * <p>「前面有没有代理、信任哪几跳」是基础设施层的知识，不该出现在应用代码里。
     * 将来前面加反代时配 {@code server.forward-headers-strategy=native} + {@code server.tomcat.remoteip.internal-proxies}：
     * Tomcat 的 RemoteIpValve 从 XFF 右侧跳过可信代理、把第一个不可信 IP 写进 getRemoteAddr()——<b>代码一行不动</b>。
     * 漏配的后果是所有客户端落进反代那一个桶、成批 429，很快就会被发现，不是静默放行。
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * 路径要连查询串一起签：只签路径的话，{@code /api/v1/withdrawals?status=QUEUED} 的签名
     * 可以被拿去请求 {@code /api/v1/withdrawals?status=FAILED} —— 查询参数没被保护。
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

    /**
     * 413 也走信封：裸状态码 + 空体的话，客户端按信封解析会失败，多半当传输失败去重试一个永远失败的请求。
     */
    private void payloadTooLarge(HttpServletResponse response) throws IOException {
        errors.write(response, HttpStatus.CONTENT_TOO_LARGE,
                ErrorCode.PAYLOAD_TOO_LARGE, "请求体超过 1 MB 上限");
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        errors.write(response, HttpStatus.UNAUTHORIZED,
                ErrorCode.UNAUTHORIZED, "签名校验失败");
    }

    /**
     * 重放同样回 401：它和「签名无效」在客户端看来都是「这个请求不能再用了」。
     *
     * <p>码不同（1002 vs 1001）是为了让客户端知道<b>该怎么办</b>：换一个新 nonce 重签后重发即可；
     * 上一次可能已经成功了，保持同一个幂等键就不会重复执行。
     *
     * <p><b>已知的一处不理想</b>：很多通用 HTTP 客户端遇到 401 会去刷新凭证再重试，而这里凭证是好的。
     * 保留 401 是因为它确实是「这次认证不被接受」；正确的引导靠错误码和文档，不靠状态码。
     */
    private void replayed(HttpServletResponse response) throws IOException {
        errors.write(response, HttpStatus.UNAUTHORIZED,
                ErrorCode.REPLAYED, "该 nonce 已使用过，请换一个新 nonce 重新签名后重发");
    }

    /**
     * 429 必须带 {@code Retry-After}：不带的话客户端不知道该等多久，会立刻重试——<b>限流反而制造了更多请求</b>。
     */
    private void tooManyRequests(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        errors.write(response, HttpStatus.TOO_MANY_REQUESTS,
                ErrorCode.RATE_LIMITED, "请求过于频繁");
    }
}
