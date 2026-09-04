package com.chainpay.chain.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 节点地址按密码对待：URL 的路径里就是提供商的 key。
 * 解析失败时 JDK 的 URISyntaxException 会把整条输入连出错位置一起放进 message，
 * 那正是最容易被整段贴进工单的一行日志——这一层的职责是把它挡在异常信息之外。
 */
@DisplayName("M2-⑥ 补丁 3 · 节点地址按密码对待")
class RpcEndpointTest {

    static final String KEY = "THIS-IS-THE-SECRET-KEY";
    static final String ENV = "CHAINPAY_CHAIN_RPC_URL";

    @Test
    @DisplayName("合法地址：留下 URI 与主机名；toString 只给主机名")
    void parsesAValidUrl() {
        RpcEndpoint e = RpcEndpoint.parse(ENV, "https://eth-sepolia.g.alchemy.com/v2/" + KEY);

        assertThat(e.host()).isEqualTo("eth-sepolia.g.alchemy.com");
        assertThat(e.uri().getPath()).endsWith(KEY);
        assertThat(e.toString()).isEqualTo("eth-sepolia.g.alchemy.com");
    }

    @Test
    @DisplayName("★ 解析失败：异常信息带变量名和主机名，绝不带原文里的 key")
    void neverEchoesTheKeyOnFailure() {
        List<String> bad = List.of(
                "https://eth-sepolia.g.alchemy.com/v2/" + KEY + " \"",      // 粘贴时多了空格和引号
                "https://eth-sepolia.g.alchemy.com/v2/" + KEY + "\n",       // 多了换行
                "ftp://eth-sepolia.g.alchemy.com/v2/" + KEY,                // 不是 http(s)
                "eth-sepolia.g.alchemy.com/v2/" + KEY);                     // 没有 scheme
        for (String url : bad) {
            assertThatThrownBy(() -> RpcEndpoint.parse(ENV, url))
                    .as(url.replace(KEY, "<key>"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ENV)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY));
        }
    }

    @Test
    @DisplayName("空值：主节点必填，报错说明变量名；审计节点可选，空就是空")
    void blankHandling() {
        assertThatThrownBy(() -> RpcEndpoint.parse(ENV, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ENV);
        assertThat(RpcEndpoint.parseOptional("CHAINPAY_CHAIN_AUDIT_RPC_URL", null)).isEmpty();
        assertThat(RpcEndpoint.parseOptional("CHAINPAY_CHAIN_AUDIT_RPC_URL", "")).isEmpty();
        assertThat(RpcEndpoint.parseOptional("CHAINPAY_CHAIN_AUDIT_RPC_URL", "https://sepolia.gateway.tenderly.co"))
                .map(RpcEndpoint::host).contains("sepolia.gateway.tenderly.co");
    }
}
