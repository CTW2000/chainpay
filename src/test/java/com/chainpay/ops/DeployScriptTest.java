package com.chainpay.ops;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 部署脚本的<b>行为</b>。{@link DeployGuardTest} 读的是脚本文本：八个环节在不在、顺序对不对；
 * 这里管文本看不出来的东西——标签能不能唯一确定内容、密钥会不会漏进后面的子进程、
 * 一句日志能不能掐断部署、第一次部署失败时留下什么。
 *
 * <p>做法：{@code source deploy/deploy.sh} 只定义函数、不跑 main（脚本末尾的守卫），PATH 最前面放一个假 docker，
 * 逐环调函数，看它对 docker 说了什么、留下了什么。git 用的是临时目录里现造的仓库，真仓库一点不碰。
 */
@DisplayName("部署脚本的行为（假 docker）")
class DeployScriptTest {

    private static final Path DEPLOY_SH = Path.of("deploy/deploy.sh").toAbsolutePath();

    /**
     * 假 docker。每次调用记一行到 calls；image inspect 查 tags 文件（有 = 0，没有 = 1）；tag / rmi 改 tags 文件；
     * build 从标准输入读上下文时把 tar 存成 context.tar；compose run 按 FAKE_MIGRATE_* 给输出和退出码；exec 打出 FAKE_WORK_JSON。
     */
    private static final String FAKE_DOCKER = """
            #!/usr/bin/env bash
            echo "$*" >> "$FAKE_DIR/calls"
            case "$1" in
              image) grep -qxF -- "${!#}" "$FAKE_DIR/tags"; exit $? ;;
              build) if [[ ${!#} == - ]]; then cat > "$FAKE_DIR/context.tar"; fi ;;
              tag) echo "$3" >> "$FAKE_DIR/tags" ;;
              rmi) { grep -vxF -- "$2" "$FAKE_DIR/tags" || true; } > "$FAKE_DIR/tags.new"; mv "$FAKE_DIR/tags.new" "$FAKE_DIR/tags" ;;
              compose) if [[ $2 == run ]]; then printf '%s\\n' "${FAKE_MIGRATE_OUTPUT:-}"; exit "${FAKE_MIGRATE_EXIT:-0}"; fi ;;
              exec) printf '%s' "${FAKE_WORK_JSON:-}" ;;
            esac
            exit 0
            """;

    /** 现造一个有两个提交的仓库：a.txt 从 v1 改成 v2。g 是不受本机全局配置影响的 git（不签名、不跑钩子）。 */
    private static final String REPO = """
            g() { git -c user.name=t -c user.email=t@t -c commit.gpgsign=false -c core.hooksPath=/dev/null "$@"; }
            g init -q repo
            cd repo
            echo v1 > a.txt
            g add a.txt
            g commit -qm one
            echo v2 > a.txt
            g commit -qam two
            """;

    @TempDir
    Path dir;

    private Path fake;

    @BeforeEach
    void installFakeDocker() throws IOException {
        fake = Files.createDirectories(dir.resolve("fake"));
        Path docker = Files.createDirectories(fake.resolve("bin")).resolve("docker");
        Files.writeString(docker, FAKE_DOCKER);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(fake.resolve("tags"), "");
        Files.writeString(fake.resolve("calls"), "");
    }

