package com.chainpay.api;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 让<b>过滤器</b>也能吐出和控制器一模一样的响应信封。
 *
 * <p><b>为什么需要这个类 —— 先要理解请求是怎么走的：</b>
 *
 * <pre>
 *   Tomcat --&gt; 过滤器链 --&gt; DispatcherServlet --&gt; 控制器 --&gt; 消息转换器 --&gt; JSON
 *              ^^^^^^^^                                     ^^^^^^^^^^
 *              认证失败在这里就 return 了                      所以永远走不到这里
 * </pre>
 *
 * <p>消息转换器（Jackson）转换的是<b>控制器方法的返回值</b>。
 * 过滤器提前 return 时，控制器根本没被调用，也就没有「返回值」可转换 ——
 * 那条自动流水线整个用不上，过滤器必须自己把字节写进响应体。
 *
 * <p><b>第一版是手写 JSON 字符串，那是个次优解：</b>
 * 信封的形状被定义了两次（{@link ApiResponse} 这个 record 一次，字符串模板一次），
 * 靠一条测试守着两份定义别走散。
 * 真实的失败长这样：有人给信封加一个 {@code requestId} 字段，
 * 改了 record，<b>完全不知道过滤器里还有一份手写的</b> ——
 * 于是正常响应有这个字段、认证失败的响应没有。
 * 而这个 bug 只在「认证失败」时出现，开发环境几乎碰不到，
 * 它会在生产、在某个商户第一次配错凭证的那一刻暴露。
 *
 * <p><b>这一版让形状只有一份定义。</b>过滤器虽然拿不到那条自动流水线，
 * 但 {@link ObjectMapper} 本身是个普通的 Spring bean，注入进来直接用即可。
 * 序列化的是<b>同一个</b> {@link ApiResponse} 对象，
 * 于是给 record 加字段，两条路径自动同步 —— 「分叉」这件事在结构上不存在了。
 *
 * <p><b>能靠结构保证的，不要靠纪律保证。</b>
 * 测试「两份定义一致」是好事；<b>只留一份定义</b>更好 ——
 * 前者在守一个可能被破坏的约定，后者让那个约定根本不存在。
 *
 * <p>附带解决的隐患：手写版本不做 JSON 转义（当时的前提是 msg 全是代码里的常量）。
 * 一旦有人把用户输入放进 msg，一个引号就能把响应体拼坏。
 * 交给 Jackson 之后，转义是它的事。
 */
@Component
public class ErrorResponseWriter {

    /**
     * ★ 注意包名是 {@code tools.jackson}，不是 {@code com.fasterxml.jackson} ★
     *
     * <p>Spring Boot 4 带的是 <b>Jackson 3</b>，它把整个包名从
     * {@code com.fasterxml.jackson} 改成了 {@code tools.jackson}
     * （注解那一部分例外，仍在 {@code com.fasterxml.jackson.annotation}）。
     *
     * <p>网上和模型记忆里 99% 的 Jackson 示例都是 2.x 的写法，照抄会得到
     * {@code package com.fasterxml.jackson.databind does not exist} ——
     * 一个看起来像「少了依赖」、实际是「版本换了包名」的报错。
     *
     * <p>这是本项目第三个同类陷阱（前两个：Testcontainers 2.x 的坐标前缀、
     * Spring Boot 4 拆分后的 spring-boot-flyway）。
     * <b>共同点都是：报错信息指向的方向，和真正的原因不是一回事。</b>
     */
    private final ObjectMapper objectMapper;

    public ErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写一个错误信封。
     *
     * <p>用 {@code writeValueAsString} 再 write，而不是
     * {@code objectMapper.writeValue(response.getWriter(), ...)}：
     * 后者默认会<b>关闭</b>传进去的 Writer（{@code AUTO_CLOSE_TARGET}），
     * 而那是容器的响应 Writer，谁关它是容器的事，不是我们的事。
     * 错误响应都很小，先转成字符串没有任何代价。
     */
    public void write(HttpServletResponse response, HttpStatus status,
                      ErrorCode code, String msg) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, msg)));
    }
}
