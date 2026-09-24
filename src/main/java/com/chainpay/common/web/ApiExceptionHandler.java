package com.chainpay.common.web;


import com.chainpay.chain.deposit.service.DepositAddressService.UnsupportedTokenException;
import com.chainpay.merchant.service.AdminService.AlreadyExistsException;
import com.chainpay.ledger.service.LedgerException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 把内部异常翻译成对调用方有意义的 HTTP 响应（信封见 {@link ApiResponse}，错误码见 {@link ErrorCode}）。
 *
 * <p>业务拒绝不能落成 500：500 对调用方的含义是「<b>我坏了，请重试</b>」，客户端会无限重试一个永远不会成功的请求
 * （比如余额不足）。OWASP REST Security：<i>"Always use the semantically appropriate status code."</i>
 * <b>状态码是给机器读的指令。</b>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 账本拒绝原因 → 对外错误码。用显式映射表，不直接用 {@code reason().name()}：
     *
     * <ul>
     *   <li><b>内部枚举名不该是对外契约。</b>为了可读性改个名字，所有客户端一起坏掉，而改名的人不知道自己动了公开接口。</li>
     *   <li><b>ACCOUNT_NOT_FOUND 必须折叠成「无权访问」</b>（403 + 3001）：分开回答等于给攻击者一个账户枚举器。
     *       在行级安全下「不存在」和「不是你的」本来就是同一条路径，对外也要一样。</li>
     *   <li><b>漏一项会立刻炸。</b>新增 Reason 却忘了映射，{@link #errorCodeFor} 返回 null，测试当场变红——
     *       比「默认回一个通用码」好，后者会悄悄把新错误伪装成老错误。</li>
     * </ul>
     */
    private static final Map<LedgerException.Reason, ErrorCode> LEDGER_CODES = Map.of(
            LedgerException.Reason.MISSING_IDEMPOTENCY_KEY, ErrorCode.MISSING_IDEMPOTENCY_KEY,
            LedgerException.Reason.MISSING_TRANSFER_CODE,   ErrorCode.MISSING_TRANSFER_CODE,
            LedgerException.Reason.INVALID_AMOUNT,          ErrorCode.INVALID_AMOUNT,
            LedgerException.Reason.SAME_ACCOUNT,            ErrorCode.SAME_ACCOUNT,
            LedgerException.Reason.CURRENCY_MISMATCH,       ErrorCode.CURRENCY_MISMATCH,
            LedgerException.Reason.INSUFFICIENT_BALANCE,    ErrorCode.INSUFFICIENT_BALANCE,
            LedgerException.Reason.IDEMPOTENCY_CONFLICT,    ErrorCode.IDEMPOTENCY_CONFLICT,
            // 刻意折叠：不存在 与 无权访问 必须不可区分
            LedgerException.Reason.ACCOUNT_NOT_FOUND,       ErrorCode.ACCESS_DENIED);

    /** 代币不在白名单 → 400 + 2008。消息里只有代币地址，没有内部结构。 */
    @ExceptionHandler(UnsupportedTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedToken(UnsupportedTokenException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.TOKEN_NOT_SUPPORTED, e.getMessage()));
    }

    /** 提现的业务拒绝（白名单、平台地址、状态不对）：状态码与错误码由业务定。 */
    @ExceptionHandler(com.chainpay.chain.payout.service.WithdrawalRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleWithdrawalRejected(com.chainpay.chain.payout.service.WithdrawalRejectedException e) {
        return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage()));
    }

    /** 管理员认证的拒绝：登录失败 401、再认证失败 401、重名 409、口令不合规 400。 */
    @ExceptionHandler(com.chainpay.admin.service.AdminAuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleAdminAuth(com.chainpay.admin.service.AdminAuthException e) {
        return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage()));
    }

    /** 注资登记的拒绝：状态码与错误码由业务定。 */
    @ExceptionHandler(com.chainpay.chain.payout.service.FundingRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleFundingRejected(com.chainpay.chain.payout.service.FundingRejectedException e) {
        return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage()));
    }

    /**
     * 要建的东西已经存在 → 409 Conflict：请求本身没问题，但和服务端当前状态冲突——改个标识再来，别原样重试。
     * 响应里<b>不带</b>数据库的原始报错：约束名、表名对调用方毫无用处，对想摸清库结构的人却很有用。
     */
    @ExceptionHandler(AlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadyExists(AlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.ALREADY_EXISTS, e.getMessage()));
    }

    /** 暴露给测试：{@code ApiContractTest.everyLedgerReasonIsMapped} 遍历全部 Reason，断言这里不返回 null。 */
    static ErrorCode errorCodeFor(LedgerException.Reason reason) {
        return LEDGER_CODES.get(reason);
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ApiResponse<Void>> handleLedger(LedgerException e) {
        ErrorCode code = errorCodeFor(e.reason());
        // 余额不足是 400（你的请求没错，是账户状态不允许）；ACCOUNT_NOT_FOUND 折叠成 403 + 3001，见 LEDGER_CODES。
        HttpStatus status = switch (code) {
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            // 409：你的请求本身没错，是和服务端已有状态冲突——和重复建商户同一个语义
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiResponse.error(code, e.getMessage()));
    }

    /**
     * 参数无效 → 400 + 2001（IllegalArgumentException 及其子类，比如 NumberFormatException）。
     *
     * <p>响应里不回显 {@code e.getMessage()}：异常消息可能包含内部实现细节
     * （比如枚举的全部合法值、类名），那属于免费送给攻击者的情报。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadInput(IllegalArgumentException e) {
        log.warn("请求参数无效: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST, "请求参数无效"));
    }

    /**
     * 框架自己认得的那 20 来种异常（校验不过、JSON 坏了、参数缺失或类型不对、方法不允许、媒体类型不支持、路径不存在……）
     * 由父类 {@link ResponseEntityExceptionHandler} 判状态码，全部汇到这里套信封、不回显框架细节。
     *
     * <p>不交给父类判的话，它们（比如 405 / 415 / 404）会落进下面的兜底回 500 + 9001，
     * 而 9xxx 对客户端的含义是「稍后重试」——这些请求原样重试永远一样。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("框架层异常 → {}", status.value(), ex);
            return ResponseEntity.status(status).headers(headers).body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "服务内部错误"));
        }
        log.warn("请求形状无效 → {}: {}", status.value(), ex.getClass().getSimpleName());
        String message = switch (status.value()) {
            case 404 -> "路径不存在";
            case 405 -> "方法不允许";
            case 415 -> "媒体类型不支持";
            default -> "请求参数无效";
        };
        return ResponseEntity.status(status).headers(headers).body(ApiResponse.error(ErrorCode.INVALID_REQUEST, message));
    }

    /**
     * 兜底 → 500，且<b>不泄露任何内部细节</b>（OWASP REST Security：
     * <i>"Do not pass technical details (e.g. call stacks or other internal hints) to the client."</i>）。
     * 细节记在服务端日志里 —— 排查问题的人有服务器权限，攻击者没有。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "服务内部错误"));
    }
}
