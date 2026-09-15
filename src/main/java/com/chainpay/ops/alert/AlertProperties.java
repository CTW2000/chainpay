package com.chainpay.ops.alert;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param webhookUrl   告警出口。空 = 只打日志。按密码对待，只从环境变量来
 * @param format       载荷形状：generic / slack / dingtalk / feishu
 * @param interval     多久看一眼 work 组
 * @param initialDelay 启动后多久开始看：给中间件和索引器就位的时间
 */
@ConfigurationProperties("chainpay.alert")
public record AlertProperties(String webhookUrl, @DefaultValue("generic") WebhookSender.Format format,
                              @DefaultValue("30s") Duration interval, @DefaultValue("1m") Duration initialDelay) {}
