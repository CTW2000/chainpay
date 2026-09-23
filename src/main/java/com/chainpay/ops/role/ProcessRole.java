package com.chainpay.ops.role;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;

/**
 * 进程角色（进程拆分 ①，2026-09-23）：同一个镜像起成两种常驻进程，靠 Spring profile 区分。
 * {@code web} 对外接商户请求；{@code worker} 跑定时任务与控制面，握着重钥匙。
 *
 * <p>两条规矩，任何一条不成立就拒绝启动（{@link ProcessRoleConfig} 在创建任何 bean 之前调 {@link #resolve}）：
 * <ol>
 *   <li><b>恰好一个角色。</b>profile 天生可以同时开好几个，「恰好一个」不是 Spring 给的保证。没指定、两个都指定，一律拒绝：
 *       默认全开的角色划分等于没划分（对标 Apache Fineract：角色开关默认全开，它 compose 里的「worker」照样对外提供全部读写接口）。</li>
 *   <li><b>这个角色不该拿的凭证，环境里一个都不许有。</b>报错只写变量名和它能干什么，永不带值。</li>
 * </ol>
 *
 * <p><b>「有没有」的口径</b>和 {@code @ConditionalOnProperty} 一致：解析出非空白、且不是 {@code false}（测试基类用 "false" 表示不装配节点）。
 * application.yml 里好几个键是没有默认值的 {@code ${…}} 占位符，没设对应的环境变量时一读就抛异常——那等于没配，不是错误。
 *
 * <p><b>名单上的每一项都要真实存在</b>：写错一个字母，这条禁令永远不触发、也不报错（对标 Fineract：同一个开关在文档、报错提示、代码里三种拼法）。
 * 所以每项同时写运维设的环境变量名与应用读它用的配置键：{@code ProcessRoleBootTest} 用真的 application.yml 逐项证明「设了这个环境变量就会被拦」，
 * {@code EnvInventoryTest} 把名单和 env 样例里的变量清单对齐。
 */
public enum ProcessRole {

    WEB("web", List.of(
            new Credential("CHAINPAY_SYSTEM_DB_PASSWORD", "chainpay.system-db.password", "系统角色：看得到全部商户的数据，能改提现状态、写账本"),
            new Credential("CHAINPAY_FLYWAY_PASSWORD", "spring.flyway.password", "属主：开发库里是超级用户"),
            new Credential("CHAINPAY_PAYOUT_HOT_WALLET_KEY", "chainpay.payout.hot-wallet-key", "热钱包私钥：能直接把热钱包转空"),
            new Credential("CHAINPAY_CHAIN_RPC_URL", "chainpay.chain.rpc-url", "主节点地址，里面带 key"),
            new Credential("CHAINPAY_CHAIN_AUDIT_RPC_URL", "chainpay.chain.audit-rpc-url", "审计节点地址，里面带 key"),
            new Credential("CHAINPAY_ALERT_WEBHOOK_URL", "chainpay.alert.webhook-url", "告警地址，令牌常在 URL 里"),
            Credential.ADMIN_PASSWORD)),

    /** 属主口令第 ⑤ 步再加进来：在那之前唯一的容器以 worker 身份跑，启动时还要自己迁移。 */
    WORKER("worker", List.of(
            Credential.ADMIN_PASSWORD));

    /** 一把凭证：运维设的环境变量名、应用读它用的配置键、落到别人手里能干什么（写进拒绝启动的报错里）。 */
    public record Credential(String envName, String property, String power) {

        /** 建第一个管理员的口令：只该出现在 {@code --create-admin} 那一条一次性命令的环境里，两个常驻进程都不许有。 */
        static final Credential ADMIN_PASSWORD =
                new Credential("CHAINPAY_ADMIN_PASSWORD", "chainpay.admin-password", "建管理员的口令：只属于那一条一次性命令");
    }

    private final String profile;
    private final List<Credential> forbidden;

    ProcessRole(String profile, List<Credential> forbidden) {
        this.profile = profile;
        this.forbidden = List.copyOf(forbidden);
    }

    public String profile() {
        return profile;
    }

    public List<Credential> forbidden() {
        return forbidden;
    }

    /** 认出这个进程的角色；角色不成立或环境里有它不该拿的凭证就抛 {@link ProcessRoleException}。 */
    public static ProcessRole resolve(Environment env) {
        List<String> active = Arrays.asList(env.getActiveProfiles());
        List<ProcessRole> matched = Arrays.stream(values()).filter(r -> active.contains(r.profile)).toList();
        if (matched.size() != 1) {
            throw new ProcessRoleException("进程角色必须恰好是 web、worker 之一（环境变量 SPRING_PROFILES_ACTIVE），现在激活的 profile 是 " + active
                    + "。没有角色的进程不知道自己该拿哪些钥匙；两个角色都开，等于没拆");
        }
        ProcessRole role = matched.getFirst();
        List<Credential> present = role.forbidden.stream().filter(c -> isConfigured(env, c.property())).toList();
        if (!present.isEmpty()) {
            throw new ProcessRoleException(role.profile + " 进程的环境里有它不该拿的凭证，拒绝启动："
                    + present.stream().map(c -> c.envName() + "（" + c.power() + "）").collect(Collectors.joining("；"))   // 不用「、」：说明文字里就有顿号
                    + "。从这个进程的环境里去掉它们；怎么做见 docs/runbook/ops.md「进程角色」");
        }
        return role;
    }

    /** 解析出非空白、且不是 false，才算配了；解析不出来的占位符等于没配。 */
    static boolean isConfigured(PropertyResolver env, String key) {
        String value;
        try {
            value = env.getProperty(key);
        } catch (IllegalArgumentException unresolvable) {
            return false;   // ${…} 既没有对应的环境变量、也没有默认值
        }
        return value != null && !value.isBlank() && !value.contains("${") && !"false".equalsIgnoreCase(value.strip());
    }
}
