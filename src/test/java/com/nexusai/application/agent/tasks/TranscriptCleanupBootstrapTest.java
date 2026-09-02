package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TranscriptCleanupBootstrap 静态方法测试（guard + settings 读取，不触发真实调度）·
 * 对齐 CC cleanup.ts:575-585 guard + backgroundHousekeeping.ts:80-83 一次性触发。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>guard 防误删</b>——CC {@code cleanupOldMessageFilesInBackground}：settings 有校验错误且显式设置
 *       {@code cleanupPeriodDays} → 跳过（防用户意图漂移时用默认 30 天误删）。Java 近似：键存在但值非法
 *       （非整数/负数）→ skip。</li>
 *   <li><b>=0 语义</b>——显式 0 → 返回 0 → cutoff=now → 既有文件全删（全删行为由 SessionStorageCleanupTest 锚定）。</li>
 *   <li><b>缺省</b>——settings.json 缺失 / 键缺失 → null → run() 兜底 DEFAULT_CLEANUP_PERIOD_DAYS=30。</li>
 * </ol>
 */
class TranscriptCleanupBootstrapTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetOverride() {
        ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
        NexusaiPaths.setAppNameOverride(null);
    }

    private void writeSettings(String json) throws Exception {
        // G5：生产读 nexusai user settings（TranscriptCleanupBootstrap.java:94）→ 写 nexusai 自有根
        Path file = Path.of(NexusaiPaths.getAppConfigHomeDir(), "settings.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    @DisplayName("无 settings.json / 键缺失 → readCleanupPeriodDays 返回 null（缺省 30 由 run() 兜底）")
    void readCleanupPeriodDays_missingKey_returnsNull() throws Exception {
        // G5：唯一 appName 隔离（防读/写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        assertThat(TranscriptCleanupBootstrap.readCleanupPeriodDays()).isNull();

        writeSettings("{\"theme\": \"dark\"}");
        assertThat(TranscriptCleanupBootstrap.readCleanupPeriodDays()).isNull();
    }

    @Test
    @DisplayName("显式 30 → 30；显式 0 → 0（=0 语义：cutoff=now）")
    void readCleanupPeriodDays_explicitValues() throws Exception {
        // G5：唯一 appName 隔离（防读/写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        writeSettings("{\"cleanupPeriodDays\": 30}");
        assertThat(TranscriptCleanupBootstrap.readCleanupPeriodDays()).isEqualTo(30);

        writeSettings("{\"cleanupPeriodDays\": 0}");
        assertThat(TranscriptCleanupBootstrap.readCleanupPeriodDays()).isEqualTo(0);
    }

    @Test
    @DisplayName("guard：null/合法值 → false；键存在但值非法（\"abc\"/-5）→ true（跳过清理）· 对齐 cleanup.ts:575-585")
    void skipDueToInvalidExplicitSetting_guard() throws Exception {
        // G5：唯一 appName 隔离（防读/写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        // 未配置 → 不跳过
        assertThat(TranscriptCleanupBootstrap.skipDueToInvalidExplicitSetting(null)).isFalse();

        // 显式合法值 → 不跳过
        writeSettings("{\"cleanupPeriodDays\": 30}");
        assertThat(TranscriptCleanupBootstrap.skipDueToInvalidExplicitSetting(
            TranscriptCleanupBootstrap.readCleanupPeriodDays())).isFalse();
        writeSettings("{\"cleanupPeriodDays\": 0}");
        assertThat(TranscriptCleanupBootstrap.skipDueToInvalidExplicitSetting(
            TranscriptCleanupBootstrap.readCleanupPeriodDays())).isFalse();

        // 显式非整数 "abc" → 非法 → 跳过
        writeSettings("{\"cleanupPeriodDays\": \"abc\"}");
        Integer abc = TranscriptCleanupBootstrap.readCleanupPeriodDays();
        assertThat(abc).isNotNull();
        assertThat(TranscriptCleanupBootstrap.skipDueToInvalidExplicitSetting(abc)).isTrue();

        // 显式负数 -5 → 非法 → 跳过
        writeSettings("{\"cleanupPeriodDays\": -5}");
        Integer neg = TranscriptCleanupBootstrap.readCleanupPeriodDays();
        assertThat(neg).isNotNull();
        assertThat(TranscriptCleanupBootstrap.skipDueToInvalidExplicitSetting(neg)).isTrue();
    }
}
