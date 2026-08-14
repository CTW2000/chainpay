package com.chainpay.ledger.service;

import com.chainpay.ledger.service.LedgerException.Reason;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 复式记账账本的实现。
 *
 * <p><b>整体思路：把并发正确性交给数据库，而不是交给 Java。</b>
 * 这里没有一个 {@code synchronized}、没有一把 Java 锁、没有一个 {@code AtomicX}——
 * 因为应用可能有多个实例，JVM 内的锁对另一台机器上的进程毫无约束力。
 * 唯一所有实例都共享的东西是数据库，所以互斥必须在数据库里发生。
 *
 * <p>{@link #transfer} 的执行顺序是刻意设计的，见方法内注释。
 */
@Service
public class LedgerServiceImpl implements LedgerService {

    /** 数据库列 {@code NUMERIC(38,18)} 的小数位数。超过这个位数会被静默四舍五入。 */
    private static final int MAX_SCALE = 18;

    private final JdbcClient jdbcClient;

    public LedgerServiceImpl(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 账户上与转账决策相关的三列。故意只取这三列，不做通用的 Account 实体。 */
    private record AccountRow(long id, String currency, boolean allowNegative) {}

    // ==================================================================
    // 转账
    // ==================================================================

    /**
     * <p><b>步骤顺序不能随意调换，每一步都有它必须待在那个位置的理由：</b>
     *
     * <pre>
     * ① 参数校验          纯 Java，零 IO —— 能在不碰数据库时否掉的，就别碰数据库
     * ② 读两个账户        为了给出清晰的错误；放在 ③ 之前，否则坏账户 id 会先撞上外键报错
     * ③ 幂等插入          重复请求在这里短路返回，不会走到 ④ 去抢账户锁
     * ④ 锁定两个账户      ★ 承重墙：这一步是"账不会被超支"的唯一保证。按 id 升序！
     * ⑤ 查余额并判断      必须在 ④ 之后。放在 ④ 之前就是 check-then-act，并发下必错
     * ⑥ 写两条分录        一条 SQL 写两行，同生同死
     * ⑦ 更新两个账户余额  物化余额必须和分录在同一事务内更新，否则两者会漂移
     * </pre>
     */
    @Override
    @Transactional
    public long transfer(TransferCommand command) {
        // ① 参数校验
        validate(command);

        // ② 读两个账户：确认存在、确认币种。
        //    这里不加锁——加锁的成本要留给真正需要互斥的 ④。
        AccountRow debit = readAccount(command.debitAccountId());
        AccountRow credit = readAccount(command.creditAccountId());
        requireCurrencyMatches(command, debit, credit);

        // ③ 幂等：靠数据库的 UNIQUE 约束，不靠"先 SELECT 查有没有"。
        //    "先查再插"在并发下必然失败——两个线程可以同时查到"不存在"。
        Optional<Long> created = insertTransferIfAbsent(command);
        if (created.isEmpty()) {
            // 这个幂等键已经被别人用过了 → 返回那一笔的 id。
            // 注意是"成功返回"而不是抛异常：幂等的定义是重复调用得到同样的结果，
            // 而不是"第二次开始报错"。客户端网络超时后重试是正常行为，不该被惩罚。
            return existingTransferId(command.idempotencyKey());
        }
        long transferId = created.get();

        // ④ ★ 承重墙 ★ 锁住两个账户所在的行。
        //
        //    删掉这一步会怎样：并发测试里 100 个线程同时读到"余额够"，
        //    然后同时扣款，alice 余额变成负数，成功笔数远超 500。
        //    这就是 check-then-act，本项目实测过：余额 100 并发扣 30 和 50，
        //    无锁时结果是 50（应该是 20），凭空多出 30 块。
        //
        //    为什么 V2 之后两个账户都要锁 —— 理由是死锁避免，不是丢失更新：
        //
        //    ⑦ 的 `UPDATE ... SET balance = balance + :delta` 本身已经是原子的
        //    （语句自己持有行锁，并基于最新已提交版本求值），不存在丢失更新。
        //    真正的问题是 ⑦ 要动两行，于是每个事务都会先后持有两把行锁：
        //      事务 A（alice→bob）：持 alice，在 UPDATE bob 处等 bob
        //      事务 B（bob→alice）：持 bob，  在 UPDATE alice 处等 alice
        //    → 循环等待，死锁。V1 只锁借方一行时不存在这个问题。
        //
        //    也就是说：「加一个物化余额列」这个看似只关乎性能的改动，
        //    把单锁操作变成了双锁操作，凭空引入了死锁风险。
        //
        //    ★ 必须按 id 升序 ★：A→B 与 B→A 并发时，如果各自先锁自己的借方，
        //    就会互相等待对方持有的锁 —— 经典死锁。统一按 id 升序，
        //    两个事务的加锁顺序就一致了，后到的那个只会等待，不会形成环。
        //
        //    用 FOR NO KEY UPDATE 而不是 FOR UPDATE：
        //    写 entry 时外键检查会对 account 行申请 FOR KEY SHARE 锁。
        //    FOR UPDATE 与 FOR KEY SHARE 冲突，FOR NO KEY UPDATE 不冲突。
        //    已实测：同样的双向并发场景，前者报 "deadlock detected"，后者正常通过。
        AccountRow lockedDebit =
                lockBothInIdOrder(command.debitAccountId(), command.creditAccountId());

        // ⑤ 余额检查。必须用锁之后重新读到的那一行——
        //    ② 里读的 allowNegative 是没有锁保护的旧值。
        //
        //    注意：这个检查现在**不是**最后一道防线。数据库上的
        //    account_balance_ck 约束才是（见 V2 迁移）。这里的检查只负责
        //    在到达约束之前给出可读的错误 —— 约束报出来的是
        //    "violates check constraint"，对调用方没有信息量。
        if (!lockedDebit.allowNegative()) {
            BigDecimal balance = balanceOf(command.debitAccountId());
            if (balance.compareTo(command.amount()) < 0) {
                throw new LedgerException(
                        Reason.INSUFFICIENT_BALANCE,
                        "账户 %d 余额 %s，不足以支出 %s"
                                .formatted(command.debitAccountId(), balance.toPlainString(),
                                        command.amount().toPlainString()));
            }
        }

        // ⑥ 两条分录用一条 INSERT 写入。
        //    分成两条 INSERT 在事务里也是原子的，但一条语句让"同生同死"这件事
        //    在代码形状上就成立，不依赖读代码的人记得它们在同一个事务里。
        insertEntries(transferId, command);

        // ⑦ 更新物化余额。
        //    必须和 ⑥ 在同一个事务里 —— 分录写了而余额没更新（或反过来），
        //    就是 balance_consistency 视图要抓的那种漂移。
        applyBalanceDeltas(command);

        return transferId;
    }

    // ==================================================================
    // 余额
    // ==================================================================

    /**
     * 从 {@code account_balance} 视图取余额。
     *
     * <p>V2 之后这个视图直接读物化的 {@code account.balance} 列，不再对分录求和。
     * <b>调用方一行都不用改</b> —— 这正是当初把余额封装进视图的目的。
     *
     * <p>账户不存在时抛异常而不是返回 0：<b>"不存在"和"余额为零"是两件事</b>，
     * 把它们混成同一个返回值，等于把一个 bug 变成一个看起来正常的数字。
     */
    @Override
    public BigDecimal balanceOf(long accountId) {
        return jdbcClient
                .sql("SELECT balance FROM account_balance WHERE account_id = :id")
                .param("id", accountId)
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> new LedgerException(
                        Reason.ACCOUNT_NOT_FOUND, "账户不存在：" + accountId));
    }

    // ==================================================================
    // 私有步骤
    // ==================================================================

    private void validate(TransferCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new LedgerException(Reason.MISSING_IDEMPOTENCY_KEY, "幂等键不能为空");
        }
        if (command.code() == null) {
            throw new LedgerException(Reason.MISSING_TRANSFER_CODE, "业务类型 code 不能为空");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new LedgerException(Reason.INVALID_AMOUNT, "转账金额必须为正数：" + command.amount());
        }
        // 小数位超过 18 位，数据库会静默四舍五入成 18 位 —— 用户以为转了
        // 0.1234567890123456789，实际记的是别的数。必须在写入前拒绝，不能默默改人家的钱。
        if (command.amount().stripTrailingZeros().scale() > MAX_SCALE) {
            throw new LedgerException(
                    Reason.INVALID_AMOUNT,
                    "金额小数位超过 %d 位，拒绝四舍五入：%s"
                            .formatted(MAX_SCALE, command.amount().toPlainString()));
        }
        if (command.debitAccountId() == command.creditAccountId()) {
            throw new LedgerException(Reason.SAME_ACCOUNT, "借贷方不能是同一个账户");
        }
    }

    private void requireCurrencyMatches(TransferCommand command, AccountRow debit, AccountRow credit) {
        if (!debit.currency().equals(command.currency()) || !credit.currency().equals(command.currency())) {
            throw new LedgerException(
                    Reason.CURRENCY_MISMATCH,
                    "转账币种 %s 与账户币种不符（借方 %s / 贷方 %s）"
                            .formatted(command.currency(), debit.currency(), credit.currency()));
        }
    }

    private AccountRow readAccount(long accountId) {
        return queryAccount("SELECT id, currency, allow_negative FROM account WHERE id = :id", accountId);
    }

    /**
     * 按 id 升序锁定两个账户，返回借方那一行。
     *
     * <p>排序是死锁避免的标准手法：只要所有事务都按同一个顺序申请锁，
     * 就不可能形成"你等我、我等你"的环。
     */
    private AccountRow lockBothInIdOrder(long debitAccountId, long creditAccountId) {
        long first = Math.min(debitAccountId, creditAccountId);
        long second = Math.max(debitAccountId, creditAccountId);
        AccountRow firstRow = lockAccount(first);
        AccountRow secondRow = lockAccount(second);
        return first == debitAccountId ? firstRow : secondRow;
    }

    /** 锁定并读取账户。锁在事务提交或回滚时自动释放。 */
    private AccountRow lockAccount(long accountId) {
        return queryAccount(
                "SELECT id, currency, allow_negative FROM account WHERE id = :id FOR NO KEY UPDATE",
                accountId);
    }

    private AccountRow queryAccount(String sql, long accountId) {
        return jdbcClient.sql(sql)
                .param("id", accountId)
                // 显式写映射，不用框架的自动列名映射：账本层的每一步都要看得见。
                .query((rs, rowNum) -> new AccountRow(
                        rs.getLong("id"), rs.getString("currency"), rs.getBoolean("allow_negative")))
                .optional()
                .orElseThrow(() -> new LedgerException(
                        Reason.ACCOUNT_NOT_FOUND, "账户不存在：" + accountId));
    }

    /**
     * 尝试写入 transfer。
     *
     * <p>返回空 = 这个幂等键已经存在。{@code ON CONFLICT DO NOTHING} 在并发下的行为：
     * 后到的事务会等先到的那个提交或回滚——先到的提交则本次什么都不做，
     * 先到的回滚（比如余额不足）则本次正常插入。这正是我们要的语义。
     */
    private Optional<Long> insertTransferIfAbsent(TransferCommand command) {
        return jdbcClient.sql("""
                        INSERT INTO transfer
                            (idempotency_key, currency, amount, debit_account_id, credit_account_id,
                             code, occurred_at)
                        VALUES (:key, :currency, :amount, :debit, :credit, :code, :occurredAt)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id
                        """)
                .param("key", command.idempotencyKey())
                .param("currency", command.currency())
                .param("amount", command.amount())
                .param("debit", command.debitAccountId())
                .param("credit", command.creditAccountId())
                .param("code", command.code().name())
                // 显式转成 OffsetDateTime。
                //
                // 诚实说明：pgjdbc 大概率能直接接受 Instant，这三行未必是必需的。
                // 保留它不是因为验证过驱动会失败，而是因为**我没有验证过它会成功**——
                // 账本层的取舍是「宁可多写三行显式代码，也不依赖一个未经本项目验证的隐式行为」。
                // 这是偏好，不是必要；换个层次（比如 M1 的 API 层）我不会这么写。
                .param("occurredAt", command.occurredAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC))
                .query(Long.class)
                .optional();
    }

    private long existingTransferId(String idempotencyKey) {
        return jdbcClient.sql("SELECT id FROM transfer WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(Long.class)
                .single();
    }

    /** 借方为负、贷方为正。两行一条语句，SUM 恒为 0 由此保证。 */
    private void insertEntries(long transferId, TransferCommand command) {
        jdbcClient.sql("""
                        INSERT INTO entry (transfer_id, account_id, currency, amount) VALUES
                            (:transferId, :debit,  :currency, :negative),
                            (:transferId, :credit, :currency, :positive)
                        """)
                .param("transferId", transferId)
                .param("debit", command.debitAccountId())
                .param("credit", command.creditAccountId())
                .param("currency", command.currency())
                .param("negative", command.amount().negate())
                .param("positive", command.amount())
                .update();
    }

    /**
     * 同步物化余额。两个账户在 ④ 都已按 id 升序锁定，这里的更新顺序不再有死锁风险。
     *
     * <p>用 {@code balance = balance + :delta} 而不是"先读再写"：即便没有 ④ 的锁，
     * 这种写法本身也是原子的（UPDATE 语句持有行锁并基于最新已提交版本求值）。
     * 两层保护是有意为之 —— 承重墙不该只有一根柱子。
     */
    private void applyBalanceDeltas(TransferCommand command) {
        updateBalance(command.debitAccountId(), command.amount().negate());
        updateBalance(command.creditAccountId(), command.amount());
    }

    private void updateBalance(long accountId, BigDecimal delta) {
        jdbcClient.sql("UPDATE account SET balance = balance + :delta WHERE id = :id")
                .param("delta", delta)
                .param("id", accountId)
                .update();
    }
}
