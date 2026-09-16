package com.chainpay.support;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * 给测试里 new 出来的对象套上和容器一模一样的事务代理：方法上的 {@code @Transactional} 由指定的事务管理器执行。
 * 容器外 new 的实例没有代理、也就没有事务（CLAUDE.md「事务的两种写法」）；测试要换假依赖、或者要一个坏掉的事务管理器时，用它补上。
 */
public final class TransactionalProxy {

    private TransactionalProxy() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T of(T target, TransactionManager transactionManager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
