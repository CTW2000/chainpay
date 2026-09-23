package com.chainpay.common.web;

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
 * <pre>
 *   Tomcat --&gt; 过滤器链 --&gt; DispatcherServlet --&gt; 控制器 --&gt; 消息转换器 --&gt; JSON
 *              ^^^^^^^^                                     ^^^^^^^^^^
 *              认证失败在这里就 return 了                      所以永远走不到这里
 * </pre>
 *
 * <p>消息转换器（Jackson）转换的是控制器方法的返回值；过滤器提前 return 时没有返回值可转换，必须自己把字节写进响应体。
 *
 * <p>这里序列化的是<b>同一个</b> {@link ApiResponse} 对象（用注入的 {@link ObjectMapper}），信封的形状只有一份定义：
 * 给 record 加字段，两条路径自动同步。别在过滤器里手写 JSON 字符串——那是第二份定义，迟早和 record 走散
 * （而且只在认证失败时暴露），还不做 JSON 转义。
 */
@Component
public class ErrorResponseWriter {

    /**
     * 注意包名是 {@code tools.jackson}（Spring Boot 4 带的 Jackson 3），不是 2.x 的 {@code com.fasterxml.jackson}
     * （注解那一部分例外，仍在 {@code com.fasterxml.jackson.annotation}）。照抄 2.x 示例会得到一个看起来像「少了依赖」的编译错误。
     */
    private final ObjectMapper objectMapper;

    public ErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写一个错误信封。
     *
     * <p>用 {@code writeValueAsString} 再 write，而不是 {@code objectMapper.writeValue(response.getWriter(), ...)}：
     * 后者默认会<b>关闭</b>传进去的 Writer（{@code AUTO_CLOSE_TARGET}），而那是容器的响应 Writer，该由容器关。
     */
    public void write(HttpServletResponse response, HttpStatus status,
                      ErrorCode code, String msg) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, msg)));
    }
}
