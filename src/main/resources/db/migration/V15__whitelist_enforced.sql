-- ============================================================================
-- V15 · 白名单由数据库守（M2-⑥ 补丁 3）
--
-- 「只索引、只入账白名单里的代币」原来只写在 Java 的一个入口上（轮询器第一次轮询时问一次）。
-- 规则写在应用层只拦走过那一行的调用方：新接口、手工 SQL、直接 new 出来的索引器都绕得开，
-- 而且绕过时连报错都没有。外键让「事件表 / 书签表里出现白名单外的代币」在结构上不可能。
--
-- 书签记住自己服务的代币：换了 token-address 却沿用同一个 cursor-name，索引器会从旧进度开始
-- 为新代币拉日志，新代币部署早于旧进度的那段历史静默丢失。有了这一列，启动时对不上就停下。
-- ============================================================================
ALTER TABLE chain_transfer_log
    ADD CONSTRAINT chain_transfer_log_token_fk FOREIGN KEY (token) REFERENCES chain_token (address);

ALTER TABLE indexer_cursor ADD COLUMN token TEXT;

-- V15 之前建的书签没记代币。白名单里恰好只有一枚时可以无歧义地补上；
-- 多于一枚就让下面的 NOT NULL 失败，迁移停下，由人来指定。
UPDATE indexer_cursor
SET token = (SELECT address FROM chain_token)
WHERE token IS NULL AND (SELECT count(*) FROM chain_token) = 1;

ALTER TABLE indexer_cursor
    ALTER COLUMN token SET NOT NULL,
    ADD CONSTRAINT indexer_cursor_token_fk FOREIGN KEY (token) REFERENCES chain_token (address);

COMMENT ON COLUMN indexer_cursor.token IS '这枚书签服务的代币。配置换了代币而书签没换，索引器启动即停';
