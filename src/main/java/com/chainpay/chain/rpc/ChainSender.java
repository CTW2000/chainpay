package com.chainpay.chain.rpc;

/**
 * 往链上<b>写</b>的唯一入口：广播一条签好的原文。和只读的 {@link ChainReader} 分开，
 * 于是审计节点永远只拿得到读接口，付款任务只从主节点发。
 *
 * <p>节点按内容识别交易：同一份原文再发一次回「already known」，不会变成第二笔——这是「先落库再广播」能重发的前提。
 */
public interface ChainSender {

    /** {@code eth_sendRawTransaction}：返回交易哈希（0x + 64 位十六进制）。节点拒绝时抛带 code 的 {@link JsonRpcException}。 */
    String sendRawTransaction(byte[] raw);
}
