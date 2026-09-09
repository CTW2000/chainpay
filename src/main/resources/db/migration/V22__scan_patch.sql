-- ============================================================================
-- V22 · 扫描补丁（2026-09-09）：M0–M3 全量安全扫描里落在数据库上的五处
--
-- 一、判官以系统身份跑
-- 二、链上日志表按地址的索引
-- 三、开了 RLS 的表一律 FORCE
-- 四、租户策略里的会话函数每条语句只求值一次
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 一、判官以系统身份跑
--
-- ★ 两个判官视图此前没授权给任何角色、也没有 security_invoker，而 entry 是 FORCE RLS ★
-- 它们「能查通」只因为开发库与测试库的属主恰好是超级用户（超级用户无条件绕过 RLS）。
-- 换成托管数据库那种非超级用户属主，CLAUDE.md 里那句必查语句会静默返回 0 行——0 行恰好就是「平账」的定义，
-- 坏账被报成平账，且不会有任何报错。根源是判官从来没有被赋予一个身份（V1 建视图时全系统只有超级用户一个身份）。
--
-- 修法：判官授权给 chainpay_system——它是唯一被**声明**能看全部行的身份（BYPASSRLS，SystemLedger 建池时核对）；
-- 再包一个拒绝盲跑的函数：不是超级用户也没有 BYPASSRLS 就直接抛，绝不在看不全的身份下给出「0 处违规」。
-- 不做成视图里的 WHERE 条件：一行都看不见时过滤器根本不会被求值，抛不出来。
-- ----------------------------------------------------------------------------
GRANT SELECT ON ledger_invariant, balance_consistency TO chainpay_system;

CREATE FUNCTION ledger_judge()
    RETURNS TABLE (check_name TEXT, subject TEXT, detail TEXT)
    LANGUAGE plpgsql
    STABLE
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION '判官必须以能看到全部行的身份运行（BYPASSRLS 的 chainpay_system）：当前是 %，行级安全会让它对一部分行视而不见，0 行不等于平账',
            current_user
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
        SELECT 'ledger_invariant'::TEXT, i.currency::TEXT, 'total=' || i.total::TEXT
        FROM ledger_invariant i
        WHERE i.total <> 0;
    RETURN QUERY
        SELECT 'balance_consistency'::TEXT, b.code::TEXT, 'stored=' || b.stored::TEXT || ' computed=' || b.computed::TEXT
        FROM balance_consistency b
        WHERE b.stored <> b.computed;
    RETURN QUERY
        SELECT 'negative_balance'::TEXT, a.code::TEXT, 'balance=' || a.balance::TEXT
        FROM account_balance a
        WHERE a.balance < 0 AND NOT a.allow_negative;
END
$$;

COMMENT ON FUNCTION ledger_judge() IS
    '账本判官：三条不变量的违规行。只在能看到全部行的身份（超级用户或 BYPASSRLS）下给结论，其余身份直接拒绝';


-- ----------------------------------------------------------------------------
-- 二、链上日志表按地址的索引
--
-- V9 只建了块号索引，M2 的查询全按块号走。M3 加了四条按收款地址走的查询：入账候选、余额核对（转入减转出）、
-- 商户的在途列表与余额。四条都没有可用索引，每次全表扫，成本随全链历史增长——一个商户刷余额接口，所有租户付账。
-- 部分索引只收 CANONICAL 行：四条查询的谓词都带 status = 'CANONICAL'（视图里或显式），标废的行永远不进索引。
-- ★ 谓词耦合 ★ 谁把查询改成不带这个条件，索引就静默失效；SchemaGuardTest 用 EXPLAIN 守着。
-- 表大了以后加索引要 CONCURRENTLY，Flyway 的事务包不住它，届时走 mixed 或手工脚本；现在表小，普通 CREATE INDEX。
-- ----------------------------------------------------------------------------
CREATE INDEX chain_transfer_log_to_idx   ON chain_transfer_log (to_address, token)   WHERE status = 'CANONICAL';
CREATE INDEX chain_transfer_log_from_idx ON chain_transfer_log (from_address, token) WHERE status = 'CANONICAL';
-- 外键列的索引（V15 / V19 漏的）
CREATE INDEX chain_transfer_log_token_idx ON chain_transfer_log (token);
CREATE INDEX deposit_by_address_idx       ON deposit (address);
CREATE INDEX deposit_by_token_idx         ON deposit (token);


