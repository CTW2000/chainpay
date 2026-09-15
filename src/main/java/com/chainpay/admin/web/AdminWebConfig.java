package com.chainpay.admin.web;

import com.chainpay.admin.service.AdminAuthProperties;
import com.chainpay.admin.service.AdminAuthService;
import com.chainpay.common.web.ErrorResponseWriter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 装配：Argon2id 编码器、系统时钟、再认证拦截器（只挂 /admin/**）。 */
@Configuration
@EnableConfigurationProperties(AdminAuthProperties.class)
class AdminWebConfig implements WebMvcConfigurer {

    private final AdminAuthService auth;
    private final ErrorResponseWriter errors;

    AdminWebConfig(AdminAuthService auth, ErrorResponseWriter errors) {
        this.auth = auth;
        this.errors = errors;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminReauthInterceptor(auth, errors)).addPathPatterns("/admin/**");
    }

    @Configuration
    static class Beans {

        /** Argon2id，Spring Security 5.8 起的推荐参数（16 MiB、2 轮、并行 1）：一次约几十毫秒，暴力猜口令的成本在这里。 */
        @Bean
        PasswordEncoder passwordEncoder() {
            return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        }

        @Bean
        @ConditionalOnMissingBean(Clock.class)
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
