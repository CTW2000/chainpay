-- M6-② 注资登记：运营往热钱包充的币，账本不记（它不是任何商户的钱），但托管等式要能解释它。
-- 一行对应链上一条转给热钱包的 Transfer 日志：金额、块、代币从日志来，运营只指认「是哪一笔」并留一句备注。
-- 只追加、无 RLS（平台事实），系统身份写，谁都没有 DELETE / UPDATE：登记错了另登记一条冲不掉，那就该查是谁登记的（M6-⑤ 审计表）。
CREATE TABLE hot_wallet_funding (
    id              BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_log_id BIGINT         NOT NULL UNIQUE REFERENCES chain_transfer_log (id),
    hot_wallet      TEXT           NOT NULL REFERENCES hot_wallet (address),
    token           TEXT           NOT NULL,
    raw_value       NUMERIC(78, 0) NOT NULL,
    block_number    BIGINT         NOT NULL,
    tx_hash         TEXT           NOT NULL,
    note            TEXT,
    registered_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT hot_wallet_funding_value_ck CHECK (raw_value > 0),
    CONSTRAINT hot_wallet_funding_note_ck  CHECK (note IS NULL OR length(note) <= 500)
);
CREATE INDEX hot_wallet_funding_token_idx ON hot_wallet_funding (token, block_number);

COMMENT ON TABLE hot_wallet_funding IS 'M6-② 已登记的外部注资：每行指向一条转给热钱包的主分支日志；对账把它算进托管等式';

GRANT SELECT, INSERT ON hot_wallet_funding TO chainpay_system;
