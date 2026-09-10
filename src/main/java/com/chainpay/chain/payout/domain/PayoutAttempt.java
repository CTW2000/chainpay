package com.chainpay.chain.payout.domain;

/** payout_tx 的一行：一笔提现的一次链上尝试。 */
public record PayoutAttempt(long id, long payoutId, String hotWallet, long nonce, String txHash, String rawHex, String status) {}
