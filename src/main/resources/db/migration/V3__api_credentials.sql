-- ============================================================================
-- V3 · 商户与 API 凭证
--
-- M1 第二步：让接口能回答「你是谁」。
-- 这一版只做认证（你是谁），不做授权（你能动谁的钱）——
-- 后者在下一步单独加，好让「只做认证不做授权会怎样」看得见。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 商户
--
-- 为什么 API key 不直接挂在 account 上：
-- 一个商户会有多个账户（不同币种、可用余额与冻结余额分开、手续费账户……），
-- 而凭证属于「谁在调用」，不属于「动哪个账户」。
-- 把两者绑死，商户加一个币种就要发一把新 key。
-- ----------------------------------------------------------------------------
CREATE TABLE merchant (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    -- ACTIVE 才能调用 API。停用商户时改这里，而不是删凭证——
    -- 删掉之后就查不出「这把 key 曾经属于谁」，审计断链。
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT merchant_code_uk   UNIQUE (code),
    CONSTRAINT merchant_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);


-- ----------------------------------------------------------------------------
-- API 凭证
--
-- ★ 只存 secret 的哈希，不存明文 ★
--
-- 这一条没有商量余地：数据库一旦泄露（备份被拖走、只读账号被滥用、
-- 运维截图发群里），存明文等于所有商户的钱当场可被转走。
-- 存哈希的话，攻击者拿到的是一堆算不回去的字符串。
--
-- 与「用户密码」的一个重要区别：
--   密码是人记的，熵低（"abc123"），必须用 bcrypt/argon2 这类**故意很慢**的算法，
--   让暴力破解每次尝试都付出代价。
--   API secret 是机器生成的高熵随机串（我们用 32 字节），猜不出来，
--   所以可以用快哈希（SHA-256）。而且它必须快 —— 每个 API 请求都要验一次，
--   用 bcrypt 会让每个请求多花几百毫秒。
--
-- 这个取舍的前提是「secret 必须由服务端随机生成，绝不允许商户自选」。
-- 一旦允许自选，商户会填 "123456"，快哈希就守不住了。
-- ----------------------------------------------------------------------------
CREATE TABLE api_credential (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id  BIGINT      NOT NULL,
    -- api_key 是公开标识（相当于用户名），明文存储，会出现在请求头里
    api_key      TEXT        NOT NULL,
    -- secret 只存 SHA-256 十六进制，明文只在创建那一刻返回给商户一次
    secret_hash  TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 最后使用时间：用来发现「三个月没用过的 key」并回收。
    -- 长期不用却一直有效的凭证是最容易被忘掉、也最容易被滥用的那种。
    last_used_at TIMESTAMPTZ,

    CONSTRAINT api_credential_key_uk      UNIQUE (api_key),
    CONSTRAINT api_credential_merchant_fk FOREIGN KEY (merchant_id) REFERENCES merchant (id),
    CONSTRAINT api_credential_status_ck   CHECK (status IN ('ACTIVE', 'REVOKED'))
);

-- 每个请求都要按 api_key 查一次，必须走索引。
-- UNIQUE 约束已经自带索引，这里不用再建。

CREATE INDEX api_credential_merchant_idx ON api_credential (merchant_id);


-- ----------------------------------------------------------------------------
-- 账户归属
--
-- 这一列是下一步（授权）的地基：认证回答「你是谁」，
-- 归属回答「这个账户是不是你的」。
--
-- 可为空，且 NULL 有明确含义：**平台自有账户**（house:mint、house:fee 这些），
-- 不属于任何商户，商户永远不能直接操作它们。
-- 用 NULL 表达「不适用」在这里是恰当的。
-- ----------------------------------------------------------------------------
ALTER TABLE account ADD COLUMN merchant_id BIGINT;

ALTER TABLE account
    ADD CONSTRAINT account_merchant_fk FOREIGN KEY (merchant_id) REFERENCES merchant (id);

CREATE INDEX account_merchant_idx ON account (merchant_id);


COMMENT ON TABLE  merchant                   IS '商户。status=SUSPENDED 时其所有凭证立即失效';
COMMENT ON TABLE  api_credential             IS 'API 凭证。secret 只存 SHA-256，明文仅在创建时返回一次';
COMMENT ON COLUMN api_credential.api_key     IS '公开标识，相当于用户名，出现在请求头里';
COMMENT ON COLUMN api_credential.secret_hash IS 'SHA-256 十六进制。绝不存明文';
COMMENT ON COLUMN account.merchant_id        IS '账户归属商户；NULL = 平台自有账户，商户不可操作';
