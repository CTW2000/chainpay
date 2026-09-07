-- ============================================================================
-- V17 · 系统角色的授权（M3-⓪）
--
-- 角色本身由 db/init/01-roles.sql 创建（集群级对象、带密码，不能进迁移，同 chainpay_app 的理由）。
-- 这里只做模式级的授权。授什么、不授什么，就是「系统身份能做什么」的全部定义：
--   · 账本三表 SELECT / INSERT / UPDATE：入账要写 transfer、entry，更新 account.balance
--   · 没有 DELETE：账本对谁都是只追加的，记错了只能反向再记一笔（CORRECTION）
--   · merchant 只读：入账前要看商户还在不在
--   · 链表只读：入账任务从 chain_transfer_confirmation 取 FINAL 的行，从 chain_token 取 decimals
--   · 序列 USAGE：INSERT 要用 IDENTITY 列的序列
-- RLS 对它不生效来自角色属性 BYPASSRLS，不来自任何策略改动——策略一行没动。
-- ============================================================================

-- 先把「角色还没建」说成一句人能照着做的话，而不是让下面第一条 GRANT 报 role does not exist。
-- 已有的开发库卷不会重跑 docker-entrypoint-initdb.d，最容易撞到的就是这一步。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chainpay_system') THEN
        RAISE EXCEPTION USING
            MESSAGE = '角色 chainpay_system 不存在：请先执行 db/init/01-roles.sql 里的那条 CREATE ROLE（已有的开发库卷不会重跑初始化脚本，见 README「把应用跑起来」）';
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO chainpay_system;

GRANT SELECT, INSERT, UPDATE ON account, transfer, entry TO chainpay_system;
GRANT SELECT ON account_balance TO chainpay_system;

GRANT SELECT ON merchant TO chainpay_system;

GRANT SELECT ON chain_transfer_log, chain_head, chain_token, chain_transfer_confirmation TO chainpay_system;

GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO chainpay_system;

COMMENT ON ROLE chainpay_system IS '系统身份：BYPASSRLS、非超级用户、非属主；账本可读写不可删，链表只读。由 SystemLedger 独占使用';
