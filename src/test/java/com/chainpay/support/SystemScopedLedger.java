package com.chainpay.support;

import com.chainpay.ledger.service.LedgerService;
import java.math.BigDecimal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把每一次账本调用包进一个系统事务：以系统身份、在系统连接上跑。系统账本的转账必须已经在 system 事务里（MANDATORY），这里替调用方开。
 *
 * <p>直接调账本的测试做的是<b>系统级操作</b>：从平台账户注资、house 与 house 之间划转——没有商户，也不该有商户。
 * 系统权限只来自连接身份，这些测试和生产里的系统任务走同一条路。
 * 包一层而不是改每个调用点，是为了让「这些测试以系统身份跑」<b>只在一个地方声明</b>。
 */
public final class SystemScopedLedger implements LedgerService {

    private final TransactionTemplate tx;
    private final LedgerService ledger;

    public SystemScopedLedger(PlatformTransactionManager systemTransactionManager, LedgerService systemLedger) {
        this.tx = new TransactionTemplate(systemTransactionManager);
        this.ledger = systemLedger;
    }

    @Override
    public long transfer(TransferCommand command) {
        return tx.execute(s -> ledger.transfer(command));
    }

    @Override
    public BigDecimal balanceOf(long accountId) {
        return tx.execute(s -> ledger.balanceOf(accountId));
    }
}
