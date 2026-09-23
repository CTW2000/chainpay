package com.chainpay.ops;

import com.chainpay.admin.service.AdminAuthException;
import com.chainpay.admin.service.AdminAuthProperties;
import com.chainpay.admin.service.AdminAuthService;
import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * 第一个管理员怎么来：{@code java -jar chainpay.jar --create-admin <用户名>}，口令只从环境变量 CHAINPAY_ADMIN_PASSWORD 来——
 * 不进参数（ps 能看到）、不进文件。和 {@link Migrate} 一样只起一个最小上下文（数据源），不装配应用。
 */
public final class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    static final String PASSWORD_ENV = "CHAINPAY_ADMIN_PASSWORD";

    public record Outcome(int exitCode, String detail) {}

    @Configuration
    @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
    static class BootstrapConfig {}

    private AdminBootstrap() {}

    public static Outcome run(String[] args, Map<String, String> env) {
        String username = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--create-admin".equals(args[i])) {
                username = args[i + 1];
            }
        }
        if (username == null || username.startsWith("--")) {
            return fail("用法：--create-admin <用户名>，口令放环境变量 " + PASSWORD_ENV);
        }
        String password = env.get(PASSWORD_ENV);
        if (password == null || password.isBlank()) {
            return fail("没有环境变量 " + PASSWORD_ENV + "：口令只从它来，不进参数");
        }
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(BootstrapConfig.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off").run(args)) {
            AdminAuthService auth = new AdminAuthService(JdbcClient.create(ctx.getBean(DataSource.class)),
                    Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), Clock.systemUTC(), AdminAuthProperties.defaults());
            long id = auth.createUser(username, password);
            String detail = "管理员 " + username + " 已创建（id " + id + "）";
            log.info(detail);
            return new Outcome(0, detail);
        } catch (AdminAuthException e) {
            return fail(e.getMessage());
        } catch (RuntimeException e) {
            return fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Outcome fail(String detail) {
        log.error("创建管理员失败：{}", detail);
        return new Outcome(1, detail);
    }
}
