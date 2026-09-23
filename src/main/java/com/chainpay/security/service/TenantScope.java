package com.chainpay.security.service;

import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在<b>数据库层面</b>把当前事务限制在一个商户的数据范围内。
 *
 * <p><b>2026-09-22 起这是唯一的一道锁，而且够了。</b>此前还有一道应用层的
 * {@code AccountAccessService.requireOwned}：回答「请求体里这个账户 id 是你的吗」，靠调用方记得调。
 * 它只有一个调用方——通用转账接口。那个接口连同它一起删了（用户定：真实业务走专用接口），
 * 于是商户接口<b>不再收任何账本账户 id</b>：收款地址、提现给的都是代币地址，账户由服务端推导。
 *
 * <pre>
 *   没有账户 id 进来       应用层就没有「这个 id 是谁的」这个问题要答
 *   TenantScope + RLS     数据库层。别人的行**根本查不出来**，绕不过去
 * </pre>
 *
 * <p>为什么这不算「少了一层」：那一层守的是「调用方递进来一个账户 id」这种形状，而这种形状本身没有了——
 * 参数里不给填的东西不需要校验（同「注资登记的请求体里没有金额字段」）。RLS 这一道仍然兜住所有
 * 「忘了写 WHERE merchant_id」和不走应用层的路径。
 *
 * <p><b>关键的不对称：</b>
 * <ul>
 *   <li>没有 RLS 时，漏掉一次授权检查 → <b>数据泄露</b>，而且没人会发现</li>
 *   <li>有 RLS 时，漏掉一次 {@code asMerchant} → <b>一行都查不到</b>，立刻炸给你看</li>
 * </ul>
 * 这正是本项目反复用的那条：<b>让失误的方向指向「立刻暴露」，而不是「悄悄地错」。</b>
 *
 * <p><b>★ 这句话在 2026-08-31 之前是假的，值得记下来 ★</b>
 * 那时应用以超级用户连库，本类在事务内 {@code SET LOCAL ROLE} 临时降权。
 * 于是「漏掉 asMerchant」的真实后果不是查不到，是<b>以超级用户跑、看到全库</b>——
 * 质询扫描实测 acme 拿到了 evilco 的账户，200、无报错、无日志。
 * 而当时的测试在 {@code asMerchant} 里面清变量，模拟的是一个生产里构造不出来的状态，
 * 隔壁那条测试还把「脱离作用域看到全部」断言成了正常行为。
 *
 * <p>修法不是让本类更小心，是<b>让连接本身就是普通角色</b>（见 db/init/01-roles.sql）。
 * RLS 对它无条件生效，本类只剩一件事：设租户变量。上面那句不对称，现在才是真的。
 *
 * <p><b>asSystem 已删（M4-⓪，2026-09-09）。</b>它曾用会话变量 {@code chainpay.system = on} 放行策略，
 * 是应用还以超级用户连库时留下的第二条路。M3-⓪ 起系统权限是连接身份（{@code SystemLedger}，BYPASSRLS 的独立角色），
 * V21 把策略里的 {@code is_system_scope()} 分支一起拆掉：从此没有任何一个会话变量能打开整库。
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
     * <p><b>必须开事务（{@code REQUIRES_NEW} 不必，{@code REQUIRED} 即可），
     * 因为下面两条设置都是事务级的。</b>
     *
     * <p>没有事务的话，{@code SET LOCAL} 会静静地不起作用 ——
     * PostgreSQL 只会给一条 WARNING，语句照常成功，
     * 于是租户上下文没设上，而代码看起来一切正常。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T asMerchant(long merchantId, Supplier<T> work) {
        enterTenantScope(merchantId);
        return work.get();
    }

    /**
     * 在当前事务里写入租户 id。
     *
     * <p><b>为什么是 SET LOCAL 而不是 SET —— 这一处错了会跨商户串数据：</b>
     *
     * <pre>
     *   SET LOCAL  COMMIT 后身份和变量自动复位
     *   SET        COMMIT 后**身份和变量都还在**
     * </pre>
     *
     * <p>连接是从池子里借的，用完要还回去。用 {@code SET} 的话，
     * 这条连接带着上一个商户的身份回到池子里，
     * <b>下一个借到它的请求就继承了别人的租户上下文</b>。
     * 这种 bug 只在有并发时出现，单跑测试永远是绿的。
     *
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
