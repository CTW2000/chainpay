package com.chainpay.admin.web;

import com.chainpay.admin.domain.AdminSession;
import com.chainpay.admin.service.AdminAuthService;
import com.chainpay.common.web.ErrorCode;
import com.chainpay.common.web.ErrorResponseWriter;
import com.chainpay.security.filter.AdminAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 到了控制器门口才知道方法上有没有 {@link Sensitive}：有就看会话的 reauth_at 够不够新。过滤器认人，拦截器认操作。 */
public final class AdminReauthInterceptor implements HandlerInterceptor {

    private final AdminAuthService auth;
    private final ErrorResponseWriter errors;

    public AdminReauthInterceptor(AdminAuthService auth, ErrorResponseWriter errors) {
        this.auth = auth;
        this.errors = errors;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(Sensitive.class)) {
            return true;
        }
        Object attr = request.getAttribute(AdminAuthFilter.SESSION_ATTRIBUTE);
        if (!(attr instanceof AdminSession session)) {
            errors.write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "无权访问管理接口");
            return false;
        }
        if (!auth.reauthFresh(session)) {
            errors.write(response, HttpStatus.FORBIDDEN, ErrorCode.REAUTH_REQUIRED, "敏感操作要先用口令再认证：POST /admin/v1/auth/reauth");
            return false;
        }
        return true;
    }
}
