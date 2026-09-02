-- ============================================================================
-- V6 · 租户隔离下沉到数据库（PostgreSQL Row Level Security）
--
-- 在这之前，「商户 A 不能碰商户 B 的钱」只由一个 Java 方法保证：
-- AccountAccessService.requireOwned()。它是对的，但它只保护**走这条路的人**。
--
-- 下面这些访问路径统统绕过它：
--   · 运维用 psql 排查问题
--   · 报表服务用只读账号直连
--   · 数据分析脚本
--   · 将来新增的、忘了调 requireOwned 的接口
--
-- 最后一条最要命：漏调一次，**编译器不报错、现有测试不变红**，
-- 只有代码审查能发现 —— 也就是靠纪律。
-- 而纪律在本项目已经失效过一次（balanceDrift() 写好了却没人调用）。
--
-- ★ 能靠结构保证的，不要靠纪律保证。★
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 一、应用的运行时角色
--
-- 角色本身**不在这里创建**——它由基础设施建（见 db/init/01-roles.sql），
-- 这里只负责授权。角色是带密码的集群对象，迁移是进 git 的模式对象，两者不该混。
--
-- ★ 这段的历史值得记下来 ★
-- 第一版在这里 CREATE ROLE chainpay_app NOLOGIN，让超级用户连接在事务内
-- SET LOCAL ROLE 切过去。2026-08-31 质询扫描实测：忘了切就以超级用户跑，
-- RLS 整套失效、看到全库、零报错——和当时 TenantScope 注释写的
-- 「忘了会一行都查不到」恰好相反。
--
-- 修法不是让代码更小心，是让连接本身就是普通角色：
-- 它不是超级用户、不是表的所有者，RLS 对它**无条件**生效，没有任何路径能绕。
-- 应用现在以 chainpay_app 直接连库；Flyway 仍以属主（chainpay）跑迁移。
--
-- 这份迁移在角色不存在时会失败——那是**对的**：基础设施没就位，应用就不该起来。
-- ----------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO chainpay_app;
GRANT SELECT, INSERT, UPDATE ON account, transfer, entry TO chainpay_app;
GRANT SELECT ON account_balance TO chainpay_app;

-- 控制面（AdminService）也以这个角色跑：开户、发凭证、吊销、停用。
-- merchant / api_credential 没有 RLS——它们本来就是跨租户的管理表。
GRANT SELECT, INSERT, UPDATE ON merchant, api_credential TO chainpay_app;
-- IDENTITY 列的序列：INSERT 要用，没有这条会报 permission denied for sequence
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO chainpay_app;

-- 注意没有授 DELETE：账本里的行**永远不删**。
-- 权限层就把这条纪律固化下来，比在代码评审里反复提醒可靠。


-- ----------------------------------------------------------------------------
-- 二、视图必须以「调用者」身份执行
--
-- ★ 又一个静默绕过 ★
--
-- PostgreSQL 的视图默认以**视图所有者**的身份执行底层查询。
-- account_balance 的所有者是 chainpay（超级用户），所以：
--
--   直接查 account            → RLS 生效，只看得到自己的
--   透过 account_balance 查   → RLS 完全失效，看得到所有人的
--
-- 而 LedgerServiceImpl.balanceOf() 读的正是这个视图。
-- 不加这行，整套隔离会在「查余额」这条最常用的路径上开一个大洞。
--
-- security_invoker 是 PostgreSQL 15 引入的，让视图改用**调用者**的身份，
-- 底层表的 RLS 于是照常生效。
-- ----------------------------------------------------------------------------
ALTER VIEW account_balance SET (security_invoker = true);


-- ----------------------------------------------------------------------------
-- 三、当前租户是谁
--
-- 从会话变量读。设置它的地方在 TenantScope（Java 侧），用的是
-- set_config(..., is_local => true)，等价于 SET LOCAL —— 事务结束自动清掉。
--
-- ★ 为什么必须是 LOCAL，实测证据 ★
--   用 SET LOCAL：COMMIT 后身份和变量都自动复位
--   用 SET      ：COMMIT 后**身份和变量都还在**
-- 连接池会把这条连接还回池子，下一个借到它的请求就继承了上一个商户的身份。
-- 这种 bug 只在有并发时出现，单跑测试永远是绿的。
--
-- 第二个参数 true 表示「没设置就返回 NULL，不要报错」。
-- NULLIF(...,'') 处理被显式设成空串的情况 —— ''::bigint 会抛异常。
--
-- 没设租户时返回 NULL，而 `x = NULL` 恒为 NULL（不是 true），
-- 于是**一行都看不到**。★ 忘记设置的后果是「什么都查不到」，
-- 而不是「什么都查得到」—— 失败方向朝着立刻暴露。★
-- ----------------------------------------------------------------------------
CREATE FUNCTION current_merchant_id() RETURNS BIGINT
    LANGUAGE sql
    STABLE
    AS $$
        SELECT NULLIF(current_setting('chainpay.merchant_id', true), '')::BIGINT
    $$;

COMMENT ON FUNCTION current_merchant_id() IS
    '当前会话的租户 id，由 TenantScope 用 set_config(...,true) 在事务内设置';


