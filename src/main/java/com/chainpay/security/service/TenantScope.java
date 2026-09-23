package com.chainpay.security.service;

import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在<b>数据库层面</b>把当前事务限制在一个商户的数据范围内：设租户变量，行级安全（RLS）按它过滤。
 *
 * <p>这是租户隔离的唯一一道锁，也够了：商户接口不收任何账本账户 id（收款地址、提现给的都是代币地址，
 * 账户由服务端推导），应用层没有「这个 id 是谁的」要回答；RLS 兜住所有「忘了写 WHERE merchant_id」
 * 和不走应用层的路径，别人的行<b>根本查不出来</b>。
 *
 * <p><b>关键的不对称：</b>没有 RLS 时，漏掉一次授权检查 = 悄悄地数据泄露；有 RLS 时，漏掉一次
 * {@code asMerchant} = 一行都查不到，立刻暴露。<b>让失误的方向指向「立刻暴露」，而不是「悄悄地错」。</b>
 *
 * <p>这个不对称的前提是连接本身就是普通角色（见 db/init/01-roles.sql），RLS 对它无条件生效——
 * 以超级用户连库的话，漏掉 asMerchant 的后果是看到全库。系统权限是连接身份（BYPASSRLS 的独立角色
 * chainpay_system），不是会话变量：没有任何一个会话变量能打开整库。
 */
@Service
public class TenantScope {

    private final JdbcClient jdbcClient;

    public TenantScope(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 在「以某商户身份」的事务里执行一段工作。
     *
     * <p><b>必须开事务（{@code REQUIRES_NEW} 不必，{@code REQUIRED} 即可），因为租户变量是事务级的。</b>
     * 没有事务的话，它随设置它的那一条语句结束就失效：语句照常成功，租户上下文却没设上，而代码看起来一切正常。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T asMerchant(long merchantId, Supplier<T> work) {
        enterTenantScope(merchantId);
        return work.get();
    }

    /**
     * 在当前事务里写入租户 id。
     *
     * <p><b>必须是 SET LOCAL（COMMIT 后自动复位）而不是 SET（COMMIT 后变量还在）——错了会跨商户串数据：</b>
     * 连接是从池子里借的，用 {@code SET} 的话，这条连接带着上一个商户的租户变量回到池子里，
     * <b>下一个借到它的请求就继承了别人的租户上下文</b>。这种 bug 只在有并发时出现，单跑测试永远是绿的。
     */
    private void enterTenantScope(long merchantId) {
        // set_config 是 SET LOCAL 的函数形式，第三个参数 true = LOCAL。
        // 用它是因为函数可以接参数，而 SET LOCAL 的值不能参数化。
        jdbcClient.sql("SELECT set_config('chainpay.merchant_id', :id, true)")
                .param("id", String.valueOf(merchantId))
                .query(String.class)
                .single();
    }
}
