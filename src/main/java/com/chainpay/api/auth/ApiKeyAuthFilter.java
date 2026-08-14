package com.chainpay.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个请求进业务代码之前，先在这里验身份。
 *
 * <p><b>为什么用一个手写的过滤器，而不是 Spring Security：</b>
 * Spring Security 能力强，但它是一整套框架，行为大量藏在配置和自动装配里。
 * 本项目的原则是「越是不能错的地方，抽象越要薄」——
 * 认证是钱的第一道门，它做了什么必须一眼看得见。
 * 等需求复杂到手写划不来时（OAuth、多种登录方式、细粒度权限），再换不迟。
 *
 * <p><b>⚠️ 这一版把 secret 明文放在请求头里传，这是故意的中间态。</b>
 * 它能挡住「完全不带凭证的人」，但挡不住「看到过一次请求的人」。
 * 下一步用签名替换它，那时你会看到这中间差了什么。
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_API_KEY = "X-CP-API-KEY";
    public static final String HEADER_API_SECRET = "X-CP-API-SECRET";

    /** 认证成功后，商户 id 放在这个请求属性里，供控制器读取。 */
    public static final String ATTR_MERCHANT_ID = "chainpay.merchantId";
    public static final String ATTR_MERCHANT_CODE = "chainpay.merchantCode";

    private final ApiCredentialService credentials;

    public ApiKeyAuthFilter(ApiCredentialService credentials) {
        this.credentials = credentials;
    }

    /**
     * 只保护 {@code /api/} 开头的路径。
     *
     * <p>注意这里的写法是<b>默认拦截</b>，而不是「列出需要保护的接口」。
     * 后者每加一个新接口都要记得去登记，忘一次就是一个裸奔的接口 ——
     * 而人一定会忘。默认拦截的话，忘记登记的后果是「接口打不开」，
     * 会立刻被发现；反过来的后果是「接口没人管」，可能几个月都没人发现。
     *
     * <p><b>让失误的方向指向「立刻被发现」，而不是「悄悄地错」。</b>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        var merchant = credentials.authenticate(
                request.getHeader(HEADER_API_KEY),
                request.getHeader(HEADER_API_SECRET));

        if (merchant.isEmpty()) {
            // ★ 所有失败给同一个回答 ★
            //
            // 不能分别回「缺少请求头」「这个 key 不存在」「secret 不对」——
            // 那等于告诉攻击者「你猜的 key 是真的，只是密码错了」，
            // 他就能拿这个信息去枚举有效的 key。
            //
            // 对合法商户来说，信息少一点的代价是排查时要多问一句；
            // 对攻击者来说，信息多一点的代价是我们的钱。
            reject(response);
            return;
        }

        request.setAttribute(ATTR_MERCHANT_ID, merchant.get().merchantId());
        request.setAttribute(ATTR_MERCHANT_CODE, merchant.get().merchantCode());

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 响应体里不含任何内部细节：不说是哪一步失败的，也不回显收到的 key。
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"API 凭证无效\"}");
    }
}
