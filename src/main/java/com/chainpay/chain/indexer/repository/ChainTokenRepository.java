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

    public void insert(ChainToken token, String note) {
        jdbc.sql("""
                        INSERT INTO chain_token (address, symbol, decimals, status, note)
                        VALUES (:address, :symbol, :decimals, :status, :note)
                        """)
                .param("address", token.address())
                .param("symbol", token.symbol())
                .param("decimals", token.decimals())
                .param("status", token.status())
                .param("note", note)
                .update();
    }

    public void markVerified(String address) {
        jdbc.sql("UPDATE chain_token SET verified_at = now() WHERE address = :address")
                .param("address", address)
                .update();
    }
}
