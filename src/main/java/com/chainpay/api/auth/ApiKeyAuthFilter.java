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
 * 每个请求进业务代码之前，先在这里验签名。
 *
 * <p><b>相比上一版的变化：不再接收 secret 明文。</b>
 * 客户端用 secret 对「时间戳 + 方法 + 路径 + 请求体」算一个 HMAC 签名，
 * 只把签名发过来；服务端用自己存的 secret 重算比对。<b>钥匙从不上路。</b>
 *
 * <pre>
 *   prehash = timestamp + method + path + body
 *   sign    = Base64(HMAC-SHA256(prehash, secret))
 * </pre>
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
     * 也就是说不需要任何凭证就能发起。
     *
     * <p>这是「为了做安全检查而引入新攻击面」的典型例子：
     * 加一层防护时要问一句，这层防护本身能不能被用来打我。
     */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final ApiCredentialService credentials;

    public ApiKeyAuthFilter(ApiCredentialService credentials) {
        this.credentials = credentials;
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

        // 先读 body —— 它是被签名的内容之一。
        // 读完必须用 CachedBodyHttpServletRequest 包一层，否则控制器读到的是空流：
        // HttpServletRequest 的输入流只能读一次。
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        if (body.length > MAX_BODY_BYTES) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request, body);

        var merchant = credentials.authenticate(new SignedRequest(
                request.getHeader(HEADER_API_KEY),
                parseTimestamp(request.getHeader(HEADER_TIMESTAMP)),
                request.getMethod(),
                fullPath(request),
                cached.bodyAsString(),
                request.getHeader(HEADER_SIGNATURE)));

        if (merchant.isEmpty()) {
            // ★ 所有失败给同一个回答 ★
            //
            // 不能分别回「缺少请求头」「这个 key 不存在」「签名不对」「时间戳过期」——
            // 那等于告诉攻击者「你猜的 key 是真的，只是签名错了」，可用于枚举；
            // 「时间戳过期」还会泄露服务器的时钟。
            reject(response);
            return;
        }

        cached.setAttribute(ATTR_MERCHANT_ID, merchant.get().merchantId());
        cached.setAttribute(ATTR_MERCHANT_CODE, merchant.get().merchantCode());

        // 往下传的是包装后的请求，控制器才能读到 body
        chain.doFilter(cached, response);
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

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 响应体里不含任何内部细节：不说是哪一步失败的，也不回显收到的 key。
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"签名校验失败\"}");
    }
}
