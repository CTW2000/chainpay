package com.chainpay.chain.payout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.deposit.service.AbstractDepositPostingTest;
import com.chainpay.chain.payout.repository.HotWalletRepository;
import com.chainpay.common.web.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 平台自己的口袋不能当提现目标：任何商户的收款地址、热钱包自己的地址（提到它是 to == from 的一笔交易，账本记「付出去了」而链上什么都没变）。
 * 服务只拿应用角色的东西：商户连接看不到别家的收款地址，问的是库里的是 / 否函数；热钱包也从库里认，不靠私钥推地址。
 */
@SpringBootTest
@DisplayName("平台自己的地址不能当提现目标")
class WithdrawalServiceTest extends AbstractDepositPostingTest {

    static final String HOT = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";      // 当热钱包用的地址（只用地址，不用私钥）
    static final String OTHER = "0x90f79bf6eb2c4f870365e785982e1f101e93b906";

    private WithdrawalService service;

    /** 只给应用角色的两样东西：拆分之后 web 手里就这些。 */
    @BeforeEach
    void onlyTheAppRole() {
        service = new WithdrawalService(appJdbc, appLedger);
    }

    @AfterEach
    void cleanPayoutTables() {
        jdbc.sql("TRUNCATE payout_tx, payout, payout_address, payout_limit, hot_wallet CASCADE").update();
    }

    @Test
    @DisplayName("★ 登记别家商户的收款地址 → 2010：evilco 看不到 acme 的那一行，照样被拒；外面的地址照常")
    void anotherMerchantsDepositAddressIsRejected() {
        assertThatThrownBy(() -> tenantScope.asMerchant(evilcoId, () -> service.registerAddress(evilcoId, ACME_ADDRESS, null)))
                .isInstanceOf(WithdrawalRejectedException.class)
                .extracting(e -> ((WithdrawalRejectedException) e).code()).isEqualTo(ErrorCode.INTERNAL_ADDRESS);
        assertThat(tenantScope.asMerchant(evilcoId, () -> service.registerAddress(evilcoId, OTHER, null).status())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("★ 热钱包（库里已有它的行）：登记 → 2010；绕过登记直接申请 → 2010，一笔都不插")
    void theHotWalletIsRejectedOnceItHasARow() {
        new HotWalletRepository(systemJdbc).insertIfAbsent(HOT, "sepolia", 0);

        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> service.registerAddress(acmeId, HOT, null)))
                .isInstanceOf(WithdrawalRejectedException.class)
                .extracting(e -> ((WithdrawalRejectedException) e).code()).isEqualTo(ErrorCode.INTERNAL_ADDRESS);
        jdbc.sql("INSERT INTO payout_address (merchant_id, address) VALUES (:m, :a)").param("m", acmeId).param("a", HOT).update();   // 假设它曾被登记进去
        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> service.request(acmeId, LINK, HOT, new BigDecimal("1"), "w-hot")))
                .isInstanceOf(WithdrawalRejectedException.class)
                .extracting(e -> ((WithdrawalRejectedException) e).code()).isEqualTo(ErrorCode.INTERNAL_ADDRESS);
        assertThat(jdbc.sql("SELECT count(*) FROM payout").query(Long.class).single()).isZero();
    }
}
