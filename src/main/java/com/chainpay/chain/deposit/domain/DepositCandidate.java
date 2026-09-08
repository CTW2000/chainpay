package com.chainpay.chain.deposit.domain;

import java.math.BigInteger;

/** 一条已 FINAL、收款方是我们的地址、还没处理过的链上转账，连同它要记到的商户账户与代币的 decimals。 */
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
        long accountId
) {
    /** 账本幂等键：链上坐标，不是 tx_hash——重组后同一笔交易会换块重现（V9）。 */
    public String idempotencyKey() {
        return "deposit:" + blockHash + ":" + logIndex;
    }
}
