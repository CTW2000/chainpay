package com.chainpay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.security.filter.ApiKeyAuthFilter;
import com.chainpay.support.AbstractPostgresTest;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 请求体上限的守卫。
 *
 * <p><b>为什么不能只写一条「发 2 MB → 期望 413」的 HTTP 测试：</b>
 * 那条对错误的实现也是绿的——先把 2 MB 全读进堆、再量大小、再回 413，照样 413。
 * 它证明不了「有界」。质询扫描（7.9 / 1.5）抓到的正是这个：
 * 常量、注释、检查都在，但检查跑在无界读取之后，攻击者的字节早已进堆。
 *
 * <p>唯一能区分对错的输入是<b>一条永远不结束的流</b>：
 * 有界读取在读满上限那一刻停手，返回 413；
 * 「读到底」的实现永远读不完——测试超时。
 */
@SpringBootTest
@DisplayName("M1 · 请求体上限契约")
class BodyLimitTest extends AbstractPostgresTest {

    @Autowired
    private ApiKeyAuthFilter filter;

    /** 无限流先老实给这么多字节，之后永远阻塞——远大于 1 MB 上限，但不至于炸 JVM。 */
    private static final long STALL_AFTER_BYTES = 4L * 1024 * 1024;

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("★ 永不结束的请求体 —— 必须在有限时间内被拒，且不进入业务链")
    void endlessBodyIsRejectedInBoundedTime() throws Exception {
        // ★ 第一版的无限流是「永远返回 'x'」，结果对未修复的代码不是超时而是
        // OutOfMemoryError: Required array size too large——readAllBytes 一路涨到
        // 2 GB 数组上限，把 Surefire 的 fork 整个炸掉，连「跑了 1 个」都报不出来。
        // 那恰好证明了漏洞的真实后果：不是「最终会 413」，是 JVM 直接没了。
        // 这一版先给 4 MB（远超 1 MB 上限），再永远阻塞：
        //   有界读取 → 在 1 MB + 1 处停手，根本走不到阻塞点，立刻 413
        //   读到底   → 卡在阻塞点，被 @Timeout 的独立线程打断，干净地红
        var mock = new MockHttpServletRequest("POST", "/api/v1/transfers");
        mock.setContentType("application/json");
        // 不设 Content-Length：模拟 chunked 编码——服务端无从预判长度，只能边读边数
        var stall = new CountDownLatch(1);
        var endless = new HttpServletRequestWrapper(mock) {
            @Override
            public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    private long served;
                    @Override public int read() throws java.io.IOException {
                        if (served++ < STALL_AFTER_BYTES) return 'x';
                        try { stall.await(); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("interrupted", e);
                        }
                        return -1;
                    }
                    @Override public boolean isFinished() { return false; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener l) { }
                };
            }
        };
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(endless, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString())
                .as("413 也必须走信封，不能是裸状态码 + 空体")
                .contains("\"code\":\"2007\"")
                .contains("\"data\":null");
        assertThat(chain.getRequest())
                .as("被拒的请求不能进入业务链")
                .isNull();
    }
}
