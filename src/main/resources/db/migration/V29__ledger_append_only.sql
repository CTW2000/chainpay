-- V29：账本只追加，余额只准改 balance 一列（2026-09-22，用户定「多给的 UPDATE 权限顺手收掉」）
--
-- 起因是移除通用转账接口时顺手清点了一遍权限（information_schema.role_table_grants）：
-- 两个角色在 transfer / entry 上都有 UPDATE，而**全仓库没有任何一条 SQL 用它**——
-- 账本运行时唯一的 UPDATE 是 LedgerServiceImpl 那句 `UPDATE account SET balance = balance + :delta`。
--
-- 「账本只追加」此前只靠「没人写那种 SQL」这条纪律。纪律会被绕过（新接口、手工 SQL、将来的自己），
-- 权限不会：撤掉之后，任何改写历史的语句在数据库层就报 permission denied，不管它从哪条路径来。
-- 这和 V17「系统角色的 GRANT 里没有 DELETE」是同一条思路，只是当时漏了 UPDATE。
--
-- account 的 UPDATE 收成列级：余额要改，但 allow_negative（能不能透支）、merchant_id（这是谁的钱）、
-- currency、kind、code 都不该被应用连接改。列级授权之后，改它们需要属主身份，
-- 而属主凭证只在迁移那一步出现（M6 进程拆分的取舍 1）。
--
-- 迁移本身以属主身份跑，所以 V2 里那条历史 UPDATE 不受影响；
-- SchemaGuardTest 把这套权限钉住：撤销被改回去、或者有人给 account 重新 GRANT 整行 UPDATE，都会红。

REVOKE UPDATE ON transfer, entry FROM chainpay_app, chainpay_system;

REVOKE UPDATE ON account FROM chainpay_app, chainpay_system;
GRANT  UPDATE (balance) ON account TO chainpay_app, chainpay_system;
