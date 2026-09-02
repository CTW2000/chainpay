-- ============================================================================
-- V8 · 分录币种必须等于账户币种 —— 用复合外键焊死（质询扫描 9.3 / 3.1-a / 5.10）
--
-- 之前这条规则只由 Java 的 requireCurrencyMatches 守。绕过 Java 层（手写 SQL、
-- 将来 asSystem 的入账任务）往 USDT 账户挂一条 BTC 分录，数据库照单全收，而且：
--   ledger_invariant    按 entry.currency 分组，两条 BTC 分录自己配平   → 绿
--   balance_consistency 求和不看币种                                  → 绿
--   account_balance_ck  只看数字                                       → 绿
-- 三个判官全绿。判官有盲区，测试集又从不踩进盲区（全是 USDT），互相掩护。
-- 摘掉 requireCurrencyMatches，76 个测试全绿——实测。
--
-- 能让状态无法构造的约束，强过任何检测。
-- ============================================================================

-- PostgreSQL 要求外键的目标列有唯一约束。(id) 已是主键，(id, currency) 在逻辑上
-- 同样唯一，但必须显式声明才能被引用。这是冗余索引，代价是每个账户多几十字节。
ALTER TABLE account
    ADD CONSTRAINT account_id_currency_uk UNIQUE (id, currency);

-- 分录的 (account_id, currency) 必须能在 account 里找到那一对。
-- 「USDT 账户上的 BTC 分录」从此在数据库层不存在，不需要判官、不需要任何人记得。
ALTER TABLE entry
    ADD CONSTRAINT entry_account_currency_fk
        FOREIGN KEY (account_id, currency) REFERENCES account (id, currency);

-- 顺带把 transfer 也绑上：转账币种必须等于借贷双方账户的币种。
-- 两个外键各绑一方，跨币种转账在 transfer 行插入时就被拒，走不到分录。
ALTER TABLE transfer
    ADD CONSTRAINT transfer_debit_currency_fk
        FOREIGN KEY (debit_account_id, currency) REFERENCES account (id, currency),
    ADD CONSTRAINT transfer_credit_currency_fk
        FOREIGN KEY (credit_account_id, currency) REFERENCES account (id, currency);

-- 副作用要写明：有分录或转账的账户从此不能改 currency（外键会拦）。
-- 那是对的——有历史分录的账户本来就不该改币种。

COMMENT ON CONSTRAINT entry_account_currency_fk ON entry IS
    '分录币种 = 账户币种。此前只由 Java 守，判官对错配分录全部失明';
