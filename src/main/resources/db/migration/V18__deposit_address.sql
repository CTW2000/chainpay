-- ============================================================================
-- V18 · 收款地址（M3-①b）
--
-- 以太坊的转账没有附言，归属只能靠地址：这张表回答「这个地址是谁的、收哪种币、记到哪个账本账户」。
-- 它是新的租户边界，所以和账户表一样挂 RLS；它的 token 是指向白名单的外键，未登记的代币在结构上进不来。
--
-- 三条唯一性，各守一件事：
--   address 主键            —— 同一个地址不能属于两个人。冲突不是「再取一个序号」的信号，是序号被重用或 xpub 配错，必须报
--   (merchant_id, token)    —— 一户一币一址。并发申请靠 INSERT … ON CONFLICT 裁决，输的一方读回赢家的地址
--   derivation_index        —— 序号不重用。序号来自序列：序列不随事务回滚，失败的分配会跳号，跳号无害（M3-before 第 22 问）
-- ============================================================================
CREATE SEQUENCE deposit_address_index_seq AS BIGINT MINVALUE 0 START WITH 0;

CREATE TABLE deposit_address (
    address          TEXT        PRIMARY KEY,
    merchant_id      BIGINT      NOT NULL,
    token            TEXT        NOT NULL,
    -- 这个地址收到的钱记到哪个账本账户（user:<商户 code>:<SYMBOL>），分配时顺手确保存在
    account_id       BIGINT      NOT NULL,
    -- m/44'/60'/0'/0/<derivation_index>；BIP-32 普通派生的上限是 2^31 - 1
    derivation_index BIGINT      NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT deposit_address_address_ck  CHECK (address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT deposit_address_index_ck    CHECK (derivation_index BETWEEN 0 AND 2147483647),
    CONSTRAINT deposit_address_status_ck   CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT deposit_address_index_uk    UNIQUE (derivation_index),
    CONSTRAINT deposit_address_owner_uk    UNIQUE (merchant_id, token),
    CONSTRAINT deposit_address_merchant_fk FOREIGN KEY (merchant_id) REFERENCES merchant (id),
    CONSTRAINT deposit_address_token_fk    FOREIGN KEY (token)       REFERENCES chain_token (address),
    CONSTRAINT deposit_address_account_fk  FOREIGN KEY (account_id)  REFERENCES account (id)
);

CREATE INDEX deposit_address_merchant_idx ON deposit_address (merchant_id);

-- 租户边界：同 V6 的账户策略，ENABLE 即对应用角色无条件生效（它不是属主）；系统角色 BYPASSRLS 不受策略约束
ALTER TABLE deposit_address ENABLE ROW LEVEL SECURITY;

CREATE POLICY deposit_address_tenant ON deposit_address
    USING      (is_system_scope() OR merchant_id = current_merchant_id())
    WITH CHECK (is_system_scope() OR merchant_id = current_merchant_id());

GRANT SELECT, INSERT, UPDATE ON deposit_address TO chainpay_app;
GRANT USAGE, SELECT ON SEQUENCE deposit_address_index_seq TO chainpay_app;
-- 入账任务只读地址表：谁的地址、记到哪个账户
GRANT SELECT ON deposit_address TO chainpay_system;

-- 账本的币种名用 symbol（2026-09-06 取舍 ⑤）：两个 ACTIVE 代币不能同名，哪个 USDT 是真的由运营在白名单里决定；
-- 停用的可以重名，历史行不受影响
CREATE UNIQUE INDEX chain_token_active_symbol_uk ON chain_token (symbol) WHERE status = 'ACTIVE';

COMMENT ON TABLE  deposit_address                  IS '收款地址：谁的、收哪种币、记到哪个账户。一户一币一址；RLS 租户边界';
COMMENT ON COLUMN deposit_address.derivation_index IS 'm/44''/60''/0''/0/<index>，来自序列 deposit_address_index_seq，全局唯一不重用';
COMMENT ON COLUMN deposit_address.status           IS 'DISABLED 的地址链上仍能收到钱，怎么处理见 M3-before 第 5 问';
