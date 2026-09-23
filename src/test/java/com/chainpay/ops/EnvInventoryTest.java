package com.chainpay.ops;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.ops.role.ProcessRole;
import com.chainpay.support.SecretNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 进程拆分 ① · 变量清单：env 样例里的每个变量都有归属——是不是密钥、web 能不能拿、worker 能不能拿。
 *
 * <p>三份东西必须互相对得上，这里把它们绑在一起：
 * <ul>
 *   <li>env 样例（运维照着填的那份）里出现的变量 = 这张清单，一个不多一个不少——新加一个密钥不归类，这里就红；</li>
 *   <li>清单判为密钥的 = {@code tools/image-check.sh} 按值扫描的（{@link SecretNames}）——密钥不在扫描名单上，就可能悄悄进了镜像；</li>
 *   <li>每个角色的禁用名单（{@link ProcessRole}）= 清单里这个角色不许拿的密钥。</li>
 * </ul>
 * 对标 Fineract 的教训：同一个开关在文档、报错提示、代码里三种拼法，写在名单上却从不触发的规矩，和没有一样，而且不会有任何报错。
 */
@DisplayName("进程拆分 ① · 变量清单：每个变量都有归属，密钥都在扫描名单上，禁用名单与清单一致")
class EnvInventoryTest {

    /** 一个变量归谁。secret：镜像扫描要按值查它；web / worker：这个常驻进程能不能拿到它。 */
    enum Rule {
        /** 拓扑、端口、用户名、格式：不是密钥。 */
        CONFIG(false, true, true),
        /** 两个进程都要：应用角色、签名密钥（web 验签、worker 发凭证时加密）、xpub（web 分配地址；取舍 8 起 worker 记账前要用它重新派生）。 */
        BOTH(true, true, true),
        /** 只有 worker：系统角色、热钱包私钥、两个节点地址、告警地址。 */
        WORKER_ONLY(true, false, true),
        /** 属主口令：只该在迁移那一步。web 现在就禁；worker 第 ⑤ 步起禁——在那之前唯一的容器以 worker 身份跑，启动时还要自己迁移。 */
        OWNER(true, false, true),
        /** 只在一条一次性命令的环境里：建第一个管理员的口令。 */
        ONE_SHOT(true, false, false),
        /** 只给测试探针用：进程从不读它们，所以不上运行时名单，靠样例文件分开（放 env/probe.env.example）。 */
        TEST_ONLY(true, false, false);

        final boolean secret;
        final boolean web;
        final boolean worker;

        Rule(boolean secret, boolean web, boolean worker) {
            this.secret = secret;
            this.web = web;
            this.worker = worker;
        }

        boolean allows(ProcessRole role) {
            return role == ProcessRole.WEB ? web : worker;
        }
    }

    static final Map<String, Rule> INVENTORY = Map.ofEntries(
            entry("JAVA_HOME", Rule.CONFIG),
            entry("CHAINPAY_DB_URL", Rule.CONFIG),
            entry("CHAINPAY_DB_USER", Rule.CONFIG),
            entry("CHAINPAY_DB_PASSWORD", Rule.BOTH),
            entry("CHAINPAY_FLYWAY_USER", Rule.CONFIG),
            entry("CHAINPAY_FLYWAY_PASSWORD", Rule.OWNER),
            entry("CHAINPAY_SYSTEM_DB_USER", Rule.CONFIG),
            entry("CHAINPAY_SYSTEM_DB_PASSWORD", Rule.WORKER_ONLY),
            entry("CHAINPAY_REDIS_HOST", Rule.CONFIG),
            entry("CHAINPAY_REDIS_PORT", Rule.CONFIG),
            entry("CHAINPAY_SECRET_KEY", Rule.BOTH),
            entry("CHAINPAY_ADMIN_PASSWORD", Rule.ONE_SHOT),
            entry("CHAINPAY_PORT", Rule.CONFIG),
            entry("CHAINPAY_MANAGEMENT_PORT", Rule.CONFIG),
            entry("CHAINPAY_BIND_ADDRESS", Rule.CONFIG),
            entry("CHAINPAY_LOG_LEVEL", Rule.CONFIG),
            entry("CHAINPAY_ALERT_WEBHOOK_URL", Rule.WORKER_ONLY),
            entry("CHAINPAY_ALERT_FORMAT", Rule.CONFIG),
            entry("CHAINPAY_CHAIN_RPC_URL", Rule.WORKER_ONLY),
            entry("CHAINPAY_CHAIN_AUDIT_RPC_URL", Rule.WORKER_ONLY),
            entry("CHAINPAY_CHAIN_START_BLOCK", Rule.CONFIG),
            entry("CHAINPAY_DEPOSIT_XPUB", Rule.BOTH),
            entry("CHAINPAY_PAYOUT_HOT_WALLET_KEY", Rule.WORKER_ONLY),
            entry("CHAINPAY_SEPOLIA_RPC", Rule.TEST_ONLY),
            entry("CHAINPAY_SEPOLIA_AUDIT_RPC", Rule.TEST_ONLY));

