package com.chainpay.api.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 控制面的门卫：只有<b>本机</b>发起、且<b>带正确管理员令牌</b>的请求才能进 {@code /admin/}。
 *
 * <p><b>为什么控制面必须和数据面用完全不同的认证：</b>
 *
 * <p>如果商户能用自己的 API 凭证去调「发放凭证」接口，就出现了
 * <b>权限提升</b>：一把泄露的钥匙可以配出第二把。商户发现泄露、吊销了泄露的那把，
 * 攻击者手上新配的那把<b>还活着</b>。
 * <b>能配钥匙的钥匙，吊销不掉。</b>
 *
 * <p>币安、OKX 的做法是彻底不给这条路：API key 只能在网页控制台里创建，
 * 要登录密码 + 2FA，<b>创建 key 这件事根本没有 API</b>。
 * 我们没有用户体系和 2FA（那是另一个里程碑），所以用两层更简单的限制代替。
 *
 * <p><b>两层限制，缺一不可：</b>
 *
 * <pre>
 *   (1) 管理员令牌   知道秘密的人才能调
 *   (2) 本机地址     只有能登上这台服务器的人才能调
 * </pre>
 *
 * <p>为什么两层都要：
 * <ul>
 *   <li>只有令牌 —— 它是一把万能钥匙，泄露一次全平台沦陷，
 *       而且它会出现在部署脚本、CI 变量、运维的终端历史里</li>
 *   <li>只有本机 —— 见下面 {@link #cameThroughProxy} 那段，
 *       同机反代会让这层保护<b>完全失效而且看不出来</b></li>
 * </ul>
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_ADMIN_TOKEN = "X-CP-ADMIN-TOKEN";

    /**
     * 表明「这个请求是被转发过来的」的请求头。
     *
     * <p>同一批请求头，在数据面是<b>身份信息</b>（用来按真实 IP 限流），
     * 在控制面是<b>拒绝的理由</b>。同一个东西两种用法，取决于你在问什么问题：
     * 「客户端是谁」还是「这个请求有没有经过第三方」。
     */
    private static final String[] PROXY_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Forwarded", "X-Forwarded-Host"
    };

    private final byte[] expectedToken;

    public AdminAuthFilter(@Value("${chainpay.admin-token}") String adminToken) {
        // 启动即校验：令牌太短等于没有。
        // 让「配错了就起不来」，而不是「配错了但看起来正常」。
        if (adminToken == null || adminToken.length() < 32) {
            throw new IllegalStateException(
                    "chainpay.admin-token 必须至少 32 个字符。生成：openssl rand -hex 32");
        }
        this.expectedToken = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 只管 {@code /admin/} 开头的路径。
     *
     * <p>和 {@code ApiKeyAuthFilter} 一样是<b>默认拦截</b>：
     * 新加的管理接口自动被保护，忘了配的后果是「接口打不开」，会立刻被发现。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (cameThroughProxy(request) || !isLoopback(request) || !hasValidToken(request)) {
            // 三种失败给同一个回答。
            // 分别回答「令牌错了」和「你的 IP 不对」，等于告诉探测者
            // 「令牌是对的，只差网络位置」—— 那正是他最想知道的一半答案。
            unauthorized(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 请求是否经过了反向代理。
     *
     * <p><b>这是本类里最容易被忽略、后果最严重的一处。</b>
     *
     * <p>「只允许本机调用」这层保护有一个默认前提：<b>远程请求的源地址不是回环地址</b>。
     * 而典型部署恰恰打破这个前提 ——
     *
     * <pre>
     *   公网用户 --&gt; nginx（同一台机器）--&gt; 应用
     *                                       getRemoteAddr() 返回 127.0.0.1
     * </pre>
     *
     * <p>于是<b>全世界的请求看起来都来自本机</b>，回环检查形同虚设，
     * 而且不会有任何报错、任何日志、任何异常 —— 它只是悄悄地不再起作用。
     *
     * <p>代理转发时一定会加上 {@code X-Forwarded-For} 之类的头。
     * 这些头在这里不是「客户端是谁」的答案，而是「这个请求不是本机发起的」的证据。
     *
     * <p><b>已知残留风险</b>：如果反代被配置成不加任何转发头，这层就失效了，
     * 那时只剩管理员令牌在守。这正是为什么令牌和地址两层都要有 ——
     * 任何一层被绕过，另一层还在。
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

    /** 源地址是否是回环地址（127.0.0.1 / ::1）。 */
    private boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            // 解析不出来就当作不可信。失败方向指向「拒绝」，不是「放行」。
            return false;
        }
    }

    /**
     * 令牌比对。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
     * 后者一发现字符不同就返回，「前 1 个字符对」和「前 30 个字符对」耗时不同，
     * 理论上可以被逐字符试探出来。和验签那里是同一个理由。
     */
    private boolean hasValidToken(HttpServletRequest request) {
        String provided = request.getHeader(HEADER_ADMIN_TOKEN);
        if (provided == null || provided.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8));
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"无权访问管理接口\"}");
    }
}
