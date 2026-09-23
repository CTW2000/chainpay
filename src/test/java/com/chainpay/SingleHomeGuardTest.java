package com.chainpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 收口守卫（CLAUDE.md「收口按概念，不按层」）：一个事实只在它的家里写一份，这里一条规则守一个家。
 *
 * <p>光有一个收口的文件挡不住下一个人再抄一份，而抄出来的副本常常写得不一样（金额写成字符串，1 wei 可能成了 {@code 1E-18}）。
 * 扫源码、不引 ArchUnit（同 {@code ControllerBoundaryTest}）；每条规则先断言家里确实有它（匹配集合不能为空），
 * 再断言别处一个都没有。以后再收口一个概念，就在这里加一条。
 */
@DisplayName("收口守卫 · 一个概念一个家")
class SingleHomeGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/chainpay");

    @Test
    @DisplayName("★ 地址的形状（0x 加 40 位十六进制）只写在 EthAddress —— 注解用 SHAPE，代码用 isWellFormed")
    void addressShapeLivesOnlyInEthAddress() throws IOException {
        assertOnlyHomeContains("{40}", "chain/wallet/EthAddress.java");
    }

    @Test
    @DisplayName("★ 金额写成字符串只经 LedgerAmounts.text —— 别处不直接 toPlainString，SQL 里也不 ::text")
    void amountsBecomeTextOnlyInLedgerAmounts() throws IOException {
        assertOnlyHomeContains("toPlainString()", "ledger/service/LedgerAmounts.java");
        // SQL 的 ::text 是同一件事的另一种写法（输出碰巧一样时，只有这条规则抓得住它）：
        // 金额列在 SQL 里原样取出，交给 LedgerAmounts.text。只认得出 ::text 这一种写法，CAST(… AS text) 之类要靠评审
        Pattern castToText = Pattern.compile("(?i)\\w*(amount|balance|max)\\w*::text");
        try (Stream<Path> files = Files.walk(MAIN)) {
            assertThat(files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> castToText.matcher(read(p)).find())
                    .map(MAIN::relativize).toList())
                    .as("在 SQL 里把金额 ::text 的源文件").isEmpty();
        }
    }

    private static void assertOnlyHomeContains(String marker, String home) throws IOException {
        List<Path> holders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            holders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains(marker))
                    .map(MAIN::relativize)
                    .sorted()
                    .toList();
        }
        assertThat(holders).as("家里必须有「%s」（守卫的匹配集合不能为空）", marker).contains(Path.of(home));
        assertThat(holders).as("「%s」只许出现在 %s", marker, home).containsExactly(Path.of(home));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
