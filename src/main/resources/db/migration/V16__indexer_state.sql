-- ============================================================================
-- V16 · 索引器状态表（M2-⑥ 补丁 3）
--
-- 「停下叫人」需要的是状态，不是事件：一行 ERROR 日志响一次就没了，重启后内存里的 halted 也清零，
-- 结构性原因（finalized 倒退、两个节点意见不同）会在自动拉起下变成静默的重启死循环。
-- 这张表让停机成为一个能被问到的事实：进程启动先读它，HALTED 就不碰节点；恢复必须由人把它改回 RUNNING。
-- DEGRADED = 还在跑但该有人来看：连续瞬时失败到阈值、审计节点连续答不出。
-- ============================================================================
CREATE TABLE indexer_state (
    name       TEXT        PRIMARY KEY,
    status     TEXT        NOT NULL,
    reason     TEXT,
    since      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT indexer_state_status_ck CHECK (status IN ('RUNNING', 'DEGRADED', 'HALTED')),
    CONSTRAINT indexer_state_reason_ck CHECK (reason IS NULL OR char_length(reason) <= 2000)
);

GRANT SELECT, INSERT, UPDATE ON indexer_state TO chainpay_app;

COMMENT ON TABLE indexer_state IS '索引器状态：RUNNING / DEGRADED / HALTED。HALTED 由人改回 RUNNING 才恢复，重启不算';
