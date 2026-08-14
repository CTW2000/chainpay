-- ============================================================================
-- V1 · 复式记账账本
--
-- 设计原则：能让数据库守的不变量，绝不交给应用代码守。
-- 应用代码会被绕过（新写的接口、手工 SQL、并发路径），约束不会。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 账户
--
-- kind 是会计科目类型。为什么需要它：
--   ASSET（资产）/ EXPENSE（费用）                    → 借方增加
--   LIABILITY（负债）/ EQUITY（权益）/ REVENUE（收入） → 贷方增加
-- 用户的余额对你来说是「负债」（你欠用户的），不是资产。
-- 这个区分在对外报表和审计时才会显出价值。
-- ----------------------------------------------------------------------------
CREATE TABLE account (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 业务标识，如 'user:1001:USDT'、'house:fee:USDT'、'chain:hotwallet:USDT'
    code           TEXT        NOT NULL,
    currency       TEXT        NOT NULL,
    kind           TEXT        NOT NULL,
    -- 是否允许余额为负。
    -- 用户账户必须是 FALSE —— 余额为负意味着你凭空多付了钱。
    -- 但「资金来源」账户（EQUITY / 发行账户）必须是 TRUE：
    -- 复式记账里钱不能凭空出现，注资时它就是那个变负的对手方。
    -- 见 tigerbeetle/recipes/balance-invariant-transfers.md
    allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT account_code_uk  UNIQUE (code),
    CONSTRAINT account_kind_ck  CHECK (kind IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'))
);


-- ----------------------------------------------------------------------------
-- 转账（一次业务操作）
--
-- amount 用 NUMERIC(38,18)：
--   · 18 位小数 —— ETH 和多数 ERC-20 的精度就是 18（1 ETH = 10^18 wei）
--   · 38 位总长 —— 留 20 位整数部分，任何现实供应量都装得下
--   · NUMERIC 是 Postgres 的任意精度真十进制，不是二进制浮点
--
-- 绝不能用 DOUBLE PRECISION：0.1 + 0.2 ≠ 0.3
-- 也不要拆成「整数部分 + 小数部分」两个字段
--   —— Google Ads 把 $250 算成 $25,000 就是这么来的
--
-- idempotency_key 上的 UNIQUE 是幂等的唯一可靠实现。
-- 「先 SELECT 查有没有，没有再 INSERT」在并发下必然失败：
-- 两个线程可以同时查到"没有"。
-- ----------------------------------------------------------------------------
CREATE TABLE transfer (
    id                BIGINT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key   TEXT            NOT NULL,
    currency          TEXT            NOT NULL,
    amount            NUMERIC(38, 18) NOT NULL,
    debit_account_id  BIGINT          NOT NULL,
    credit_account_id BIGINT          NOT NULL,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT transfer_idem_uk     UNIQUE (idempotency_key),
    CONSTRAINT transfer_debit_fk    FOREIGN KEY (debit_account_id)  REFERENCES account (id),
    CONSTRAINT transfer_credit_fk   FOREIGN KEY (credit_account_id) REFERENCES account (id),

    -- 金额必须为正。负数转账 == 反向转账，是个后门
    CONSTRAINT transfer_amount_ck   CHECK (amount > 0),
    -- 自己转给自己：余额不变但产生两条分录，会让统计翻倍
    CONSTRAINT transfer_distinct_ck CHECK (debit_account_id <> credit_account_id)
);


-- ----------------------------------------------------------------------------
-- 分录（每笔转账恰好产生两条）
--
-- amount 是有符号的，约定用最直观的那种：
--     正（+）= 该账户余额增加
--     负（-）= 该账户余额减少
--
-- 一笔 transfer 产生两条 entry：
--     debit_account_id  → amount 为负（钱从这里出）
--     credit_account_id → amount 为正（钱到这里去）
--
-- 于是整个账本的核心不变量就变成一条 SQL：
--
--     SELECT currency, SUM(amount) FROM entry GROUP BY currency;   -- 每行必须是 0
--
-- 这个不变量是你的判官。任何时刻它不成立，就是账错了。
--
-- （注：正统会计里「借/贷」的正负取决于科目类型，比这个复杂。
--   M0 先用这个简化约定把并发正确性练熟；等你需要出财务报表时再回来改。）
-- ----------------------------------------------------------------------------
CREATE TABLE entry (
    id          BIGINT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT          NOT NULL,
    account_id  BIGINT          NOT NULL,
    currency    TEXT            NOT NULL,
    amount      NUMERIC(38, 18) NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT entry_transfer_fk FOREIGN KEY (transfer_id) REFERENCES transfer (id),
    CONSTRAINT entry_account_fk  FOREIGN KEY (account_id)  REFERENCES account (id),
    CONSTRAINT entry_amount_ck   CHECK (amount <> 0)
);

CREATE INDEX entry_account_idx  ON entry (account_id);
CREATE INDEX entry_transfer_idx ON entry (transfer_id);


-- ----------------------------------------------------------------------------
-- 余额视图
--
-- M0 阶段余额是「算出来」的，不是存的 —— 永远正确，但数据量大了会慢。
--
-- 加分题：加一个物化的 account.balance 列，并写测试证明它永远等于这个视图。
-- 那一刻你就会明白，为什么「存余额」是所有账务系统 bug 的主要来源。
-- ----------------------------------------------------------------------------
CREATE VIEW account_balance AS
SELECT a.id                       AS account_id,
       a.code,
       a.currency,
       a.kind,
       a.allow_negative,
       COALESCE(SUM(e.amount), 0) AS balance
FROM account a
         LEFT JOIN entry e ON e.account_id = a.id
GROUP BY a.id, a.code, a.currency, a.kind, a.allow_negative;


-- ----------------------------------------------------------------------------
-- 不变量视图 —— 你的判官
--
--     SELECT * FROM ledger_invariant WHERE total <> 0;
--
-- 这条查询在任何时刻都必须返回 0 行。
-- ----------------------------------------------------------------------------
CREATE VIEW ledger_invariant AS
SELECT currency,
       SUM(amount) AS total,
       COUNT(*)    AS entry_count
FROM entry
GROUP BY currency;


COMMENT ON TABLE account          IS '账户。code 是业务标识，如 user:1001:USDT';
COMMENT ON TABLE transfer         IS '转账。idempotency_key UNIQUE 是幂等的唯一可靠实现';
COMMENT ON TABLE entry            IS '分录。amount 有符号：借为正、贷为负。SUM 必须为 0';
COMMENT ON VIEW  ledger_invariant IS '核心不变量：每种币的 total 必须恒为 0';
