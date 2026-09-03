-- ============================================================================
-- V10 · 三态确认（M2-③）：链头 + 确认等级视图
--
-- 一条转账什么时候算数？只有 finalized 是事实，latest 和 safe 是不同可信度的看法：
--   latest     节点此刻的头，日常就会被重组（信标链 2022-05 一次 7 块）
--   safe       已 justified，除非大规模协同攻击不会翻
--   finalized  已 finalized，翻它要罚没 ≥ 1/3 质押，协议视为不可逆；落后头部 64～96 块
-- 给用户加钱这一步绑在 finalized 上（M3）：等十几分钟，换掉整类「钱已加上又要撤」的问题，
-- 重组回滚（M2-④）于是永远只碰链表，不碰账本。
--
-- 链表上已有一根轴 status（这条日志还在不在链上，M2-④ 翻它）。确认等级是另一根轴
-- （它有多确定），不混进同一列：等级不存，用视图按「最后一次看到的链头」算出来。
-- 理由同 M0 的余额视图：存下来的状态需要一个任务去推进，任务会停、会漏、会打架；
-- 算出来的永远正确。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 一、链头：我们最后一次看到的 latest / safe / finalized
--
-- 单行表：现在只有一条链。主键是一个恒为 TRUE 的布尔列，CHECK 保证永远只能有这一行；
-- 视图用 CROSS JOIN 拿它，不用在视图里写死链的名字。多链时加列、重建视图。
--
-- 三个头只能往前走，规则在 ChainHeadTracker 里：
--   finalized 倒退、或同一个号换了哈希 → 不是重组，是世界观崩了，停下叫人
--   safe / latest 倒退                 → 节点落后（或负载均衡切到旧节点），保留旧值
-- ----------------------------------------------------------------------------
CREATE TABLE chain_head (
    singleton        BOOLEAN     PRIMARY KEY DEFAULT TRUE,
    chain            TEXT        NOT NULL,
    latest_number    BIGINT      NOT NULL,
    latest_hash      TEXT        NOT NULL,
    safe_number      BIGINT      NOT NULL,
    safe_hash        TEXT        NOT NULL,
    finalized_number BIGINT      NOT NULL,
    finalized_hash   TEXT        NOT NULL,
    observed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chain_head_singleton_ck CHECK (singleton),
    -- 顺序是协议保证的：finalized ≤ safe ≤ latest。节点给出别的顺序就是节点坏了
    CONSTRAINT chain_head_order_ck     CHECK (finalized_number <= safe_number AND safe_number <= latest_number),
    CONSTRAINT chain_head_lhash_ck     CHECK (latest_hash    ~ '^0x[0-9a-f]{64}$'),
    CONSTRAINT chain_head_shash_ck     CHECK (safe_hash      ~ '^0x[0-9a-f]{64}$'),
    CONSTRAINT chain_head_fhash_ck     CHECK (finalized_hash ~ '^0x[0-9a-f]{64}$')
);


-- ----------------------------------------------------------------------------
-- 二、确认等级视图
--
-- level：给代码用。M3 的入账队列 = level = 'FINAL' 且还没记账的行。
-- confirmations：给人看的 Binance 式计数（头 − 块号 + 1）；日志的块比我们记的头还新时
--                （索引器用的是实时的头，链头表可能慢一拍）夹到 0，不出负数。
-- 按金额分级的确认策略现在不做，这两列留着，做时不用改表。
-- 被抛弃的行（status = ORPHANED）不出现：它们不在链上，谈不上确认。
--
-- security_invoker 同 V6：视图以调用者身份执行，将来给链表加 RLS 也不会被视图绕过。
-- ----------------------------------------------------------------------------
CREATE VIEW chain_transfer_confirmation WITH (security_invoker = true) AS
SELECT l.id, l.token, l.from_address, l.to_address, l.value,
       l.block_number, l.block_hash, l.tx_hash, l.log_index, l.seen_at,
       GREATEST(h.latest_number - l.block_number + 1, 0)         AS confirmations,
       CASE WHEN l.block_number <= h.finalized_number THEN 'FINAL'
            WHEN l.block_number <= h.safe_number      THEN 'SAFE'
            ELSE 'SEEN' END                                        AS level
FROM chain_transfer_log l
CROSS JOIN chain_head h
WHERE l.status = 'CANONICAL';

GRANT SELECT, INSERT, UPDATE ON chain_head TO chainpay_app;
GRANT SELECT ON chain_transfer_confirmation TO chainpay_app;

COMMENT ON TABLE chain_head IS '最后一次看到的链头。单行；三个头只进不退，finalized 倒退 = 停下叫人';
COMMENT ON VIEW  chain_transfer_confirmation IS '确认等级算出来不存：SEEN < SAFE < FINAL；ORPHANED 不出现';
