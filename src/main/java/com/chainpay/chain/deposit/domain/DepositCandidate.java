package com.chainpay.chain.deposit.domain;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 一条已 FINAL、收款方是我们的地址、还没处理过的链上转账，连同它要记到的商户账户、代币的 decimals 与最小入账额。
 * approvedDepositId 非空 = 这是人复核后改成 APPROVED 的旧行，重新记账时不再核对。
 */
public record DepositCandidate(
        long logId,
        long blockNumber,
        String blockHash,
        int logIndex,
        String token,
        String symbol,
        int decimals,
        String toAddress,
        BigInteger rawValue,
        long merchantId,
        long accountId,
        BigDecimal minDeposit,
        Long approvedDepositId
) {
    public boolean isApproved() {
        return approvedDepositId != null;
    }

    /** 账本幂等键：链上坐标，不是 tx_hash——重组后同一笔交易会换块重现（V9）。 */
    public String idempotencyKey() {
        return "deposit:" + blockHash + ":" + logIndex;
    }
}
