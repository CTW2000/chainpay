-- ============================================================================
-- V21 · 提现（M4-⓪）：拆掉会话变量那道门；热钱包、提现、链上尝试、白名单四张表
--
-- 出账是「账本先扣，链上后发生」。中间那段不确定期里，钱在一个冻结账户上（user:<商户>:<币>:frozen），
-- 这里的表记录的是「我打算做什么、做到哪一步了」——意图与进度，不是账本。账本永远只由 transfer / entry 承担。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 一、拆门：is_system_scope() 曾是「系统权限」的第二条路
--
-- V6 时应用以超级用户连库，系统级操作靠会话变量 chainpay.system = on 放行策略；M3-⓪ 起系统权限是
-- 连接身份（chainpay_system，BYPASSRLS）——BYPASSRLS 的角色根本不评估策略，这个分支对它毫无意义，
-- 却给应用连接留着一扇「谁 set_config 一下就整库可见」的门。CLAUDE.md 承诺 M4 删，这里兑现。
--
-- 策略里从此只剩 merchant_id = current_merchant_id()：没设租户就是 NULL，一行都看不到。
-- 拆门之后 TenantScope.asSystem() 同时删除；M0 的账本测试改走 SystemLedger（系统连接）。
-- ----------------------------------------------------------------------------
DROP POLICY account_tenant ON account;
CREATE POLICY account_tenant ON account
    USING      (merchant_id = current_merchant_id())
    WITH CHECK (merchant_id = current_merchant_id());

DROP POLICY entry_tenant ON entry;
CREATE POLICY entry_tenant ON entry
    USING (EXISTS (
        SELECT 1 FROM account a
        WHERE a.id = entry.account_id
          AND a.merchant_id = current_merchant_id()))
    WITH CHECK (EXISTS (
        SELECT 1 FROM account a
        WHERE a.id = entry.account_id
          AND a.merchant_id = current_merchant_id()));

DROP POLICY transfer_tenant ON transfer;
CREATE POLICY transfer_tenant ON transfer
    USING (EXISTS (
        SELECT 1 FROM account a
        WHERE a.id IN (transfer.debit_account_id, transfer.credit_account_id)
          AND a.merchant_id = current_merchant_id()))
    WITH CHECK (EXISTS (
        SELECT 1 FROM account a
        WHERE a.id IN (transfer.debit_account_id, transfer.credit_account_id)
          AND a.merchant_id = current_merchant_id()));

DROP POLICY deposit_address_tenant ON deposit_address;
CREATE POLICY deposit_address_tenant ON deposit_address
    USING      (merchant_id = current_merchant_id())
    WITH CHECK (merchant_id = current_merchant_id());

DROP POLICY deposit_tenant ON deposit;
CREATE POLICY deposit_tenant ON deposit
    USING (merchant_id = current_merchant_id());

DROP FUNCTION is_system_scope();


