package com.chainpay.admin.controller;

import com.chainpay.admin.domain.AdminSession;
import com.chainpay.admin.service.AdminAuthService;
import com.chainpay.common.web.ApiResponse;
import com.chainpay.security.filter.AdminAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录 / 退出 / 再认证 / 改口令 / 我是谁。登录也只认回环 + 无代理头（过滤器管）；令牌只在登录响应里出现一次。 */
@RestController
@RequestMapping("/admin/v1/auth")
public class AdminAuthController {

    public record LoginRequest(@NotBlank @Size(max = 32) String username, @NotBlank @Size(max = 256) String password) {}
    public record LoginView(String token, String expiresAt, String username) {}
    public record PasswordRequest(@NotBlank @Size(max = 256) String password) {}
    public record ChangePasswordRequest(@NotBlank @Size(max = 256) String current, @NotBlank @Size(max = 256) String next) {}
    public record MeView(String username, boolean reauthFresh, String expiresAt, String lastSeenAt) {}

    private final AdminAuthService auth;

    public AdminAuthController(AdminAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public ApiResponse<LoginView> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AdminAuthService.Issued issued = auth.login(request.username(), request.password(), http.getRemoteAddr());
        return ApiResponse.ok(new LoginView(issued.token(), issued.expiresAt().toString(), request.username()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest http) {
        auth.logout(session(http));
        return ApiResponse.ok(null);
    }

    @PostMapping("/reauth")
    public ApiResponse<Void> reauth(@Valid @RequestBody PasswordRequest request, HttpServletRequest http) {
        auth.reauth(session(http), request.password());
        return ApiResponse.ok(null);
    }

    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpServletRequest http) {
        auth.changePassword(session(http), request.current(), request.next());
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<MeView> me(HttpServletRequest http) {
        AdminSession s = session(http);
        return ApiResponse.ok(new MeView(s.username(), auth.reauthFresh(s), s.expiresAt().toString(), s.lastSeenAt().toString()));
    }

    private static AdminSession session(HttpServletRequest http) {
        return (AdminSession) http.getAttribute(AdminAuthFilter.SESSION_ATTRIBUTE);
    }
}
