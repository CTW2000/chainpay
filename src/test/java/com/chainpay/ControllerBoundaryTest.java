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
 *
 * <p>不引 ArchUnit：为一条规则背一个依赖，且它对 Java 25 的类文件支持还要碰运气。
 * 扫源码就够——这条规则的形状是「某个包里不出现某个字符串」。守卫的匹配集合不能为空（质询模板 5.10）：
 * 先断言真的找到了控制器，再断言它们干净。
 */
@DisplayName("架构边界 · controller 包不得引用 asSystem")
class ControllerBoundaryTest {

    @Test
    @DisplayName("★ 每个 controller 包里的源码都不含 asSystem(；且扫描到的控制器不少于三个")
    void controllersNeverEscalateToSystemScope() throws IOException {
        List<Path> controllers;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            controllers = files
                    .filter(p -> p.toString().endsWith(".java") && p.toString().contains("/controller/"))
                    .toList();
        }

        assertThat(controllers).as("守卫的匹配集合不能是空的").hasSizeGreaterThanOrEqualTo(3);
        for (Path controller : controllers) {
            assertThat(Files.readString(controller)).as(controller.toString()).doesNotContain("asSystem(");
        }
    }
}
