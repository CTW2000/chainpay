package com.chainpay.admin.repository;

import com.chainpay.admin.domain.AdminSession;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 会话表：只存令牌的散列。这里只管「没吊销」；过期与闲置由服务按它的表判（可注入的时钟）。 */
public class AdminSessionRepository {

    private final JdbcClient jdbc;

    public AdminSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String tokenHash, long userId, Instant now, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO admin_session (token_hash, user_id, created_at, last_seen_at, reauth_at, expires_at)
                        VALUES (:h, :u, :n, :n, :n, :e) RETURNING id
                        """)
                .param("h", tokenHash).param("u", userId).param("n", ts(now)).param("e", ts(expiresAt)).query(Long.class).single();
    }

    public Optional<AdminSession> findLive(String tokenHash) {
        return jdbc.sql("""
                        SELECT s.id, s.user_id, u.username, s.created_at, s.last_seen_at, s.reauth_at, s.expires_at
                        FROM admin_session s JOIN admin_user u ON u.id = s.user_id
                        WHERE s.token_hash = :h AND s.revoked_at IS NULL AND u.status = 'ACTIVE'
                        """)
                .param("h", tokenHash)
                .query((rs, i) -> new AdminSession(rs.getLong("id"), rs.getLong("user_id"), rs.getString("username"), at(rs, "created_at"),
                        at(rs, "last_seen_at"), at(rs, "reauth_at"), at(rs, "expires_at"))).optional();
    }

    public void touch(long id, Instant now) {
        jdbc.sql("UPDATE admin_session SET last_seen_at = :n WHERE id = :id").param("n", ts(now)).param("id", id).update();
    }

    public void reauth(long id, Instant now) {
        jdbc.sql("UPDATE admin_session SET reauth_at = :n, last_seen_at = :n WHERE id = :id").param("n", ts(now)).param("id", id).update();
    }

    public void revoke(long id, Instant now) {
        jdbc.sql("UPDATE admin_session SET revoked_at = :n WHERE id = :id AND revoked_at IS NULL").param("n", ts(now)).param("id", id).update();
    }

    public int revokeOthers(long userId, long keepId, Instant now) {
        return jdbc.sql("UPDATE admin_session SET revoked_at = :n WHERE user_id = :u AND id <> :k AND revoked_at IS NULL")
                .param("n", ts(now)).param("u", userId).param("k", keepId).update();
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant at(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        return rs.getObject(col, OffsetDateTime.class).toInstant();
    }
}
