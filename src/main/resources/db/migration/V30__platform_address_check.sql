-- V30：「这是不是平台自己的地址」改由库里一个只答是 / 否的函数回答（2026-09-24，进程拆分第 ② 步，取舍 2）
--
-- 提现不能提到平台自己的口袋：任何商户的收款地址（那是内部挪动），或热钱包自己的地址（to == from 的一笔交易：
-- 编号照用、gas 照付，账本记「付出去了」，链上什么都没变）。商户连接受 RLS 约束，看不到别家的收款地址，
-- 以前 web 为这一问借用系统身份（BYPASSRLS 的 chainpay_system）；拆分之后对外的 web 不该握着系统角色。
--
-- 做法：函数按属主 chainpay_system 的身份执行（SECURITY DEFINER），调用方只拿到 true / false，拿不到任何一行。
-- 不选「把地址同步成一张应用角色能读的表」：那等于把全部商户的收款地址交给对外的进程。
--
-- SECURITY DEFINER 的纪律（PostgreSQL 手册「Writing SECURITY DEFINER Functions Safely」）：
-- 1. search_path 钉死、pg_temp 放最后：不写的话临时 schema 排在最前。调用方在自己的会话里建同名的空临时表、再授权给函数属主
--    （临时表归它自己，它就能授），函数读到的就是这两张空表，对所有地址都答「不是」（PlatformAddressFunctionTest 实测）。
-- 2. 新建函数默认 PUBLIC 可执行：先 REVOKE ALL … FROM PUBLIC，再只授权 chainpay_app。
--    和 CREATE 在同一个事务里（Flyway 每个迁移一个事务），中间没有「谁都能调」的窗口。
-- 3. 属主是 chainpay_system（BYPASSRLS）。属主既不是超级用户、也没有 BYPASSRLS 时，FORCE 的 RLS 让函数一行都看不到，
--    对所有地址都答「不是」——提现检查被静默放行；而开发库、测试库的属主恰好是超级用户，测试照绿（拆分文档坑 3）。
-- 4. 所以函数体先自检执行身份，看不全就抛异常，不返回 false（照 ledger_judge 的「拒绝盲跑」）。
--
-- 入参先转小写再比：库里的地址一律小写，调用方忘了转，也不会因为大小写不同漏判。
-- 热钱包要等发送任务第一轮对账、hot_wallet 里有了它那一行，这里才认得出。
-- SchemaGuardTest 守每个 SECURITY DEFINER 函数的属主、search_path、PUBLIC 执行权；PlatformAddressFunctionTest 守行为与自检。

CREATE FUNCTION is_platform_address(candidate TEXT)
    RETURNS BOOLEAN
    LANGUAGE plpgsql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION '平台地址检查必须以能看到全部行的身份执行（属主 chainpay_system，BYPASSRLS）：当前是 %，行级安全会让它对所有地址都答「不是」',
            current_user
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN EXISTS (SELECT 1 FROM deposit_address WHERE address = lower(candidate))
        OR EXISTS (SELECT 1 FROM hot_wallet WHERE address = lower(candidate));
END
$$;

ALTER FUNCTION is_platform_address(TEXT) OWNER TO chainpay_system;
REVOKE ALL ON FUNCTION is_platform_address(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION is_platform_address(TEXT) TO chainpay_app;

COMMENT ON FUNCTION is_platform_address(TEXT) IS
    '是 / 否：这是不是平台自己的地址（任何商户的收款地址，或热钱包）。按属主 chainpay_system 执行，调用方只拿到布尔值；属主看不全行时拒绝回答';
