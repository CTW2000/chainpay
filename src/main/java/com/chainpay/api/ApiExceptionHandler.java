package com.chainpay.api;

import com.chainpay.api.admin.AdminService.AlreadyExistsException;
import com.chainpay.api.auth.AccountAccessService.AccessDeniedException;
import com.chainpay.ledger.service.LedgerException;
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
     * 统一错误体。
     *
     * @param code    机器可读。客户端按它分流（该重试还是该改参数），不要去解析 message
     * @param message 人可读。仅用于排查，措辞可能随时变化，客户端不应依赖它
     */
    public record ErrorResponse(String code, String message) {}

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
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        // 记服务端日志，但响应里不加任何额外信息
        log.warn("拒绝访问账户 {}", e.accountId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("ACCESS_DENIED", e.getMessage()));
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
    public ResponseEntity<ErrorResponse> handleAlreadyExists(AlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ALREADY_EXISTS", e.getMessage()));
    }

    /**
     * 账本拒绝 → 400 Bad Request。
     *
     * <p>code 直接用 {@link LedgerException.Reason} 的名字，客户端可以按它分流：
     * {@code INSUFFICIENT_BALANCE} 要改金额，{@code CURRENCY_MISMATCH} 要改币种，
     * 两者都不该重试。
     */
    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ErrorResponse> handleLedger(LedgerException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(e.reason().name(), e.getMessage()));
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
    public ResponseEntity<ErrorResponse> handleBadInput(IllegalArgumentException e) {
        log.warn("请求参数无效: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", "请求参数无效"));
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
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "服务内部错误"));
    }
}