-- ----------------------------------------------------------------------------
-- 三、开了 RLS 的表一律 FORCE
--
-- V6 给三张账本表加 FORCE 时把理由写清了：「哪天有人拿属主身份写运维脚本，也逃不掉策略」。
-- V18、V19、V21 建租户表时照抄了策略、漏了这一行——规矩只存在于 V6 的注释里。从此由 SchemaGuardTest 守：
-- relrowsecurity 为真的表，relforcerowsecurity 也必须为真。
-- ----------------------------------------------------------------------------
ALTER TABLE deposit_address FORCE ROW LEVEL SECURITY;
ALTER TABLE deposit         FORCE ROW LEVEL SECURITY;
ALTER TABLE payout_address  FORCE ROW LEVEL SECURITY;
ALTER TABLE payout          FORCE ROW LEVEL SECURITY;


-- ----------------------------------------------------------------------------
-- 四、租户策略里的会话函数每条语句只求值一次
--
-- 策略里直接写 current_merchant_id()，规划器把它当成每行都要算的表达式；包成 (SELECT current_merchant_id())
-- 就成了 InitPlan，一条语句算一次。语义完全不变（V21 的定义原样照抄，只加括号），差别只在成本随行数增长与否。
-- ----------------------------------------------------------------------------
DROP POLICY account_tenant ON account;
CREATE POLICY account_tenant ON account
    USING      (merchant_id = (SELECT current_merchant_id()))
    WITH CHECK (merchant_id = (SELECT current_merchant_id()));

DROP POLICY entry_tenant ON entry;
CREATE POLICY entry_tenant ON entry
    USING (EXISTS (
        SELECT 1 FROM account a
        WHERE a.id = entry.account_id
          AND a.merchant_id = (SELECT current_merchant_id())))
    WITH CHECK (EXISTS (
        SELECT 1 FROM account a
        WHERE a.id = entry.account_id
          AND a.merchant_id = (SELECT current_merchant_id())));

DROP POLICY transfer_tenant ON transfer;
CREATE POLICY transfer_tenant ON transfer
    USING (EXISTS (
        SELECT 1 FROM account a
        WHERE a.id IN (transfer.debit_account_id, transfer.credit_account_id)
          AND a.merchant_id = (SELECT current_merchant_id())))
    WITH CHECK (EXISTS (
        SELECT 1 FROM account a
        WHERE a.id IN (transfer.debit_account_id, transfer.credit_account_id)
          AND a.merchant_id = (SELECT current_merchant_id())));

DROP POLICY deposit_address_tenant ON deposit_address;
CREATE POLICY deposit_address_tenant ON deposit_address
    USING      (merchant_id = (SELECT current_merchant_id()))
    WITH CHECK (merchant_id = (SELECT current_merchant_id()));

DROP POLICY deposit_tenant ON deposit;
CREATE POLICY deposit_tenant ON deposit
    USING (merchant_id = (SELECT current_merchant_id()));

DROP POLICY payout_address_tenant ON payout_address;
CREATE POLICY payout_address_tenant ON payout_address
    USING      (merchant_id = (SELECT current_merchant_id()))
    WITH CHECK (merchant_id = (SELECT current_merchant_id()));

DROP POLICY payout_tenant ON payout;
CREATE POLICY payout_tenant ON payout
    USING      (merchant_id = (SELECT current_merchant_id()))
    WITH CHECK (merchant_id = (SELECT current_merchant_id()));
