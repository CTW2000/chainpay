package com.chainpay.chain.erc20;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.rpc.RawLog;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ERC-20 Transfer 日志解码的<b>已知答案测试</b>。
 *
 * <p>向量是 2026-09-02 从 Sepolia 抓的一条真实 LINK 转账
 * （区块 11617625，交易 0xce33…698b，logIndex 28），
 * 期望值由 Python 独立解出——不是 Java 自己算自己比（质询扫描 5.3 的教训）。
 *
 * <p>三条「畸形日志必须拒绝」对应 M2-before 第 19 问：
 * 解析代码遇到不该出现的形状，是拒绝，还是把错的数当成对的？
 */
@DisplayName("M2 · Transfer 日志解码")
class TransferLogDecoderTest {

    static final String TRANSFER_TOPIC0 =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    /** 原样照抄 eth_getLogs 的返回。 */
    static final RawLog REAL_LINK_TRANSFER = new RawLog(
            "0x779877a7b0d9e8603169ddbd7836e478b4624789",
            List.of(TRANSFER_TOPIC0,
                    "0x0000000000000000000000004281ecf07378ee595c564a59048801330f3084ee",
                    "0x0000000000000000000000005e97b169613aff0c40a1910e597e9736c3a5ebc3"),
            "0x0000000000000000000000000000000000000000000000015af1d78b58c40000",
            "0xb14559",
            "0x6e2ea8c8b871ef3f31108fbf22f6113455b79e327bae2c7d6e2fbce44282f54d",
            "0xce337e371c841a449898b51a5a269e38a780ab637c16901e0f71168c865c698b",
            "0x38",
            "0x1c",
            false);

    @Test
    @DisplayName("★ 真实的 LINK 转账：from / to / value / 坐标 全部与独立解码一致")
    void decodesARealTransfer() {
        Erc20Transfer t = TransferLogDecoder.decode(REAL_LINK_TRANSFER);

        assertThat(t.token()).isEqualTo("0x779877a7b0d9e8603169ddbd7836e478b4624789");
        assertThat(t.from()).isEqualTo("0x4281ecf07378ee595c564a59048801330f3084ee");
        assertThat(t.to()).isEqualTo("0x5e97b169613aff0c40a1910e597e9736c3a5ebc3");
        // 25 LINK = 25 × 10^18 原始单位。链上没有小数点。
        assertThat(t.value()).isEqualTo(new BigInteger("25000000000000000000"));
        assertThat(t.blockNumber()).isEqualTo(11617625L);
        assertThat(t.blockHash()).isEqualTo(
                "0x6e2ea8c8b871ef3f31108fbf22f6113455b79e327bae2c7d6e2fbce44282f54d");
        assertThat(t.transactionHash()).isEqualTo(
                "0xce337e371c841a449898b51a5a269e38a780ab637c16901e0f71168c865c698b");
        assertThat(t.logIndex()).isEqualTo(28);
    }

    @Test
    @DisplayName("★ topic0 不是 Transfer 的签名 —— 拒绝，而不是把别的事件当转账")
    void rejectsOtherEventSignatures() {
        var approval = new RawLog(REAL_LINK_TRANSFER.address(),
                List.of("0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925",   // Approval
                        REAL_LINK_TRANSFER.topics().get(1), REAL_LINK_TRANSFER.topics().get(2)),
                REAL_LINK_TRANSFER.data(), "0xb14559", REAL_LINK_TRANSFER.blockHash(),
                REAL_LINK_TRANSFER.transactionHash(), "0x38", "0x1c", false);

        assertThatThrownBy(() -> TransferLogDecoder.decode(approval))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic0");
    }

    @Test
    @DisplayName("★ topics 不是 3 个（非 indexed 的 Transfer）—— 拒绝")
    void rejectsWrongTopicCount() {
        var oneTopic = new RawLog(REAL_LINK_TRANSFER.address(), List.of(TRANSFER_TOPIC0),
                REAL_LINK_TRANSFER.data(), "0xb14559", REAL_LINK_TRANSFER.blockHash(),
                REAL_LINK_TRANSFER.transactionHash(), "0x38", "0x1c", false);

        assertThatThrownBy(() -> TransferLogDecoder.decode(oneTopic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topics");
    }

    @Test
    @DisplayName("★ 地址 topic 的前 12 字节不是 0 —— 那不是地址，拒绝")
    void rejectsNonAddressTopic() {
        var junk = new RawLog(REAL_LINK_TRANSFER.address(),
                List.of(TRANSFER_TOPIC0,
                        "0xdeadbeef000000000000000000004281ecf07378ee595c564a59048801330f3084ee".substring(0, 66),
                        REAL_LINK_TRANSFER.topics().get(2)),
                REAL_LINK_TRANSFER.data(), "0xb14559", REAL_LINK_TRANSFER.blockHash(),
                REAL_LINK_TRANSFER.transactionHash(), "0x38", "0x1c", false);

        assertThatThrownBy(() -> TransferLogDecoder.decode(junk))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("地址");
    }

    @Test
    @DisplayName("★ data 不是 32 字节 —— 拒绝，绝不静默截断或补零")
    void rejectsWrongDataLength() {
        var shortData = new RawLog(REAL_LINK_TRANSFER.address(), REAL_LINK_TRANSFER.topics(),
                "0x15af1d78b58c40000", "0xb14559", REAL_LINK_TRANSFER.blockHash(),
                REAL_LINK_TRANSFER.transactionHash(), "0x38", "0x1c", false);

        assertThatThrownBy(() -> TransferLogDecoder.decode(shortData))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data");
    }
}
