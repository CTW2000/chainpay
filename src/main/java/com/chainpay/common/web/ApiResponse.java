package com.chainpay.common.web;

/**
 * 统一响应信封。所有 {@code /api/**} 与 {@code /admin/**} 的响应都是这个形状。
 *
 * <pre>
 *   成功  {"code":"0",    "msg":"",            "data":{...}}
 *   失败  {"code":"3001", "msg":"无权访问...", "data":null}
 * </pre>
 *
 * <p><b>为什么要信封：</b>客户端只需要一套解析逻辑，不用靠 HTTP 状态码猜这次该按业务对象还是按错误对象解析。
 *
 * <p><b>HTTP 状态码照常用</b>（不学 OKX 一律回 200）：负载均衡、监控告警、重试中间件、CDN 都看状态码，
 * 「200 表示失败」会让它们全部失灵、错误率永远显示 0%。两者回答的是不同的问题：
 * <pre>
 *   HTTP 状态码   给**基础设施**看：这次请求算成功还是失败？该不该重试？
 *   信封的 code   给**业务代码**看：具体是哪一种失败？
 * </pre>
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
