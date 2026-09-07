package com.chainpay.chain.indexer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 装配期的两道门：审计节点必须真的独立；节点地址解析失败不回显 key。
 * 不起 Spring：@Bean 方法是普通方法，同包直接调。
 */
@DisplayName("M2-⑥ 补丁 3 · 装配期的守门")
class ChainIndexerConfigTest {

    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String ALCHEMY_A = "https://eth-sepolia.g.alchemy.com/v2/KEY-AAAA";
    static final String ALCHEMY_B = "https://eth-sepolia.g.alchemy.com/v2/KEY-BBBB";
    static final String TENDERLY = "https://sepolia.gateway.tenderly.co";

    private static ChainIndexerProperties props(String rpc, String audit) {
        return new ChainIndexerProperties(rpc, audit, "sepolia", LINK, "sepolia:link:transfer", 100, null, 3, 30);
    }

    @Test
    @DisplayName("★ 审计节点与主节点是同一台主机：拒绝启动（同一家两把 key 不算独立），且不回显 key")
    void refusesAnAuditNodeOnTheSameHost() {
        assertThatThrownBy(() -> new ChainIndexerConfig().chainReaders(props(ALCHEMY_A, ALCHEMY_B)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eth-sepolia.g.alchemy.com")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("KEY-AAAA").doesNotContain("KEY-BBBB"));
    }

    @Test
    @DisplayName("★ 主节点地址不合法：报变量名，不回显 key")
    void badPrimaryUrlNeverEchoesTheKey() {
        assertThatThrownBy(() -> new ChainIndexerConfig().chainReaders(props(ALCHEMY_A + " \"", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CHAINPAY_CHAIN_RPC_URL")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("KEY-AAAA"));
    }

    @Test
    @DisplayName("没配审计节点：退化为单节点，模式说明写明抓不住节点整体撒谎")
    void singleNodeModeIsNamed() {
        ChainReaders readers = new ChainIndexerConfig().chainReaders(props(ALCHEMY_A, null));

        assertThat(readers.audit()).isSameAs(readers.primary());
        assertThat(readers.auditMode()).contains("单节点");
    }

    @Test
    @DisplayName("配了独立的审计节点：模式说明写明它的主机名，不带 key")
    void dualNodeModeNamesTheAuditHost() {
        ChainReaders readers = new ChainIndexerConfig().chainReaders(props(ALCHEMY_A, TENDERLY));

        assertThat(readers.audit()).isNotSameAs(readers.primary());
        assertThat(readers.auditMode()).contains("sepolia.gateway.tenderly.co").doesNotContain("KEY-AAAA");
    }
}
