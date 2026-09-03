-- ============================================================================
-- V12 · RPC 不信任（M2-⑤）：对账审计表 + 书签起点
--
-- eth_getLogs 从 logsBloom 建的索引里取数据，索引没收录的日志就不在结果里，而响应里没有
-- 任何字段提示「少了」（SQD 记录的 Polygon 74614768：getLogs 848 条，回执 856 条）。
-- 回执是事实源，getLogs 是索引。所以每次轮询后随机抽几个已 finalized、已索引的块，
-- 用 eth_getBlockReceipts 重新数一遍，和库里比。差异按「两个节点都点头才动」处理：
--   回执有、库里没有 → 主节点回执也确认有 → 补录（复活型写入）
--   库里有、回执没有 → 主节点回执也确认没有 → 标 ORPHANED（当初被骗记下的幻影）
--   只有一方说有     → 不动，记下来等人看
-- 只记有差异的检查；干净的检查不记（一天两万次，记下来是噪音）。
-- ============================================================================
CREATE TABLE chain_reconcile (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    checked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    block_number BIGINT      NOT NULL,
    block_hash   TEXT        NOT NULL,
    -- 审计路径（回执）里属于我们代币的 Transfer 日志数
    expected     INTEGER     NOT NULL,
    -- 库里该块 CANONICAL 的行数
    found        INTEGER     NOT NULL,
    repaired     INTEGER     NOT NULL,
    orphaned     INTEGER     NOT NULL,
    disputed     INTEGER     NOT NULL,

    CONSTRAINT chain_reconcile_counts_ck CHECK (expected >= 0 AND found >= 0 AND repaired >= 0
                                                AND orphaned >= 0 AND disputed >= 0),
    CONSTRAINT chain_reconcile_hash_ck   CHECK (block_hash ~ '^0x[0-9a-f]{64}$')
);

-- 审计表只增
GRANT SELECT, INSERT ON chain_reconcile TO chainpay_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO chainpay_app;

-- 抽样的下界：只抽我们索引过的块。书签只记「到哪了」，不记「从哪开始」，现在补上。
-- 已有的行填 0——它们都是从创世块附近开始的测试书签。
ALTER TABLE indexer_cursor ADD COLUMN start_block BIGINT NOT NULL DEFAULT 0;

COMMENT ON TABLE chain_reconcile IS '对账审计：只记有差异的检查。repaired/orphaned 都要两个节点点头，disputed 等人看';
COMMENT ON COLUMN indexer_cursor.start_block IS '书签放下时的起点（该块视为已处理）。抽样对账不抽它之前的块';
