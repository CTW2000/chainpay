-- ============================================================================
-- 应用的运行时角色。由基础设施在数据库初始化时执行（不是 Flyway 迁移）。
--
--   开发：docker-compose 把本目录挂到 /docker-entrypoint-initdb.d/，首次建库时跑
--   测试：AbstractPostgresTest 把本文件拷进 Testcontainers 容器的同一目录
--   生产：DBA 用同样的语句、不同的密码
--
-- 角色不放在 Flyway 里：角色是集群级对象、带密码；迁移进 git，密码不能进 git。
--
-- 应用直接以 chainpay_app 连库。它不是超级用户、不是表的所有者，RLS 对它无条件生效，忘了设租户 = 一行都看不到。
--
-- 这里的密码只用于开发和测试。生产环境由 DBA 另设，并通过 CHAINPAY_DB_PASSWORD 注入。
-- ============================================================================
CREATE ROLE chainpay_app LOGIN PASSWORD 'chainpay_app_dev';

-- 系统角色：入账、结算、对账、控制面这类跨所有商户的操作以它连库。BYPASSRLS 让行级安全对它不生效——
-- 系统权限是「连接身份」，不是 Java 里一个谁都能调的开关。它仍然不是超级用户、不是表的所有者：
-- 能读写账本，不能建表、不能改策略；没有 DELETE，账本对它同样只追加。应用启动时核对，不满足就拒绝启动（SystemLedger）。
CREATE ROLE chainpay_system LOGIN PASSWORD 'chainpay_system_dev' BYPASSRLS;
