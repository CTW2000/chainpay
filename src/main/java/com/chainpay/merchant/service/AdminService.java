package com.chainpay.merchant.service;

import com.chainpay.security.crypto.SecretCipher;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商户与凭证的发放、吊销、停用。
 */
@Service
public class AdminService {

    /**
     * 发放成功的结果。<b>明文 secret 只在这里出现这一次。</b>
     *
     * <p>手写 toString() 把 secret 挡掉：record 自动生成的 toString() 包含全部字段，
     * 一句 {@code log.info("发放凭证成功: {}", result)} 就会把密钥永久写进日志。
     * 让<b>误用的默认行为是安全的</b>，比要求每个人都记得「别打这个对象」可靠得多。
     */
    public record IssuedCredential(long credentialId, String apiKey, String secret) {
        @Override
        public String toString() {
            return "IssuedCredential[credentialId=" + credentialId
                    + ", apiKey=" + apiKey + ", secret=***]";
        }
    }

    /** 凭证列表里的一行。<b>刻意不含 secret</b> —— 明文只在发放那一次返回。 */
    public record CredentialSummary(
            long credentialId,
            String apiKey,
            String label,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime lastUsedAt,
            OffsetDateTime revokedAt) {}

    /** 要建的东西已经存在。转成 409，而不是让数据库异常冒到调用方那里。 */
    public static class AlreadyExistsException extends RuntimeException {
        public AlreadyExistsException(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbcClient;
    private final SecretCipher cipher;

    public AdminService(JdbcClient jdbcClient, SecretCipher cipher) {
        this.jdbcClient = jdbcClient;
        this.cipher = cipher;
    }

    /**
     * 创建商户。
     *
     * <p>重复的 code 由数据库的 {@code merchant_code_uk} 唯一约束拦下，
     * 这里只负责把它翻译成一个不泄露内部细节的业务异常。
     *
     * <p>靠约束而不是「先 SELECT 查一遍有没有」：后者是 check-then-act，两个并发请求可以同时查到「不存在」、
     * 然后同时插入。约束是数据库层面的原子判定，绕不过去。
     */
    @Transactional
    public long createMerchant(String code, String name) {
        try {
            return jdbcClient.sql("""
                            INSERT INTO merchant (code, name) VALUES (:code, :name)
                            RETURNING id
                            """)
                    .param("code", code)
                    .param("name", name)
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            // 只说「已存在」，不带约束名、表名、原始报错。
            // 那些是内部结构，对调用方没用，对攻击者有用。
            throw new AlreadyExistsException("商户 code 已存在");
        }
    }

    /**
     * 给商户发一把新凭证，返回<b>只此一次</b>的明文 secret。
     *
     * <p>不提供「重新查看 secret」的接口：存的是密文（解密就能拿回明文），但每个找回接口都是一条新的
     * <b>明文穿越网络的通道</b>，可以被调用无数次。丢了钥匙就换一把（作废旧的、发新的），明文仍只出现一次。
     */
    @Transactional
    public IssuedCredential issueCredential(long merchantId, String label) {
        requireMerchantExists(merchantId);

        String apiKey = "ak_" + cipher.randomToken(16);
        String secret = cipher.generateSecret();

        long credentialId = jdbcClient.sql("""
                        INSERT INTO api_credential (merchant_id, api_key, secret_encrypted, label)
                        VALUES (:merchantId, :apiKey, :secret, :label)
                        RETURNING id
                        """)
                .param("merchantId", merchantId)
                .param("apiKey", apiKey)
                .param("secret", cipher.encrypt(secret))
                .param("label", label)
                .query(Long.class)
                .single();

        return new IssuedCredential(credentialId, apiKey, secret);
    }

    /**
     * 列出商户名下全部凭证（含已吊销的）。
     *
     * <p><b>这是一道安全措施：</b>发凭证不是幂等的，同一个请求被重试两次会产生<b>两把都有效的</b>凭证
     * （api_key 是随机的，唯一约束拦不住）。商户可能只看到其中一次响应，另一把就成了没人知道、却永远有效的幽灵凭证。
     * 列出全部不能阻止它产生，但能让它<b>可见</b>。已吊销的也要列：安全复盘要回答「这个商户历史上有过哪些钥匙」。
     */
    public List<CredentialSummary> listCredentials(long merchantId) {
        requireMerchantExists(merchantId);
        return jdbcClient.sql("""
                        SELECT id, api_key, label, status, created_at, last_used_at, revoked_at
                        FROM api_credential
                        WHERE merchant_id = :merchantId
                        ORDER BY id
                        """)
                .param("merchantId", merchantId)
                .query((rs, rowNum) -> new CredentialSummary(
                        rs.getLong("id"),
                        rs.getString("api_key"),
                        rs.getString("label"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_used_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class)))
                .list();
    }

    /**
     * 吊销单把凭证。同商户的其他凭证不受影响。
     *
     * <p><b>吊销和轮换是两件事：</b>轮换是计划内的（发新的、切流量、再吊销旧的，要求零停机），
     * 吊销是紧急的（钥匙泄露了，要求立刻死）。轮换不需要单独的接口，就是「发一把新的」+ 稍后「吊销旧的」——
     * 前提是<b>一个商户可以同时持有多把有效凭证</b>：{@code api_credential.merchant_id} 上不能加唯一约束。
     *
     * <p><b>改状态而不是删行</b>：删掉就查不出「这把 key 曾经属于谁、做过什么」，
     * 安全事故复盘的第一步就断了。
     */
    @Transactional
    public void revokeCredential(long credentialId) {
        int updated = jdbcClient.sql("""
                        UPDATE api_credential
                        SET status = 'REVOKED', revoked_at = now()
                        WHERE id = :id AND status = 'ACTIVE'
                        """)
                .param("id", credentialId)
                .update();

        // 更新 0 行：不存在（报错），或者已经是 REVOKED（静默成功）。
        // 吊销是幂等的：重复吊销一把已死的钥匙，结果还是「它是死的」，不是错误。
        // 创建相反：重复创建会多出东西，所以报 409。判据是「重做一次会不会改变最终状态」。
        if (updated == 0) {
            requireCredentialExists(credentialId);
        }
    }

    /**
     * 停用商户 —— 他名下<b>所有</b>凭证同时失效。
     *
     * <p>生效点不在这里，而在 {@code ApiCredentialService.authenticate()} 那条 SQL 的
     * {@code AND m.status = 'ACTIVE'}：一次 UPDATE 让所有钥匙同时失效，不用逐把吊销
     * （逐把吊销会有时间差，还可能漏掉刚发出去的那把）。每个请求都实打实查一次库、没有缓存，所以立刻生效。
     *
     * <p><b>将来加凭证缓存时，这条会变成一个隐患</b>：缓存 5 分钟，被停用的商户还能再打 5 分钟。
     * 那时必须同时做<b>主动失效</b>，不能只靠 TTL 自然过期。
     */
    @Transactional
    public void suspendMerchant(long merchantId) {
        int updated = jdbcClient.sql("""
                        UPDATE merchant SET status = 'SUSPENDED'
                        WHERE id = :id AND status = 'ACTIVE'
                        """)
                .param("id", merchantId)
                .update();

        if (updated == 0) {
            requireMerchantExists(merchantId);
        }
    }

    // ==================================================================

    private void requireMerchantExists(long merchantId) {
        Long found = jdbcClient.sql("SELECT id FROM merchant WHERE id = :id")
                .param("id", merchantId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (found == null) {
            throw new IllegalArgumentException("商户不存在");
        }
    }

    private void requireCredentialExists(long credentialId) {
        Long found = jdbcClient.sql("SELECT id FROM api_credential WHERE id = :id")
                .param("id", credentialId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (found == null) {
            throw new IllegalArgumentException("凭证不存在");
        }
    }
}
