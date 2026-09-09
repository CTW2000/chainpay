package com.chainpay.support;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigDecimal;

/**
 * 把每一次账本调用包进 {@link SystemLedger#inTransaction}：以系统身份、在系统连接上跑。
 *
 * <p>M0 的账本测试做的是<b>系统级操作</b>：从平台账户注资、house 与 house 之间划转——没有商户，也不该有商户。
 * 2026-08-31 之前它们能直接调 {@code ledger.transfer()}，是因为应用以超级用户连库；
 * 之后靠 {@code TenantScope.asSystem()} 的会话变量放行；M4-⓪（2026-09-09）那道门拆掉，
 * 系统权限只剩连接身份一条路——这些测试从此和生产里的系统任务走同一条路。
 * 包一层而不是改十几个调用点，是为了让「M0 测试以系统身份跑」这件事<b>只在一个地方声明</b>。
 */
public final class SystemScopedLedger implements LedgerService {

    private final SystemLedger system;

    public SystemScopedLedger(SystemLedger system) {
        this.system = system;
    }

    @Override
    public long transfer(TransferCommand command) {
        return system.inTransaction(s -> s.ledger().transfer(command));
    }

    @Override
    public BigDecimal balanceOf(long accountId) {
        return system.inTransaction(s -> s.ledger().balanceOf(accountId));
    }
}
