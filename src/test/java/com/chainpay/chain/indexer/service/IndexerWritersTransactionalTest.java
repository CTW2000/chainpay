package com.chainpay.chain.indexer.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.support.AbstractPostgresTest;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * 索引器的四段事务各在一个写入类里：BatchWriter / ChainHeadWriter / ReorgWriter / ReconcileWriter。
 * 注解靠代理生效，所以钉住三件事：容器给的是代理；每个公开方法都带 REQUIRED 的事务属性；类与方法都不是 final。
 */
@SpringBootTest
@DisplayName("索引器的写入类：事务靠代理生效")
class IndexerWritersTransactionalTest extends AbstractPostgresTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("★ 四个写入类都是容器里的代理，每个公开方法都带 REQUIRED，类与方法都不是 final")
    void everyWriterIsATransactionalProxy() {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        for (String name : List.of("batchWriter", "chainHeadWriter", "reorgWriter", "reconcileWriter")) {
            Object bean = context.getBean(name);
            assertThat(AopUtils.isCglibProxy(bean)).as(name + " 必须是代理：不是代理，注解形同虚设").isTrue();
            Class<?> type = AopUtils.getTargetClass(bean);
            assertThat(Modifier.isFinal(type.getModifiers())).as(name + " 不能是 final 类：生成不了代理").isFalse();
            List<Method> methods = Arrays.stream(type.getDeclaredMethods()).filter(m -> Modifier.isPublic(m.getModifiers())).toList();
            assertThat(methods).as(name + " 至少有一个公开方法").isNotEmpty();
            for (Method m : methods) {
                TransactionAttribute attribute = source.getTransactionAttribute(m, type);
                assertThat(attribute).as(name + "." + m.getName() + " 必须带 @Transactional").isNotNull();
                assertThat(attribute.getPropagationBehavior()).as(name + "." + m.getName()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                assertThat(Modifier.isFinal(m.getModifiers())).as(name + "." + m.getName() + " 不能是 final：代理拦不住").isFalse();
            }
        }
    }
}
