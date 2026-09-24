package com.chainpay.ops.role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

/**
 * 进程角色的守卫：一个静态的、优先级最高的 BeanFactoryPostProcessor。
 *
 * <p><b>为什么挂在这个时机</b>：Spring 启动分两段，先读完所有 bean 的「图纸」，再照图纸把 bean 造出来；
 * BeanFactoryPostProcessor 恰好跑在两段之间。在这里拒绝启动，连接池还没建、定时任务还没注册、端口还没开——
 * 凭证放错的进程什么都没碰就退出。写成普通 bean 构造器里的检查就晚了：bean 的创建顺序不由我们定，
 * 等它跑到时，系统连接池可能已经拿着不该有的口令连过库。
 *
 * <p><b>为什么是静态方法</b>：BeanFactoryPostProcessor 要在所有普通 bean 之前造出来，写成实例方法会拖着整个配置类提前实例化。
 *
 * <p><b>只在完整应用里生效</b>：{@code --migrate-only} 与 {@code --create-admin} 的最小上下文只导入两三个自动配置、不扫描这个包——
 * 它们不属于任何角色，也不该被它拦住。
 */
@Configuration(proxyBeanMethods = false)
class ProcessRoleConfig {

    @Bean
    static ProcessRoleGuard processRoleGuard(Environment environment) {
        return new ProcessRoleGuard(environment);
    }

    static final class ProcessRoleGuard implements BeanFactoryPostProcessor, PriorityOrdered {

        private static final Logger log = LoggerFactory.getLogger(ProcessRoleGuard.class);

        private final Environment environment;

        ProcessRoleGuard(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            ProcessRole role = ProcessRole.resolve(environment);
            log.info("进程角色：{}（禁用名单 {} 项，环境里一项都没有；必填 {} 项，一项不缺）",
                    role.profile(), role.forbidden().size(), role.required().size());
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
