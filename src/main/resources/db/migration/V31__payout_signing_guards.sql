-- V31：提现队列的两道库守与核准标记（2026-09-24，进程拆分第 ③ 步的 8a，取舍 8）
--
-- 拆分之后 web 可能被攻破，而它仍能往 payout 里插行（申请本来就是它写的）。今天的库对这些行几乎不设防（拆分文档坑 4）：
-- 状态的 CHECK 允许全部八种、收款地址只查形状——被攻破的 web 冒充商户冻结一笔钱、插一行「放行」、收款地址写成攻击者的，
-- 发送任务就照签照发。发送任务签名前会复核（PayoutSigningGate），但复核只有核对「应用角色写不了的事实」才有用。
-- 这个迁移把三件事变成应用角色写不了的事实：
--
-- 1. 核准标记 approved_by / approved_at：管理员经 worker 核准时由系统身份写下是谁、何时。应用角色的 INSERT 收成列级，
--    这两列不在它能写的列里——超限的提现要么有人核准，要么发不出去，web 伪造不了「有人核准过」。
--    两列同时有或同时没有，由 CHECK 守。
-- 2. 收款地址外键指向本商户的白名单：(merchant_id, to_address) → payout_address (merchant_id, address)。
--    不在这个商户白名单里的地址（包括只在别家白名单里的）谁都插不进去，属主也不行。
--    白名单被停用挡不住（外键不看状态），那一条留给签名前的复核。
-- 3. 应用角色插入时状态只能是「待核准 / 放行」：限制性（RESTRICTIVE）的行级策略，与租户策略同时成立才放行。
--    写成普通（PERMISSIVE）策略不行：多条宽松策略之间是「或」，只会放得更宽。系统身份有 BYPASSRLS，不受它影响。
--
-- 应用角色对 payout 本来就没有 UPDATE 与 DELETE（V21），插进去之后状态只能由系统身份沿转换表往前推。
-- PayoutSchemaTest 守这三条；PayoutSigningGateTest 以应用角色直接写库，模拟被攻破的 web。

ALTER TABLE payout
    ADD COLUMN approved_by TEXT,
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD CONSTRAINT payout_approval_ck CHECK ((approved_by IS NULL) = (approved_at IS NULL));

REVOKE INSERT ON payout FROM chainpay_app;
GRANT INSERT (merchant_id, idempotency_key, token, to_address, amount, raw_value, status, freeze_transfer_id) ON payout TO chainpay_app;

ALTER TABLE payout
    ADD CONSTRAINT payout_whitelist_fk FOREIGN KEY (merchant_id, to_address) REFERENCES payout_address (merchant_id, address);

CREATE POLICY payout_app_inserts_initial_status ON payout
    AS RESTRICTIVE
    FOR INSERT
    TO chainpay_app
    WITH CHECK (status IN ('PENDING_APPROVAL', 'QUEUED'));
