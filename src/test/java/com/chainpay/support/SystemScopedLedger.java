package com.chainpay.support;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.security.service.TenantScope;
import java.math.BigDecimal;

/**
 * 把每一次账本调用包进 {@link TenantScope#asSystem}。
 *
 * <p>M0 的账本测试做的是<b>系统级操作</b>：从平台账户注资、house 与 house 之间划转——
 * 没有商户，也不该有商户。它们之前能直接调 {@code ledger.transfer()}，
 * 是因为应用以超级用户连库、RLS 对它不生效。
 *
 * <p>应用改用普通角色后（2026-08-31），这些测试必须像生产里的系统任务一样，
 * 显式进入系统作用域。包一层而不是改 14 个调用点，是为了让
 * 「M0 测试以系统身份跑」这件事<b>只在一个地方声明</b>。
 */
public final class SystemScopedLedger implements LedgerService {

    private final LedgerService delegate;
    private final TenantScope scope;

    public SystemScopedLedger(LedgerService delegate, TenantScope scope) {
        this.delegate = delegate;
        this.scope = scope;
    }

    @Override
    public long transfer(TransferCommand command) {
        return scope.asSystem(() -> delegate.transfer(command));
    }

    @Override
    public BigDecimal balanceOf(long accountId) {
        return scope.asSystem(() -> delegate.balanceOf(accountId));
    }
}
