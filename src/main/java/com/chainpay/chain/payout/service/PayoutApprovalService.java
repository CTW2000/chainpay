package com.chainpay.chain.payout.service;

import com.chainpay.chain.deposit.service.DepositAddressService.UnsupportedTokenException;
import com.chainpay.chain.payout.domain.PayoutStatus;
import com.chainpay.chain.payout.repository.PayoutSendRepository;
import com.chainpay.chain.wallet.EthAddress;
import com.chainpay.common.web.ErrorCode;
import com.chainpay.ledger.service.LedgerAmounts;
import com.chainpay.ledger.system.SystemLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 管理侧（M4-④）：待核准列表、核准、拒绝、限额。改的是 payout 的状态与限额表，只有系统身份有 UPDATE，所以整段走 {@link SystemLedger}。
 * 拒绝 = 解冻 + REJECTED + 原因，一个事务；核准只是 PENDING_APPROVAL → QUEUED，之后和普通申请一样由发送任务处理。
 */
@Service
public class PayoutApprovalService {

    private static final Logger log = LoggerFactory.getLogger(PayoutApprovalService.class);

    private final SystemLedger system;

    public PayoutApprovalService(SystemLedger system) {
        this.system = system;
    }

    public List<Map<String, Object>> pending() {
        List<Map<String, Object>> rows = system.inTransaction(s -> s.jdbc().sql("""
                        SELECT p.id, m.code AS merchant, t.symbol, p.to_address AS "toAddress", p.amount, p.created_at AS "createdAt"
                        FROM payout p JOIN merchant m ON m.id = p.merchant_id JOIN chain_token t ON t.address = p.token
                        WHERE p.status = 'PENDING_APPROVAL' ORDER BY p.id
                        """).query().listOfRows());
        // 金额写成字符串只经 LedgerAmounts.text（2026-09-22 收口）：此前这里用 SQL 的 ::text，是写法的第二份定义——输出碰巧一样，
        // 哪天写法要改（比如去掉尾零），这一处会被漏掉
        return rows.stream().map(PayoutApprovalService::withAmountAsText).toList();
    }

    private static Map<String, Object> withAmountAsText(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>(row);
        view.put("amount", LedgerAmounts.text((BigDecimal) row.get("amount")));
        return view;
    }

    public void approve(long payoutId) {
        PayoutStatus.PENDING_APPROVAL.require(PayoutStatus.QUEUED);
        boolean moved = system.inTransaction(s -> new PayoutSendRepository(s.jdbc())
                .moveStatus(payoutId, PayoutStatus.PENDING_APPROVAL.name(), PayoutStatus.QUEUED.name()));
        if (!moved) {
            throw new WithdrawalRejectedException(HttpStatus.CONFLICT, ErrorCode.PAYOUT_NOT_PENDING, "这笔提现不在等待核准的状态");
        }
        log.info("提现 {} 已核准，进入队列", payoutId);
    }

    public void reject(long payoutId, String reason) {
        PayoutStatus.PENDING_APPROVAL.require(PayoutStatus.REJECTED);
        system.inTransaction(s -> {
            PayoutSendRepository repo = new PayoutSendRepository(s.jdbc());
            if (!repo.findStatus(payoutId).filter(PayoutStatus.PENDING_APPROVAL.name()::equals).isPresent()) {
                throw new WithdrawalRejectedException(HttpStatus.CONFLICT, ErrorCode.PAYOUT_NOT_PENDING, "这笔提现不在等待核准的状态");
            }
            repo.lockStatus(payoutId);
            PayoutSendRepository.Settlement t = repo.findSettlement(payoutId);
            long reverse = new PayoutLedger(s.jdbc(), s.ledger()).reverse(t.payoutId(), t.symbol(), t.amount(), t.frozenAccountId(), t.userAccountId(), Instant.now());
            repo.markRejected(payoutId, "人工拒绝：" + reason, reverse);
            return null;
        });
        log.warn("提现 {} 已拒绝并解冻：{}", payoutId, reason);
    }

    public void setLimit(String tokenAddress, BigDecimal perTxMax, BigDecimal dailyMax) {
        if (perTxMax.signum() <= 0 || dailyMax.compareTo(perTxMax) < 0) {
            throw new WithdrawalRejectedException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "单笔上限必须大于 0，当日上限不能小于单笔上限");
        }
        String token = EthAddress.lowercase(tokenAddress);
        int rows = system.inTransaction(s -> s.jdbc().sql("""
                        INSERT INTO payout_limit (token, per_tx_max, daily_max) SELECT :t, :p, :d WHERE EXISTS (SELECT 1 FROM chain_token WHERE address = :t)
                        ON CONFLICT (token) DO UPDATE SET per_tx_max = EXCLUDED.per_tx_max, daily_max = EXCLUDED.daily_max, updated_at = now()
                        """).param("t", token).param("p", perTxMax).param("d", dailyMax).update());
        if (rows != 1) {
            throw new UnsupportedTokenException("代币未登记：" + token);
        }
        log.info("代币 {} 的出账限额：单笔 {}，当日 {}", token, perTxMax, dailyMax);
    }
}
