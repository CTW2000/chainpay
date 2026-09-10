package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LEARNING-PATH 的验收标准：「私钥不在代码里、不在镜像里、不在日志里（写个检查脚本）」。
 * 脚本是 tools/check-secrets.sh；这里证明它在当前仓库上过，且真的能抓住埋进去的私钥与助记词，同时不回显命中的值。
 */
@DisplayName("M4-① · 私钥检查脚本")
class SecretScanTest {

    static final Path SCRIPT = Path.of("tools/check-secrets.sh");

    record Result(int exitCode, String output) {}

    @Test
    @DisplayName("★ 当前仓库干净：退出码 0")
    void theRepositoryIsClean() throws Exception {
        Result result = run();

        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    @Test
    @DisplayName("★ 埋一把私钥进配置文件：退出码 1，指名文件，但不回显整把密钥")
    void aPlantedPrivateKeyIsReported() throws Exception {
        Path dir = Files.createTempDirectory("secret-scan");
        String key = "0x" + "ab".repeat(32);
        Files.writeString(dir.resolve("app.env"), "CHAINPAY_PAYOUT_HOT_WALLET_KEY=" + key + "\n", StandardCharsets.UTF_8);

        Result result = run(dir.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("app.env");
        assertThat(result.output()).as("命中的值不能整个回显到终端与日志").doesNotContain("ab".repeat(16));
    }

    @Test
    @DisplayName("★ 助记词形状的一整行也报（12 或 24 个小写单词）")
    void aMnemonicLineIsReported() throws Exception {
        Path dir = Files.createTempDirectory("secret-scan");
        Files.writeString(dir.resolve("notes.txt"), "备份\nzebra apple ocean rocket window garden silver planet music forest candle bridge\n", StandardCharsets.UTF_8);

        assertThat(run(dir.toString()).exitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("区块哈希、交易哈希不算：64 位十六进制只在带 key / private / secret 字样的行里才是私钥形态")
    void hashesWithoutKeyContextAreNotReported() throws Exception {
        Path dir = Files.createTempDirectory("secret-scan");
        Files.writeString(dir.resolve("runbook.md"), "块 11660486 的哈希 0x329a4afbb1c8a940af16f6f5c9abba84b2f6842e18c82a280f8627719df719d6\n", StandardCharsets.UTF_8);

        assertThat(run(dir.toString()).exitCode()).isZero();
    }

    static Result run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new Result(exit, output);
    }
}