-- ----------------------------------------------------------------------------
-- 系统作用域：不属于任何商户的操作（注资、结算、M3 入账、M4 出账）
--
-- ★ 这是应用改用普通角色之后才暴露出来的设计空白 ★
-- 之前应用是超级用户，平台账户（merchant_id 为 NULL）随手就能读写，
-- 「系统级操作以什么身份访问平台账户」这个问题从来没被问过——超级用户把它盖住了。
-- 2026-08-31 换成普通角色的那一刻，M0 的 9 个账本测试全部「账户不存在」。
--
-- 答案是显式的第二种作用域：TenantScope.asSystem() 在事务内把 chainpay.system 设为 on。
-- 系统作用域看得到**全部**行，因为入账要从平台账户转到商户账户，跨两个域。
--
-- 它仍然是 fail-closed 的：默认（两个变量都没设）一行都看不到。
-- 系统模式必须由代码显式开启，HTTP 控制器永远不该调 asSystem()——
-- 这条目前靠评审守着，见 TenantScope.asSystem 的 javadoc。
-- ----------------------------------------------------------------------------
CREATE FUNCTION is_system_scope() RETURNS BOOLEAN
    LANGUAGE sql
    STABLE
    AS $$
        SELECT current_setting('chainpay.system', true) = 'on'
    $$;

COMMENT ON FUNCTION is_system_scope() IS
    '系统作用域开关，由 TenantScope.asSystem() 在事务内设置。开启后策略放行全部行';


-- ----------------------------------------------------------------------------
-- 四、开启 RLS
--
-- ENABLE 让策略开始生效；FORCE 让策略**连表的所有者也管**。
--
-- FORCE 只影响**表的所有者**（chainpay，跑迁移的那个），对应用角色没有意义——
-- 应用角色不是所有者，ENABLE 就已经对它无条件生效了。
-- 保留 FORCE 是为了：哪天有人拿属主身份写运维脚本，也逃不掉策略。
--
-- 「应用仍用超级用户连库」这个缺口已于 2026-08-31 关闭，见本文件开头第一节。
-- ----------------------------------------------------------------------------
ALTER TABLE account  ENABLE ROW LEVEL SECURITY;
ALTER TABLE account  FORCE  ROW LEVEL SECURITY;
ALTER TABLE entry    ENABLE ROW LEVEL SECURITY;
ALTER TABLE entry    FORCE  ROW LEVEL SECURITY;
ALTER TABLE transfer ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfer FORCE  ROW LEVEL SECURITY;


-- ----------------------------------------------------------------------------
-- 五、策略
--
-- 账户：直接看 merchant_id。
-- WITH CHECK 管写入，USING 管读取，两个都要 —— 只写 USING 的话，
-- 商户能把自己的账户 UPDATE 成别人的（改 merchant_id），一次性把账户送人。
-- ----------------------------------------------------------------------------
CREATE POLICY account_tenant ON account
    USING      (is_system_scope() OR merchant_id = current_merchant_id())
    WITH CHECK (is_system_scope() OR merchant_id = current_merchant_id());


-- ----------------------------------------------------------------------------
-- 分录：归属**从账户推导**，entry 表上没有也不需要 merchant_id。
--
-- WITH CHECK 这一半是真正的收获：
-- ★ 数据库本身会拒绝「往别人的账户上写一条分录」★
-- 哪怕有人手写 SQL 绕过整个 Java 层，也做不到。
-- ----------------------------------------------------------------------------
CREATE POLICY entry_tenant ON entry
    USING (is_system_scope() OR EXISTS (
        SELECT 1 FROM account a
        WHERE a.id = entry.account_id
          AND a.merchant_id = current_merchant_id()))
    WITH CHECK (is_system_scope() OR EXISTS (
        SELECT 1 FROM account a
        WHERE a.id = entry.account_id
          AND a.merchant_id = current_merchant_id()));


-- ----------------------------------------------------------------------------
-- 转账：借贷任意一方是我的账户，我就看得到这笔转账。
--
-- ★ 为什么不给 transfer 加一个 merchant_id 列 ★
--
-- 一笔转账**碰两个账户**，A 转给 B，两边都该看得到自己那一面。
-- 加一个 merchant_id 列就必须回答「填 A 还是填 B」—— 填谁另一方就丢了可见性。
-- transfer 没有单一主人，它有**参与方**。
--
-- ★ 第一版写错了，值得记下来 ★
-- 我最初写的是「能看到它的任意一条分录就能看到这笔转账」：
--     USING (EXISTS (SELECT 1 FROM entry e WHERE e.transfer_id = transfer.id))
-- 语义上更漂亮（归属完全从分录推导），但它在**插入的那一刻**是假的 ——
-- 转账行刚插进去时还没有任何分录。而 INSERT ... RETURNING id 需要**读回**新行，
-- 读要过 USING，于是账本的每一次转账都被自己的策略挡死：
--     ERROR: new row violates row-level security policy for table "transfer"
--
-- 教训：策略是在**每一行的每个时刻**被求值的，包括那一行刚出生、
-- 关联数据还没写进去的瞬间。"逻辑上应该成立"不等于"任何时刻都成立"。
--
-- 改用 debit_account_id / credit_account_id：这两列在插入时就已经有值，
-- 表达的还是同一件事（参与方可见），而且少一次子查询。
-- ----------------------------------------------------------------------------
CREATE POLICY transfer_tenant ON transfer
    USING (is_system_scope() OR EXISTS (
        SELECT 1 FROM account a
        WHERE a.id IN (transfer.debit_account_id, transfer.credit_account_id)
          AND a.merchant_id = current_merchant_id()))
    WITH CHECK (is_system_scope() OR EXISTS (
        SELECT 1 FROM account a
        WHERE a.id IN (transfer.debit_account_id, transfer.credit_account_id)
          AND a.merchant_id = current_merchant_id()));


COMMENT ON POLICY account_tenant  ON account  IS '账户只对归属商户可见可改';
COMMENT ON POLICY entry_tenant    ON entry    IS '分录归属从账户推导；写入也受限，别人的账户挂不上分录';
COMMENT ON POLICY transfer_tenant ON transfer IS '借贷任一方是我的账户即可见（转账有参与方，无单一主人）';
