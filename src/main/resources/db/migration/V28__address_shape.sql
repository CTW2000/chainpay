-- V28：库里的地址形状收成一处（2026-09-22，CLAUDE.md「收口按概念，不按层」）
--
-- 此前 8 个 CHECK 各抄一份 '^0x[0-9a-f]{40}$'（V9 三列、V13、V18、V21 三处）。收成一个函数，8 个约束都调它，
-- 正则只在这里写一份。SchemaGuardTest 守两件事：库里除了这个函数，没有任何约束或函数再写这个正则；
-- 每个地址类的列要么自带调用它的 CHECK，要么外键指向这样的列。
--
-- 这里管的是「存库的写法」：一律小写（Java 侧 EthAddress.lowercase 负责转换）。
-- 「输入的写法」（大小写都收，大小写就是 EIP-55 校验和）是另一件事，只在 Java 的 EthAddress.SHAPE。
--
-- ★ 为什么是函数 + CHECK，而不是 DOMAIN（把列类型改成 eth_address）★
-- 改列类型会让运行中实例已经缓存的预编译语句失效：结果列的类型变了，PostgreSQL 在事务里执行它时报
-- SQLSTATE 0A000「cached plan must not change result type」。2026-09-22 用 pgjdbc 42.7.11（驱动默认设置）实测：
-- 自动提交模式下驱动悄悄重试一次、掩盖了它；事务里——也就是应用的定时任务里——迁移后第一次必失败。
-- 部署脚本先迁移、旧版本还在跑（M6 取舍 6），这一下会落在索引器、入账、发送任务上：Spring 不把 0A000 当瞬时错误，
-- 索引器会当成结构性错误停机、状态落库，重启不算恢复。函数 + CHECK 不改列类型、不用删视图，
-- 同一次实测里缓存的语句照常可用，新约束照样拦住坏地址。代价：新加的地址列要自己带上这个 CHECK——由上面的守卫提醒。

CREATE FUNCTION is_eth_address(address TEXT) RETURNS BOOLEAN
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN address ~ '^0x[0-9a-f]{40}$';

ALTER TABLE chain_transfer_log
    DROP CONSTRAINT chain_transfer_log_token_ck,
    DROP CONSTRAINT chain_transfer_log_from_ck,
    DROP CONSTRAINT chain_transfer_log_to_ck,
    ADD CONSTRAINT chain_transfer_log_token_ck CHECK (is_eth_address(token)),
    ADD CONSTRAINT chain_transfer_log_from_ck  CHECK (is_eth_address(from_address)),
    ADD CONSTRAINT chain_transfer_log_to_ck    CHECK (is_eth_address(to_address));

ALTER TABLE chain_token
    DROP CONSTRAINT chain_token_address_ck,
    ADD CONSTRAINT chain_token_address_ck CHECK (is_eth_address(address));

ALTER TABLE deposit_address
    DROP CONSTRAINT deposit_address_address_ck,
    ADD CONSTRAINT deposit_address_address_ck CHECK (is_eth_address(address));

ALTER TABLE hot_wallet
    DROP CONSTRAINT hot_wallet_address_ck,
    ADD CONSTRAINT hot_wallet_address_ck CHECK (is_eth_address(address));

ALTER TABLE payout_address
    DROP CONSTRAINT payout_address_address_ck,
    ADD CONSTRAINT payout_address_address_ck CHECK (is_eth_address(address));

ALTER TABLE payout
    DROP CONSTRAINT payout_to_ck,
    ADD CONSTRAINT payout_to_ck CHECK (is_eth_address(to_address));

-- 清点时发现的缺口：注资登记表的 token 既没有 CHECK 也没有外键（同表的 hot_wallet 有外键，别的表的 token 都指向白名单）。
-- 值来自已索引的日志、那一列本身有守，所以正常写入从没出过错；补上外键，和其余 token 列一样由白名单裁决。
ALTER TABLE hot_wallet_funding
    ADD CONSTRAINT hot_wallet_funding_token_fk FOREIGN KEY (token) REFERENCES chain_token (address);
