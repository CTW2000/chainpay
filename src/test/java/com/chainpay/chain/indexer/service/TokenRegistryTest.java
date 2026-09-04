package com.chainpay.chain.indexer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.erc20.Erc20Calls;
import com.chainpay.chain.indexer.domain.ChainToken;
import com.chainpay.chain.indexer.repository.ChainTokenRepository;
import com.chainpay.chain.support.FakeChain;
import com.chainpay.support.AbstractPostgresTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 代币白名单：登记时问链，使用前核对。
 *
 * <p>V13 预置了 LINK 一行；每个测试前把别的行清掉、把 LINK 恢复成初始状态，不 TRUNCATE 整张表——
 * 其他测试类（轮询）依赖那一行存在。
 */
@SpringBootTest
@DisplayName("M2-⑥ · 代币白名单")
class TokenRegistryTest extends AbstractPostgresTest {

    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String USDC_LIKE = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final String ODD = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    private ChainTokenRepository tokens;

    private FakeChain chain;

    @BeforeEach
    void resetTokens() {
        jdbc.sql("DELETE FROM chain_token WHERE address <> :link").param("link", LINK).update();
        jdbc.sql("UPDATE chain_token SET status = 'ACTIVE', verified_at = NULL WHERE address = :link").param("link", LINK).update();
        chain = new FakeChain().withBlocks(1);
    }

    @Test
    @DisplayName("V13 预置了 LINK：decimals 18、ACTIVE、还没核对过")
    void linkIsSeeded() {
        assertThat(tokens.find(LINK)).contains(new ChainToken(LINK, "LINK", 18, "ACTIVE"));
        assertThat(verifiedAt(LINK)).isNull();
    }

    @Test
    @DisplayName("★ 登记：问链上的 decimals 与 symbol，问得到才写表")
    void registersATokenFromTheChain() {
        chain.defineToken(USDC_LIKE, "USDC", 6);

        ChainToken registered = registry().register(USDC_LIKE);

        assertThat(registered).isEqualTo(new ChainToken(USDC_LIKE, "USDC", 6, "ACTIVE"));
        assertThat(tokens.find(USDC_LIKE)).contains(registered);
    }

