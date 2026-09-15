package com.chainpay.admin.repository;

import com.chainpay.admin.domain.AdminUser;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public class AdminUserRepository {

    private static final String SELECT = "SELECT id, username, password_hash, status, failed_logins, locked_until FROM admin_user";

    private final JdbcClient jdbc;

    public AdminUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AdminUser> findByUsername(String username) {
        return jdbc.sql(SELECT + " WHERE username = :u").param("u", username).query(this::map).optional();
    }

    public Optional<AdminUser> findById(long id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public long insert(String username, String passwordHash) {
        return jdbc.sql("INSERT INTO admin_user (username, password_hash) VALUES (:u, :h) RETURNING id").param("u", username).param("h", passwordHash)
                .query(Long.class).single();
    }

    public void loginFailed(long id, int failedLogins, Instant lockedUntil) {
        jdbc.sql("UPDATE admin_user SET failed_logins = :f, locked_until = :l WHERE id = :id").param("f", failedLogins)
                .param("l", lockedUntil == null ? null : OffsetDateTime.ofInstant(lockedUntil, ZoneOffset.UTC)).param("id", id).update();
    }

    public void loginSucceeded(long id, Instant at) {
        jdbc.sql("UPDATE admin_user SET failed_logins = 0, locked_until = NULL, last_login_at = :t WHERE id = :id")
                .param("t", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).param("id", id).update();
    }

    public void setPasswordHash(long id, String passwordHash) {
        jdbc.sql("UPDATE admin_user SET password_hash = :h WHERE id = :id").param("h", passwordHash).param("id", id).update();
    }

    private AdminUser map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        OffsetDateTime locked = rs.getObject("locked_until", OffsetDateTime.class);
        return new AdminUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("status"), rs.getInt("failed_logins"),
                locked == null ? null : locked.toInstant());
    }
}
