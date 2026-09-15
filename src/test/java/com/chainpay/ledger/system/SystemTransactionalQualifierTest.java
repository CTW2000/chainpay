package com.chainpay.ledger.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 2026-09-15 起容器里有两个事务管理器：主池的（默认候选）与系统池的（限定名 system）。
 * 没写限定名的 {@code @Transactional} 一律落在主池上——放在一个用系统身份干活的类里，就是「以为在系统事务里，其实系统连接上的 SQL 各自提交」，
 * 不报错。所以凡是在代码里（不算注释）用到 SystemLedger 的源文件，其中的 {@code @Transactional} 必须写明限定名：
 * 写 system，或者明确写主池的名字，逼作者当场想清楚用哪个池（CLAUDE.md「薄实现 vs Boot 官方双数据源」的换法里定好的这条）。
 * 扫源码就够，规则的形状是「某类文件里某种注解必须带参数」；匹配集合不能为空。
 */
@DisplayName("系统侧的 @Transactional 必须写明限定名")
class SystemTransactionalQualifierTest {

    @Test
    @DisplayName("★ 在代码里用到 SystemLedger 的源文件，每个 @Transactional 都写明了限定名；且扫描到的文件不少于五个")
    void transactionalNextToTheSystemLedgerNamesItsManager() throws IOException {
        List<Path> systemSide;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            systemSide = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("SystemLedger.java"))
                    .filter(SystemTransactionalQualifierTest::usesSystemLedgerInCode)
                    .toList();
        }
        assertThat(systemSide).as("守卫的匹配集合不能是空的").hasSizeGreaterThanOrEqualTo(5);

        List<String> violations = new ArrayList<>();
        for (Path file : systemSide) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("@Transactional") && !namesAManager(line)) {
                    violations.add(file + ":" + (i + 1) + "  " + line);
                }
            }
        }
        assertThat(violations).as("没写限定名的 @Transactional 会静默落在主池上").isEmpty();
    }

    @Test
    @DisplayName("判定本身：不带参数、只带传播方式或只读的算没写；带字符串、常量或 transactionManager 属性的算写了")
    void theRuleItselfTellsNamedFromUnnamed() {
        assertThat(namesAManager("@Transactional")).isFalse();
        assertThat(namesAManager("@Transactional(propagation = Propagation.REQUIRES_NEW)")).isFalse();
        assertThat(namesAManager("@Transactional(readOnly = true)")).isFalse();
        assertThat(namesAManager("@Transactional(\"system\")")).isTrue();
        assertThat(namesAManager("@Transactional(SystemLedger.QUALIFIER)")).isTrue();
        assertThat(namesAManager("@Transactional(transactionManager = \"transactionManager\")")).isTrue();
    }

    /** 写了字符串（"system" 或主池的名字）、常量、或 transactionManager / value 属性，都算写明了。 */
    static boolean namesAManager(String annotationLine) {
        return annotationLine.contains("\"") || annotationLine.contains("QUALIFIER")
                || annotationLine.contains("transactionManager") || annotationLine.contains("value");
    }

    /** 只数代码行：注释里提到 SystemLedger 的文件（比如解释「为什么不用它」）不算系统侧。 */
    private static boolean usesSystemLedgerInCode(Path file) {
        try {
            return Files.readAllLines(file).stream().map(String::strip)
                    .filter(l -> !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("/*"))
                    .anyMatch(l -> l.contains("SystemLedger"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
