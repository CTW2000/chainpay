package com.chainpay.audit.domain;

/** 一条差异：哪条检查、哪种差异、主体（地址 / 入账 id / 提现 id / 币）、期望与实际、人话。 */
public record AuditFinding(String check, AuditKind kind, String subject, String expected, String actual, String detail) {}
