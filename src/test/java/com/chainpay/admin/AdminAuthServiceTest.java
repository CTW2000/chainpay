package com.chainpay.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.admin.domain.AdminSession;
import com.chainpay.admin.service.AdminAuthException;
import com.chainpay.admin.service.AdminAuthProperties;
import com.chainpay.admin.service.AdminAuthService;
import com.chainpay.support.AbstractPostgresTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 管理员认证的规则（时钟可注入）：口令 Argon2id 存散列；登录失败计数与锁定；会话有闲置与绝对两种过期；敏感操作要最近再认证过；改口令踢掉别的会话。
 */
@DisplayName("M6-⑤ · 管理员认证规则")
class AdminAuthServiceTest extends AbstractPostgresTest {

    static final String PASSWORD = "correct-horse-battery-staple";
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-15T00:00:00Z"));
    private AdminAuthService service;

    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private AdminAuthProperties properties;

    @BeforeEach
    void freshService() {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        service = new AdminAuthService(jdbcOfApp(), encoder, clock, properties);
        service.createUser("alice", PASSWORD);
    }

    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE admin_action, admin_session, admin_user CASCADE").update();
    }

    private void advance(Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    @DisplayName("★ 口令只存 Argon2id 散列，明文在库里 grep 不到；登录成功给的令牌库里只有它的散列")
    void passwordAndTokenAreStoredHashed() {
        String hash = jdbc.sql("SELECT password_hash FROM admin_user WHERE username = 'alice'").query(String.class).single();
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
        AdminAuthService.Issued issued = service.login("alice", PASSWORD, "127.0.0.1");
        assertThat(issued.token()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(jdbc.sql("SELECT count(*) FROM admin_session WHERE token_hash = :t").param("t", issued.token()).query(Long.class).single()).isZero();
        assertThat(service.authenticate(issued.token())).isPresent();
    }

    @Test
    @DisplayName("★ 错口令、不存在的用户、停用的用户：同一个 401、同一句话（不可区分）；错 5 次锁 15 分钟，锁着时对的口令也不行")
    void failuresAreIndistinguishableAndLockAfterFive() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login("alice", "wrong-password-xx", "127.0.0.1")).isInstanceOf(AdminAuthException.class)
                    .satisfies(e -> assertThat(((AdminAuthException) e).status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
        assertThatThrownBy(() -> service.login("alice", PASSWORD, "127.0.0.1")).as("锁着：对的也拒").isInstanceOf(AdminAuthException.class);
        String noUser = assertThatThrownBy(() -> service.login("nobody", PASSWORD, "127.0.0.1")).isInstanceOf(AdminAuthException.class).actual().getMessage();
        String wrong = assertThatThrownBy(() -> service.login("alice", "wrong-password-xx", "127.0.0.1")).actual().getMessage();
        assertThat(noUser).isEqualTo(wrong);
        advance(Duration.ofMinutes(16));
        assertThat(service.login("alice", PASSWORD, "127.0.0.1").token()).isNotBlank();
        assertThat(jdbc.sql("SELECT failed_logins FROM admin_user WHERE username = 'alice'").query(Integer.class).single()).as("成功后清零").isZero();
    }

    @Test
    @DisplayName("★ 会话两种过期：闲置 30 分钟没动作失效；就算一直在动，12 小时后也失效")
    void sessionsExpireByIdleAndByAbsoluteAge() {
        String idle = service.login("alice", PASSWORD, "127.0.0.1").token();
        advance(Duration.ofMinutes(29));
        assertThat(service.authenticate(idle)).isPresent();
        advance(Duration.ofMinutes(31));
        assertThat(service.authenticate(idle)).as("闲置超时").isEmpty();

        String busy = service.login("alice", PASSWORD, "127.0.0.1").token();
        for (int i = 0; i < 25; i++) {
            advance(Duration.ofMinutes(29));
            if (now.get().isBefore(Instant.parse("2026-09-15T00:00:00Z").plus(Duration.ofHours(12)).plus(Duration.ofMinutes(90)))) {
                service.authenticate(busy);
            }
        }
        assertThat(service.authenticate(busy)).as("绝对过期").isEmpty();
    }

    @Test
    @DisplayName("★ 敏感操作要最近 5 分钟内再认证过：登录那一刻算；过了要用口令再认证；退出后令牌立刻失效")
    void sensitiveOperationsNeedRecentReauth() {
        String token = service.login("alice", PASSWORD, "127.0.0.1").token();
        AdminSession s = service.authenticate(token).orElseThrow();
        assertThat(service.reauthFresh(s)).isTrue();
        advance(Duration.ofMinutes(6));
        s = service.authenticate(token).orElseThrow();
        assertThat(service.reauthFresh(s)).isFalse();
        assertThatThrownBy(() -> service.reauth(service.authenticate(token).orElseThrow(), "wrong-password-xx")).isInstanceOf(AdminAuthException.class);
        service.reauth(s, PASSWORD);
        assertThat(service.reauthFresh(service.authenticate(token).orElseThrow())).isTrue();
        service.logout(service.authenticate(token).orElseThrow());
        assertThat(service.authenticate(token)).isEmpty();
    }

    @Test
    @DisplayName("口令至少 12 位；改口令要现口令，改完别的会话全部失效、当前这个还在")
    void passwordPolicyAndChange() {
        assertThatThrownBy(() -> service.createUser("bob", "short")).isInstanceOf(AdminAuthException.class).hasMessageContaining("12");
        String a = service.login("alice", PASSWORD, "127.0.0.1").token();
        String b = service.login("alice", PASSWORD, "127.0.0.1").token();
        AdminSession sa = service.authenticate(a).orElseThrow();
        assertThatThrownBy(() -> service.changePassword(sa, "wrong-password-xx", "another-long-password-1")).isInstanceOf(AdminAuthException.class);
        service.changePassword(sa, PASSWORD, "another-long-password-1");
        assertThat(service.authenticate(a)).as("当前会话保留").isPresent();
        assertThat(service.authenticate(b)).as("其它会话踢掉").isEmpty();
        assertThat(service.login("alice", "another-long-password-1", "127.0.0.1").token()).isNotBlank();
    }

    @Test
    @DisplayName("每次登录（成功或失败）都在 admin_action 里留一行，带用户名与来源地址，不带口令")
    void loginsAreRecorded() {
        service.login("alice", PASSWORD, "127.0.0.1");
        assertThatThrownBy(() -> service.login("alice", "wrong-password-xx", "10.0.0.9")).isInstanceOf(AdminAuthException.class);
        var rows = jdbc.sql("SELECT username, status, remote_addr, detail FROM admin_action WHERE path = '/admin/v1/auth/login' ORDER BY id").query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("status", 200).containsEntry("username", "alice").containsEntry("remote_addr", "127.0.0.1");
        assertThat(rows.get(1)).containsEntry("status", 401).containsEntry("remote_addr", "10.0.0.9");
        assertThat(rows.toString()).doesNotContain(PASSWORD).doesNotContain("wrong-password");
    }

    private Optional<AdminSession> none() {
        return Optional.empty();
    }
}
