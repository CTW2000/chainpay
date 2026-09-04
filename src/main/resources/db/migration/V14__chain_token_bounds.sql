-- ============================================================================
-- V14 · 白名单的两个长度上限（M2-⑥ 评审补丁，2026-09-03）
--
-- symbol 是从别人的合约里解码出来的：一个恶意合约的 symbol() 可以返回几 MB 的串。
-- Java 侧「超过 64 个字符当问不到」（Erc20Calls.MAX_SYMBOL_LENGTH）是第一道；这里是第二道——
-- 应用会被绕过（新接口、手工 SQL），约束不会。note 是运营或登记代码写的来源说明，500 个字符够写清出处。
-- ============================================================================
ALTER TABLE chain_token
    ADD CONSTRAINT chain_token_symbol_len_ck CHECK (char_length(symbol) BETWEEN 1 AND 64),
    ADD CONSTRAINT chain_token_note_len_ck   CHECK (note IS NULL OR char_length(note) <= 500);
