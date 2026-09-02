package com.chainpay.chain.rpc;

/**
 * 一次 JSON-RPC 调用失败。
 *
 * <p>{@link #code()} 分两类：
 * <ul>
 *   <li><b>非空</b> —— 节点在 HTTP 200 里返回了 {@code error} 对象，这是它的业务错误码
 *       （-32602 参数错、提供商自定义的 35 "chain not available on free plan" 等）</li>
 *   <li><b>空</b> —— 传输层失败：连不上、超时、5xx、响应不是 JSON、id 对不上</li>
 * </ul>
 * 调用方据此决定：前者多半是我们的请求有问题（比如范围太大要减半），后者该换节点或重试。
 */
public class JsonRpcException extends RuntimeException {

    private final Integer code;

    public JsonRpcException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public JsonRpcException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    public Integer code() {
        return code;
    }
}
