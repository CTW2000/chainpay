package com.chainpay.chain.deposit.repository;

import com.chainpay.chain.deposit.domain.DepositAddress;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * deposit_address 表，以及分配时依赖的两处读写：商户行（code、状态）和商户的账本账户。
 * 走应用连接，所以 RLS 生效：只在 {@code asMerchant} 作用域里能看到、写到自己的行。
 */
@Repository
public class DepositAddressRepository {

    /** 商户的两列：账户 code 用它的 code 拼，停用的商户不分配地址。 */
    public record MerchantRow(long id, String code, String status) {}

    private static final RowMapper<DepositAddress> ROW = (rs, i) -> new DepositAddress(
            rs.getString("address"), rs.getLong("merchant_id"), rs.getString("token"),
            rs.getLong("account_id"), rs.getLong("derivation_index"), rs.getString("status"));

    private final JdbcClient jdbc;

    public DepositAddressRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DepositAddress> find(long merchantId, String token) {
        return jdbc.sql("""
                        SELECT address, merchant_id, token, account_id, derivation_index, status
                        FROM deposit_address WHERE merchant_id = :m AND token = :t
                        """)
                .param("m", merchantId).param("t", token)
                .query(ROW).optional();
    }

    public Optional<MerchantRow> findMerchant(long merchantId) {
        return jdbc.sql("SELECT id, code, status FROM merchant WHERE id = :id")
                .param("id", merchantId)
                .query((rs, i) -> new MerchantRow(rs.getLong("id"), rs.getString("code"), rs.getString("status")))
                .optional();
    }

    /**
     * 确保商户在这种币上有账本账户（user:&lt;商户 code&gt;:&lt;SYMBOL&gt;），返回它的 id。
     * 唯一性由 account_code_uk 裁决（ON CONFLICT DO NOTHING），不先查再插。
     */
    public long ensureAccount(long merchantId, String merchantCode, String currency) {
        String code = "user:" + merchantCode + ":" + currency;
        jdbc.sql("""
                        INSERT INTO account (code, currency, kind, merchant_id)
                        VALUES (:code, :currency, 'LIABILITY', :m)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", code).param("currency", currency).param("m", merchantId)
                .update();
        return jdbc.sql("SELECT id FROM account WHERE code = :code")
                .param("code", code)
                .query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("账本账户 " + code + " 已存在但不属于本商户，或不可见"));
    }

    /** 下一个派生序号。序列不随事务回滚：分配失败会跳号，跳号无害。 */
    public long nextIndex() {
        return jdbc.sql("SELECT nextval('deposit_address_index_seq')").query(Long.class).single();
    }

    /**
     * 插入；同商户同代币已有行就什么都不做，返回 false（并发申请时输的一方）。
     * 注意 ON CONFLICT 只接管 (merchant_id, token) 这一条：address 主键或序号唯一约束的冲突照常抛出——那是配置错误，不是并发。
     */
    public boolean insertIfAbsent(DepositAddress a) {
        return jdbc.sql("""
                        INSERT INTO deposit_address (address, merchant_id, token, account_id, derivation_index, status)
                        VALUES (:address, :m, :t, :account, :index, :status)
                        ON CONFLICT (merchant_id, token) DO NOTHING
                        """)
                .param("address", a.address()).param("m", a.merchantId()).param("t", a.token())
                .param("account", a.accountId()).param("index", a.derivationIndex()).param("status", a.status())
                .update() == 1;
    }
}
