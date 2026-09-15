package com.chainpay.admin.domain;

import java.time.Instant;

/** 一个活着的管理员会话（已经过了「没吊销、没过期」的检查）。reauthAt 是最近一次用口令再认证的时刻，登录那一刻算一次。 */
public record AdminSession(long id, long userId, String username, Instant createdAt, Instant lastSeenAt, Instant reauthAt, Instant expiresAt) {}
