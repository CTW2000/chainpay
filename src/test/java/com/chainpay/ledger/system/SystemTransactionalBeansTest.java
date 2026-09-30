package com.chainpay.ledger.system;

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
 * 系统侧改用注解之后，事务靠代理生效：容器给的是代理、类与方法都不是 final、注解落在 system 的事务管理器上、传播方式对。
 * 删掉一处注解、写错限定名或传播方式，业务测试多半照绿（调用方常常已经开着事务），只有这里红。
 */
@SpringBootTest
@DisplayName("系统侧的事务：注解靠代理生效，落在 system 上")
class SystemTransactionalBeansTest extends AbstractPostgresTest {

    private record Expected(String bean, String method, int propagation) {}

    private static final int REQUIRED = TransactionDefinition.PROPAGATION_REQUIRED;

    private static final List<Expected> EXPECTED = List.of(
            new Expected("systemLedgerService", "transfer", TransactionDefinition.PROPAGATION_MANDATORY),
            new Expected("auditWriter", "record", REQUIRED),
            new Expected("hotWalletFundingService", "register", REQUIRED),
            new Expected("depositWriter", "apply", REQUIRED),
            new Expected("depositWriter", "holdWithError", REQUIRED),
            new Expected("payoutSendWriter", "reconcile", REQUIRED),
            new Expected("payoutSendWriter", "markBroadcast", REQUIRED),
            new Expected("payoutTrackWriter", "recordMined", REQUIRED),
            new Expected("payoutTrackWriter", "reorged", REQUIRED),
            new Expected("payoutTrackWriter", "settle", REQUIRED),
            new Expected("payoutApprovalService", "reject", REQUIRED));

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("★ 系统侧贴了注解的方法：bean 是代理，类与方法不是 final，事务落在 system 上，传播方式对")
    void systemSideTransactionsGoThroughProxiesOnTheSystemManager() {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        for (Expected e : EXPECTED) {
            String name = e.bean() + "." + e.method();
            Object bean = context.getBean(e.bean());
            assertThat(AopUtils.isAopProxy(bean)).as(e.bean() + " 必须是代理：不是代理，注解形同虚设").isTrue();
            Class<?> type = AopUtils.getTargetClass(bean);
            assertThat(Modifier.isFinal(type.getModifiers())).as(e.bean() + " 不能是 final 类：生成不了代理").isFalse();
            Method method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(e.method())).findFirst().orElseThrow();
            assertThat(Modifier.isFinal(method.getModifiers())).as(name + " 不能是 final：代理拦不住").isFalse();
            TransactionAttribute attribute = source.getTransactionAttribute(method, type);
            assertThat(attribute).as(name + " 必须带 @Transactional").isNotNull();
            assertThat(attribute.getQualifier()).as(name + " 必须落在 system 的事务管理器上").isEqualTo(SystemLedger.QUALIFIER);
            assertThat(attribute.getPropagationBehavior()).as(name + " 的传播方式").isEqualTo(e.propagation());
        }
    }

    /**
     * 签名闸口只在同时配了热钱包私钥与主节点时才装配（测试里不装配），所以按类核：类与方法不是 final、注解落在 system 上、REQUIRED。
     * 「容器给的是代理」这一条由装配处保证——它是 {@code @Bean} 方法 new 出来交给容器的，和写入类一样。
     */
    @Test
    @DisplayName("★ 签名闸口的 sign 与 fail：认领、复核、处置、签名或解冻落库各是一个系统事务（测试里不装配，按类核）")
    void theSigningGateWorksInSystemTransactions() throws NoSuchMethodException {
        Class<?> type = com.chainpay.chain.payout.service.PayoutSigningGate.class;
        assertThat(Modifier.isFinal(type.getModifiers())).as("闸口不能是 final 类：生成不了代理").isFalse();
        for (Method method : List.of(type.getMethod("sign", long.class, com.chainpay.chain.payout.service.FeePolicy.Fees.class),
                type.getMethod("fail", long.class, String.class))) {
            assertThat(Modifier.isFinal(method.getModifiers())).as(method.getName() + " 不能是 final：代理拦不住").isFalse();
            TransactionAttribute attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(method, type);
            assertThat(attribute).as(method.getName() + " 必须带 @Transactional").isNotNull();
            assertThat(attribute.getQualifier()).isEqualTo(SystemLedger.QUALIFIER);
            assertThat(attribute.getPropagationBehavior()).isEqualTo(REQUIRED);
        }
    }
}
