package com.chainpay.chain.indexer.repository;

import com.chainpay.chain.indexer.domain.ChainToken;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 白名单表 chain_token。 */
@Repository
public class ChainTokenRepository {

    private static final RowMapper<ChainToken> ROW = (rs, i) -> new ChainToken(
            rs.getString("address"), rs.getString("symbol"), rs.getInt("decimals"), rs.getString("status"));

    private final JdbcClient jdbc;

    public ChainTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChainToken> find(String address) {
        return jdbc.sql("SELECT address, symbol, decimals, status FROM chain_token WHERE address = :address")
                .param("address", address)
                .query(ROW)
                .optional();
    }

    /** 插入白名单行；已有同地址的行就什么都不做，返回 false。唯一性由主键裁决，不靠先查再插。 */
    public boolean insertIfAbsent(ChainToken token, String note) {
        return jdbc.sql("""
                        INSERT INTO chain_token (address, symbol, decimals, status, note)
                        VALUES (:address, :symbol, :decimals, :status, :note)
                        ON CONFLICT (address) DO NOTHING
                        """)
                .param("address", token.address())
                .param("symbol", token.symbol())
                .param("decimals", token.decimals())
                .param("status", token.status())
                .param("note", note)
                .update() == 1;
    }

    public void markVerified(String address) {
        jdbc.sql("UPDATE chain_token SET verified_at = now() WHERE address = :address")
                .param("address", address)
                .update();
    }
}
