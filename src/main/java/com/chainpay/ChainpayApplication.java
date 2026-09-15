package com.chainpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * chainpay —— 加密支付网关（学习项目）。
 *
 * <p>当前进度：M0 · 账本地基。见 {@code LEARNING-PATH.md}。
 */
@SpringBootApplication
public class ChainpayApplication {

    public static void main(String[] args) {
        for (String a : args) {
            if ("--migrate-only".equals(a)) {                       // M6-④：部署脚本在切换之前单独跑迁移，成功 0、失败非 0
                System.exit(com.chainpay.ops.Migrate.run(args).exitCode());
            }
            if ("--create-admin".equals(a)) {                       // M6-⑤：第一个管理员；口令只从环境变量 CHAINPAY_ADMIN_PASSWORD 来
                System.exit(com.chainpay.ops.AdminBootstrap.run(args, System.getenv()).exitCode());
            }
        }
        SpringApplication.run(ChainpayApplication.class, args);
    }
}
