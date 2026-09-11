package com.chainpay.chain.payout.domain;

import java.math.BigInteger;
import java.time.Instant;

/** payout_tx 的一行：一次签名并（试图）广播的尝试。同一笔提现可能有多次尝试（加价替换），同一编号最多一次 MINED。 */
public record PayoutAttempt(long id, long payoutId, String hotWallet, long nonce, String txHash, String rawHex, String status,
                            long gasLimit, BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas,
                            Long blockNumber, String blockHash, Boolean reverted, Instant createdAt) {}
