package com.chainpay.api;

import com.chainpay.api.auth.AccountAccessService;
import com.chainpay.api.auth.AccountAccessService.AuthorizedAccount;
import com.chainpay.api.auth.ApiKeyAuthFilter;
import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户对外接口。
 *
 * <p>身份由 {@link ApiKeyAuthFilter} 在进入本类之前认证完毕，
 * 商户 id 通过请求属性传进来（见 {@code @RequestAttribute}）。
 * 本类<b>不</b>处理「你是谁」，只处理「你要干什么、能不能干」。
 */
@RestController
@RequestMapping("/api/v1")
public class TransferController {

    private final LedgerService ledger;
    private final AccountAccessService accounts;

    public TransferController(LedgerService ledger, AccountAccessService accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /**
     * 转账请求体。
     *
     * @param clientTransferId 客户端生成的唯一编号，就是账本层的幂等键。
     *                         币安叫 {@code newClientOrderId}，OKX 叫 {@code clOrdId}，
     *                         都是同一个东西：<b>让调用方决定「这两次请求是不是同一笔」</b>
     * @param amount           <b>金额是字符串，不是数字。</b>
     *                         JSON 里的数字在 JavaScript 里会被读成 double（双精度浮点），
     *                         而 0.1 + 0.2 在 double 里不等于 0.3。
     *                         币安和 OKX 的所有价格、数量字段也全是字符串，同一个原因。
     */
    public record CreateTransferRequest(
            String clientTransferId,
            String currency,
            String amount,
            long debitAccountId,
            long creditAccountId,
            String code
    ) {}

    /**
     * @param transferId 同样用字符串返回。
     *                   数据库主键将来可能变成 19 位的雪花 ID，
     *                   而 JavaScript 的数字最大安全整数是 9007199254740991（16 位），
     *                   19 位数字用 JSON number 传过去会被<b>静默改掉最后几位</b>。
     */
    public record CreateTransferResponse(String transferId) {}

    public record BalanceResponse(String accountId, String balance) {}

    /**
     * 在<b>自己名下的两个账户之间</b>转账。
     *
     * <p><b>为什么借贷双方都必须属于调用方（默认拒绝）：</b>
     *
     * <ul>
     *   <li><b>借方必须是你的</b> —— 这是显然的，钱从这里出。上一步 evilco
     *       就是靠指定别人的借方账户把 acme 的钱转走的。</li>
     *   <li><b>贷方也必须是你的</b> —— 这条不那么显然。放开贷方看似无害
     *       （你的钱爱给谁给谁），但它会变成一个<b>账户探测器</b>：
     *       给任意 id 转一个极小的金额，成功就说明那个账户存在。
     *       攻击者能借此画出整个系统的账户分布。</li>
     * </ul>
     *
     * <p>所以这个接口目前<b>只支持商户在自己账户之间划转</b>。
     * 充值（M3）、提现（M4）、手续费扣收都有各自的流程和各自的规则，
     * 不共用这个入口。
     *
     * <p><b>这是「默认拒绝、按需放开」</b>：先关到最小，等真有业务需要再逐个开口子。
     * 反过来（先全开，出事了再收）意味着每个口子都要有人记得去关。
     */
    @PostMapping("/transfers")
    public CreateTransferResponse create(
            @RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId,
            @RequestBody CreateTransferRequest request) {

        // ★ 授权发生在这里，而且绕不过去 ★
        //
        // requireOwned 要么返回一个「已校验的账户」，要么抛异常 —— 它不返回布尔值。
        // 如果它返回 boolean，调用方可以忘记看返回值，而忘记看一个布尔值
        // 不会有任何编译错误。返回校验过的对象，意味着想拿到账户就必须先过校验。
        AuthorizedAccount debit = accounts.requireOwned(merchantId, request.debitAccountId());
        AuthorizedAccount credit = accounts.requireOwned(merchantId, request.creditAccountId());

        long transferId = ledger.transfer(new TransferCommand(
                request.clientTransferId(),
                request.currency(),
                new BigDecimal(request.amount()),
                debit.id(),
                credit.id(),
                TransferCode.valueOf(request.code()),
                null));

        return new CreateTransferResponse(String.valueOf(transferId));
    }

    /** 查询自己名下账户的余额。别人的账户查不了 —— 余额本身就是敏感信息。 */
    @GetMapping("/accounts/{accountId}/balance")
    public BalanceResponse balance(
            @RequestAttribute(ApiKeyAuthFilter.ATTR_MERCHANT_ID) long merchantId,
            @PathVariable long accountId) {

        AuthorizedAccount account = accounts.requireOwned(merchantId, accountId);
        return new BalanceResponse(
                String.valueOf(account.id()),
                ledger.balanceOf(account.id()).toPlainString());
    }
}
