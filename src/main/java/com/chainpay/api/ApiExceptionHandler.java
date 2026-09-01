package com.chainpay.api;

import com.chainpay.api.admin.AdminService.AlreadyExistsException;
import com.chainpay.api.auth.AccountAccessService.AccessDeniedException;
import com.chainpay.ledger.service.LedgerException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把内部异常翻译成对调用方有意义的 HTTP 响应。
 *
 * <p><b>为什么这个类是必需的，而不是锦上添花：</b>
 *
 * <p>没有它之前，实测「alice 只有 1 块却要转 99999」返回的是 <b>500</b>。
 * 但 500 对调用方的含义是「<b>我坏了，请重试</b>」，而实际含义是
 * 「<b>你余额不够，别再试了</b>」。商户的客户端看到 500 会重试，
 * 重试还是 500，于是无限重试一个永远不会成功的请求 ——
 * 一个正常的业务拒绝，被伪装成了服务故障。
 *
 * <p>OWASP REST Security 的原话：<i>"don't just use 200 for success or 404 for error.
 * Always use the semantically appropriate status code."</i>
 * <b>状态码不是装饰，它是给机器读的指令。</b>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 账本拒绝原因 → 对外错误码。
     *
     * <p><b>为什么要一张显式的映射表，而不是直接用 {@code reason().name()}：</b>
     *
     * <ul>
     *   <li><b>内部枚举名不该是对外契约。</b>直接暴露的话，
     *       哪天为了代码可读性把 {@code SAME_ACCOUNT} 改个名字，
     *       所有客户端一起坏掉 —— 而改名的人完全不知道自己动了公开接口。</li>
     *   <li><b>ACCOUNT_NOT_FOUND 必须被折叠成「无权访问」。</b>
     *       分开回答等于给攻击者一个账户枚举器。
     *       这里映射到 ACCESS_DENIED，连状态码一起变成 403，
     *       让「不存在」和「不是你的」在<b>码、消息、状态码</b>三个维度上都一样。</li>
     *   <li><b>漏一项会立刻炸。</b>新增 Reason 却忘了映射，
     *       下面 {@code LEDGER_CODES.get()} 返回 null，测试当场变红。
     *       比「默认回一个通用码」好 —— 后者会悄悄把新错误伪装成老错误。</li>
     * </ul>
     */
    private static final Map<LedgerException.Reason, ErrorCode> LEDGER_CODES = Map.of(
            LedgerException.Reason.MISSING_IDEMPOTENCY_KEY, ErrorCode.MISSING_IDEMPOTENCY_KEY,
            LedgerException.Reason.MISSING_TRANSFER_CODE,   ErrorCode.MISSING_TRANSFER_CODE,
            LedgerException.Reason.INVALID_AMOUNT,          ErrorCode.INVALID_AMOUNT,
            LedgerException.Reason.SAME_ACCOUNT,            ErrorCode.SAME_ACCOUNT,
            LedgerException.Reason.CURRENCY_MISMATCH,       ErrorCode.CURRENCY_MISMATCH,
            LedgerException.Reason.INSUFFICIENT_BALANCE,    ErrorCode.INSUFFICIENT_BALANCE,
            // 刻意折叠：不存在 与 无权访问 必须不可区分
            LedgerException.Reason.ACCOUNT_NOT_FOUND,       ErrorCode.ACCESS_DENIED);


    /**
     * 无权访问账户 → 403 Forbidden。
     *
     * <p>403 与 401 的区别，容易混：
     * <ul>
     *   <li><b>401 Unauthorized</b>：我不知道你是谁（凭证缺失/无效）——「请先证明身份」</li>
     *   <li><b>403 Forbidden</b>：我知道你是谁，但你不能干这个 ——「换个身份也没用，别再试了」</li>
     * </ul>
     * 回错了会误导客户端：401 会让它去刷新凭证再重试，而这里重试多少次都没用。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        // 记服务端日志，但响应里不加任何额外信息
        log.warn("拒绝访问账户 {}", e.accountId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.ACCESS_DENIED, e.getMessage()));
    }

    /**
     * 要建的东西已经存在 --&gt; 409 Conflict。
     *
     * <p>没有这一条的话，重复创建商户会走到兜底的 500 ——
     * 而 500 对客户端的含义是「我坏了，请重试」，于是它会不停重试一个
     * 永远不会成功的请求。这和上面余额不足被伪装成 500 是同一个错误。
     *
     * <p>409 的准确含义是「你的请求本身没问题，但和服务端当前状态冲突」。
     * 客户端据此知道：改个 code 再来，别重试原样的。
     *
     * <p>响应里<b>不带</b>数据库的原始报错。Postgres 的唯一约束冲突消息长这样：
     * {@code duplicate key value violates unique constraint "merchant_code_uk"} ——
     * 表名和约束名对调用方毫无用处，对想摸清库结构的人却很有用。
     */
    @ExceptionHandler(AlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadyExists(AlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.ALREADY_EXISTS, e.getMessage()));
    }

    /**
     * 账本拒绝 → 400 Bad Request。
     *
     * <p>code 直接用 {@link LedgerException.Reason} 的名字，客户端可以按它分流：
     * {@code INSUFFICIENT_BALANCE} 要改金额，{@code CURRENCY_MISMATCH} 要改币种，
     * 两者都不该重试。
     */
    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ApiResponse<Void>> handleLedger(LedgerException e) {
        ErrorCode code = LEDGER_CODES.get(e.reason());
        // 余额不足是 400（你的请求没错，是账户状态不允许）；
        // ACCOUNT_NOT_FOUND 被映射成 403 + 3001，和「无权访问」完全一致 ——
        // 见 LEDGER_CODES 上面那段注释。
        HttpStatus status = code == ErrorCode.ACCESS_DENIED
                ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.error(code, e.getMessage()));
    }

    /**
     * 参数格式错误 → 400。
     *
     * <p>{@code new BigDecimal("abc")} 抛 NumberFormatException（它是
     * IllegalArgumentException 的子类），{@code TransferCode.valueOf("HAHA")}
     * 抛 IllegalArgumentException —— 上一步实测这两个都变成了 500。
     *
     * <p>注意响应里没有回显 {@code e.getMessage()}：异常消息可能包含内部实现细节
     * （比如枚举的全部合法值、类名），那属于免费送给攻击者的情报。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadInput(IllegalArgumentException e) {
        log.warn("请求参数无效: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST, "请求参数无效"));
    }

    /**
     * 兜底 → 500，且<b>不泄露任何内部细节</b>。
     *
     * <p>OWASP REST Security 明确要求：
     * <i>"Do not pass technical details (e.g. call stacks or other internal hints) to the client."</i>
     *
     * <p>细节记在服务端日志里 —— 排查问题的人有服务器权限，攻击者没有。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "服务内部错误"));
    }
}