-- ----------------------------------------------------------------------------
-- 二、热钱包：一行一把
--
-- next_nonce 是「我打算发的下一笔编号」——这是意图，先落库；真相在链上（eth_getTransactionCount），
-- 由 M4-② 的对账拉回来。库比链多且没有未终结的尝试 = 跳号；链比库多 = 有人在别处用了这把私钥，HALTED 叫人。
-- 没有 RLS：热钱包不属于任何商户。应用角色连读都不给——控制器没有任何理由知道它。
-- ----------------------------------------------------------------------------
CREATE TABLE hot_wallet (
    address     TEXT        PRIMARY KEY,
    chain       TEXT        NOT NULL,
    next_nonce  BIGINT      NOT NULL DEFAULT 0,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    halt_reason TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT hot_wallet_address_ck CHECK (address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT hot_wallet_nonce_ck   CHECK (next_nonce >= 0),
    CONSTRAINT hot_wallet_status_ck  CHECK (status IN ('ACTIVE', 'HALTED')),
    CONSTRAINT hot_wallet_halt_ck    CHECK (status <> 'HALTED' OR halt_reason IS NOT NULL)
);

COMMENT ON TABLE hot_wallet IS '平台热钱包，一行一把；next_nonce 是意图，真相在链上，由对账拉回';


-- ----------------------------------------------------------------------------
-- 三、白名单：提现目标必须先登记
--
-- 同商户同地址唯一；RLS 同账户表（商户只看、只写自己的）。系统任务只读它。
-- ----------------------------------------------------------------------------
CREATE TABLE payout_address (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id BIGINT      NOT NULL,
    address     TEXT        NOT NULL,
    label       TEXT,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT payout_address_merchant_fk FOREIGN KEY (merchant_id) REFERENCES merchant (id),
    CONSTRAINT payout_address_address_ck  CHECK (address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT payout_address_status_ck   CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT payout_address_uk          UNIQUE (merchant_id, address)
);

ALTER TABLE payout_address ENABLE ROW LEVEL SECURITY;
CREATE POLICY payout_address_tenant ON payout_address
    USING      (merchant_id = current_merchant_id())
    WITH CHECK (merchant_id = current_merchant_id());


-- ----------------------------------------------------------------------------
-- 四、提现：一笔业务
--
--   freeze_transfer_id  NOT NULL UNIQUE —— 每一笔提现都以一笔冻结开始，一笔冻结只属于一笔提现。
--                       申请时先冻结再插行，同一个事务：插不进去（幂等键撞了）冻结一起回滚。
--   settle / reverse    UNIQUE，且互斥 —— 一笔钱只有一个结局。
--   状态与结局一一对应 —— CONFIRMED 当且仅当有结算转账；FAILED / REJECTED 当且仅当有解冻转账，且写明原因。
--   UNIQUE (merchant_id, idempotency_key) —— 商户重发同一笔申请得到同一笔提现（M1 转账接口的幂等形状）。
--   RLS：商户只看自己的、只能以自己的名义插；改状态是系统任务的事，应用角色没有 UPDATE。
-- ----------------------------------------------------------------------------
CREATE TABLE payout (
    id                  BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id         BIGINT         NOT NULL,
    idempotency_key     TEXT           NOT NULL,
    token               TEXT           NOT NULL,
    to_address          TEXT           NOT NULL,
    amount              NUMERIC(38,18) NOT NULL,
    raw_value           NUMERIC(78,0)  NOT NULL,
    status              TEXT           NOT NULL,
    freeze_transfer_id  BIGINT         NOT NULL,
    settle_transfer_id  BIGINT,
    reverse_transfer_id BIGINT,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    confirmed_at        TIMESTAMPTZ,

    CONSTRAINT payout_merchant_fk  FOREIGN KEY (merchant_id)         REFERENCES merchant (id),
    CONSTRAINT payout_token_fk     FOREIGN KEY (token)               REFERENCES chain_token (address),
    CONSTRAINT payout_freeze_fk    FOREIGN KEY (freeze_transfer_id)  REFERENCES transfer (id),
    CONSTRAINT payout_settle_fk    FOREIGN KEY (settle_transfer_id)  REFERENCES transfer (id),
    CONSTRAINT payout_reverse_fk   FOREIGN KEY (reverse_transfer_id) REFERENCES transfer (id),
    CONSTRAINT payout_idem_uk      UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT payout_freeze_uk    UNIQUE (freeze_transfer_id),
    CONSTRAINT payout_settle_uk    UNIQUE (settle_transfer_id),
    CONSTRAINT payout_reverse_uk   UNIQUE (reverse_transfer_id),
    CONSTRAINT payout_to_ck        CHECK (to_address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT payout_amount_ck    CHECK (amount > 0),
    CONSTRAINT payout_raw_ck       CHECK (raw_value > 0),
    CONSTRAINT payout_status_ck    CHECK (status IN ('PENDING_APPROVAL', 'QUEUED', 'SIGNED', 'BROADCAST', 'MINED',
                                                     'CONFIRMED', 'FAILED', 'REJECTED')),
    CONSTRAINT payout_settled_ck   CHECK ((status = 'CONFIRMED') = (settle_transfer_id IS NOT NULL)),
    CONSTRAINT payout_reversed_ck  CHECK ((status IN ('FAILED', 'REJECTED')) = (reverse_transfer_id IS NOT NULL)),
    CONSTRAINT payout_outcome_ck   CHECK (settle_transfer_id IS NULL OR reverse_transfer_id IS NULL),
    CONSTRAINT payout_reason_ck    CHECK (status NOT IN ('FAILED', 'REJECTED') OR failure_reason IS NOT NULL)
);

CREATE INDEX payout_status_idx   ON payout (status, id);
CREATE INDEX payout_merchant_idx ON payout (merchant_id, id DESC);

ALTER TABLE payout ENABLE ROW LEVEL SECURITY;
CREATE POLICY payout_tenant ON payout
    USING      (merchant_id = current_merchant_id())
    WITH CHECK (merchant_id = current_merchant_id());

COMMENT ON TABLE payout IS '提现业务：每笔以冻结开始，以结算或解冻结束（互斥）；状态词表与 PayoutStatus 一致';


-- ----------------------------------------------------------------------------
-- 五、链上尝试：一笔提现可能签好几笔（加速、重发），同一个 nonce 只有一笔能上链
--
--   raw_tx      签好的原文。先落库再广播：进程死在提交与广播之间，重启后凭它重发，节点回 already known 不会有第二笔
--   tx_hash     广播前就能算出（Keccak 整条原文），UNIQUE
--   MINED 当且仅当带块号与块哈希；重组退回 BROADCAST 时两列要清空
--   部分唯一索引 (hot_wallet, nonce) WHERE status = 'MINED' —— 「加速后只有一笔上链」由数据库守
--   reverted    回执 status 0：上链了、nonce 用了、gas 扣了、代币没动——提现在 FINAL 后判 FAILED、解冻
-- 没有 RLS（链上事实不属于商户）；应用角色只读（商户接口要显示哈希与状态，经 payout 的 RLS 连接过来）。
-- ----------------------------------------------------------------------------
CREATE TABLE payout_tx (
    id                       BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payout_id                BIGINT        NOT NULL,
    hot_wallet               TEXT          NOT NULL,
    nonce                    BIGINT        NOT NULL,
    tx_hash                  TEXT          NOT NULL,
    raw_tx                   TEXT          NOT NULL,
    gas_limit                BIGINT        NOT NULL,
    max_fee_per_gas          NUMERIC(78,0) NOT NULL,
    max_priority_fee_per_gas NUMERIC(78,0) NOT NULL,
    status                   TEXT          NOT NULL,
    block_number             BIGINT,
    block_hash               TEXT,
    reverted                 BOOLEAN,
    gas_used                 BIGINT,
    effective_gas_price      NUMERIC(78,0),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT payout_tx_payout_fk FOREIGN KEY (payout_id)  REFERENCES payout (id),
    CONSTRAINT payout_tx_wallet_fk FOREIGN KEY (hot_wallet) REFERENCES hot_wallet (address),
    CONSTRAINT payout_tx_hash_uk   UNIQUE (tx_hash),
    CONSTRAINT payout_tx_hash_ck   CHECK (tx_hash ~ '^0x[0-9a-f]{64}$'),
    CONSTRAINT payout_tx_nonce_ck  CHECK (nonce >= 0),
    CONSTRAINT payout_tx_gas_ck    CHECK (gas_limit > 0 AND max_fee_per_gas >= 0 AND max_priority_fee_per_gas >= 0),
    CONSTRAINT payout_tx_status_ck CHECK (status IN ('SIGNED', 'BROADCAST', 'MINED', 'DROPPED', 'REPLACED')),
    CONSTRAINT payout_tx_mined_ck  CHECK ((status = 'MINED') = (block_number IS NOT NULL AND block_hash IS NOT NULL))
);

CREATE UNIQUE INDEX payout_tx_mined_nonce_uk ON payout_tx (hot_wallet, nonce) WHERE status = 'MINED';
CREATE INDEX payout_tx_payout_idx ON payout_tx (payout_id);
CREATE INDEX payout_tx_status_idx ON payout_tx (status, id);

COMMENT ON TABLE payout_tx IS '一笔提现的链上尝试；先落库再广播；同 nonce 只有一笔 MINED';


-- ----------------------------------------------------------------------------
-- 六、权限：应用角色只做商户视角能做的事，系统角色不能删任何一行
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT         ON payout         TO chainpay_app;
GRANT SELECT, INSERT, UPDATE ON payout_address TO chainpay_app;
GRANT SELECT                 ON payout_tx      TO chainpay_app;

GRANT SELECT, INSERT, UPDATE ON payout, payout_tx, hot_wallet TO chainpay_system;
GRANT SELECT                 ON payout_address               TO chainpay_system;

GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO chainpay_app, chainpay_system;
