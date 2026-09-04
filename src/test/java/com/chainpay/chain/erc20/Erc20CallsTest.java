package com.chainpay.chain.erc20;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.rpc.JsonRpcException;
import com.chainpay.chain.support.FakeChain;
import java.math.BigInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 问合约三个问题时，「问不到」与「没问到」的分界。
 *
 * <p>decimals / symbol：合约没有这个函数（revert）、返回空、返回值形状不对、值超出合理范围 → 空，调用方另想办法；
 * 传输失败 → 原样抛出，轮询器当瞬时失败重试。balanceOf 不降级：「不知道」和「余额为零」在数字上分不开。
 */
@DisplayName("M2-⑥ · 问合约：问不到 vs 没问到")
class Erc20CallsTest {

    static final String TOKEN = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String ALICE = "0x4281ecf07378ee595c564a59048801330f3084ee";
    static final String WORD_32 = "0000000000000000000000000000000000000000000000000000000000000020";
    static final String WORD_2_POW_30 = "0000000000000000000000000000000000000000000000000000000040000000";
    static final String LINK_PADDED = "4c494e4b00000000000000000000000000000000000000000000000000000000";

    private FakeChain chain;
    private Erc20Calls calls;

    @BeforeEach
    void setUp() {
        chain = new FakeChain().withBlocks(1);
        calls = new Erc20Calls(chain);
    }

    @Test
    @DisplayName("答得出：decimals 18、symbol LINK、balanceOf 原样")
    void answersWhenTheContractAnswers() {
        chain.defineToken(TOKEN, "LINK", 18);
        chain.defineBalance(TOKEN, ALICE, BigInteger.TEN);

        assertThat(calls.decimals(TOKEN)).hasValue(18);
        assertThat(calls.symbol(TOKEN)).contains("LINK");
        assertThat(calls.balanceOf(TOKEN, ALICE, "latest")).isEqualTo(BigInteger.TEN);
    }

    @Test
    @DisplayName("★ 合约没有这个函数（revert，带 code）：问不到，返回空")
    void revertMeansNoAnswer() {
        assertThat(calls.decimals(TOKEN)).isEmpty();
        assertThat(calls.symbol(TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("★ 返回空、不是一个整字、装不进 uint8、bytes32 冒充 string：都当问不到")
    void malformedAnswersMeanNoAnswer() {
        chain.defineCall(TOKEN, Abi.DECIMALS, "0x");
        assertThat(calls.decimals(TOKEN)).isEmpty();
        chain.defineCall(TOKEN, Abi.DECIMALS, "0x12");
        assertThat(calls.decimals(TOKEN)).isEmpty();
        chain.defineCall(TOKEN, Abi.DECIMALS, Abi.encodeUint(BigInteger.valueOf(256)));
        assertThat(calls.decimals(TOKEN)).isEmpty();
        chain.defineCall(TOKEN, Abi.SYMBOL, "0x" + "4d4b5200" + "0".repeat(56));
        assertThat(calls.symbol(TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("★ symbol 的长度字大到溢出机器整数：仍然只是「问不到」，不是从解码器里漏出来的异常")
    void overflowingLengthWordIsJustNoAnswer() {
        chain.defineCall(TOKEN, Abi.SYMBOL, "0x" + WORD_32 + WORD_2_POW_30 + LINK_PADDED);

        assertThat(calls.symbol(TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("★ symbol 长得离谱（超过 64 个字符）或空白：不是代号，当问不到")
    void absurdlyLongOrBlankSymbolIsNoAnswer() {
        chain.defineCall(TOKEN, Abi.SYMBOL, Abi.encodeString("A".repeat(65)));
        assertThat(calls.symbol(TOKEN)).isEmpty();

        chain.defineCall(TOKEN, Abi.SYMBOL, Abi.encodeString("   "));
        assertThat(calls.symbol(TOKEN)).isEmpty();

        chain.defineCall(TOKEN, Abi.SYMBOL, Abi.encodeString("A".repeat(64)));
        assertThat(calls.symbol(TOKEN)).hasValueSatisfying(s -> assertThat(s).hasSize(64));
    }

    @Test
    @DisplayName("★ 传输失败（code 为空）：没问到，原样抛出，不能当成「没有」")
    void transportFailureIsRethrown() {
        chain.defineToken(TOKEN, "LINK", 18);
        chain.beforeCall(() -> { throw new JsonRpcException(null, "超时（20000 ms，含正文）· eth_call"); });

        assertThatThrownBy(() -> calls.decimals(TOKEN)).isInstanceOf(JsonRpcException.class).hasMessageContaining("超时");
        assertThatThrownBy(() -> calls.symbol(TOKEN)).isInstanceOf(JsonRpcException.class).hasMessageContaining("超时");
    }

    @Test
    @DisplayName("balanceOf 不降级：问不到就抛，因为「不知道」和「余额为零」在数字上分不开")
    void balanceOfDoesNotDegrade() {
        assertThatThrownBy(() -> calls.balanceOf(TOKEN, ALICE, "latest")).isInstanceOf(JsonRpcException.class);
    }
}
