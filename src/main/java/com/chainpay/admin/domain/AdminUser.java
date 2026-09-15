package com.chainpay.admin.domain;

import java.time.Instant;

/** 一个管理员：口令只有散列；连续失败次数与锁到什么时候。 */
public record AdminUser(long id, String username, String passwordHash, String status, int failedLogins, Instant lockedUntil) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
