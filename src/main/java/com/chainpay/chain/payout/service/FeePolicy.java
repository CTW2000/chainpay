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

    /** 加价的幅度：节点要求两个费率都 ≥ 旧的 110% 才肯顶替，25% 留出基础费继续上涨的余地。 */
    private static final BigInteger BUMP_PERCENT = BigInteger.valueOf(125);

    /**
     * 给卡住的尝试算替身的费率：同编号再发一笔，两个费率都取「市价」与「旧的 125%」里大的那个——
     * 只按市价可能不到节点要求的 +10%（被拒 underpriced），只按 +25% 可能仍低于此刻的基础费（照样排不上）。
     * 超过上限抛 {@link FeeTooHighException}：这一轮不加，等回落；旧的那笔仍在池里排队，没有损失。gasLimit 沿用旧值——同一笔执行，估算不变。
     */
    public Fees bump(FeeQuote quote, BigInteger oldMaxFeePerGas, BigInteger oldMaxPriorityFeePerGas, long gasLimit) {
        BigInteger priority = quote.maxPriorityFeePerGas().max(priorityFloorWei).max(percentUp(oldMaxPriorityFeePerGas));
        BigInteger maxFee = quote.baseFeePerGas().multiply(BigInteger.TWO).add(priority).max(percentUp(oldMaxFeePerGas));
        if (maxFee.compareTo(maxFeeCapWei) > 0) {
            throw new FeeTooHighException("费率超上限：加价后总费率 " + gwei(maxFee) + " gwei，上限 " + gwei(maxFeeCapWei) + " gwei；这一轮不加价，等回落");
        }
        return new Fees(priority, maxFee, gasLimit);
    }

    private static BigInteger percentUp(BigInteger value) {
        return value.multiply(BUMP_PERCENT).add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100));   // 向上取整，保证 ≥ 125%
    }

    private static String gwei(BigInteger wei) {
        return wei.divide(GWEI).toString();
    }
}
