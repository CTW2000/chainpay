package com.chainpay.ledger.system;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 「数据库这一下没成，下一轮再来」的判定，三个定时任务共用。
 *
 * <p>Spring 把「拿不到连接」归为 {@link DataAccessResourceFailureException}，它在层级里是 <i>NonTransient</i>——
 * 那是 Spring 的口径（资源坏了，不是重试就能好的乐观锁冲突）。对我们的任务来说，库抖一下、连接池耗尽、主从切换
 * 都是几秒后就好的事，不该把索引器 HALTED 落库（重启不恢复，要人来改）或把一笔入账记成 HELD_ERROR 等人。
 * 事务开不出来（{@link CannotCreateTransactionException}）是同一件事在 TransactionTemplate 那一层的样子。
 * 2026-09-10 扫描（6.10）发现，受控复现后修。
 */
public final class TransientDbFailure {

    private TransientDbFailure() {}

    public static boolean isTransient(RuntimeException e) {
        return e instanceof TransientDataAccessException
                || e instanceof DataAccessResourceFailureException
                || e instanceof CannotCreateTransactionException;
    }
}
