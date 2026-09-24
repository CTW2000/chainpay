package com.chainpay.chain.payout.service;

import com.chainpay.ledger.system.SystemLedger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「这是不是平台自己的收款地址」：任何商户的都算。商户连接看不到别家的行（RLS），所以走系统身份，只问是或否。
 *
 * <p>单独成类、自己开一个系统事务，因为调用方（{@link WithdrawalService}）在商户的主池事务里。直接用系统 JdbcClient 的话，
 * Spring 会把那条系统连接绑到正在进行的商户事务上、等它结束才还；系统池很小（默认 2 条），几个并发的提现申请就能把它占满。
 * 自己的系统事务问完就提交、连接就还。{@code WithdrawalServiceTest} 钉住。
 */
@Service
public class PlatformAddresses {

    private final JdbcClient jdbc;

    public PlatformAddresses(@Qualifier(SystemLedger.QUALIFIER) JdbcClient systemJdbc) {
        this.jdbc = systemJdbc;
    }

    @Transactional(SystemLedger.QUALIFIER)
    public boolean isDepositAddress(String lowercaseAddress) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM deposit_address WHERE address = :a)")
                .param("a", lowercaseAddress).query(Boolean.class).single();
    }
}
