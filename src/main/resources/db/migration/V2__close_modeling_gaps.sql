-- ============================================================================
-- V2 · 补上三个建模缺口
--
-- 背景：V1 是凭自己的理解写的。之后对照 TigerBeetle 官方文档
-- （concepts/debit-credit.md、coding/financial-accounting.md、
--   coding/reliable-transaction-submission.md）逐条核对，发现三处欠缺。
-- 行为层面的四个测试一个都抓不到它们——它们是建模层面的问题。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 缺口 1（最严重）：余额不变量原本在应用层，不在数据库层
--
-- V1 的做法是在 Java 里判断 `if (balance < amount) throw`。
-- 这违反了本项目自己的规则：「能让数据库守的不变量，绝不交给应用守」——
-- 应用代码会被绕过（新接口、手工 SQL、并发路径），CHECK 约束不会。
--
-- TigerBeetle 把它做成账户上的 flag（debits_must_not_exceed_credits），
-- 由数据库强制。Postgres 里的等价物就是：物化一列余额 + CHECK 约束。
--
-- 顺带说明：这一列常被当成「性能优化」（把 O(分录数) 的求和变成 O(1) 读取）。
-- 性能是副产品。它真正的价值是让不变量能写进数据库。
-- ----------------------------------------------------------------------------
ALTER TABLE account ADD COLUMN balance NUMERIC(38, 18) NOT NULL DEFAULT 0;

-- 回填。当前库里没有历史数据，但迁移必须能应对已有数据的库——
-- 否则这条迁移在测试环境通过、在生产上把所有余额清零。
UPDATE account a
SET balance = COALESCE((SELECT SUM(e.amount) FROM entry e WHERE e.account_id = a.id), 0);

ALTER TABLE account
    ADD CONSTRAINT account_balance_ck CHECK (balance >= 0 OR allow_negative);


-- ----------------------------------------------------------------------------
-- 缺口 2：转账缺少「为什么」
--
-- TigerBeetle 的六个维度里，"Why" 对应 transfer.code，
-- 文档原话：should map to an enum or table of all the possible business events。
--
-- V1 完全没有这个字段，于是充值/提现/手续费/换汇/冲正在账本层长得一模一样。
-- 后果在 M3 之后才显现：报表切不出来，M5 对账无法按业务类型定位差异。
--
-- 现在加是「加一列」，等有了几十万条记录再加就是「加一列 + 回填历史」——
-- 而历史数据的业务类型是猜不回来的。
--
-- 不加 CHECK 约束：业务事件的词表会随里程碑增长，
-- 每加一种就改一次约束不划算。词表由 Java 的 TransferCode 枚举维护。
-- ----------------------------------------------------------------------------
ALTER TABLE transfer ADD COLUMN code TEXT NOT NULL DEFAULT 'UNSPECIFIED';


-- ----------------------------------------------------------------------------
-- 缺口 3：只有记账时间，没有业务发生时间
--
-- created_at  = 这条记录写进数据库的时刻
-- occurred_at = 这笔业务在真实世界发生的时刻
--
-- 内部转账时两者相同，所以 V1 没觉出差别。但从 M2 开始它们必然分叉：
-- 链上区块时间和索引器写库时间差几秒到几分钟，重组重放时能差几小时。
-- 对账、审计、以及「这笔钱到底什么时候到的」这类客服问题，要的都是 occurred_at。
--
-- 可为 NULL，且 NULL 有明确含义：「没有独立的业务时间，与 created_at 相同」。
-- 这里用 NULL 表达「不适用」是恰当的；注意它和「UNIQUE 约束里的 NULL」
-- 是两回事——后者会让约束静默失效。
-- ----------------------------------------------------------------------------
ALTER TABLE transfer ADD COLUMN occurred_at TIMESTAMPTZ;


-- ----------------------------------------------------------------------------
-- 视图更新
--
-- 必须 DROP 而不能 CREATE OR REPLACE：
-- 旧的 balance 列是 COALESCE(SUM(...), 0)，类型是无约束的 numeric；
-- 新的是 account.balance，类型是 numeric(38,18)。
-- CREATE OR REPLACE VIEW 不允许改变已有列的类型，会报
-- "cannot change data type of view column"。
-- ----------------------------------------------------------------------------
DROP VIEW account_balance;

CREATE VIEW account_balance AS
SELECT a.id AS account_id,
       a.code,
       a.currency,
       a.kind,
       a.allow_negative,
       a.balance
FROM account a;


-- ----------------------------------------------------------------------------
-- 新的判官：物化余额与分录求和必须永远一致
--
-- 上面那一列一旦物化，就签下了一份「永远和分录保持一致」的合约。
-- 合约需要有人监督 —— 就是这个视图。
--
--     SELECT * FROM balance_consistency WHERE stored <> computed;   -- 必须 0 行
--
-- 注意这和 ledger_invariant 管的是两件不同的事：
--   ledger_invariant      —— 账本内部平不平（每种币 SUM 为 0）
--   balance_consistency   —— 缓存的余额和事实（分录）对不对得上
-- 两个都成立，账才既平又准。
-- ----------------------------------------------------------------------------
CREATE VIEW balance_consistency AS
SELECT a.id                       AS account_id,
       a.code,
       a.currency,
       a.balance                  AS stored,
       COALESCE(SUM(e.amount), 0) AS computed
FROM account a
         LEFT JOIN entry e ON e.account_id = a.id
GROUP BY a.id, a.code, a.currency, a.balance;


COMMENT ON COLUMN account.balance      IS '物化余额。必须恒等于该账户分录之和，由 balance_consistency 视图监督';
COMMENT ON COLUMN transfer.code        IS '业务事件类型，词表见 Java 的 LedgerService.TransferCode';
COMMENT ON COLUMN transfer.occurred_at IS '业务在真实世界发生的时刻；NULL 表示与 created_at 相同';
COMMENT ON VIEW   balance_consistency  IS '物化余额 vs 分录求和；stored <> computed 的行必须恒为 0 行';
