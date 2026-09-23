package com.chainpay.ledger.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 复式记账账本。接口很小，但三条契约（见 {@link #transfer}）每一条在并发下都会以不同方式失败。
 */
public interface LedgerService {

    /**
     * 业务事件类型 —— 一笔转账「为什么」发生（对应 TigerBeetle 的 {@code transfer.code}）。
     *
     * <p>充值、提现、手续费、冲正在账本上形状完全一样（一借一贷），区别只在业务意图里；
     * 意图不落到列上，事后是猜不回来的。
     *
     * <p>词表在这里维护，不在数据库的 {@code CHECK} 约束里：它会随业务增长，每加一种就改一次约束不划算。
     */
    enum TransferCode {
        /** 初始注资：从资金来源账户把钱放进系统。 */
        SEED,
        /** 系统内两个账户之间的转账。 */
        INTERNAL,
        /** 链上充值入账。 */
        DEPOSIT,
        /** 提现申请时把可用余额挪进冻结账户：user → user:…:frozen。 */
        WITHDRAWAL_FREEZE,
        /** 链上提现出账：链上 FINAL 之后，冻结 → 托管镜像。 */
        WITHDRAWAL,
        /** 提现失败或被拒后把冻结的钱还给商户：user:…:frozen → user。 */
        WITHDRAWAL_REVERSE,
        /** 手续费。 */
        FEE,
        /** 换汇的其中一条腿。 */
        FX,
        /** 差错冲正 —— 账本不改历史，记错了只能反向再记一笔。 */
        CORRECTION,
        /** 未指定：V2 加这一列时旧行的默认值。新代码不要用。 */
        UNSPECIFIED
    }

    /**
     * 一次转账请求。
     *
     * @param idempotencyKey  调用方生成的幂等键。<b>由调用方决定幂等边界</b>，不要由服务端猜
     *                        （TigerBeetle："The client software... should generate the id (not your API)"）
     * @param currency        币种。跨币种数值不可直接比较
     * @param amount          金额，必须为正，且装得下 NUMERIC(38,18)（见 {@link LedgerAmounts}）
     * @param debitAccountId  借方账户（钱从这里出）
     * @param creditAccountId 贷方账户（钱到这里去）
     * @param code            业务事件类型
     * @param occurredAt      业务在真实世界发生的时刻（入账用区块时间）；{@code null} 表示与记账时刻相同
     */
    record TransferCommand(
            String idempotencyKey,
            String currency,
            BigDecimal amount,
            long debitAccountId,
            long creditAccountId,
            TransferCode code,
            Instant occurredAt
    ) {}

    /**
     * 执行一笔转账，返回 transfer 主键。
     *
     * <p>三条必须满足的契约：
     * <ol>
     *   <li><b>幂等</b> —— 同一个 {@code idempotencyKey} 无论调用多少次、
     *       并发调用多少次，数据库里恰好一笔，且每次都返回同一个 id。</li>
     *   <li><b>原子</b> —— 一条 {@code transfer} + 两条 {@code entry}（借负贷正）
     *       + 两个账户的余额更新，要么全部落库，要么一条不留。</li>
     *   <li><b>余额不为负</b> —— 借方余额不足必须拒绝（除非账户允许为负），<b>并发下也必须拒绝</b>。
     *       数据库的 {@code account_balance_ck} 约束兜底，Java 里的检查只负责给出可读的错误。</li>
     * </ol>
     *
     * <p><b>已知限制</b>：返回值无法区分「本次新建」和「幂等命中已有的」
     * （TigerBeetle 用 {@code created} / {@code exists} 两种响应码区分）。
     */
    long transfer(TransferCommand command);

    /**
     * 查账户余额。
     *
     * <p>读的是物化的 {@code account.balance} 列（O(1)），而不是对分录求和（O(分录数)）。
     * 两者必须永远相等，由判官的 {@code balance_consistency} 视图监督。
     *
     * <p>账户不存在时抛异常而不是返回 0：<b>"不存在"和"余额为零"是两件事</b>，
     * 把它们混成同一个返回值，等于把一个 bug 变成一个看起来正常的数字。
     */
    BigDecimal balanceOf(long accountId);
}
