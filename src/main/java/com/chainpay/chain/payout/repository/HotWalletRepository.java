package com.chainpay.chain.payout.repository;

import com.chainpay.chain.payout.domain.HotWallet;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 热钱包那一行（系统连接）。{@code next_nonce} 的分配靠 {@link #lockForUpdate}：读的同时锁住，别的实例要读就得等我提交，
 * 唯一性由数据库保证，不靠任何一方记得。
 */
public class HotWalletRepository {

    private static final String COLUMNS = "address, chain, next_nonce, status, halt_reason";
    private static final RowMapper<HotWallet> MAPPER = (rs, i) -> new HotWallet(
            rs.getString("address"), rs.getString("chain"), rs.getLong("next_nonce"), rs.getString("status"), rs.getString("halt_reason"));

    private final JdbcClient jdbc;

    public HotWalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<HotWallet> find(String address) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hot_wallet WHERE address = :a").param("a", address).query(MAPPER).optional();
    }

    /** 读的同时锁住这一行，直到事务结束。 */
    public Optional<HotWallet> lockForUpdate(String address) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hot_wallet WHERE address = :a FOR UPDATE").param("a", address).query(MAPPER).optional();
    }

    /** 第一次见到这把钱包：从链上的计数开始，不从 0 猜。已有的行不动。 */
    public void insertIfAbsent(String address, String chain, long nextNonce) {
        jdbc.sql("INSERT INTO hot_wallet (address, chain, next_nonce) VALUES (:a, :c, :n) ON CONFLICT (address) DO NOTHING")
                .param("a", address).param("c", chain).param("n", nextNonce).update();
    }

    /** 编号 +1，带期望值做守卫：不等于期望说明别的实例在我锁外动了它——不该发生，炸出来。 */
    public void advanceNonce(String address, long expected) {
        int rows = jdbc.sql("UPDATE hot_wallet SET next_nonce = next_nonce + 1, updated_at = now() WHERE address = :a AND next_nonce = :n")
                .param("a", address).param("n", expected).update();
        if (rows != 1) {
            throw new IllegalStateException("热钱包编号被别人动过：期望 " + expected);
        }
    }

    public void halt(String address, String reason) {
        jdbc.sql("UPDATE hot_wallet SET status = 'HALTED', halt_reason = :r, updated_at = now() WHERE address = :a")
                .param("a", address).param("r", reason).update();
    }
}
