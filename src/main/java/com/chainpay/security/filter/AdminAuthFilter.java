package com.chainpay.security.filter;

import com.chainpay.admin.domain.AdminSession;
import com.chainpay.admin.service.AdminAuthService;
import com.chainpay.common.web.ErrorCode;
import com.chainpay.common.web.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 控制面的门卫：只有<b>本机</b>发起、不经代理、且带着<b>活着的管理员会话</b>的请求才能进 {@code /admin/}。
 *
 * <p><b>控制面必须和数据面用完全不同的认证：</b>如果商户能用自己的 API 凭证去调「发放凭证」接口，
 * 一把泄露的钥匙就能配出第二把——商户吊销了泄露的那把，攻击者新配的那把<b>还活着</b>。
 * <b>能配钥匙的钥匙，吊销不掉。</b>
 *
 * <p><b>两层限制，缺一不可：</b>
 *
 * <pre>
 *   (1) 管理员会话   登录过的人才能调（口令 Argon2id、闲置 30 分钟失效、12 小时到点失效）
 *   (2) 本机地址     只有能登上这台服务器的人才能调
 * </pre>
 *
 * <p>只有会话：令牌泄露的那段时间里它仍是万能的；只有本机：同机反代会让这层保护<b>完全失效而且看不出来</b>
 * （见 {@link #cameThroughProxy}）。
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    /** 会话令牌的请求头。 */
    public static final String HEADER_ADMIN_SESSION = "X-CP-ADMIN-SESSION";
    /** 过滤器认完人之后把会话放在请求属性里，控制器与再认证拦截器从这里拿。 */
    public static final String SESSION_ATTRIBUTE = AdminSession.class.getName();
    static final String LOGIN_PATH = "/admin/v1/auth/login";

    private static final String[] PROXY_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Forwarded", "X-Forwarded-Host"
    };

    private final AdminAuthService auth;
    private final ErrorResponseWriter errors;

    public AdminAuthFilter(AdminAuthService auth, ErrorResponseWriter errors) {
        this.auth = auth;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    /**
     * 三步：① 不经代理且来自回环——登录接口也要；② 除登录外要一个活着的会话；③ 无论结果如何，每次调用都在 admin_action 留一行
     * （登录由服务自己记，带用户名与成败）。三种失败给同一个回答：分别回答等于告诉探测者「只差哪一半」。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean login = LOGIN_PATH.equals(request.getRequestURI());
        AdminSession session = null;
        if (cameThroughProxy(request) || !isLoopback(request)) {
            reject(request, response, login);
            return;
        }
        if (!login) {
            session = auth.authenticate(request.getHeader(HEADER_ADMIN_SESSION)).orElse(null);
            if (session == null) {
                reject(request, response, false);
                return;
            }
            request.setAttribute(SESSION_ATTRIBUTE, session);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (!login) {
                record(request, response.getStatus(), session);
            }
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, boolean login) throws IOException {
        errors.write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "无权访问管理接口");
        if (!login) {
            record(request, HttpStatus.UNAUTHORIZED.value(), null);
        }
    }

    private void record(HttpServletRequest request, int status, AdminSession session) {
        try {
            auth.recordAction(session, request.getMethod(), request.getRequestURI(), status, request.getRemoteAddr());
        } catch (RuntimeException e) {
            log.error("管理操作没能记进 admin_action（{} {} → {}）：{}", request.getMethod(), request.getRequestURI(), status, e.toString());
        }
    }

    /**
     * 请求是否经过了反向代理。同机的反代从回环地址连进来，公网上任何人的请求经它转发都能通过回环检查；
     * 所以转发头在这里不是「客户端是谁」的答案，而是「这个请求不是本机发起的」的证据，有就拒绝。
     */
    private boolean cameThroughProxy(HttpServletRequest request) {
        for (String header : PROXY_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** 源地址是否是回环地址（127.0.0.1 / ::1）。解析不出来当作不可信：失败方向指向「拒绝」。 */
    private boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
