package com.chainpay.chain.payout.domain;

import java.math.BigDecimal;
import java.math.BigInteger;

/** 一笔排队等发的提现，连同发它需要的一切：代币合约、收款人、原始金额，以及失败时解冻要用的两个账户。 */
public record QueuedPayout(long id, long merchantId, String token, String symbol, String toAddress, BigDecimal amount,
                           BigInteger rawValue, long userAccountId, long frozenAccountId) {}
