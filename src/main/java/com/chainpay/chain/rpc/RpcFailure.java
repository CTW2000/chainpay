package com.chainpay.chain.rpc;

import java.util.Locale;

/**
 * 一次节点失败该怎么处理。
 *
 * <p>{@link JsonRpcException#code()} 说的是失败<b>从哪一层来</b>（正文里有没有 error 对象），
 * 调用方要的却是<b>该做什么</b>。两者不是一一对应的：Alchemy 的 429 带 code 429、401 带 code -32000
 * （见 {@link JsonRpcClient}），链头附近单块取日志失败也带码（见 BlockIndexer），这些都得翻译回「没问到」。
 *
 * <p>所以这里只认一件事：<b>合约执行的结论（revert）才算节点的最终回答</b>。
 * 其余错误码一律当作不认识，由调用方先重试、超过预算再下结论——
 * 后端落后几秒就好、非归档节点永远不好，这两者在错误码上分不开。
 */
public enum RpcFailure {

    /** 没问到：传输失败、超时、限流、id 不符、区块不存在。等下一轮。 */
    NO_ANSWER,

    /** 凭证被拒（HTTP 401 / 403）：重试永远没用，停下叫人。 */
    NOT_ALLOWED,

    /** 节点对这次调用的最终回答：合约 revert。可以据此下结论。 */
    ANSWER,

    /** 带码但不认识：后端落后、非归档、提供商配额都长这样。分不清暂时还是永久，先重试。 */
    UNKNOWN;

    public static RpcFailure of(JsonRpcException e) {
        if (e instanceof RpcAuthException) {
            return NOT_ALLOWED;
        }
        if (e.code() == null) {
            return NO_ANSWER;
        }
        return isRevert(e) ? ANSWER : UNKNOWN;
    }

    /** geth 的 revert 是 code 3；提供商包一层之后码会变，但原文里一定带 revert。 */
    private static boolean isRevert(JsonRpcException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return e.code() == 3 || message.contains("revert");
    }
}