    @Test
    @DisplayName("★ 标签由内容决定：两份不同的改动两个标签，同一份改动两次同一个标签；未跟踪的新文件也算改动；改回去就是干净的提交号")
    void theTagIdentifiesTheContent() throws Exception {
        Map<String, String> t = values(bash(REPO + """
                tag_of_head() { resolve_source HEAD | cut -d' ' -f2; }
                echo "@head=$(g rev-parse --short HEAD)"
                echo "@clean=$(tag_of_head)"
                echo v3 > a.txt
                echo "@first=$(tag_of_head)"
                echo "@again=$(tag_of_head)"
                echo v4 > a.txt
                echo "@second=$(tag_of_head)"
                g checkout -q a.txt
                echo new > b.txt
                echo "@untracked=$(tag_of_head)"
                rm b.txt
                echo "@back=$(tag_of_head)"
                """));
        String dirty = "chainpay:" + t.get("head") + "-dirty";
        assertThat(t.get("clean")).isEqualTo("chainpay:" + t.get("head"));
        assertThat(t.get("first")).startsWith(dirty);
        assertThat(t.get("again")).as("同一份改动 = 同一个标签，「已有就跳过构建」这时才是对的").isEqualTo(t.get("first"));
        assertThat(t.get("second")).as("不同的改动必须是不同的标签，否则会把上一次的镜像当成这一次部署出去").isNotEqualTo(t.get("first"));
        assertThat(t.get("untracked")).as("未跟踪的新文件也会进构建上下文，所以也算改动").startsWith(dirty).isNotEqualTo(t.get("first"));
        assertThat(t.get("back")).isEqualTo(t.get("clean"));
    }

    @Test
    @DisplayName("★ 同一秒里改成同样长度的内容、过了这一秒才打标签：照样认得出改动——快照按内容算，不借暂存区按时间戳记下的缓存")
    void anEditInTheSameSecondIsStillSeen() throws Exception {
        Map<String, String> t = values(bash(REPO + """
                boundary() { python3 -c 'import time; time.sleep(1 - time.time() % 1 + 0.02)'; }   # 睡到刚过下一个整秒
                missed=0
                for n in 1 2 3; do
                  boundary; g checkout -q a.txt; echo v9 > a.txt     # 同一秒：暂存区刚记下 a.txt 的属性，紧接着改成同样长度的内容
                  boundary                                           # 过了这一秒再打标签
                  if [[ $(resolve_source HEAD | cut -d' ' -f2) != *-dirty.* ]]; then missed=$((missed+1)); fi
                done
                echo "@missed=$missed"
                """));
        assertThat(t.get("missed")).as("按秒比修改时间的 git 会把这种改动当成没改；3 次里一次都不能漏").isEqualTo("0");
    }

    @Test
    @DisplayName("★ 部署一个旧提交：标签写的是那个提交，构建上下文里也只有那个提交的文件——工作区的改动和杂物一个都带不进去")
    void anOlderRefBuildsExactlyThatCommit() throws Exception {
        Map<String, String> t = values(bash(REPO + """
                echo dirty > a.txt
                echo stray > stray.txt
                read -r src image < <(resolve_source HEAD~1)
                build_image "$src" "$image"
                echo "@image=$image"
                echo "@old=$(g rev-parse --short HEAD~1)"
                echo "@a=$(tar -xOf "$FAKE_DIR/context.tar" a.txt 2>&1)"
                echo "@entries=$(tar -tf "$FAKE_DIR/context.tar" 2>&1 | tr '\\n' ' ')"
                """));
        assertThat(t.get("image")).isEqualTo("chainpay:" + t.get("old"));
        assertThat(t.get("a")).as("构建上下文里的 a.txt 必须是那个提交的 v1，不是工作区的 dirty").isEqualTo("v1");
        assertThat(t.get("entries")).as("工作区的杂物不进上下文").doesNotContain("stray.txt");
    }

    @Test
    @DisplayName("★ 配置检查之后，密钥不留在部署脚本的环境里：后面的 docker / compose 子进程一个都看不到")
    void checkConfigLeavesNoSecretsBehind() throws Exception {
        Path env = dir.resolve("deploy.env");
        Files.writeString(env, """
                CHAINPAY_DB_PASSWORD=fake-db-password-0001
                CHAINPAY_FLYWAY_PASSWORD=fake-flyway-password-0001
                CHAINPAY_SYSTEM_DB_PASSWORD=fake-system-password-0001
                CHAINPAY_SECRET_KEY=%s
                CHAINPAY_DEPOSIT_XPUB=xpub-fake-0001
                """.formatted(Base64.getEncoder().encodeToString(new byte[32])));
        Run r = bash("""
                check_config
                echo "@parent=${CHAINPAY_DB_PASSWORD:-none}"
                echo "@child=$(bash -c 'echo ${CHAINPAY_FLYWAY_PASSWORD:-none}')"
                """, "CHAINPAY_ENV_FILE", env.toString());
        Map<String, String> t = values(r);
        assertThat(r.out()).contains("✓ CHAINPAY_DB_PASSWORD").contains("✓ CHAINPAY_SECRET_KEY");
        assertThat(t.get("parent")).as("部署脚本自己的环境里不能留着").isEqualTo("none");
        assertThat(t.get("child")).as("后面起的子进程也不能继承").isEqualTo("none");
    }

