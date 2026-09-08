-- ============================================================================
-- V19 · 入账记录（M3-②）
--
-- 一条已 FINAL 的链上转账在我们这里的结局：记了账（CREDITED）、被忽略（零值）、等人看（HELD_*）。
-- 它和账本的关系是「一条日志 → 最多一笔 transfer」，两条唯一约束各守一半：
--   transfer_log_id UNIQUE —— 同一条日志只能被处理一次。入账任务先 INSERT … ON CONFLICT DO NOTHING 占坑，占不到就是别的实例已处理
--   transfer_id     UNIQUE —— 一笔 transfer 只能属于一条入账
--
-- 先占坑再动钱：占坑行的状态是 POSTING，记账后在同一事务里改成 CREDITED 并挂上 transfer_id。
-- 崩在中间整个事务回滚，所以提交出来的行永远不会是 POSTING；CHECK 只允许 CREDITED 带 transfer_id。
-- 反过来「先记账再占坑」不安全：另一个实例可能已把同一条日志判成 HELD，我们却把钱记上了。
--
-- HELD_* 永远不自动变 CREDITED，只能人处理后改状态（M3-③）；入账任务遇到它跳过继续，不卡队列。
-- ============================================================================
CREATE TABLE deposit (
    id               BIGINT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_log_id  BIGINT          NOT NULL,
    address          TEXT            NOT NULL,
    merchant_id      BIGINT          NOT NULL,
    token            TEXT            NOT NULL,
    -- 链上原始单位，一位不丢；amount 是经 TokenAmounts.toLedger 换算的账本金额，装不下时为 NULL
    raw_value        NUMERIC(78, 0)  NOT NULL,
    amount           NUMERIC(38, 18),
    status           TEXT            NOT NULL,
    transfer_id      BIGINT,
    hold_reason      TEXT,
    block_number     BIGINT          NOT NULL,
    block_hash       TEXT            NOT NULL,
    -- 区块时间 = 业务发生时刻，写进 transfer.occurred_at 的也是它
    occurred_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    credited_at      TIMESTAMPTZ,

    CONSTRAINT deposit_log_uk       UNIQUE (transfer_log_id),
    CONSTRAINT deposit_transfer_uk  UNIQUE (transfer_id),
    CONSTRAINT deposit_log_fk       FOREIGN KEY (transfer_log_id) REFERENCES chain_transfer_log (id),
    CONSTRAINT deposit_address_fk   FOREIGN KEY (address)         REFERENCES deposit_address (address),
    CONSTRAINT deposit_merchant_fk  FOREIGN KEY (merchant_id)     REFERENCES merchant (id),
    CONSTRAINT deposit_token_fk     FOREIGN KEY (token)           REFERENCES chain_token (address),
    CONSTRAINT deposit_transfer_fk  FOREIGN KEY (transfer_id)     REFERENCES transfer (id),
    CONSTRAINT deposit_status_ck    CHECK (status IN ('POSTING', 'CREDITED', 'IGNORED_ZERO',
                                                      'HELD_OVERFLOW', 'HELD_NODE_DISAGREE', 'HELD_BALANCE_MISMATCH', 'REJECTED_DUST')),
    CONSTRAINT deposit_credited_ck  CHECK ((status = 'CREDITED') = (transfer_id IS NOT NULL)),
    CONSTRAINT deposit_held_ck      CHECK (status NOT LIKE 'HELD\_%' OR hold_reason IS NOT NULL),
    CONSTRAINT deposit_raw_ck       CHECK (raw_value >= 0),
    CONSTRAINT deposit_amount_ck    CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT deposit_bhash_ck     CHECK (block_hash ~ '^0x[0-9a-f]{64}$')
);

CREATE INDEX deposit_merchant_idx ON deposit (merchant_id);

-- 商户只读自己的入账；写入只由系统身份做（BYPASSRLS，不受策略约束），所以策略没有 WITH CHECK
ALTER TABLE deposit ENABLE ROW LEVEL SECURITY;

CREATE POLICY deposit_tenant ON deposit
    USING (is_system_scope() OR merchant_id = current_merchant_id());

GRANT SELECT ON deposit TO chainpay_app;
GRANT SELECT, INSERT, UPDATE ON deposit TO chainpay_system;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO chainpay_system;

COMMENT ON TABLE  deposit             IS '链上入账的结局：CREDITED / IGNORED_ZERO / HELD_*。一条日志最多一笔 transfer；先占坑再动钱';
COMMENT ON COLUMN deposit.status      IS 'POSTING 只在事务内存在；HELD_* 永不自动变 CREDITED，人处理后改';
COMMENT ON COLUMN deposit.hold_reason IS 'HELD_* 必须写明原因：两个节点意见不同 / 金额装不下 / 余额对不上';
