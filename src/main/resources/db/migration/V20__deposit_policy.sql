-- ============================================================================
-- V20 · 入账策略（M3-③）
--
-- 最小入账额：每种代币一个阈值（账本单位），低于它的入账记录为 REJECTED_DUST、不入账也不退。
-- 灰尘攻击的成本在归集：把 0.000001 LINK 挪走要付的 gas 比它本身贵，「手续费不能比金额大」就是这条的由来。
-- 0 = 不限。策略放在表里、不写死在代码里，运营可以按币调。
--
-- 状态词表补两个：
--   HELD_ERROR —— 入账时撞上结构性异常（账本约束、币种不符……），这一笔等人看，队列继续。瞬时失败不进这里，那是下一轮重试
--   APPROVED   —— 人复核 HELD 之后改成它；入账任务下一轮用同一套占坑与幂等键把它记上。人永远不用手工碰账本表
-- ============================================================================
ALTER TABLE chain_token ADD COLUMN min_deposit NUMERIC(38, 18) NOT NULL DEFAULT 0;
ALTER TABLE chain_token ADD CONSTRAINT chain_token_min_deposit_ck CHECK (min_deposit >= 0);

ALTER TABLE deposit DROP CONSTRAINT deposit_status_ck;
ALTER TABLE deposit ADD CONSTRAINT deposit_status_ck
    CHECK (status IN ('POSTING', 'CREDITED', 'IGNORED_ZERO', 'REJECTED_DUST', 'APPROVED',
                      'HELD_OVERFLOW', 'HELD_NODE_DISAGREE', 'HELD_BALANCE_MISMATCH', 'HELD_ERROR'));

COMMENT ON COLUMN chain_token.min_deposit IS '最小入账额（账本单位）。低于它记 REJECTED_DUST 不入账；0 = 不限';
COMMENT ON COLUMN deposit.status IS 'POSTING 只在事务内存在；HELD_* 永不自动变 CREDITED；人复核后改 APPROVED，任务下一轮记上';