    /** 样例里的一行赋值：{@code NAME=…}，或注释里示范用法的 {@code #   NAME=…}。 */
    private static final Pattern ASSIGNMENT = Pattern.compile("^(#?)\\s*([A-Z][A-Z0-9_]*)=");

    @Test
    @DisplayName("env 样例里出现的每个变量都在清单上，清单上的每个变量也都还在样例里")
    void everyVariableIsClassified() {
        Set<String> names = new TreeSet<>();
        for (Path example : examples()) {
            names.addAll(names(example, false));
        }
        assertThat(names).isEqualTo(new TreeSet<>(INVENTORY.keySet()));
    }

    @Test
    @DisplayName("清单判为密钥的，镜像扫描都会按值查；不是密钥的，它不去查——两份口径一致")
    void imageCheckScansExactlyTheSecrets() {
        Pattern secret = SecretNames.wholeName();
        INVENTORY.forEach((name, rule) ->
                assertThat(secret.matcher(name).matches()).as(name + " 是不是密钥").isEqualTo(rule.secret));
        assertThat(SecretNames.inText().matcher("ENV CHAINPAY_DB_PASSWORD=x").find())
                .as("在一行文字里也找得到（ContainerGuardTest 用这个扫 Dockerfile 与 compose）").isTrue();
    }

    @Test
    @DisplayName("每个角色的禁用名单 = 清单里这个角色不许拿的密钥（测试探针的除外：进程从不读它们）")
    void forbiddenListsMatchTheInventory() {
        for (ProcessRole role : ProcessRole.values()) {
            Set<String> expected = INVENTORY.entrySet().stream()
                    .filter(e -> e.getValue().secret && !e.getValue().allows(role) && e.getValue() != Rule.TEST_ONLY)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> actual = role.forbidden().stream()
                    .map(ProcessRole.Credential::envName)
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(actual).as(role.profile() + " 的禁用名单").isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("进程的 env 样例里没有测试探针的地址、也没有建管理员的口令：那份文件整份进容器环境")
    void processTemplateCarriesOnlyWhatProcessesUse() {
        Set<String> active = names(Path.of("env/local.env.example"), true);
        INVENTORY.forEach((name, rule) -> {
            if (rule == Rule.TEST_ONLY || rule == Rule.ONE_SHOT) {
                assertThat(active).as(name + " 不该是 local.env.example 里生效的一行").doesNotContain(name);
            }
        });
    }

    private static List<Path> examples() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(Path.of("env"), "*.env.example")) {
            dir.forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(files).as("env/ 下至少有 local.env.example").isNotEmpty();
        return files;
    }

    /** 文件里赋值的变量名；{@code activeOnly} 为真时只算没被注释掉的行。 */
    private static Set<String> names(Path file, boolean activeOnly) {
        try {
            Set<String> names = new TreeSet<>();
            for (String line : Files.readAllLines(file)) {
                Matcher m = ASSIGNMENT.matcher(line);
                if (m.find() && (!activeOnly || m.group(1).isEmpty())) {
                    names.add(m.group(2));
                }
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