    @Test
    @DisplayName("配置检查不通过就停：缺必填只报名字不报值；env 文件不在也不通过")
    void checkConfigFailsClosed() throws Exception {
        Path env = dir.resolve("partial.env");
        Files.writeString(env, "CHAINPAY_DB_PASSWORD=fake-db-password-0001\n");
        Run missing = bash("check_config", "CHAINPAY_ENV_FILE", env.toString());
        assertThat(missing.exit()).isNotZero();
        assertThat(missing.out()).contains("✗ CHAINPAY_FLYWAY_PASSWORD 没设").doesNotContain("fake-db-password-0001");
        assertThat(missing.out()).as("收款 xpub 必填：应用没它起不来，部署前就该拦下").contains("✗ CHAINPAY_DEPOSIT_XPUB 没设");

        Run absent = bash("check_config", "CHAINPAY_ENV_FILE", dir.resolve("nope.env").toString());
        assertThat(absent.exit()).isNotZero();
        assertThat(absent.out()).contains("找不到");
    }

    @Test
    @DisplayName("★ 迁移成功、但日志里没有那几句话：部署照样往下走——挑几行给人看的 grep 不能决定成败；成功不留临时日志，失败留着并报路径")
    void theMigrationStepDoesNotDependOnLogWording() throws Exception {
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        // 分两行写，不能写成 migrate … && echo：函数出现在 && 左边时，bash 在它里面暂停 set -e，正好把要抓的 bug 藏起来
        Run ok = bash("""
                migrate chainpay:x
                echo "@after=yes"
                """, "TMPDIR", tmp.toString(), "FAKE_MIGRATE_OUTPUT", "nothing worth showing", "FAKE_MIGRATE_EXIT", "0");
        assertThat(values(ok).get("after")).isEqualTo("yes");
        assertThat(entries(tmp)).as("成功时临时日志要删掉").isZero();

        Run failed = bash("migrate chainpay:x", "TMPDIR", tmp.toString(), "FAKE_MIGRATE_OUTPUT", "boom", "FAKE_MIGRATE_EXIT", "1");
        assertThat(failed.exit()).isNotZero();
        assertThat(failed.out()).as("迁移失败时必须把原因说出来，不能被一条没匹配上的 grep 抢先退出").contains("✗ 迁移失败");
        assertThat(entries(tmp)).as("失败时日志留着给人查").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 第一次部署就没就绪：没有上一版可退——停下 app，坏镜像改叫 chainpay:failed，摘掉 current，不留一个被反复拉起的坏容器")
    void aFailedFirstDeployStopsInsteadOfLeavingABadCurrent() throws Exception {
        Run r = bash("""
                tag_release chainpay:abc1234
                roll_back_or_stop
                echo "@tags=$(tr '\\n' ' ' < "$FAKE_DIR/tags")"
                """);
        Map<String, String> t = values(r);
        assertThat(r.out()).contains("第一次部署");
        assertThat(calls()).contains("compose stop app").contains("tag chainpay:current chainpay:failed").contains("rmi chainpay:current");
        assertThat(t.get("tags")).contains("chainpay:failed").doesNotContain("chainpay:current");
    }

    @Test
    @DisplayName("有上一版时照旧回滚：交给 rollback.sh，不停机")
    void aFailedLaterDeployRollsBack() throws Exception {
        Files.writeString(fake.resolve("tags"), "chainpay:current\n");
        Path rollback = Files.createDirectories(dir.resolve("deploy")).resolve("rollback.sh");
        Files.writeString(rollback, "#!/usr/bin/env bash\necho \"@rollback=called\"\n");
        Files.setPosixFilePermissions(rollback, PosixFilePermissions.fromString("rwxr-xr-x"));
        Run r = bash("""
                tag_release chainpay:abc1234
                roll_back_or_stop
                """);
        assertThat(values(r).get("rollback")).isEqualTo("called");
        assertThat(calls()).doesNotContain("compose stop");
    }

    @Test
    @DisplayName("就绪之后顺带打出 work 组的总状态给人看（Boot 4 的字母序与「status 在前」两种键序都认得）；取不到也不影响部署")
    void theWorkGroupIsShownButNeverDecides() throws Exception {
        // Boot 4 的 JSON 按字母序：components 在前、顶层 status 在最后，部件里 details 排在 status 前（样本取自在跑的容器）
        String boot4 = "{\"components\":{\"audit\":{\"details\":{\"stale\":false,\"lastRun\":\"OK\"},\"status\":\"UP\"},"
                + "\"deposit\":{\"details\":{\"ending\":\"RETRY_LATER\"},\"status\":\"DEGRADED\"},\"redis\":{\"status\":\"UP\"}},\"status\":\"DEGRADED\"}";
        String statusFirst = "{\"status\":\"DOWN\",\"components\":{\"indexer\":{\"status\":\"DOWN\",\"details\":{\"reason\":\"x\"}}}}";
        Run r = bash("work_summary", "FAKE_WORK_JSON", boot4);
        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out()).as("取的是顶层，不是第一个部件（audit=UP）").contains("work 组：DEGRADED");
        assertThat(bash("work_summary", "FAKE_WORK_JSON", statusFirst).out()).contains("work 组：DOWN");

        Run nothing = bash("work_summary");
        assertThat(nothing.exit()).as("取不到只是少一行信息，不能让部署失败：%n%s", nothing.out()).isZero();
        assertThat(nothing.out()).contains("work 组：取不到");
    }

