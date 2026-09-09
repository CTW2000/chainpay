package com.chainpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
