package com.chainpay.chain.payout.service;

import com.chainpay.chain.rpc.FeeQuote;
import java.math.BigInteger;

/**
 * 三个数怎么定（M4-②）：
 * <ul>
 *   <li>小费上限 = max(节点建议, 地板)：小费决定排队位置，节点建议可能给 0，太低会一直排不上；</li>
 *   <li>总费率上限 = 2 × 最新块基础费 + 小费，且不超过配置的上限：基础费每块最多涨 12.5%，两倍能扛几分钟的上涨，多设的会退回；
 *       超上限这一轮不发（{@link FeeTooHighException}），等费率回落；</li>
 *   <li>gasLimit = 估算 × 1.2，且不超过配置的上限：估算在事务外做；估算 × 1.2 都超上限说明这笔交易不正常（{@link GasTooHighException}）。</li>
 * </ul>
 */
public final class FeePolicy {

    private static final BigInteger GWEI = BigInteger.TEN.pow(9);

    public record Fees(BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas, long gasLimit) {}

    /** 费率超上限：瞬时的，等回落。 */
    public static final class FeeTooHighException extends RuntimeException {
        public FeeTooHighException(String message) {
            super(message);
        }
    }

    /** gas 超上限：结构性的，这笔交易不正常。 */
    public static final class GasTooHighException extends RuntimeException {
        public GasTooHighException(String message) {
            super(message);
        }
    }

    private final BigInteger priorityFloorWei;
    private final BigInteger maxFeeCapWei;
    private final long gasLimitCap;

    public FeePolicy(BigInteger priorityFloorWei, BigInteger maxFeeCapWei, long gasLimitCap) {
        this.priorityFloorWei = priorityFloorWei;
        this.maxFeeCapWei = maxFeeCapWei;
        this.gasLimitCap = gasLimitCap;
    }

    public Fees quote(FeeQuote quote, BigInteger estimatedGas) {
        BigInteger priority = quote.maxPriorityFeePerGas().max(priorityFloorWei);
        BigInteger maxFee = quote.baseFeePerGas().multiply(BigInteger.TWO).add(priority);
        if (maxFee.compareTo(maxFeeCapWei) > 0) {
            throw new FeeTooHighException("费率超上限：2 × 基础费 + 小费 = " + gwei(maxFee) + " gwei，上限 " + gwei(maxFeeCapWei) + " gwei；这一轮不发，等回落");
        }
        BigInteger gasLimit = estimatedGas.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
        if (gasLimit.compareTo(BigInteger.valueOf(gasLimitCap)) > 0) {
            throw new GasTooHighException("估算 gas " + estimatedGas + " × 1.2 = " + gasLimit + " 超过上限 " + gasLimitCap + "：这笔交易不正常");
        }
        return new Fees(priority, maxFee, gasLimit.longValueExact());
    }

    private static String gwei(BigInteger wei) {
        return wei.divide(GWEI).toString();
    }
}