    private record Run(int exit, String out) {}

    /** 在临时目录里跑一段 bash：先 source deploy.sh（只有函数），PATH 最前面是假 docker；env 成对给出。stdout 与 stderr 合并。 */
    private Run bash(String script, String... env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "source \"$DEPLOY_SH\"\n" + script)
                .directory(dir.toFile()).redirectErrorStream(true);
        Map<String, String> e = pb.environment();
        e.keySet().removeIf(k -> k.startsWith("CHAINPAY_") || k.startsWith("GIT_"));   // 跑测试那个 shell 的环境不许混进来
        e.put("DEPLOY_SH", DEPLOY_SH.toString());
        e.put("FAKE_DIR", fake.toString());
        e.put("PATH", fake.resolve("bin") + ":" + System.getenv("PATH"));
        for (int i = 0; i < env.length; i += 2) {
            e.put(env[i], env[i + 1]);
        }
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("bash 没在 60 秒内结束").isTrue();
        return new Run(p.exitValue(), out);
    }

    /** 取出以 @ 开头的「名=值」行；顺带要求这段 bash 正常结束，否则把整段输出打出来方便看。 */
    private static Map<String, String> values(Run r) {
        assertThat(r.exit()).as("bash 应该正常结束，输出：%n%s", r.out()).isZero();
        Map<String, String> m = new HashMap<>();
        r.out().lines().filter(l -> l.startsWith("@") && l.contains("="))
                .forEach(l -> m.put(l.substring(1, l.indexOf('=')), l.substring(l.indexOf('=') + 1)));
        return m;
    }

    private String calls() throws IOException {
        return Files.readString(fake.resolve("calls"));
    }

    private static long entries(Path directory) throws IOException {
        try (Stream<Path> s = Files.list(directory)) {
            return s.count();
        }
    }
}
