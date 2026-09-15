package com.chainpay.ops.alert;

import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 告警永远装配：没配 webhook 也要有人把变化打进 ERROR 日志。 */
@Configuration
@EnableConfigurationProperties(AlertProperties.class)
class AlertConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertConfig.class);

    @Bean
    WebhookSender webhookSender(AlertProperties p) {
        Optional<URI> url = Optional.ofNullable(p.webhookUrl()).filter(s -> !s.isBlank()).map(String::trim).map(URI::create);
        url.ifPresent(u -> {
            if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme())) {
                throw new IllegalStateException("CHAINPAY_ALERT_WEBHOOK_URL 必须是 http(s) 地址");
            }
        });
        WebhookSender sender = new WebhookSender(url, p.format());
        log.info("告警出口：{}，每 {} 看一眼 work 组，启动后 {} 开始", sender.describe(), p.interval(), p.initialDelay());
        return sender;
    }

    @Bean
    AlertScheduler alertScheduler(HealthEndpoint health, WebhookSender sender) {
        return new AlertScheduler(() -> health.healthForPath(AlertScheduler.GROUP), new AlertPolicy(), sender);
    }
}
