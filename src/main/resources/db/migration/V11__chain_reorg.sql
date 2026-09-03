-- ============================================================================
-- V11 · 重组回滚（M2-④）：审计表
--
-- 重组本身不新建业务表：被丢弃区块里的日志在 chain_transfer_log 上标 ORPHANED（V9 就为此
-- 留了 status 列），书签退回共同祖先（indexer_cursor），然后正常重放。
-- 这张表只记「发生过什么」：什么时候、书签在哪、退到哪、废了几行。
-- 2022-05 信标链那次 7 块的重组，Coinbase 靠的就是「深度重组检测」报警——这张表是那个报警的原料。
--
-- 回滚的地板是 finalized（③ 存在 chain_head 里）：祖先一定在它之上，找到 finalized 都对不上
-- 就不是重组，是 FinalityViolationException，停下叫人。不用 Envio 那种 200 块的魔法数字，
-- 以太坊 PoS 把这个数字交给了协议。
-- ============================================================================
CREATE TABLE chain_reorg (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    detected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 发现时书签指着的块（已不在链上）
    cursor_block   BIGINT      NOT NULL,
    cursor_hash    TEXT        NOT NULL,
    -- 退到的共同祖先：我们记的和链上一致的最高一块（可能比真正的分叉点低，多退不伤）
    ancestor_block BIGINT      NOT NULL,
    ancestor_hash  TEXT        NOT NULL,
    -- 生成列：库自己算，谁也写不出一个和两端对不上的深度
    depth          BIGINT      GENERATED ALWAYS AS (cursor_block - ancestor_block) STORED,
    orphaned_logs  INTEGER     NOT NULL,

    CONSTRAINT chain_reorg_depth_ck    CHECK (ancestor_block < cursor_block),
    CONSTRAINT chain_reorg_orphaned_ck CHECK (orphaned_logs >= 0),
    CONSTRAINT chain_reorg_chash_ck    CHECK (cursor_hash   ~ '^0x[0-9a-f]{64}$'),
    CONSTRAINT chain_reorg_ahash_ck    CHECK (ancestor_hash ~ '^0x[0-9a-f]{64}$')
);

-- 审计表只增：应用角色连 UPDATE 都没有
GRANT SELECT, INSERT ON chain_reorg TO chainpay_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO chainpay_app;

COMMENT ON TABLE chain_reorg IS '重组审计：每次回滚一行，只增不改。depth 是生成列';
