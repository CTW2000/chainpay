-- ============================================================================
-- V4 · secret 从「只存哈希」改为「存密文」
--
-- 这是一次**安全性上的主动降级**，必须写清楚为什么值得。
--
-- V3 存的是 SHA-256 哈希：单向，数据库泄露也算不回去，这是更安全的形态。
-- 但签名认证要求服务端用 secret **重算一遍签名**，重算需要明文 —— 哈希做不到。
--
--   存哈希：数据库泄露 = 安全；但只能做「把 secret 原样发过来比对」，
--          而那意味着钥匙每次请求都要在网络上走一遍，谁看到一次就永久拥有它。
--   存密文：数据库泄露 = 仍然安全（前提是加密密钥没跟着泄露）；
--          可以做签名认证，钥匙永远不离开双方。
--
-- 币安、OKX 面对同样的取舍，选择相同。
--
-- 「存密文」的全部安全性押在一件事上：**加密密钥和数据分开存放**。
-- 密钥从环境变量 CHAINPAY_SECRET_KEY 注入，不进数据库、不进代码、不进 git。
-- 只拖走一份数据库备份的攻击者，拿到的是一堆解不开的 Base64。
--
-- 算法：AES-256-GCM。依据 OWASP Cryptographic_Storage Cheat Sheet：
--   "For symmetric encryption AES with a key that's at least 128 bits (ideally 256 bits)"
--   "authenticated modes should always be used... GCM and CCM should be used as a first preference"
-- ============================================================================


-- 密文格式：Base64( 12 字节 IV ‖ 密文 ‖ 16 字节 GCM 认证标签 )
-- IV 不是秘密，不需要保护，但每次加密必须不同 —— 所以和密文存在一起。
ALTER TABLE api_credential ADD COLUMN secret_encrypted TEXT;


-- ----------------------------------------------------------------------------
-- 现有数据怎么办
--
-- 哈希是单向的，**已有的 secret 无法从 secret_hash 还原成密文**。
-- 所以这次迁移做不到「平滑转换」，只能作废重发。
--
-- 当前库里只有演示用的两条凭证，直接清掉。
-- 若这是生产库，正确做法是：
--   1) 两列并存一段时间，新发的凭证写 secret_encrypted
--   2) 老凭证继续走哈希比对（保留旧的认证路径）
--   3) 通知商户在截止日前重新生成凭证
--   4) 截止后再删除 secret_hash 列和旧代码路径
-- 那是一次「双写 + 灰度 + 下线」的标准迁移，不是一条 ALTER 能解决的。
--
-- 这里直接删，是因为**现在还没有真实商户**。
-- 这个前提哪天不成立了，这段注释就是提醒。
-- ----------------------------------------------------------------------------
DELETE FROM api_credential;

ALTER TABLE api_credential ALTER COLUMN secret_encrypted SET NOT NULL;
ALTER TABLE api_credential DROP COLUMN secret_hash;


COMMENT ON COLUMN api_credential.secret_encrypted IS
    'AES-256-GCM 密文，Base64(IV‖密文‖标签)。加密密钥从环境变量注入，绝不落库';