    @Test
    @DisplayName("★ 链上问不到 decimals（合约没有这个函数）：拒绝登记，表里没有它")
    void refusesATokenWithoutDecimals() {
        assertThatThrownBy(() -> registry().register(ODD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimals");
        assertThat(tokens.find(ODD)).isEmpty();
    }

    @Test
    @DisplayName("★ decimals 超过 18：账本装不下，拒绝登记")
    void refusesMoreThanEighteenDecimals() {
        chain.defineToken(ODD, "ODD", 24);

        assertThatThrownBy(() -> registry().register(ODD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("18");
        assertThat(tokens.find(ODD)).isEmpty();
    }

    @Test
    @DisplayName("手工登记：链上问不到的代币，运营填 decimals 并注明来源")
    void registersManuallyWithANote() {
        ChainToken registered = registry().registerManually(ODD, "ODD", 8, "合约没有 decimals()，按发行方文档填 8");

        assertThat(tokens.find(ODD)).contains(new ChainToken(ODD, "ODD", 8, "ACTIVE"));
        assertThat(registered.decimals()).isEqualTo(8);
    }

    @Test
    @DisplayName("★ 使用前核对：链上的 decimals 和表里的不一致，停下")
    void verificationFailsWhenTheChainDisagrees() {
        chain.defineToken(LINK, "LINK", 6);

        assertThatThrownBy(() -> registry().verifyAgainstChain(LINK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不一致");
        assertThat(verifiedAt(LINK)).isNull();
    }

    @Test
    @DisplayName("核对一致：记下核对时间")
    void verificationRecordsTheTime() {
        chain.defineToken(LINK, "LINK", 18);

        registry().verifyAgainstChain(LINK);

        assertThat(verifiedAt(LINK)).isNotNull();
    }

    @Test
    @DisplayName("核对时链上问不到 decimals（手工登记的代币）：无从比对，放过")
    void verificationSkipsWhenTheChainCannotAnswer() {
        registry().registerManually(ODD, "ODD", 8, "无 decimals()");

        registry().verifyAgainstChain(ODD);

        assertThat(tokens.find(ODD)).isPresent();
        assertThat(verifiedAt(ODD)).as("链上答不出，无从核对，不能假装核对过").isNull();
    }

    @Test
    @DisplayName("★ 未登记或已停用的代币不可用")
    void unregisteredOrDisabledTokensAreUnusable() {
        assertThatThrownBy(() -> registry().requireUsable(ODD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未登记");

        jdbc.sql("UPDATE chain_token SET status = 'DISABLED' WHERE address = :link").param("link", LINK).update();
        assertThatThrownBy(() -> registry().requireUsable(LINK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("停用");
    }

    @Test
    @DisplayName("已登记且 ACTIVE：可用，返回它")
    void activeTokenIsUsable() {
        assertThat(registry().requireUsable(LINK)).isEqualTo(new ChainToken(LINK, "LINK", 18, "ACTIVE"));
    }

    @Test
    @DisplayName("重复登记：拒绝，表里还是原来那一行")
    void refusesToRegisterTwice() {
        chain.defineToken(LINK, "LINK", 18);

        assertThatThrownBy(() -> registry().register(LINK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已登记");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM chain_token").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 两个实例同时登记同一个代币：先查再插之间被人插了队，输的一方得到「已登记」，不是裸的唯一约束错")
    void concurrentRegistrationLosesCleanly() {
        chain.defineToken(USDC_LIKE, "USDC", 6);
        chain.beforeCall(() -> {                                     // 查过「没有」之后、插入之前，另一个实例抢先写入
            if (tokens.find(USDC_LIKE).isEmpty()) {
                tokens.insertIfAbsent(new ChainToken(USDC_LIKE, "USDC", 6, "ACTIVE"), "另一个实例先登记的");
            }
        });

        assertThatThrownBy(() -> registry().register(USDC_LIKE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已登记");
        assertThat(noteOf(USDC_LIKE)).as("赢的那个实例写的行原样保留").isEqualTo("另一个实例先登记的");
    }

    @Test
    @DisplayName("★ symbol / note 有长度上限：链上 symbol 长得离谱就退回「?」，手工登记超长直接拒绝")
    void boundsSymbolAndNoteLength() {
        chain.defineToken(USDC_LIKE, "A".repeat(65), 6);

        assertThat(registry().register(USDC_LIKE).symbol()).isEqualTo("?");

        assertThatThrownBy(() -> registry().registerManually(ODD, "A".repeat(65), 8, "来源"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol");
        assertThatThrownBy(() -> registry().registerManually(ODD, " ", 8, "来源"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol");
        assertThatThrownBy(() -> registry().registerManually(ODD, "ODD", 8, "x".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("note");
        assertThat(tokens.find(ODD)).isEmpty();
    }

    @Test
    @DisplayName("★ 库自己也守着长度：symbol 超 64、note 超 500 直接插也插不进（V14）")
    void databaseEnforcesTheLengthBounds() {
        assertThatThrownBy(() -> tokens.insertIfAbsent(new ChainToken(ODD, "A".repeat(65), 8, "ACTIVE"), "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tokens.insertIfAbsent(new ChainToken(ODD, "ODD", 8, "ACTIVE"), "x".repeat(501)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tokens.find(ODD)).isEmpty();
    }

    // ------------------------------------------------------------------ 脚手架

    private TokenRegistry registry() {
        return new TokenRegistry(new Erc20Calls(chain), tokens);
    }

    private String noteOf(String address) {
        return jdbc.sql("SELECT note FROM chain_token WHERE address = :a").param("a", address).query(String.class).single();
    }

    private Object verifiedAt(String address) {
        // query(Object.class) 会走 bean 映射，NULL 也映成一个新 Object；要拿原始列值得用 RowMapper
        List<Object> rows = jdbc.sql("SELECT verified_at FROM chain_token WHERE address = :a")
                .param("a", address).query((rs, i) -> rs.getObject(1)).list();
        return rows.isEmpty() ? null : rows.get(0);
    }
}
