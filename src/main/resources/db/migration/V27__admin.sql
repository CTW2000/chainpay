-- M6-⑤ 控制面：管理员、会话、管理操作审计。平台表，无 RLS；应用角色读写用户与会话，审计表只追加（没有 UPDATE / DELETE）。
-- 口令只存 Argon2id 散列；会话只存令牌的 SHA-256；两张表里都 grep 不到任何可以直接拿来用的东西。
CREATE TABLE admin_user (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE',
    failed_logins INTEGER     NOT NULL DEFAULT 0,
    locked_until  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,

    CONSTRAINT admin_user_name_ck   CHECK (username ~ '^[a-z0-9][a-z0-9_.-]{2,31}$'),
    CONSTRAINT admin_user_hash_ck   CHECK (password_hash LIKE '$argon2id$%'),
    CONSTRAINT admin_user_status_ck CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT admin_user_failed_ck CHECK (failed_logins >= 0)
);

CREATE TABLE admin_session (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash   TEXT        NOT NULL UNIQUE,
    user_id      BIGINT      NOT NULL REFERENCES admin_user (id),
    created_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    reauth_at    TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,

    CONSTRAINT admin_session_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX admin_session_user_idx ON admin_session (user_id) WHERE revoked_at IS NULL;

CREATE TABLE admin_action (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id     BIGINT,
    username    TEXT,
    session_id  BIGINT,
    method      TEXT        NOT NULL,
    path        TEXT        NOT NULL,
    status      INTEGER     NOT NULL,
    remote_addr TEXT,
    detail      TEXT
);
CREATE INDEX admin_action_at_idx ON admin_action (at DESC);

COMMENT ON TABLE admin_user    IS 'M6-⑤ 管理员：口令只存 Argon2id 散列；连续失败锁定';
COMMENT ON TABLE admin_session IS 'M6-⑤ 管理员会话：只存令牌散列；闲置与绝对两种过期；reauth_at 是最近一次用口令再认证的时刻';
COMMENT ON TABLE admin_action  IS 'M6-⑤ 管理操作审计：每次 /admin 调用一行（含登录成败），只追加';

GRANT SELECT, INSERT, UPDATE ON admin_user, admin_session TO chainpay_app;
GRANT SELECT, INSERT         ON admin_action              TO chainpay_app;
