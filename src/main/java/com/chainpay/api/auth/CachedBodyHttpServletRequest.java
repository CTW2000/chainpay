package com.chainpay.api.auth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 把请求体读进内存，使它可以被读<b>两次</b>。
 *
 * <p><b>为什么需要这个类 —— 一个 Servlet 的经典陷阱：</b>
 *
 * <p>{@code HttpServletRequest.getInputStream()} 是一个<b>只能读一次的流</b>。
 * 签名验证必须读 body（因为 body 是被签名的内容之一），
 * 而读完之后，控制器里的 {@code @RequestBody} 就拿到一个空流 ——
 * 表现是「签名验过了，但业务收到的请求体是空的」。
 *
 * <p>这类 bug 特别难查，因为<b>两边的代码单独看都是对的</b>。
 *
 * <p>解法是在过滤器最外层把 body 一次性读进 byte 数组，
 * 之后无论谁来读，都从这个数组里给。
 *
 * <p><b>代价：整个请求体会驻留内存。</b>所以必须有大小上限 ——
 * 否则一个几 GB 的请求体就能把服务打死。上限由调用方（过滤器）负责检查。
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /** 原始请求体字节。签名计算用它，保证和控制器读到的是同一份数据。 */
    public byte[] body() {
        return body;
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return buffer.read();
            }

            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // 我们是同步读内存数组，不需要异步读取的回调。
                throw new UnsupportedOperationException("不支持异步读取");
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
