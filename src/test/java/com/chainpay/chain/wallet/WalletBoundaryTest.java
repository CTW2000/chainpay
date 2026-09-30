package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「服务端没有私钥」不能只靠纪律：主代码里除了 wallet 包自己，谁都不许碰私钥数学与助记词。
 * 同 ControllerBoundaryTest 的做法，扫源码，匹配集合不能为空。
 */
@DisplayName("架构边界 · 主代码里只有 wallet 包能碰私钥与助记词")
class WalletBoundaryTest {

    /**
     * 主代码里只有 wallet 包能碰的东西：私钥派生、助记词、裸签名（Ecdsa）、
     * 从助记词算热钱包私钥（HotWalletDerivation / HotWalletTool）。payout 包可以用的是 HotWalletSigner——它只暴露地址与签名，不暴露密钥。
     */
    static final List<String> PRIVATE_KEY_SYMBOLS = List.of("ExtendedPrivateKey", "Bip39", "Ecdsa", "HotWalletDerivation", "HotWalletTool",
            "org.web3j.", "org.bouncycastle.");   // 第三方密码学类型只许 wallet 包碰：按包前缀挡，不按类名点名（点名表会写出库里不存在的类）

    @Test
    @DisplayName("★ wallet 包之外的主代码不出现私钥数学、助记词、裸签名的符号；且扫描到的文件不少于三十个")
    void onlyTheWalletPackageTouchesPrivateKeyMath() throws IOException {
        List<Path> others;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            others = files.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("/chain/wallet/")).toList();
        }

        assertThat(others).as("守卫的匹配集合不能是空的").hasSizeGreaterThanOrEqualTo(30);
        for (Path file : others) {
            String source = Files.readString(file);
            for (String symbol : PRIVATE_KEY_SYMBOLS) {
                assertThat(source).as(file.toString()).doesNotContain(symbol);
            }
        }
    }

    /**
     * 取舍 9：签名只有一个入口 {@code PayoutSigningGate}（签之前复核，web 写下的行不直接变成交易）。wallet 包之外，
     * 除了它，只有装配类（*Config）能提到签名器——把它递给闸口、或只取地址；装配类里不许出现签名调用。
     */
    @Test
    @DisplayName("★ wallet 包之外只有签名闸口拿着签名器签名；别的服务提都不能提签名器")
    void onlyTheSigningGateSigns() throws IOException {
        List<Path> holders;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            holders = files.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("/chain/wallet/"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("HotWalletSigner");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
        }

        assertThat(holders).as("闸口自己必须在匹配集合里").anyMatch(p -> p.endsWith("PayoutSigningGate.java"));
        for (Path file : holders) {
            String name = file.getFileName().toString();
            String source = Files.readString(file);
            if (name.equals("PayoutSigningGate.java")) {
                assertThat(source).contains("signer.sign(");
            } else {
                assertThat(name).as(file + " 提到了签名器：签名只能经 PayoutSigningGate").endsWith("Config.java");
                assertThat(source).as(file + " 是装配类，不能签名").doesNotContain(".sign(");
            }
        }
    }
}
