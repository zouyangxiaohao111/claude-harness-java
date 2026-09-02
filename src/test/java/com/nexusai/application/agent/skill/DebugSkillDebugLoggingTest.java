package com.nexusai.application.agent.skill;

import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拍板#9 part1（NG-CDB-1 关闭）· DebugSkillRegistrar.DebugLogging 文件型 debug log 基建测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>生产 /debug 必须读真实 debug log 文件</b>（CC debug.ts:31-57）——旧生产 wiring 注入
 *       stat=抛 NoSuchFile + tail=空 + enabler=()->true 全桩，/debug 恒 ENOENT + justEnabled 恒不渲染
 *       （EV-V-CDB-011/012/014）。本测试锁定 DebugLogging 真实 stat/tail 读真实文件、ENOENT 落 fallback、
 *       formatFileSize 对齐 CC format.ts:9-24、enabler 按真实环境（wasAlreadyLogging 语义）、
 *       settings 三路径对齐 CC settings.ts:274-296。</li>
 *   <li><b>防止退回硬编码 /tmp/debug.log</b>——DebugLogging.getDebugLogPath() 必须走
 *       CLAUDE_CODE_DEBUG_LOGS_DIR / configHome/debug/{sessionId}.txt 真实路径决议。</li>
 * </ol>
 */
class DebugSkillDebugLoggingTest {

    @AfterEach
    void cleanup() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("stat + readTail 读真实文件内容（CC debug.ts:35/:38-48）")
    void statAndReadTailReadRealFile(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("session.txt");
        Files.writeString(log, "line1\nline2\nline3\n");

        DebugSkillRegistrar.FileStat stats = DebugSkillRegistrar.DebugLogging.stat(log.toString());
        assertThat(stats.size()).isEqualTo("line1\nline2\nline3\n".length());

        // 读尾部全部（offset=0, size=size）→ 应回原内容（DebugSkillRegistrar.readLogTail 再做切 20 行）
        String tail = DebugSkillRegistrar.DebugLogging.readTail(log.toString(), 0, stats.size());
        assertThat(tail).isEqualTo("line1\nline2\nline3\n");
    }

    @Test
    @DisplayName("stat 不存在文件抛 NoSuchFileException → 落入 readLogTail ENOENT fallback")
    void statMissingFileThrowsEnoent() throws Exception {
        String missing = "C:/nonexistent-" + System.nanoTime() + "/debug.log";
        try {
            DebugSkillRegistrar.DebugLogging.stat(missing);
            throw new AssertionError("应抛 IOException (NoSuchFile)");
        } catch (java.nio.file.NoSuchFileException expected) {
            assertThat(expected).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    @DisplayName("formatFileSize 对齐 CC format.ts:9-24（bytes/KB/MB/GB 四档）")
    void formatFileSizeMatchesCc() {
        assertThat(DebugSkillRegistrar.DebugLogging.formatFileSize(100)).isEqualTo("100 bytes");
        assertThat(DebugSkillRegistrar.DebugLogging.formatFileSize(1536)).isEqualTo("1.5KB");
        assertThat(DebugSkillRegistrar.DebugLogging.formatFileSize(1024)).isEqualTo("1KB");
        assertThat(DebugSkillRegistrar.DebugLogging.formatFileSize(1024L * 1024L)).isEqualTo("1MB");
        assertThat(DebugSkillRegistrar.DebugLogging.formatFileSize((long) (1.5 * 1024 * 1024))).isEqualTo("1.5MB");
        assertThat(DebugSkillRegistrar.DebugLogging.formatFileSize((long) (2.25 * 1024 * 1024 * 1024))).isEqualTo("2.3GB");
    }

    @Test
    @DisplayName("getDebugLogPath 无覆盖时回落 nexusaiHome/debug/{sessionId}.txt（决策 D1 · CC utils/debug.ts:230-236）")
    void debugLogPathFallsBackToConfigHomeSession(@TempDir Path configHome) {
        // G5：生产默认写盘根已迁 nexusai 自有根（DebugSkillRegistrar.java:280）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        try {
            RequestContext.set("sess-abc", null);
            String path = DebugSkillRegistrar.DebugLogging.getDebugLogPath();
            assertThat(path).isEqualTo(
                Paths.get(NexusaiPaths.getAppConfigHomeDir(), "debug", "sess-abc.txt").toString());
        } finally {
            ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
            NexusaiPaths.setAppNameOverride(null);
        }
    }

    @Test
    @DisplayName("settingsPathFor 对齐 CC settings.ts:274-296 三路径（user/project/local）")
    void settingsPathForMatchesCc(@TempDir Path configHome) {
        // G5：userSettings 源已迁 nexusai 自有根（DebugSkillRegistrar.java:340）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        try {
            assertThat(DebugSkillRegistrar.DebugLogging.settingsPathFor("userSettings"))
                .isEqualTo(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json").toString());
            assertThat(DebugSkillRegistrar.DebugLogging.settingsPathFor("projectSettings"))
                .endsWith(NexusaiPaths.getProjectDirName() + java.io.File.separator + "settings.json");
            assertThat(DebugSkillRegistrar.DebugLogging.settingsPathFor("localSettings"))
                .endsWith(NexusaiPaths.getProjectDirName() + java.io.File.separator + "settings.local.json");
        } finally {
            ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
            NexusaiPaths.setAppNameOverride(null);
        }
    }
}
