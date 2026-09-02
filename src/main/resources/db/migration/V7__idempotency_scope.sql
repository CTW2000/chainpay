-- ============================================================================
-- V7 · 幂等键：从「全局唯一」改为「每个提交方唯一」，并让回读能核对请求体
--
-- 来自 2026-08-31 质询扫描的 9.8 / 8.4 / 6.8 三条发现，根源是同一行：
--     V1:62   CONSTRAINT transfer_idem_uk UNIQUE (idempotency_key)
--
-- 它同时决定了两件事，两件都错：
--   ① 键的作用域是全表 → 商户 B 用了 A 用过的键会撞上 A 的行
--   ② 键只承载「名字」不承载「内容」 → 回读时无从核对这是不是同一笔
--
-- 实测后果（真实 HTTP 路径）：
--   同键不同体：  第二笔 200 + 第一笔的 id，777 一分没动，商户无从察觉
--   跨租户撞键：  UNIQUE 索引说「存在」→ 冲突；RLS 说「你看不见」→ 回读 0 行
--                → .single() 抛异常 → 500，且 9001 标了「可重试」
--                → 既是存在性预言机，又可被预先占坑永久阻塞
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 一、谁提交的这笔转账
--
-- ★ 这一列和 V6 里「不给 transfer 加 merchant_id」的论证不矛盾，要说清楚 ★
--
-- V6 拒绝的是「归属 / 可见性」意义上的 merchant_id：一笔转账碰两个账户，
-- 填 A 还是填 B？填谁另一方就丢了可见性。那个论证今天仍然成立，
-- transfer 的可见性仍由 transfer_tenant 策略从借贷双方账户推导。
--
-- 这一列是「幂等作用域」意义上的：**谁发起的这次请求**。
-- 幂等键是调用方起的名字，它的唯一性理应以调用方为界——
-- A 的 order-1 和 B 的 order-1 是两个毫不相干的名字。
-- 两个概念恰好都能用 merchant 表示，但回答的问题不同，所以列名不叫 merchant_id。
--
-- 允许为 NULL：系统级操作（测试注资、将来的结算任务）没有提交方。
-- ----------------------------------------------------------------------------
ALTER TABLE transfer ADD COLUMN submitter_merchant_id BIGINT;

ALTER TABLE transfer
    ADD CONSTRAINT transfer_submitter_fk
        FOREIGN KEY (submitter_merchant_id) REFERENCES merchant (id);


-- ----------------------------------------------------------------------------
-- 二、唯一约束改为 (提交方, 键)
--
-- ★ NULLS NOT DISTINCT 是承重的 ★
--
-- 普通 UNIQUE 里 NULL ≠ NULL（我们在部分索引那一课踩过：
-- UNIQUE(code, deleted_at) 因为 NULL 互不相等而形同虚设）。
-- 不加它的话，两笔 submitter 为 NULL 的系统级转账可以用同一个键——
-- 系统级操作的幂等性就悄悄没了。
-- 实测（PG 18）：
--   普通 UNIQUE          (NULL,'sys-1') 插两次 → 2 行
--   NULLS NOT DISTINCT   (NULL,'sys-1') 插两次 → 第二次 duplicate key
--   NULLS NOT DISTINCT   (1,'order-1') + (2,'order-1') → 都成功
-- ----------------------------------------------------------------------------
ALTER TABLE transfer DROP CONSTRAINT transfer_idem_uk;

ALTER TABLE transfer
    ADD CONSTRAINT transfer_idem_uk
        UNIQUE NULLS NOT DISTINCT (submitter_merchant_id, idempotency_key);


-- ----------------------------------------------------------------------------
-- 三、请求体的核对为什么不加「指纹列」
--
-- 一个方案是存请求体的哈希，回读时比一个字段。我没选它，理由：
-- 「什么算同一笔请求」这个定义会被藏进哈希函数里，改定义时旧指纹全部失效，
-- 而且每个写 transfer 的地方都得记得算它——又一条静默失效的纪律。
--
-- 改为回读时直接比对 amount / currency / debit / credit / code 五列。
-- 定义写在 Java 代码里、看得见，改定义不需要迁移数据。
-- 代价是多读四列，可以忽略。
-- ----------------------------------------------------------------------------

COMMENT ON COLUMN transfer.submitter_merchant_id IS
    '发起这次请求的商户（幂等作用域），NULL = 系统级操作。不是归属，归属由借贷账户推导';
COMMENT ON CONSTRAINT transfer_idem_uk ON transfer IS
    '幂等键按提交方分域；NULLS NOT DISTINCT 让系统级操作也保持幂等';
