package com.chainpay.security.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 账户访问授权 —— 回答「这个账户是你的吗」。
 *
 * <p>它和 {@link ApiCredentialService} 是两件不同的事，刻意分成两个类：
 *
 * <pre>
 *   ApiCredentialService  认证 Authentication  你是谁？
 *   AccountAccessService  授权 Authorization   你能动这个账户吗？
 * </pre>
 *
 * <p>上一步只做了认证，实测的后果是：evilco 用自己完全合法的凭证，
 * 把 acme 账户里的钱转进了自己口袋，HTTP 200。
 * 「加了 API key 就安全了」是错觉。
 *
 * <p><b>设计上的一个刻意选择：这个类不返回布尔值，而是返回账户本身或者抛异常。</b>
 * 如果它返回 {@code boolean canAccess(...)}，调用方可以忘记看返回值 ——
 * 而忘记看一个布尔值不会有任何编译错误。
 * 返回「校验过的账户」意味着：想拿到账户，就必须经过校验，绕不过去。
 */
@Service
public class AccountAccessService {

    private final JdbcClient jdbcClient;

    public AccountAccessService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 一个已经通过归属校验的账户。
     *
     * <p>拿到这个对象 = 校验已经发生过。类型本身就是凭据。
     */
    public record AuthorizedAccount(long id, String code, String currency) {}

    /**
     * 解析账户并校验它属于调用方，否则抛出 {@link AccessDeniedException}。
     *
     * @param merchantId 当前调用方（由 {@link ApiKeyAuthFilter} 认证得出）
     * @param accountId  请求里指定的账户
     */
    public AuthorizedAccount requireOwned(long merchantId, long accountId) {
        var row = jdbcClient.sql("""
                        SELECT id, code, currency, merchant_id
                        FROM account
                        WHERE id = :id
                        """)
                .param("id", accountId)
                .query((rs, rowNum) -> new AccountRow(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("currency"),
                        // getLong 对 NULL 返回 0，必须用 getObject 区分
                        // 「归属商户 0」和「不属于任何商户」—— 这是三态问题：
                        // 有值 / 是 NULL / 行不存在，三者不能混成同一个数字。
                        rs.getObject("merchant_id") == null ? null : rs.getLong("merchant_id")))
                .optional();

        // ★ 账户不存在时，抛的是「无权访问」而不是「账户不存在」★
        //
        // 如果分别回答这两种情况，攻击者就能用它来枚举：
        // 拿 id 1、2、3…… 挨个试，凡是回「无权访问」的就是真实存在的账户，
        // 回「不存在」的就是空号 —— 他能借此画出整个系统的账户分布。
        //
        // 这叫「不可区分响应」，和上一步认证失败统一回 401 是同一个道理。
        if (row.isEmpty() || row.get().merchantId() == null
                || row.get().merchantId() != merchantId) {
            throw new AccessDeniedException(accountId);
        }

        return new AuthorizedAccount(row.get().id(), row.get().code(), row.get().currency());
    }

    /** 调用方无权访问该账户，或该账户不存在 —— 两种情况刻意不区分。 */
    public static class AccessDeniedException extends RuntimeException {
        private final transient long accountId;

        public AccessDeniedException(long accountId) {
            // 消息里可以带 id：调用方本来就知道自己传了什么，不构成信息泄露。
            // 但绝不能带「该账户属于商户 7」这类它不该知道的信息。
            super("无权访问账户 " + accountId);
            this.accountId = accountId;
        }

        public long accountId() {
            return accountId;
        }
    }

    private record AccountRow(long id, String code, String currency, Long merchantId) {}
}
