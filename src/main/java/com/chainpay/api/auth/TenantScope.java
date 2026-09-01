package com.chainpay.api.auth;

import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在<b>数据库层面</b>把当前事务限制在一个商户的数据范围内。
 *
 * <p><b>它和 {@link AccountAccessService} 的关系：两道锁，不是二选一。</b>
 *
 * <pre>
 *   AccountAccessService  应用层。回答「这个账户是你的吗」，靠调用方记得调
 *   TenantScope + RLS     数据库层。别人的行**根本查不出来**，绕不过去
 * </pre>
 *
 * <p>为什么两道都要：应用层那道能给出清楚的 403 错误信息，
 * 数据库那道兜住所有「忘了调」和「不走应用层」的路径。
 * 去掉任何一道都还能跑，但少了一层。
 *
 * <p><b>关键的不对称：</b>
 * <ul>
 *   <li>没有 RLS 时，漏掉一次授权检查 → <b>数据泄露</b>，而且没人会发现</li>
 *   <li>有 RLS 时，漏掉一次设置租户 → <b>什么都查不到</b>，立刻炸给你看</li>
 * </ul>
 * 这正是本项目反复用的那条：<b>让失误的方向指向「立刻暴露」，而不是「悄悄地错」。</b>
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
     * 把当前事务切换成受限角色，并写入租户 id。
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
     * <p><b>为什么要切角色，而不是只设一个变量：</b>
     * 应用现在用超级用户连库，而<b>超级用户无条件绕过 RLS</b> ——
     * 策略写得再对也是装饰，且不会有任何报错。
     * {@code SET LOCAL ROLE} 让这个事务临时降权成一个普通角色，RLS 才真正生效。
     *
     * <p>注意 {@code SET LOCAL ROLE} 的角色名<b>不能</b>用占位符参数
     * （它是标识符不是值），所以这里写成字面量常量。
     * 常量是代码里写死的、不来自任何输入，因此不存在注入面。
     */
    private void enterTenantScope(long merchantId) {
        jdbcClient.sql("SET LOCAL ROLE chainpay_app").update();
        // set_config 是 SET LOCAL 的函数形式，第三个参数 true = LOCAL。
        // 用它是因为函数可以接参数，而 SET LOCAL 的值不能参数化。
        jdbcClient.sql("SELECT set_config('chainpay.merchant_id', :id, true)")
                .param("id", String.valueOf(merchantId))
                .query(String.class)
                .single();
    }
}
