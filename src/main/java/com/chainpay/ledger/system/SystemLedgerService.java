package com.chainpay.ledger.system;

import com.chainpay.ledger.service.LedgerServiceImpl;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 绑在系统连接上的账本：容器里限定名 {@value SystemLedger#QUALIFIER} 的 {@code LedgerService}（见 {@link SystemLedgerConfig}）。
 *
 * <p>父类 {@code transfer} 上那个不带限定名的 {@code @Transactional} 指向主池的事务管理器。原样继承的话：在系统事务之外调用，
 * 系统连接上的几条 SQL 各自提交，一笔转账的原子性悄悄没了；在系统事务之内调用，又白白多开一个主池事务。
 * 所以这里覆写成 system 的事务管理器加 {@code MANDATORY}：必须已经在 {@code @Transactional("system")} 里调，
 * 不在就当场抛 {@code IllegalTransactionStateException}；在就加入那个事务。SystemPoolBeansTest 钉住三种情况。
 */
class SystemLedgerService extends LedgerServiceImpl {

    SystemLedgerService(JdbcClient systemJdbc) {
        super(systemJdbc);
    }

    @Override
    @Transactional(value = SystemLedger.QUALIFIER, propagation = Propagation.MANDATORY)
    public long transfer(TransferCommand command) {
        return super.transfer(command);
    }
}
