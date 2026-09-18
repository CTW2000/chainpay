package com.chainpay;

import com.chainpay.ops.AdminBootstrap;
import com.chainpay.ops.Migrate;
import java.util.OptionalInt;
import java.util.function.IntSupplier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * chainpay —— 加密支付网关（学习项目）。进度见 {@code LEARNING-PATH.md}。
 *
 * <p>一个 jar 三种用法：不带模式参数 = 完整应用；{@code --migrate-only} = 只迁移（部署第 ④ 步，M6-④）；
 * {@code --create-admin <用户名>} = 建第一个管理员（M6-⑤）。后两种是一次性命令：跑完交出退出码就结束，
 * 绝不能顺手把完整应用起来——那等于在旁边多跑一个实例，定时任务照跑，而且永不退出。
 */
@SpringBootApplication
public class ChainpayApplication {

    static final String MIGRATE_ONLY = "--migrate-only";
    static final String CREATE_ADMIN = "--create-admin";
    /** 用法错误的退出码：参数写错了，什么都没做。 */
    static final int USAGE = 2;

    public static void main(String[] args) {
        OptionalInt exit = oneShot(args,
                () -> Migrate.run(args).exitCode(),                                // M6-④：部署脚本在切换之前单独跑迁移，成功 0、失败非 0
                () -> AdminBootstrap.run(args, System.getenv()).exitCode());       // M6-⑤：第一个管理员；口令只从环境变量 CHAINPAY_ADMIN_PASSWORD 来
        if (exit.isPresent()) {
            System.exit(exit.getAsInt());                                          // 承重：少了它，一次性命令做完会接着起完整应用
        }
        SpringApplication.run(ChainpayApplication.class, args);
    }

    /**
     * 这一次是不是一次性命令：是就跑完它、返回退出码；不是就返回空，由 {@link #main} 起完整应用。
     *
     * <p>分派和退出拆开（2026-09-18）：{@code System.exit} 会带走跑测试的 JVM，原来写在 main 里的分派没有任何测试守着。
     * 认不出来的模式参数一律拒绝（退出码 {@value #USAGE}，什么都不跑）：精确比对之外的写法——{@code --migrate_only}、
     * {@code --migrate-only=true}、{@code --create-admin=ops}——以前会被 Spring 当成没人认识的配置项忽略，静默起完整应用
     * （另起 JVM 实测）。只拦以 {@code --migrate} / {@code --create} 开头的，Spring 自己的参数
     * （{@code --server.port=…}、{@code --debug}）不受影响。两个模式同时出现也拒绝：以前是先出现的那个生效、另一个悄悄忽略。
     */
    static OptionalInt oneShot(String[] args, IntSupplier migrate, IntSupplier createAdmin) {
        boolean migrateOnly = false;
        boolean admin = false;
        for (String a : args) {
            if (MIGRATE_ONLY.equals(a)) {
                migrateOnly = true;
            } else if (CREATE_ADMIN.equals(a)) {
                admin = true;
            } else if (a.startsWith("--migrate") || a.startsWith("--create")) {
                System.err.println("不认识的模式参数：" + a + "。一次性模式只有 " + MIGRATE_ONLY + " 与 " + CREATE_ADMIN + " <用户名>");
                return OptionalInt.of(USAGE);
            }
        }
        if (migrateOnly && admin) {
            System.err.println(MIGRATE_ONLY + " 与 " + CREATE_ADMIN + " 不能同时用：一次只做一件事");
            return OptionalInt.of(USAGE);
        }
        if (migrateOnly) {
            return OptionalInt.of(migrate.getAsInt());
        }
        if (admin) {
            return OptionalInt.of(createAdmin.getAsInt());
        }
        return OptionalInt.empty();
    }
}
