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
 * 控制器不得拿到系统身份：系统权限是连接身份，控制器拿到 {@code SystemLedger} 就等于拿到全库。
 * {@code asSystem(}（用会话变量放行整库）已删除，仍扫它，防止它以任何形式回来。
 *
 * <p>不引 ArchUnit：为几条规则背一个依赖，且它对 Java 25 的类文件支持还要碰运气。
 * 扫源码就够——规则的形状是「某个包里不出现某个字符串」。守卫的匹配集合不能为空：
 * 先断言真的找到了控制器，再断言它们干净，否则一个都没扫到也是绿的。
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
            assertThat(source).as(controller.toString()).doesNotContain("SystemLedger");
            // 系统池、系统事务管理器、系统连接上的 JdbcClient 与账本都是容器里的 bean（限定名 system）：注入它们或在事务上点它们的名字，同样等于拿到全库
            assertThat(source).as(controller.toString())
                    .doesNotContain("Qualifier(\"system\")")
                    .doesNotContain("Transactional(\"system\")")
                    .doesNotContain("systemDataSource")
                    .doesNotContain("systemTransactionManager")
                    .doesNotContain("systemJdbcClient")
                    .doesNotContain("systemLedgerService");
        }
    }

    /**
     * HTTP 入口不得自己挑账本的借贷双方，也不得指定业务类型。
     *
     * <p>只问「这账户是不是你的」挡不住：商户的冻结账户<b>确实是商户的</b>。让请求体给出账户与 {@code code}，
     * 商户就能自己拼一笔「冻结 → 可用」的 {@code WITHDRAWAL_REVERSE}，把提现途中冻着的钱捞回可用余额，链上的币照样发出去。
     * 真实业务各走专用流程：入账与提现三笔流的借贷双方都由服务端定，商户填不了。
     */
    @Test
    @DisplayName("★ 没有任何 controller 能自己选借贷双方或业务类型（TransferCode / ledger.transfer）")
    void controllersNeverChooseLedgerAccountsThemselves() throws IOException {
        List<Path> controllers;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            controllers = files
                    .filter(p -> p.toString().endsWith(".java") && p.toString().contains("/controller/"))
                    .toList();
        }

        assertThat(controllers).as("守卫的匹配集合不能是空的").hasSizeGreaterThanOrEqualTo(3);
        for (Path controller : controllers) {
            String source = Files.readString(controller);
            assertThat(source).as(controller.toString())
                    .doesNotContain("TransferCode")
                    .doesNotContain("ledger.transfer(")
                    .doesNotContain("new TransferCommand");
        }
    }

    /**
     * 「校验放在请求记录，坏数据到不了 service」：漏一个 @Valid，null 字段就一路走到 NPE 变成 500。
     * 只写在注释里的规矩会有人漏，由这条测试强制。
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
     * 控制器里每一个 {@code new BigDecimal(x.y())}，{@code y} 都必须带 {@code LedgerAmounts} 里的某个正则（金额的规矩只在那里写一份）。
     * 只有 {@code @NotBlank} 的金额拿到什么就解析什么：{@code 1E-999999999}（12 个字符）一个请求就能让进程内存耗尽退出，
     * 一百万位的普通写法光解析就要 11 秒。
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
