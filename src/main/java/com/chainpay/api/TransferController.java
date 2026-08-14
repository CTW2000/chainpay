package com.chainpay.api;

import com.chainpay.ledger.service.LedgerService;
import com.chainpay.ledger.service.LedgerService.TransferCode;
import com.chainpay.ledger.service.LedgerService.TransferCommand;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M1 第一步 · 把账本开成 HTTP 接口。
 *
 * <p><b>⚠️ 这个版本是故意不设防的。</b>没有认证、没有签名、没有限频、没有校验。
 * 它存在的意义是先让整条链路跑通，然后我们一起攻击它 ——
 * 每发现一个洞，加一层防护，这样每层防护在防什么是看得见的。
 *
 * <p>不要把这个版本部署到任何能被外部访问的地方。
 */
@RestController
@RequestMapping("/api/v1")
public class TransferController {

    private final LedgerService ledger;

    // 构造器注入：Spring 启动时把 LedgerService 的实例传进来。
    // 不用 @Autowired 字段注入 —— 构造器注入能让这个类在测试里被直接 new 出来。
    public TransferController(LedgerService ledger) {
        this.ledger = ledger;
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

    @PostMapping("/transfers")
    public CreateTransferResponse create(@RequestBody CreateTransferRequest request) {
        long transferId = ledger.transfer(new TransferCommand(
                request.clientTransferId(),
                request.currency(),
                new BigDecimal(request.amount()),
                request.debitAccountId(),
                request.creditAccountId(),
                TransferCode.valueOf(request.code()),
                null));

        return new CreateTransferResponse(String.valueOf(transferId));
    }

    @GetMapping("/accounts/{accountId}/balance")
    public BalanceResponse balance(@PathVariable long accountId) {
        return new BalanceResponse(
                String.valueOf(accountId),
                ledger.balanceOf(accountId).toPlainString());
    }
}
