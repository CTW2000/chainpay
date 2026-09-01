package com.chainpay.common.web;

/**
 * 分段错误码。<b>段位（第一位数字）就是「客户端该怎么办」。</b>
 *
 * <pre>
 *   1xxx  凭证问题     别原样重试 —— 重新签名或换凭证
 *   2xxx  请求有错     别重试     —— 改参数
 *   3xxx  没有权限     别重试     —— 换个身份也没用
 *   4xxx  业务拒绝     别重试     —— 服务端状态不允许（余额不足、已存在）
 *   5xxx  被限流       等 Retry-After 之后重试
 *   9xxx  服务端故障   可以重试
 * </pre>
 *
 * <p><b>为什么要分段：</b>客户端需要决定「这个错误该不该重试」。
 * 没有分段的话，它要么维护一张写死的错误码表（我们加一个码它就得改一次），
 * 要么全部重试（把余额不足这种永远不会成功的请求打成风暴）。
 * 分段让<b>没见过的新错误码也能被正确处理</b> —— 只看第一位就够了。
 * 这是币安 {@code -1xxx 通用 / -2xxx 交易} 的思路。
 *
 * <p><b>为什么用数字而不是 {@code ACCESS_DENIED} 这样的名字：</b>
 * 名字会诱使人去改它 —— {@code ACCESS_DENIED} 改成 {@code FORBIDDEN} 读起来更好，
 * 于是有人真的改了，所有客户端一起坏掉。
 * <b>数字对人没有意义，因此没有人想去重命名它。</b>
 * 可读性由 {@code msg} 和这份枚举提供，契约由数字提供。
 *
 * <p>枚举名只在服务端代码和文档里用，<b>不出现在响应里</b>。
 */
public enum ErrorCode {

    // ---- 1xxx 凭证 ---------------------------------------------------
    /** 签名校验失败。缺请求头、key 不存在、签名不对、时间戳过期，一律回这一个。 */
    UNAUTHORIZED("1001", false),
    /**
     * 这个签名刚刚已经用过了。
     *
     * <p>放在 1xxx 而不是 4xxx，是因为客户端的正确反应是<b>换一个新 nonce 重新签名</b>
     * 而不是放弃。
     *
     * <p><b>重放防护和重试安全是两件事，靠两个不同的机制：</b>
     * <ul>
     *   <li>签名唯一性防的是「<b>别人</b>截获你的请求原样重发」</li>
     *   <li>幂等键防的是「<b>你自己</b>超时后重发」</li>
     * </ul>
     * 所以客户端重试的正确姿势是：<b>换一个新 nonce 重新签名，但保持 clientTransferId 不变。</b>
     * nonce 变了签名就变了，不会被当成重放；clientTransferId 没变，账本认得出是同一笔。
     * 两个机制都在，重发既不会被误判成攻击，也不会重复扣款。
     */
    REPLAYED("1002", false),

    // ---- 2xxx 请求 ---------------------------------------------------
    /** 参数格式非法。<b>不回显具体哪里错</b>，那会泄露枚举合法值、字段名等内部结构。 */
    INVALID_REQUEST("2001", false),
    MISSING_IDEMPOTENCY_KEY("2002", false),
    MISSING_TRANSFER_CODE("2003", false),
    INVALID_AMOUNT("2004", false),
    SAME_ACCOUNT("2005", false),
    CURRENCY_MISMATCH("2006", false),

    // ---- 3xxx 权限 ---------------------------------------------------
    /**
     * 无权访问该资源。
     *
     * <p><b>「账户不存在」也回这一个码。</b>分开回答等于给攻击者一个枚举器：
     * 拿 id 1、2、3 挨个试，回「无权访问」的就是真实存在的账户。
     * 不可区分响应必须做到<b>码、消息、状态码三者全都一样</b> ——
     * 只统一其中一个，另外两个照样泄露。
     */
    ACCESS_DENIED("3001", false),

    // ---- 4xxx 业务拒绝 -----------------------------------------------
    INSUFFICIENT_BALANCE("4001", false),
    /** 要创建的东西已经存在。改个标识再来，别原样重试。 */
    ALREADY_EXISTS("4002", false),

    // ---- 5xxx 限流 ---------------------------------------------------
    /** 唯一一个「等一会儿再试就能成功」的错误。响应必带 {@code Retry-After}。 */
    RATE_LIMITED("5001", true),

    // ---- 9xxx 服务端 -------------------------------------------------
    /** 服务端故障。<b>不带任何内部细节</b>，堆栈只进服务端日志。 */
    INTERNAL_ERROR("9001", true);

    private final String code;
    private final boolean retryable;

    ErrorCode(String code, boolean retryable) {
        this.code = code;
        this.retryable = retryable;
    }

    /** 出现在响应体 {@code code} 字段里的值。 */
    public String code() {
        return code;
    }

    /**
     * 原样重试是否有可能成功。
     *
     * <p>这个属性<b>不出现在响应里</b> —— 客户端从段位就能推出来。
     * 它存在是为了让测试能断言「段位和可重试性一致」，
     * 把「5xxx 才可重试」这条约定从注释变成<b>会失败的检查</b>。
     */
    public boolean retryable() {
        return retryable;
    }

    /** 段位：错误码的第一位。客户端只看这一位就能决定重试策略。 */
    public char segment() {
        return code.charAt(0);
    }
}
