package com.chainpay.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.support.SecretNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M6-① · 容器文件的守卫：Dockerfile、.dockerignore、docker-compose.yml 里那几条「忘了就出事」的规矩，形状都是「某个文件里必须 / 不许出现某个字符串」，
 * 和 ControllerBoundaryTest 一样扫文本，不引依赖。镜像真的有没有密钥由 tools/image-check.sh 在打完镜像后扫（那需要 Docker，不进默认测试集）。
 */
@DisplayName("M6-① · 容器文件守卫")
class ContainerGuardTest {

    /** 密钥形态的变量名：只在 tools/image-check.sh 写一份（进程拆分 ① 收口，此前这里抄了一份，两份一起漏掉告警地址）。 */
    private static final Pattern SECRET_NAME = SecretNames.inText();

    @Test
    @DisplayName("Dockerfile：运行阶段是 JRE 而不是 JDK、最后以非 root 用户跑、有打 readiness 的 HEALTHCHECK、没有任何密钥形态的 ARG / ENV")
    void dockerfileFollowsTheRules() throws IOException {
        String df = Files.readString(Path.of("Dockerfile")).replace("\\\n", " ");   // Docker 的行续接：反斜杠换行 = 同一条指令
        List<String> lines = Arrays.stream(df.split("\n")).map(String::strip).toList();
        int lastFrom = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("FROM ")) {
                lastFrom = i;
            }
        }
        assertThat(lastFrom).as("至少两个阶段：构建与运行").isGreaterThan(0);
        List<String> runtime = lines.subList(lastFrom, lines.size());
        assertThat(runtime.get(0)).as("运行阶段用 JRE 镜像，JDK 带编译器与调试工具，攻击面白白大一圈").contains("-jre");
        List<String> users = runtime.stream().filter(l -> l.startsWith("USER ")).toList();
        assertThat(users).as("运行阶段必须切到非 root（OWASP Docker RULE #2）").isNotEmpty();
        assertThat(users.getLast()).doesNotContain("root").doesNotContain("USER 0");
        int userAt = runtime.indexOf(users.getLast());
        assertThat(runtime.subList(userAt, runtime.size())).as("USER 之后不该再 COPY：文件属主会变成 root，只读文件系统下应用改不了也不该改").noneMatch(l -> l.startsWith("COPY "));
        assertThat(runtime).as("HEALTHCHECK 打 readiness（能不能接请求），不是 liveness").anyMatch(l -> l.startsWith("HEALTHCHECK ") && l.contains("/actuator/health/readiness"));
        assertThat(lines).as("密钥永远不进镜像：没有密钥形态的 ARG / ENV").noneMatch(l -> (l.startsWith("ARG ") || l.startsWith("ENV ")) && SECRET_NAME.matcher(l).find());
        assertThat(lines).as("不整目录 COPY，否则 env/ 和 target/ 会被卷进去").noneMatch(l -> l.matches("COPY\\s+\\.\\s+.*"));
    }

    @Test
    @DisplayName(".dockerignore：env/、target/、.git、docs/retro 都不进构建上下文")
    void dockerignoreKeepsSecretsAndJunkOut() throws IOException {
        List<String> ignored = Files.readAllLines(Path.of(".dockerignore")).stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        assertThat(ignored).contains("env/", "target/", ".git", "docs/retro/", "*.env", "*.key", "*.pem");
        assertThat(ignored).as("样板要进去？不：镜像里不需要任何 env 文件").doesNotContain("!env/*.env.example");
    }

    @Test
    @DisplayName("compose 的 app 服务：密钥只从 env_file 来、environment 里只有主机名与端口、端口只绑回环、去能力、禁提权、只读根、限内存、依赖健康的中间件、有 readiness 健康检查、会自动重启")
    void composeAppServiceFollowsTheRules() throws IOException {
        String app = serviceBlock(Files.readString(Path.of("docker-compose.yml")), "app");
        assertThat(app).contains("env_file:").contains("env/local.env");
        List<String> environment = Arrays.stream(app.split("\n")).map(String::strip).filter(l -> l.matches("^-?\\s*CHAINPAY_[A-Z_]+[:=].*")).toList();
        assertThat(environment).as("environment 里覆盖的只能是主机名与端口这类拓扑；密钥形态的变量一个都不许出现在 compose 里").noneMatch(l -> SECRET_NAME.matcher(l).find());
        assertThat(app).as("端口只绑 127.0.0.1（RULE #5a：Docker 绕过 UFW）").containsPattern("\"127\\.0\\.0\\.1:\\d+:\\d+\"").doesNotContainPattern("\"\\d+:\\d+\"");
        assertThat(app).as("RULE #3 去掉全部能力").containsPattern("cap_drop:\\s*\\n\\s*-\\s*ALL");
        assertThat(app).as("RULE #4 禁止容器内提权").contains("no-new-privileges");
        assertThat(app).as("RULE #8 根文件系统只读").contains("read_only: true");
        assertThat(app).as("RULE #7 限内存").containsPattern("mem_limit:|memory:");
        assertThat(app).contains("condition: service_healthy");
        assertThat(app).contains("/actuator/health/readiness");
        assertThat(app).contains("restart: unless-stopped");
        assertThat(app).as("主端口默认只绑回环，容器里必须显式放开到 0.0.0.0，否则宿主发布的端口连不进去").contains("CHAINPAY_BIND_ADDRESS: \"0.0.0.0\"");
        assertThat(app).as("进程角色必须显式给（进程拆分 ①）：没有角色的容器起不来。拆成两个服务（第 ⑥ 步）之前，唯一的容器以 worker 身份跑")
                .containsPattern("SPRING_PROFILES_ACTIVE:\\s*worker");
    }

    @Test
    @DisplayName("application.yml：监听地址与日志级别由环境变量定，默认回环 + INFO（安全扫描第 15 条，M6-②）")
    void bindAddressAndLogLevelComeFromTheEnvironment() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("address: ${CHAINPAY_BIND_ADDRESS:127.0.0.1}");
        assertThat(yml).contains("com.chainpay: ${CHAINPAY_LOG_LEVEL:INFO}");
    }

    /** 取 compose 里一个服务的整块（两个空格缩进的键到下一个同级键）。 */
    private static String serviceBlock(String compose, String name) {
        Pattern p = Pattern.compile("(?ms)^  " + name + ":\\n(.*?)(?=^  [a-z]|^[a-z]|\\z)");
        var m = p.matcher(compose);
        assertThat(m.find()).as("compose 里有 " + name + " 服务").isTrue();
        return m.group(1);
    }
}
