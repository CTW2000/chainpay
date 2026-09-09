package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 扫描补丁（2026-09-09）：没有 Console（管道、IDE 控制台、CI）时此前退回明文 readLine，违反「工具不回显」。
 * 这条测试不能真的碰 System.in——Surefire 用它做进程间指令通道——所以只测「没有终端就拒绝」这个分支。
 */
@DisplayName("扫描补丁 · 离线 xpub 工具没有终端就拒绝，绝不回显助记词")
class XpubToolTest {

    @Test
    @DisplayName("★ Console 为空：拒绝运行，而不是退回明文读行")
    void refusesToReadWithoutAConsole() {
        assertThatThrownBy(() -> XpubTool.readMnemonic(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终端");
    }
}
