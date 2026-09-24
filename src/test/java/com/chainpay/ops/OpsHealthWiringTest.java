package com.chainpay.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.chain.payout.repository.HotWalletRepository;
import com.chainpay.chain.wallet.EthAddress;
import com.chainpay.chain.wallet.HotWalletSigner;
import com.chainpay.ops.health.HotWalletHealthIndicator;
import com.chainpay.support.AbstractPostgresTest;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 健康端点里「读状态」那一步（{@link OpsHealthConfig} 的 lambda）对着真库跑。{@code HealthIndicatorsTest} 只测判定，查询是假的；
 * 查询本身写错——比如拿去查的地址和库里存的写法不一样——只有这里抓得到。
 */
@SpringBootTest
@DisplayName("健康检查的装配：按库里的写法去查")
class OpsHealthWiringTest extends AbstractPostgresTest {

    static final String HOT_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";   // Hardhat #0，公开测试私钥

    private final HotWalletSigner signer = HotWalletSigner.fromHex(HOT_KEY);

    /** 发送相关的测试也用这把钥匙：前后都清空热钱包表，别的测试留下的行不影响这里，这里停发的钱包也不留给别人。 */
    @BeforeEach
    @AfterEach
    void cleanHotWallets() {
        jdbc.sql("TRUNCATE hot_wallet CASCADE").update();
    }

    @Test
    @DisplayName("★ 热钱包停发 → hotWallet 是 DOWN 且带原因：私钥推出的地址大小写混写，库里存小写，要按库的写法去查")
    void haltedHotWalletIsDownEvenThoughItsAddressIsChecksummed() {
        String stored = EthAddress.lowercase(signer.address());
        assertThat(signer.address()).as("前提：私钥推出的是 EIP-55 大小写混写的地址").isNotEqualTo(stored);
        HotWalletRepository wallets = new HotWalletRepository(systemJdbc);
        wallets.insertIfAbsent(stored, "sepolia", 7);
        HotWalletHealthIndicator indicator = new OpsHealthConfig().hotWalletHealthIndicator(Optional.of(signer), systemJdbc);

        assertThat(indicator.health().getDetails().get("nextNonce")).as("库里有这一行，就报它的编号，不是「一笔都没发过」").isEqualTo(7L);

        wallets.halt(stored, "测试：有人在别处用了这把私钥");
        Health halted = indicator.health();

        assertThat(halted.getStatus()).as("停发了必须 DOWN：work 组变 DOWN 才会告警").isEqualTo(Status.DOWN);
        assertThat(halted.getDetails().get("reason")).isEqualTo("测试：有人在别处用了这把私钥");
    }
}
