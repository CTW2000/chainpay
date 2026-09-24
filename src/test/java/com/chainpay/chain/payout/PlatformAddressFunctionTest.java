package com.chainpay.chain.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.deposit.service.AbstractDepositPostingTest;
import com.chainpay.chain.payout.repository.HotWalletRepository;
import com.chainpay.chain.wallet.EthAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 「这是不是平台自己的地址」：库里一个只答是 / 否的函数（V30，进程拆分第 ② 步、取舍 2）。
 * 商户连接受 RLS 约束、看不到别家的收款地址；函数按属主 chainpay_system 的身份执行，调用方只拿到 true / false，拿不到任何一行。
 */
@SpringBootTest
@DisplayName("平台地址的是 / 否函数")
class PlatformAddressFunctionTest extends AbstractDepositPostingTest {

    static final String HOT = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";      // 当热钱包用的地址（只用地址，不用私钥）
    static final String OTHER = "0x90f79bf6eb2c4f870365e785982e1f101e93b906";

    @Test
    @DisplayName("★ 应用角色一行都看不到，却问得到是 / 否：收款地址 → 是，热钱包（有行）→ 是，别的地址 → 否；大小写不影响结论")
    void theAppRoleGetsOnlyYesOrNo() {
        new HotWalletRepository(systemJdbc).insertIfAbsent(HOT, "sepolia", 0);

        assertThat(appJdbc.sql("SELECT count(*) FROM deposit_address").query(Long.class).single())
                .as("前提：应用角色没进任何商户的作用域，收款地址一行都看不到").isZero();
        assertThat(ask(ACME_ADDRESS)).as("acme 的收款地址").isTrue();
        assertThat(ask(EthAddress.checksummed(ACME_ADDRESS))).as("同一个地址的大小写混写").isTrue();
        assertThat(ask(HOT)).as("热钱包（库里已有它的行）").isTrue();
        assertThat(ask(OTHER)).as("外面的地址").isFalse();
    }

    @Test
    @DisplayName("★ 调用方在自己的会话里建同名的空临时表，骗不了它：search_path 钉死、pg_temp 排在最后")
    void aCallerCannotShadowTheTablesWithTempTables() throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), "chainpay_app", "chainpay_app_dev");
             Statement st = c.createStatement()) {
            st.execute("CREATE TEMP TABLE deposit_address (address TEXT)");     // 两张空表：想让函数以为「谁都不是平台的地址」
            st.execute("CREATE TEMP TABLE hot_wallet (address TEXT)");
            // 临时表归调用方所有，函数的属主读不了；但调用方自己就能授权给它——少了这一步，没钉死的函数只会报 permission denied，不会答「不是」
            st.execute("GRANT SELECT ON pg_temp.deposit_address, pg_temp.hot_wallet TO chainpay_system");
            try (ResultSet rs = st.executeQuery("SELECT is_platform_address('" + ACME_ADDRESS + "')")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("函数读的必须是真表，不是调用方伪造的临时表").isTrue();
            }
        }
    }

    @Test
    @DisplayName("★ 属主看不全行时拒绝回答，不答「不是」：换一个没有 BYPASSRLS 的属主（坑 3：开发库、测试库的属主是超级用户，这种错在那里测不出来）")
    void refusesToAnswerWhenItsOwnerCannotSeeEveryRow() throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), ownerUsername(), ownerPassword())) {
            c.setAutoCommit(false);                                          // 建角色、改属主都在这个事务里，最后整体回滚，不留痕
            try (Statement st = c.createStatement()) {
                st.execute("CREATE ROLE blind_owner NOLOGIN");
                st.execute("GRANT SELECT ON deposit_address, hot_wallet TO blind_owner");    // 表读得到，只是行被 RLS 挡住
                st.execute("ALTER FUNCTION is_platform_address(text) OWNER TO blind_owner");

                assertThatThrownBy(() -> st.executeQuery("SELECT is_platform_address('" + ACME_ADDRESS + "')").close())
                        .as("没有自检时它会答「不是」：提现检查被静默放行")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("blind_owner")
                        .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
            } finally {
                c.rollback();
            }
        }
    }

    private boolean ask(String address) {
        return appJdbc.sql("SELECT is_platform_address(:a)").param("a", address).query(Boolean.class).single();
    }
}
