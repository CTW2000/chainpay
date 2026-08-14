package com.chainpay.ledger.service;

import java.math.BigDecimal;

/**
 * 复式记账账本。
 *
 * <p><b>这是 M0 的全部内容。</b>接口很小，但要把它做对不容易 ——
 * 三条契约每一条在并发下都会以不同方式失败。
 *
 * <p>动手前先做一件事：在 {@code docs/retro/M0-before.md} 里写下
 * <b>你认为这个接口会怎么坏</b>。写完再看 {@code LEARNING-PATH.md} 里
 * 折叠起来的那份清单，对答案。
 */
public interface LedgerService {

    /**
     * 一次转账请求。
     *
     * @param idempotencyKey  调用方生成的幂等键。<b>由调用方决定幂等边界</b>，
     *                        不要由服务端猜 —— 币安的 {@code newClientOrderId}
     *                        和 OKX 的 {@code clOrdId} 都是这个设计
     * @param currency        币种。跨币种数值不可直接比较（flow-pay 的 {@code e8fd34e} 踩过）
     * @param amount          金额，必须为正。用 {@link BigDecimal}，绝不用 {@code double}
     * @param debitAccountId  借方账户（钱从这里出）
     * @param creditAccountId 贷方账户（钱到这里去）
     */
    record TransferCommand(
            String idempotencyKey,
            String currency,
            BigDecimal amount,
            long debitAccountId,
            long creditAccountId
    ) {}

    /**
     * 执行一笔转账，返回 transfer 主键。
     *
     * <p>三条必须满足的契约：
     * <ol>
     *   <li><b>幂等</b> —— 同一个 {@code idempotencyKey} 无论调用多少次、
     *       并发调用多少次，数据库里恰好一笔，且每次都返回同一个 id。</li>
     *   <li><b>原子</b> —— 一条 {@code transfer} + 两条 {@code entry}（借正贷负）
     *       要么全部落库，要么一条不留。中途崩溃不能留下不平的账。</li>
     *   <li><b>余额不为负</b> —— 借方余额不足必须拒绝，<b>并发下也必须拒绝</b>。
     *       「先查余额再扣款」在并发下必然失败，这是 check-then-act。</li>
     * </ol>
     *
     * <p>违反任何一条，{@code LedgerInvariantTest} 会抓到你。
     */
    long transfer(TransferCommand command);

    /**
     * 查账户余额。M0 阶段直接从分录求和算出来 —— 永远正确，但慢。
     *
     * <p>加分题是加一个物化的 {@code account.balance} 列，
     * 并证明它永远等于这里算出来的值。
     */
    BigDecimal balanceOf(long accountId);
}
