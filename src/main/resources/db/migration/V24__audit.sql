-- M5 对账：每一轮落一行，差异逐条落表。两张表都是平台事实，无 RLS；只追加，谁都没有 DELETE。
-- 「没跑」和「跑了没差异」靠这张表分开：上次 OK / DIFF 距今超过两个周期 = stale。
CREATE TABLE audit_run (
    id               BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at       TIMESTAMPTZ NOT NULL,
    finished_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    status           TEXT        NOT NULL,
    finalized_number BIGINT,
    finalized_hash   TEXT,
    findings         INTEGER     NOT NULL DEFAULT 0,
    detail           TEXT,

    CONSTRAINT audit_run_status_ck CHECK (status IN ('OK', 'DIFF', 'FAILED')),
    CONSTRAINT audit_run_count_ck  CHECK (findings >= 0)
);
CREATE INDEX audit_run_finished_idx ON audit_run (finished_at DESC);

CREATE TABLE audit_finding (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id     BIGINT NOT NULL REFERENCES audit_run (id),
    check_name TEXT   NOT NULL,
    kind       TEXT   NOT NULL,
    subject    TEXT   NOT NULL,
    expected   TEXT,
    actual     TEXT,
    detail     TEXT,

    CONSTRAINT audit_finding_kind_ck CHECK (kind IN ('MISSING_IN_LEDGER', 'MISSING_ON_CHAIN', 'AMOUNT_MISMATCH', 'DISPUTED', 'JUDGE'))
);
CREATE INDEX audit_finding_run_idx ON audit_finding (run_id);

COMMENT ON TABLE audit_run IS 'M5 对账的每一轮：站在哪个 finalized 块上、结论（OK / DIFF / FAILED）、差异数';
COMMENT ON TABLE audit_finding IS 'M5 对账发现的差异：哪条检查、哪种差异、主体、期望与实际';

GRANT SELECT, INSERT ON audit_run, audit_finding TO chainpay_system;
GRANT SELECT         ON audit_run, audit_finding TO chainpay_app;
