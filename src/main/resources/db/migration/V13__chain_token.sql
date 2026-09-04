-- ============================================================================
-- V13 · 代币白名单（M2-⑥）
--
-- Transfer 事件是合约「说」的，余额是合约「做」的。事件里的金额只对行为规范的代币等于到账金额
-- （转账扣费、弹性供应、失败不回滚、uint256.max 表示「全部」……见 weird-erc20）。
-- 链下索引器做不到「转账前后各读一次余额」，只能做两件事：只接受白名单里的代币，
-- 以及在关键时刻用 balanceOf 核对（M3 入账、M5 对账）。这张表就是白名单。
--
-- decimals 是 OPTIONAL：登记时用 eth_call 去链上问，问得到才登记；问不到的要运营手工填并注明来源。
-- 上限 18：账本是 NUMERIC(38,18)，小数位更多的代币装不下，而钱不能四舍五入。
-- ============================================================================
CREATE TABLE chain_token (
    address     TEXT        PRIMARY KEY,
    symbol      TEXT        NOT NULL,
    decimals    SMALLINT    NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    -- 最近一次和链上核对 decimals 一致的时间；NULL = 还没核对过
    verified_at TIMESTAMPTZ,
    note        TEXT,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chain_token_address_ck  CHECK (address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT chain_token_decimals_ck CHECK (decimals BETWEEN 0 AND 18),
    CONSTRAINT chain_token_status_ck   CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- 第一个白名单成员：Sepolia 上的 LINK。decimals / symbol 由启动时的核对再确认一次。
INSERT INTO chain_token (address, symbol, decimals, note)
VALUES ('0x779877a7b0d9e8603169ddbd7836e478b4624789', 'LINK', 18,
        'Sepolia 测试网的 Chainlink 代币。M2 的索引对象；2026-09-03 用 eth_call 核对 decimals=18、symbol=LINK');

GRANT SELECT, INSERT, UPDATE ON chain_token TO chainpay_app;

COMMENT ON TABLE chain_token IS '代币白名单：只索引、只入账这里 ACTIVE 的代币。decimals 登记时从链上问，启动时再核对';
