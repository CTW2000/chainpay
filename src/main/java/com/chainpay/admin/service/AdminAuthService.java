package com.chainpay.admin.service;

import com.chainpay.admin.domain.AdminSession;
import com.chainpay.admin.domain.AdminUser;
import com.chainpay.admin.repository.AdminActionRepository;
import com.chainpay.admin.repository.AdminSessionRepository;
import com.chainpay.admin.repository.AdminUserRepository;
import com.chainpay.common.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 管理员认证：控制面的门是「人 + 短期会话」。
 * <ul>
 *   <li>口令只存 Argon2id 散列（引库）；登录失败一律同一个 401、同一句话；不存在的用户名也做一次散列比对，耗时一样。</li>
 *   <li>连续错 {@code maxFailures} 次锁 {@code lockFor}；锁着时对的口令也不行；成功后清零。</li>
 *   <li>会话令牌 32 字节随机、只存 SHA-256；两种过期：闲置 {@code idleTimeout}、绝对 {@code sessionTtl}。</li>
 *   <li>敏感操作要最近 {@code reauthWindow} 内用口令再认证过；登录那一刻算一次。</li>
 *   <li>改口令踢掉本人其它会话；每次登录成败都进 admin_action。</li>
 * </ul>
 * 时钟可注入：过期与锁定的规则在测试里不用真等。
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    static final String LOGIN_PATH = "/admin/v1/auth/login";
    static final int MIN_PASSWORD_LENGTH = 12;
    private static final String LOGIN_FAILED = "用户名或口令错误，或账户已锁定";

    public record Issued(String token, Instant expiresAt) {}

    private final AdminUserRepository users;
    private final AdminSessionRepository sessions;
    private final AdminActionRepository actions;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final AdminAuthProperties p;
    private final String decoyHash;
    private final SecureRandom random = new SecureRandom();

    public AdminAuthService(JdbcClient jdbc, PasswordEncoder encoder, Clock clock, AdminAuthProperties properties) {
        this.users = new AdminUserRepository(jdbc);
        this.sessions = new AdminSessionRepository(jdbc);
        this.actions = new AdminActionRepository(jdbc);
        this.encoder = encoder;
        this.clock = clock;
        this.p = properties;
        this.decoyHash = encoder.encode("decoy-" + random.nextLong());   // 用户不存在时也比对一次：耗时和存在时一样，猜不出用户名
    }

    // ---------------------------------------------------------------- 登录 / 会话

    public Issued login(String username, String password, String remoteAddr) {
        Instant now = clock.instant();
        Optional<AdminUser> found = users.findByUsername(username == null ? "" : username);
        boolean matches = encoder.matches(password == null ? "" : password, found.map(AdminUser::passwordHash).orElse(decoyHash));
        boolean ok = found.isPresent() && found.get().active() && !found.get().lockedAt(now) && matches;
        if (!ok) {
            found.ifPresent(u -> {
                if (u.active() && !u.lockedAt(now)) {
                    int failed = u.failedLogins() + 1;
                    Instant lockedUntil = failed >= p.maxFailures() ? now.plus(p.lockFor()) : null;
                    users.loginFailed(u.id(), lockedUntil == null ? failed : 0, lockedUntil);
                    if (lockedUntil != null) {
                        log.warn("管理员 {} 连续 {} 次登录失败，锁到 {}", u.username(), failed, lockedUntil);
                    }
                }
            });
            actions.record(found.map(AdminUser::id).orElse(null), username, null, "POST", LOGIN_PATH, 401, remoteAddr, "登录失败");
            throw new AdminAuthException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, LOGIN_FAILED);
        }
        AdminUser u = found.get();
        users.loginSucceeded(u.id(), now);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = now.plus(p.sessionTtl());
        long sid = sessions.insert(sha256(token), u.id(), now, expiresAt);
        actions.record(u.id(), u.username(), sid, "POST", LOGIN_PATH, 200, remoteAddr, "登录");
        return new Issued(token, expiresAt);
    }

    /** 令牌 → 活着的会话（没吊销、没到绝对寿命、没闲置超时），顺手记一次「看见」。闲置超时的当场吊销。 */
    public Optional<AdminSession> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<AdminSession> s = sessions.findLive(sha256(token));
        if (s.isEmpty()) {
            return Optional.empty();
        }
        AdminSession session = s.get();
        if (!session.expiresAt().isAfter(now) || !session.lastSeenAt().plus(p.idleTimeout()).isAfter(now)) {
            sessions.revoke(session.id(), now);
            return Optional.empty();
        }
        sessions.touch(session.id(), now);
        return Optional.of(new AdminSession(session.id(), session.userId(), session.username(), session.createdAt(), now, session.reauthAt(), session.expiresAt()));
    }

    public boolean reauthFresh(AdminSession s) {
        return s.reauthAt().plus(p.reauthWindow()).isAfter(clock.instant());
    }

    public void reauth(AdminSession s, String password) {
        AdminUser u = users.findById(s.userId()).orElseThrow(() -> new AdminAuthException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, LOGIN_FAILED));
        if (!encoder.matches(password == null ? "" : password, u.passwordHash())) {
            throw new AdminAuthException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "口令错误");
        }
        sessions.reauth(s.id(), clock.instant());
    }

    public void logout(AdminSession s) {
        sessions.revoke(s.id(), clock.instant());
    }

    public void changePassword(AdminSession s, String current, String next) {
        AdminUser u = users.findById(s.userId()).orElseThrow(() -> new AdminAuthException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, LOGIN_FAILED));
        if (!encoder.matches(current == null ? "" : current, u.passwordHash())) {
            throw new AdminAuthException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "现口令错误");
        }
        checkPasswordPolicy(next);
        users.setPasswordHash(u.id(), encoder.encode(next));
        int kicked = sessions.revokeOthers(u.id(), s.id(), clock.instant());
        sessions.reauth(s.id(), clock.instant());
        log.info("管理员 {} 改了口令，踢掉 {} 个其它会话", u.username(), kicked);
    }

    // ---------------------------------------------------------------- 用户

    public long createUser(String username, String password) {
        if (username == null || !username.matches("[a-z0-9][a-z0-9_.-]{2,31}")) {
            throw new AdminAuthException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "用户名：3–32 位小写字母、数字、_ . -，字母或数字开头");
        }
        checkPasswordPolicy(password);
        try {
            return users.insert(username, encoder.encode(password));
        } catch (DuplicateKeyException e) {
            throw new AdminAuthException(HttpStatus.CONFLICT, ErrorCode.ALREADY_EXISTS, "管理员 " + username + " 已存在");
        }
    }

    /** 审计：每次 /admin 调用一行（登录由 login 自己记，带用户名）。 */
    public void recordAction(AdminSession session, String method, String path, int status, String remoteAddr) {
        actions.record(session == null ? null : session.userId(), session == null ? null : session.username(), session == null ? null : session.id(),
                method, path, status, remoteAddr, null);
    }

    static void checkPasswordPolicy(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new AdminAuthException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "口令至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
