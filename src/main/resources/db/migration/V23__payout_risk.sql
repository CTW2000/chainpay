-- M4-④ 风控：限额表。每种代币一行：单笔上限与当日上限（账本单位，与 payout.amount 同刻度）。
-- 没有行 = 这种代币没定过限额 → 一律转人工核准（fail-closed：没人说过可以自动放行，就不自动放行）。
-- 当日 = UTC 日历日；「当日已自动放行」按 created_at 汇总：PENDING_APPROVAL 的不算（人核准时看得到全部），FAILED / REJECTED 的不算（钱已退回）。
-- 当日上限管的是「一天里不经人手能出去多少」。
CREATE TABLE payout_limit (
    token      TEXT           PRIMARY KEY REFERENCES chain_token (address),
    per_tx_max NUMERIC(38,18) NOT NULL,
    daily_max  NUMERIC(38,18) NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT payout_limit_per_tx_ck CHECK (per_tx_max > 0),
    CONSTRAINT payout_limit_daily_ck  CHECK (daily_max >= per_tx_max)
);
COMMENT ON TABLE payout_limit IS '每代币出账限额（账本单位）；超限或没有行的申请进 PENDING_APPROVAL，由管理接口核准';

-- 限额是平台策略，不属于任何商户：无 RLS；商户连接只读，改动只走系统身份（管理接口）。
GRANT SELECT                 ON payout_limit TO chainpay_app;
GRANT SELECT, INSERT, UPDATE ON payout_limit TO chainpay_system;

-- 当日汇总按 (商户, 代币, 时间) 扫；申请接口每次都算一遍
CREATE INDEX payout_merchant_token_day_idx ON payout (merchant_id, token, created_at);

-- 商户身份要给自己的 merchant 行上锁（申请提现时串行化同一商户，让「当日汇总 + 插行」不留缝）：
-- SELECT … FOR UPDATE 需要 UPDATE 权限，V6 已授；RLS 让它只锁得到自己那一行。
