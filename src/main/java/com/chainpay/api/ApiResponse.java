package com.chainpay.api;

/**
 * 统一响应信封。所有 {@code /api/**} 与 {@code /admin/**} 的响应都是这个形状。
 *
 * <pre>
 *   成功  {"code":"0",    "msg":"",            "data":{...}}
 *   失败  {"code":"3001", "msg":"无权访问...", "data":null}
 * </pre>
 *
 * <p><b>为什么要信封：</b>客户端只需要一套解析逻辑。
 * 没有信封的话，成功时要按业务对象解析、失败时要按错误对象解析，
 * 而「这次是哪一种」只能靠 HTTP 状态码猜 —— 于是每个接口都要写两个分支。
 *
 * <p><b>我们和 OKX 的一处关键分歧：HTTP 状态码照常用。</b>
 *
 * <p>OKX 的做法是所有响应一律 HTTP 200，错误放在 {@code code} 里。
 * 好处是客户端只有一条路径；代价是<b>所有读状态码的东西全都失灵</b> ——
 * 负载均衡、监控告警、重试中间件、CDN 都看状态码，
 * 「200 表示失败」会让错误率永远显示 0%。
 *
 * <p>我们两个都要，因为它们回答的是不同的问题：
 * <pre>
 *   HTTP 状态码   给**基础设施**看：这次请求算成功还是失败？该不该重试？
 *   信封的 code   给**业务代码**看：具体是哪一种失败？
 * </pre>
 *
 * <p>而且我们已经为状态码付出过代价了 —— 上一步实测「余额不足回 500」
 * 会让客户端把一个永远不会成功的请求无限重试。
 * 加信封不该把那个成果丢掉。<b>抄成熟服务商是为了少走弯路，不是照单全收。</b>
 *
 * @param code 成功恒为 {@code "0"}；失败见 {@link ErrorCode}
 * @param msg  给人看的说明。<b>措辞随时可能变，客户端不要解析它</b>
 * @param data 成功时的业务数据；失败时为 {@code null}
 */
public record ApiResponse<T>(String code, String msg, T data) {

    /** 成功的 code 固定是 "0"。用 "0" 而不是 "200"，避免和 HTTP 状态码混淆。 */
    public static final String SUCCESS = "0";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS, "", data);
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String msg) {
        return new ApiResponse<>(code.code(), msg, null);
    }
}
