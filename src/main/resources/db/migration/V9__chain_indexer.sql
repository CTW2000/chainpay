-- ============================================================================
-- V9 · 区块索引器的落库：看到的转账 + 书签（M2-②）
--
-- 索引器不是函数，是一个不能失忆的进程。它要存两样东西：看到的事件、看到的位置。
-- 麻烦全在「两样」上——两次写入之间有缝，崩在缝里有两种坏法，而且不对称：
--
--     先写事件再推书签 → 重复：唯一约束会尖叫，看得见
--     先推书签再写事件 → 丢失：静默，只有某个商户某天发现钱没到
--
-- 所以这里的一切都朝「宁可重复，不可丢失」倾斜（M2-before 第 8、9 问）：
--   · 书签和事件在同一个库里——事务只能罩住一个数据库，这样两步才能同生同死
--   · 事件表靠唯一约束 + ON CONFLICT DO NOTHING 让重复无害
--   · 永不删行——重组时标 ORPHANED，和 V6 不授 DELETE 是同一条纪律
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 一、看到的每一笔 ERC-20 转账：链上原文，不做业务解释
--
-- 这张表不是账本。它是账本的上游证据：M3 入账时从这里读、往 transfer/entry 写。
-- 没有 RLS：链上事实不属于任何商户，哪笔转账归谁是 M3 用收款地址去匹配的事。
-- ----------------------------------------------------------------------------
CREATE TABLE chain_transfer_log (
    id           BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token        TEXT           NOT NULL,
    from_address TEXT           NOT NULL,
    to_address   TEXT           NOT NULL,
    -- uint256 的原始单位：最大 2^256-1，78 位十进制，这一列一个数字都不丢。
    -- 没有小数位——链上就没有小数点，decimals 只是给人看的提示（EIP-20 说它 OPTIONAL）。
    -- 换算进账本的 NUMERIC(38,18) 是 M3 的事，且必须显式检查、拒绝溢出（M2-before 第 17 问）。
    value        NUMERIC(78, 0) NOT NULL,
    block_number BIGINT         NOT NULL,
    block_hash   TEXT           NOT NULL,
    tx_hash      TEXT           NOT NULL,
    log_index    INTEGER        NOT NULL,
    -- 永不删行。重组把行标成 ORPHANED（M2-④），不 DELETE。
    status       TEXT           NOT NULL DEFAULT 'CANONICAL',
    seen_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    -- ★ 坐标用 block_hash，不用 block_number 或 tx_hash ★
    -- 重组后同一个区块号是另一个区块；同一笔交易会被重新打包进另一个区块（logIndex 也可能变）。
    -- 那时链上「同一笔交易」在这里理应是两行：一行属于被抛弃的区块，一行属于新链。
    -- 用 tx_hash 做键，第二行会被 DO NOTHING 吃掉，新链上的事实就没记下来。
    -- logIndex 是区块内的全局编号（不按交易重排），所以 (block_hash, log_index) 唯一定位一条日志。
    CONSTRAINT chain_transfer_log_coord_uk  UNIQUE (block_hash, log_index),
    CONSTRAINT chain_transfer_log_status_ck CHECK (status IN ('CANONICAL', 'ORPHANED')),
    -- >= 0 而不是 > 0：EIP-20 原文「Transfers of 0 values MUST be treated as normal transfers」
    CONSTRAINT chain_transfer_log_value_ck  CHECK (value >= 0),
    -- 形状由库守。解码器已把地址统一成小写；这里保证一条混合大小写的地址、
    -- 一个不成形的哈希，无论从哪条路径来都进不了这张表。
    CONSTRAINT chain_transfer_log_token_ck  CHECK (token        ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT chain_transfer_log_from_ck   CHECK (from_address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT chain_transfer_log_to_ck     CHECK (to_address   ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT chain_transfer_log_bhash_ck  CHECK (block_hash   ~ '^0x[0-9a-f]{64}$'),
    CONSTRAINT chain_transfer_log_txhash_ck CHECK (tx_hash      ~ '^0x[0-9a-f]{64}$'),
    CONSTRAINT chain_transfer_log_block_ck  CHECK (block_number >= 0),
    CONSTRAINT chain_transfer_log_index_ck  CHECK (log_index >= 0)
);

-- 重组回滚（M2-④）和对账（M5）都按区块号扫「N 之后的全部」
CREATE INDEX chain_transfer_log_block_idx ON chain_transfer_log (block_number);


-- ----------------------------------------------------------------------------
-- 二、书签：每个索引器一行
--
-- 为什么不用 MAX(block_number) 当书签：没有日志的区块在事件表里不留痕迹，
-- MAX 会退到上一个有日志的区块；更要紧的是书签必须是**一行**——一行才能上锁，
-- 两个实例同时推进时靠这把行锁互斥（M2-before 第 11 问）。
-- ----------------------------------------------------------------------------
CREATE TABLE indexer_cursor (
    name              TEXT        PRIMARY KEY,
    last_block_number BIGINT      NOT NULL,
    -- M2-before 第 4 问的答案在这一列：下一批第一个区块的 parentHash 必须等于它，
    -- 不等就是重组。重组若动了区块 95，96 到 100 的哈希必然全换（它们一路链回 95），
    -- 所以只校验最后一块就能发现任何深度的重组，不必存每个区块头。
    last_block_hash   TEXT        NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT indexer_cursor_block_ck CHECK (last_block_number >= 0),
    CONSTRAINT indexer_cursor_hash_ck  CHECK (last_block_hash ~ '^0x[0-9a-f]{64}$')
);


-- ----------------------------------------------------------------------------
-- 三、授权：可读、可写、可改，不可删（同 V6）
--
-- V6 的 GRANT USAGE ON ALL SEQUENCES 只覆盖当时已存在的序列，
-- 新表的 IDENTITY 序列要重新授一次，否则 INSERT 报 permission denied for sequence。
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON chain_transfer_log, indexer_cursor TO chainpay_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO chainpay_app;

COMMENT ON TABLE  chain_transfer_log IS '链上 ERC-20 Transfer 原文。账本的上游证据，不是账本；永不删行';
COMMENT ON COLUMN chain_transfer_log.value IS 'uint256 原始单位，无小数点。进账本前必须显式检查是否装得下 NUMERIC(38,18)';
COMMENT ON TABLE  indexer_cursor IS '索引器书签。与事件在同一事务内推进；last_block_hash 是重组检测的依据';
