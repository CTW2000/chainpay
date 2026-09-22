package com.chainpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CLAUDE.md 的承诺：{@code TenantScope.asSystem} 是用会话变量模拟的权宜之计，靠「控制器不得调它」这条纪律守着，
 * 「接口多起来了，先用 ArchUnit 断言 controller 包不得引用 asSystem」。接口已经多起来了（三个 controller 包）。
 * M4-⓪（2026-09-09）asSystem 已删除；这里仍扫 {@code asSystem(}，防止它以任何形式回来。
 * M3-⓪ 之后同一条规则也管 {@code SystemLedger}：系统权限变成了连接身份，控制器拿到它等于拿到全库。
 *
 * <p>不引 ArchUnit：为一条规则背一个依赖，且它对 Java 25 的类文件支持还要碰运气。
 * 扫源码就够——这条规则的形状是「某个包里不出现某个字符串」。守卫的匹配集合不能为空（质询模板 5.10）：
 * 先断言真的找到了控制器，再断言它们干净。
 */
@DisplayName("架构边界 · controller 包不得引用 asSystem 与 SystemLedger")
class ControllerBoundaryTest {

    @Test
    @DisplayName("★ 每个 controller 包里的源码都不含 asSystem( 与 SystemLedger；且扫描到的控制器不少于三个")
    void controllersNeverEscalateToSystemScope() throws IOException {
        List<Path> controllers;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            controllers = files
                    .filter(p -> p.toString().endsWith(".java") && p.toString().contains("/controller/"))
                    .toList();
        }

        assertThat(controllers).as("守卫的匹配集合不能是空的").hasSizeGreaterThanOrEqualTo(3);
        for (Path controller : controllers) {
            String source = Files.readString(controller);
            assertThat(source).as(controller.toString()).doesNotContain("asSystem(");
            // M3-⓪ 起系统权限是连接身份：拿到 SystemLedger 就拿到了全库，HTTP 层永远不该持有它
            assertThat(source).as(controller.toString()).doesNotContain("SystemLedger");
            // 2026-09-15 起系统池与系统事务管理器是容器里的 bean（限定名 system）：注入它们或在事务上点它们的名字，同样等于拿到全库
            assertThat(source).as(controller.toString())
                    .doesNotContain("Qualifier(\"system\")")
                    .doesNotContain("Transactional(\"system\")")
                    .doesNotContain("systemDataSource")
                    .doesNotContain("systemTransactionManager");
        }
    }

    /**
     * 扫描补丁（2026-09-09）：「校验放在请求记录，坏数据到不了 service」这条规矩此前只写在 AdminController 的注释里，
     * 转账接口没跟上，null 字段一路走到 NPE 变成 500。规矩从此由这条测试强制：每个 @RequestBody 参数都必须带 @Valid。
     */
    @Test
    @DisplayName("★ 每个 @RequestBody 参数都带 @Valid；且扫描到的 @RequestBody 不少于三个")
    void everyRequestBodyIsValidated() throws IOException {
        int bodies = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path controller : files.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/controller/")).toList()) {
                String source = Files.readString(controller);
                int total = source.split("@RequestBody", -1).length - 1;
                int validated = source.split("@Valid @RequestBody", -1).length - 1
                        + source.split("@RequestBody @Valid", -1).length - 1;
                assertThat(validated).as(controller + "：@RequestBody 有 %d 处，带 @Valid 的只有 %d 处", total, validated).isEqualTo(total);
                bodies += total;
            }
        }
        assertThat(bodies).as("守卫的匹配集合不能是空的").isGreaterThanOrEqualTo(3);
    }

    /**
     * 2026-09-21：转账接口的金额只有 {@code @NotBlank}，控制器拿到什么就 {@code new BigDecimal} 什么——
     * {@code 1E-999999999}（12 个字符）一个请求让进程内存耗尽退出，一百万位的普通写法光解析就要 11 秒。
     * 提现与限额接口一直有格式正则，只有转账这一处漏了，所以规矩由这条测试强制：控制器里每一个
     * {@code new BigDecimal(x.y())}，{@code y} 都必须带 {@code LedgerAmounts} 里的某个正则（金额的规矩只在那里写一份）。
     */
    @Test
    @DisplayName("★ 控制器解析的每个金额都带 LedgerAmounts 的格式校验；且扫描到的解析点不少于三个")
    void everyAmountAControllerParsesIsFormatChecked() throws IOException {
        Pattern parse = Pattern.compile("new BigDecimal\\(\\s*\\w+\\.(\\w+)\\(\\)\\s*\\)");
        int sites = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path controller : files.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/controller/")).toList()) {
                String source = Files.readString(controller);
                Matcher parsed = parse.matcher(source);
                while (parsed.find()) {
                    sites++;
                    String component = parsed.group(1);
                    // @Pattern 之后可以再跟别的注解，然后才是 String <名字>
                    Pattern declared = Pattern.compile("@Pattern\\(regexp\\s*=\\s*LedgerAmounts\\.[A-Z_]+\\)"
                            + "(\\s*@\\w+(\\([^)]*\\))?)*\\s*String\\s+" + component + "\\b");
                    assertThat(declared.matcher(source).find())
                            .as("%s：new BigDecimal(….%s()) 之前，%s 必须带 @Pattern(regexp = LedgerAmounts.…)",
                                    controller, component, component)
                            .isTrue();
                }
            }
        }
        assertThat(sites).as("守卫的匹配集合不能是空的").isGreaterThanOrEqualTo(3);
    }
}
