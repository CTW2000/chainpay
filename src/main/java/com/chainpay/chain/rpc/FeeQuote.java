package com.chainpay.chain.rpc;

import java.math.BigInteger;

/** 节点给的费率参考：最新块的基础费（协议算出、烧掉）与节点建议的小费（给出块者）。单位 wei。 */
public record FeeQuote(BigInteger baseFeePerGas, BigInteger maxPriorityFeePerGas) {}
