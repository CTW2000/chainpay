package com.chainpay.ops.alert;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 告警的出口（M6-③）：一个 HTTP webhook。地址只从环境变量来、按密码对待（很多入站 webhook 的令牌就在 URL 里），日志与描述里只有主机名。
 * 没配地址 = 只打 ERROR 日志，且算「送到」，否则策略会每轮重试一个不存在的出口。
 * 四种载荷：GENERIC 结构化 JSON（自己的接收端）、SLACK、DINGTALK、FEISHU 各家入站 webhook 的最小形状——都是协议编码，不带业务。
 */
public final class WebhookSender {

    public enum Format { GENERIC, SLACK, DINGTALK, FEISHU }

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Optional<URI> url;
    private final Format format;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public WebhookSender(Optional<URI> url, Format format) {
        this.url = url;
        this.format = format == null ? Format.GENERIC : format;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** @return 送到了吗。没配地址只打日志并返回 true。 */
    public boolean send(Alert alert) {
        if (url.isEmpty()) {
            log.error("告警（没配 CHAINPAY_ALERT_WEBHOOK_URL，只打日志）：{}", alert.text());
            return true;
        }
        String body;
        try {
            body = json.writeValueAsString(payload(alert));
        } catch (RuntimeException e) {
            log.error("告警载荷序列化失败：{}", e.toString());
            return false;
        }
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(url.get()).timeout(TIMEOUT).header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() / 100 == 2) {
                log.warn("告警已送到 {}：{}", describe(), alert.text());
                return true;
            }
            log.error("告警没送到（{} 回 {}），下一轮再叫：{}", describe(), r.statusCode(), alert.text());
            return false;
        } catch (IOException | RuntimeException e) {
            log.error("告警没送到（{}：{}），下一轮再叫：{}", describe(), e.getClass().getSimpleName(), alert.text());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 主机名而已：URL 的路径和查询串常带令牌。 */
    public String describe() {
        return url.map(u -> "webhook " + u.getHost() + "（" + format + "）").orElse("没配 webhook，只打日志");
    }

    Map<String, Object> payload(Alert a) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (format) {
            case SLACK -> m.put("text", a.text());
            case DINGTALK -> {
                m.put("msgtype", "text");
                m.put("text", Map.of("content", a.text()));
            }
            case FEISHU -> {
                m.put("msg_type", "text");
                m.put("content", Map.of("text", a.text()));
            }
            case GENERIC -> {
                m.put("service", "chainpay");
                m.put("component", a.component());
                m.put("from", a.from());
                m.put("to", a.to());
                m.put("recovered", a.recovered());
                m.put("details", a.details());
                m.put("at", a.at().toString());
                m.put("text", a.text());
            }
        }
        return m;
    }
}
