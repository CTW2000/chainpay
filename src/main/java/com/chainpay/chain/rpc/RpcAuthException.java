package com.chainpay.chain.rpc;

/**
 * 节点拒绝了我们的凭证（HTTP 401 / 403）。
 *
 * <p>它和其它传输层失败长得一样（code 为空），处置却相反：网络抖动会自己好，
 * 被撤销的 key 永远不会。放在同一个「瞬时」桶里的后果是每 12 秒一条 WARN、永不停机、永不告警。
 */
public class RpcAuthException extends JsonRpcException {

    public RpcAuthException(int status, String method) {
        super(null, "HTTP " + status + " 节点拒绝了凭证 · " + method);
    }
}
