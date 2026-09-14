-- M5 对账要站在「finalized 与索引书签中较小的那个块」上：系统身份要能读书签。V17 只给了系统角色链头与日志表，漏了书签。
GRANT SELECT ON indexer_cursor TO chainpay_system;
