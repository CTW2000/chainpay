package com.chainpay.security.service;

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
     * 在「系统作用域」的事务里执行一段工作：注资、结算、M3 入账、M4 出账——
     * 那些<b>不属于任何商户</b>、要碰平台账户（merchant_id 为 NULL）的操作。
     *
     * <p><b>★ 这个方法永远不该从 HTTP 控制器调用 ★</b>
     * 系统作用域看得到全部行。它和 {@link #asMerchant} 的信任边界一样——
     * 都是「哪段 Java 代码在调」——但后果不对称：asMerchant 传错 id 只是串到另一个商户，
     * asSystem 从控制器调出去就是整库对商户开放。
     * 目前靠评审守着；接口多起来后用 ArchUnit 断言 controller 包不得引用本方法。
     *
     * <p>为什么它必须存在：应用改用普通角色之后，RLS 对它无条件生效，
     * 没有这个作用域就没有任何代码能碰平台账户——M0 的账本测试全部「账户不存在」。
     * 之前不需要它，是因为超级用户把这个设计空白盖住了。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T asSystem(Supplier<T> work) {
        jdbcClient.sql("SELECT set_config('chainpay.system', 'on', true)")
                .query(String.class)
                .single();
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
