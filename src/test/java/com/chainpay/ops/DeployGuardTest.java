package com.chainpay.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** M6-④ · 部署脚本的守卫：八个环节按顺序都在、失败即停、密钥不回显、compose 只跑 chainpay:current、Flyway 容忍未来版本（回滚时旧代码要能起）。 */
@DisplayName("M6-④ · 部署脚本守卫")
class DeployGuardTest {

    @Test
    @DisplayName("★ deploy.sh：set -euo pipefail；构建 → 配置检查 → 镜像扫描 → 迁移 → 打标签 → 切换 → 验证 → 失败回滚，顺序不能乱")
    void deployScriptHasTheEightStepsInOrder() throws IOException {
        Path script = Path.of("deploy/deploy.sh");
        assertThat(Files.isExecutable(script)).as("要能直接跑").isTrue();
        String s = Files.readString(script);
        assertThat(s).contains("set -euo pipefail");
        List<String> steps = List.of("docker build", "check_config", "tools/image-check.sh", "--migrate-only", "docker tag", "docker compose up -d app",
                "/actuator/health/readiness", "deploy/rollback.sh");
        int last = -1;
        for (String step : steps) {
            int at = s.indexOf(step);
            assertThat(at).as("缺少环节：" + step).isGreaterThanOrEqualTo(0);
            assertThat(at).as(step + " 的顺序不对").isGreaterThan(last);
            last = at;
        }
        assertThat(s).as("密钥永远不回显").doesNotContainPattern(Pattern.compile("echo[^\\n]*\\$\\{?CHAINPAY_[A-Z_]*(PASSWORD|KEY|TOKEN|RPC_URL|XPUB)"));
    }

    @Test
    @DisplayName("rollback.sh：可执行、把 previous 换回 current 再 up、等 readiness")
    void rollbackScriptSwapsTagsAndVerifies() throws IOException {
        Path script = Path.of("deploy/rollback.sh");
        assertThat(Files.isExecutable(script)).isTrue();
        String s = Files.readString(script);
        assertThat(s).contains("set -euo pipefail").contains("chainpay:previous").contains("chainpay:current").contains("docker compose up -d app")
                .contains("/actuator/health/readiness");
    }

    @Test
    @DisplayName("★ compose 的 app 永远跑 chainpay:current（默认值），镜像由部署脚本打标签换，不靠人记环境变量")
    void composeRunsTheCurrentTag() throws IOException {
        assertThat(Files.readString(Path.of("docker-compose.yml"))).contains("image: ${CHAINPAY_IMAGE:-chainpay:current}");
    }

    @Test
    @DisplayName("★ Flyway 容忍「库里有、代码里没有」的未来版本：回滚到上一版后它才起得来（取舍 9：迁移只前进，代码兼容前一版 schema）")
    void flywayIgnoresFutureMigrations() throws IOException {
        assertThat(Files.readString(Path.of("src/main/resources/application.yml"))).contains("ignore-migration-patterns:").contains("*:future");
    }
}
