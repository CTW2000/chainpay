package com.chainpay.admin.repository;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 管理操作审计：只追加。 */
public class AdminActionRepository {

    private final JdbcClient jdbc;

    public AdminActionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long userId, String username, Long sessionId, String method, String path, int status, String remoteAddr, String detail) {
        jdbc.sql("""
                        INSERT INTO admin_action (user_id, username, session_id, method, path, status, remote_addr, detail)
                        VALUES (:u, :n, :s, :m, :p, :st, :r, :d)
                        """)
                .param("u", userId).param("n", username).param("s", sessionId).param("m", method).param("p", path).param("st", status)
                .param("r", remoteAddr).param("d", detail).update();
    }
}
