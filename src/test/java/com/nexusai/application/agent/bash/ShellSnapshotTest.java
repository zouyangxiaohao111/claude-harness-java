package com.nexusai.application.agent.bash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2-1 SNAPSHOT_FILE 引号化 · 对齐 CC {@code quote([snapshotFilePath])}（shellQuote.ts:267-304）。
 *
 * <p><b>WHY（规则九 · 意图验证）</b>：CC quote() 对含 {@code \} 的 Windows 路径必加引号（单引号内
 * 反斜杠为字面量）。Java {@link ShellQuoteParser#quote} 对"无空白/引号"参数原样返回（不含反斜杠
 * 判定），Windows 路径 {@code C:\Users\...}（无空白）不被引号化 → bash 解析非引号赋值剥掉全部
 * 反斜杠 → 写到垃圾路径 → {@code Files.isRegularFile} 失败 → 快照机制 win32 100% 失效。
 * 本测试锁定 {@code SNAPSHOT_FILE=} 赋值恒单引号化（Windows 反斜杠路径 + 普通 POSIX 路径都带引号）。
 */
@DisplayName("ShellSnapshot SNAPSHOT_FILE 赋值引号化（G2-1，对齐 CC shellQuote.ts:267-304）")
class ShellSnapshotTest {

    @Test
    @DisplayName("SNAPSHOT_FILE 赋值对 Windows 反斜杠路径强制单引号化（win32 快照失效修复）")
    void snapshotScript_quotesWindowsBackslashPath() {
        Path snapshotPath = Path.of("C:\\Users\\WIN\\.claude\\shell-snapshots\\snapshot-bash-123456.sh");
        String script = ShellSnapshot.getSnapshotScript(
            "C:\\nonexistent\\bash.exe", snapshotPath, false);
        String firstLine = script.lines().findFirst().orElse("");

        assertThat(firstLine)
            .as("SNAPSHOT_FILE 赋值必须以单引号开头（对齐 CC quote() 对含 \\ 路径必引号）")
            .startsWith("SNAPSHOT_FILE='");
        String value = firstLine.substring("SNAPSHOT_FILE=".length());
        assertThat(value)
            .as("赋值右值必须带引号（防 bash 剥反斜杠）")
            .startsWith("'").endsWith("'");
        // Windows 主机上 native 路径被转 POSIX（/c/...，对齐 bashProvider.ts:118-121）；
        // 非 Windows 保留原样——两者都必须被引号化。
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String expected = isWindows
            ? "'/c/Users/WIN/.claude/shell-snapshots/snapshot-bash-123456.sh'"
            : "'C:\\Users\\WIN\\.claude\\shell-snapshots\\snapshot-bash-123456.sh'";
        assertThat(value).isEqualTo(expected);
    }

    @Test
    @DisplayName("SNAPSHOT_FILE 赋值对普通 POSIX 路径也强制单引号化（force-quote，防空格/特殊字符）")
    void snapshotScript_quotesPlainPosixPath() {
        Path snapshotPath = Path.of("/tmp/claude-test-123456.sh");
        String script = ShellSnapshot.getSnapshotScript("/bin/bash", snapshotPath, false);
        String firstLine = script.lines().findFirst().orElse("");

        assertThat(firstLine)
            .as("普通 POSIX 路径也被单引号化（与未引号语义等价，但防反斜杠/空格被 bash 剥除）")
            .isEqualTo("SNAPSHOT_FILE='/tmp/claude-test-123456.sh'");
    }
}
