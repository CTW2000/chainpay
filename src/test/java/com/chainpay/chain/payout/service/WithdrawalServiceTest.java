package com.chainpay.chain.payout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.deposit.service.AbstractDepositPostingTest;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.common.web.ErrorCode;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 热钱包自己的地址也是平台的口袋：提到它是 to == from 的一笔交易，账本会记「付出去了」而链上什么都没变。 */
@SpringBootTest
@DisplayName("M4-④ 补丁 · 热钱包地址不能当提现目标")
class WithdrawalServiceTest extends AbstractDepositPostingTest {

    static final String HOT_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";   // Hardhat #0，公开测试私钥
    static final String OTHER = "0x90f79bf6eb2c4f870365e785982e1f101e93b906";

    @AfterEach
    void cleanPayoutTables() {
        jdbc.sql("TRUNCATE payout_tx, payout, payout_address, payout_limit, hot_wallet CASCADE").update();
    }

    @Test
    @DisplayName("★ 装配了热钱包时：登记热钱包地址 → 2010；提到热钱包地址 → 2010；别的地址照常")
    void hotWalletAddressIsRejectedAsATarget() {
        HotWalletSigner signer = HotWalletSigner.fromHex(HOT_KEY);
        WithdrawalService service = new WithdrawalService(appJdbc, appLedger, systemLedger, Optional.of(signer));

        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> service.registerAddress(acmeId, signer.address(), null)))
                .isInstanceOf(WithdrawalRejectedException.class)
                .extracting(e -> ((WithdrawalRejectedException) e).code()).isEqualTo(ErrorCode.INTERNAL_ADDRESS);
        assertThat(tenantScope.asMerchant(acmeId, () -> service.registerAddress(acmeId, OTHER, "别的").status())).isEqualTo("ACTIVE");
        jdbc.sql("INSERT INTO payout_address (merchant_id, address) VALUES (:m, :a)").param("m", acmeId).param("a", signer.address().toLowerCase()).update();   // 假设它曾被登记进去
        assertThatThrownBy(() -> tenantScope.asMerchant(acmeId, () -> service.request(acmeId, LINK, signer.address(), new BigDecimal("1"), "w-hot")))
                .isInstanceOf(WithdrawalRejectedException.class)
                .extracting(e -> ((WithdrawalRejectedException) e).code()).isEqualTo(ErrorCode.INTERNAL_ADDRESS);
        assertThat(jdbc.sql("SELECT count(*) FROM payout").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("★ 没装配热钱包时（测试与无私钥的部署）：没有热钱包地址可拒，别的规则照常")
    void withoutAHotWalletNothingChanges() {
        WithdrawalService service = new WithdrawalService(appJdbc, appLedger, systemLedger, Optional.empty());
        assertThat(tenantScope.asMerchant(acmeId, () -> service.registerAddress(acmeId, OTHER, null).status())).isEqualTo("ACTIVE");
    }
}
