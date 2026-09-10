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
     * 主代码里只有 wallet 包能碰的东西：私钥派生、助记词、裸签名。M4-① 起多了 Ecdsa（裸私钥签名）、
     * HotWalletDerivation / HotWalletTool（从助记词算热钱包私钥）。payout 包可以用的是 HotWalletSigner——它只暴露地址与签名，不暴露密钥。
     */
    static final List<String> PRIVATE_KEY_SYMBOLS = List.of("ExtendedPrivateKey", "Bip39", "Ecdsa", "HotWalletDerivation", "HotWalletTool",
            "org.web3j.crypto.ECKeyPair", "org.web3j.crypto.Credentials", "org.web3j.crypto.Sign", "org.web3j.crypto.Bip32ECKeyPair", "org.web3j.crypto.WalletUtils");

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
}
