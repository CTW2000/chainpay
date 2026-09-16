package com.chainpay.chain.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 节点失败的分类：把「错误从哪一层来」翻译成「调用方该做什么」。
 *
 * <p>{@code code} 是不是空说的是来源（正文里有没有 error 对象），不是处置。二者不一一对应，
 * 三次踩坑都写在 {@link JsonRpcClient} 与 BlockIndexer 的注释里：Alchemy 的 429 带 code 429、
 * 401 带 code -32000、链头附近单块取日志失败也带码，每次的修法都是把带码的错误重新翻译回「没问到」。
 * 所以这里只认一件事：<b>合约执行的结论（revert）才算节点的最终回答</b>，其余错误码一律先重试。
 */
@DisplayName("节点失败怎么分类")
class RpcFailureTest {

    @Test
    @DisplayName("★ 没有错误码（传输失败、超时、限流、区块不存在）= 没问到：等下一轮")
    void transportFailuresAreNoAnswer() {
        assertThat(RpcFailure.of(new JsonRpcException(null, "HTTP 429 限流，稍后再来 · eth_call"))).isEqualTo(RpcFailure.NO_ANSWER);
        assertThat(RpcFailure.of(new JsonRpcException(null, "区块不存在：0x5"))).isEqualTo(RpcFailure.NO_ANSWER);
        assertThat(RpcFailure.of(new JsonRpcException("超时（3000 ms，含正文）· eth_call", new RuntimeException()))).isEqualTo(RpcFailure.NO_ANSWER);
    }

    @Test
    @DisplayName("★ 凭证被拒 = 重试永远没用：停下叫人")
    void revokedCredentialsAreNotAllowed() {
        assertThat(RpcFailure.of(new RpcAuthException(401, "eth_getBlockByNumber"))).isEqualTo(RpcFailure.NOT_ALLOWED);
        assertThat(RpcFailure.of(new RpcAuthException(403, "eth_call"))).isEqualTo(RpcFailure.NOT_ALLOWED);
    }

    @Test
    @DisplayName("★ 合约 revert = 节点的最终回答：可以据此下结论")
    void revertIsAnAnswer() {
        assertThat(RpcFailure.of(new JsonRpcException(3, "execution reverted"))).isEqualTo(RpcFailure.ANSWER);
        assertThat(RpcFailure.of(new JsonRpcException(-32000, "execution reverted: ERC20: transfer to the zero address"))).isEqualTo(RpcFailure.ANSWER);
    }

    @Test
    @DisplayName("★ 带码但不认识（后端落后、非归档、提供商配额）= 分不清暂时还是永久：先重试，别当回答")
    void otherCodedErrorsAreUnknown() {
        assertThat(RpcFailure.of(new JsonRpcException(-32000, "header not found"))).isEqualTo(RpcFailure.UNKNOWN);
        assertThat(RpcFailure.of(new JsonRpcException(-32000, "missing trie node 0x1c2a… (path )"))).isEqualTo(RpcFailure.UNKNOWN);
        assertThat(RpcFailure.of(new JsonRpcException(35, "chain not available on free plan"))).isEqualTo(RpcFailure.UNKNOWN);
        assertThat(RpcFailure.of(new JsonRpcException(-32602, "invalid argument 1"))).isEqualTo(RpcFailure.UNKNOWN);
    }
}
