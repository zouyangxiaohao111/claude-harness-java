package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionStorage 旧 transcript 留存清理测试（POJO，无 Spring）· 对齐 CC cleanup.ts:155-258
 * {@code cleanupOldSessionFiles()} + {@code unlinkIfOld()}（:134-145）+ {@code tryRmdir()}（:147-153）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>老文件删/新文件留</b>——cleanupPeriodDays 30 天语义：mtime &lt; cutoff 删、mtime &gt;= cutoff 留。
 *       孤儿 JSONL 含完整对话/worktree-state 属敏感残留，必须按 mtime 清理。</li>
 *   <li><b>=0 启动删既有</b>——cutoff=now，任何既有文件 mtime&lt;now → 全删（对齐 getCutoffDate 数学）。</li>
 *   <li><b>只删文件侧（双通道铁律）</b>——清理范围严格限定 config-home projects；项目根非 transcript
 *       扩展名文件（readme.md）不被删，不触碰 projects 外。</li>
 *   <li><b>.cast 同清理</b>——对齐 CC cleanup.ts:183 扩展名双判。</li>
 * </ol>
 */
class SessionStorageCleanupTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetOverride() {
        ClaudePaths.setConfigDirOverride(null);   // 同存复位（防御历史覆盖）
        NexusaiPaths.setAppNameOverride(null);
    }

    private Path projectsDir() {
        // G5：SessionStorage.getProjectsDir() 已迁 nexusai 自有根（SessionStorage.java:118）
        return Path.of(NexusaiPaths.getAppConfigHomeDir(), "projects");
    }

    private void writeFile(Path file, Instant mtime) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "test");
        Files.setLastModifiedTime(file, FileTime.from(mtime));
    }

    @Test
    @DisplayName("老文件删/新文件留 + 空目录回收 + 计数正确 · 对齐 CC cleanup.ts:155-258")
    void cleanup_deletesOldKeepsFresh_reclaimsEmptyDirs() throws IOException {
        // G5：清理根 = nexusai 自有根 → 唯一 appName 隔离（防删/写真实 ~/.nexusai/projects）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        Path slug = projectsDir().resolve("my-project");
        Path oldJsonl = slug.resolve("oldUuid.jsonl");
        Path freshJsonl = slug.resolve("freshUuid.jsonl");
        Path session = slug.resolve("sess-1");
        Path toolResults = session.resolve(ToolResultStorage.TOOL_RESULTS_SUBDIR);
        Path toolDir = toolResults.resolve("BashTool");
        Path oldToolTxt = toolDir.resolve("old.txt");
        Path freshToolTxt = toolDir.resolve("fresh.txt");
        Path rootOld = toolResults.resolve("rootOld.json");
        Path rootFresh = toolResults.resolve("rootFresh.json");
        writeFile(oldJsonl, Instant.now().minus(Duration.ofDays(40)));
        writeFile(freshJsonl, Instant.now());
        writeFile(oldToolTxt, Instant.now().minus(Duration.ofDays(40)));
        writeFile(freshToolTxt, Instant.now());
        writeFile(rootOld, Instant.now().minus(Duration.ofDays(40)));
        writeFile(rootFresh, Instant.now());
        // 空会话目录 + 空项目目录：应被 tryRmdir 逐级回收
        Path emptySession = slug.resolve("empty-session");
        Files.createDirectories(emptySession);
        Path emptySlug = projectsDir().resolve("empty-project");
        Files.createDirectories(emptySlug);

        Instant cutoff = SessionStorage.getCutoffDate(30); // now - 30 天
        SessionStorage.CleanupResult result = SessionStorage.cleanupOldSessionFiles(cutoff);

        // 老三个删（项目根 jsonl + toolDir 内 txt + tool-results 根 json）
        assertThat(oldJsonl).doesNotExist();
        assertThat(oldToolTxt).doesNotExist();
        assertThat(rootOld).doesNotExist();
        // 新三个留
        assertThat(freshJsonl).exists();
        assertThat(freshToolTxt).exists();
        assertThat(rootFresh).exists();
        // 空目录被回收
        assertThat(emptySession).doesNotExist();
        assertThat(emptySlug).doesNotExist();
        // 非空目录保留（会话目录因含 tool-results 与 fresh 文件而未回收）
        assertThat(toolDir).exists();
        assertThat(toolResults).exists();
        assertThat(session).exists();
        assertThat(slug).exists();
        // 计数正确：仅 3 个老文件被删
        assertThat(result.messages()).isEqualTo(3);
        assertThat(result.errors()).isZero();
    }

    @Test
    @DisplayName("cleanupPeriodDays=0 → cutoff=now → 既有文件全删（=0 启动删既有）· 对齐 getCutoffDate 数学")
    void cleanup_zeroCutoff_deletesAllExisting() throws IOException {
        // G5：清理根 = nexusai 自有根 → 唯一 appName 隔离（防删/写真实 ~/.nexusai/projects）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        Path slug = projectsDir().resolve("p");
        Path oldJsonl = slug.resolve("a.jsonl");
        Path freshJsonl = slug.resolve("b.jsonl");
        // 老（40 天前）+ 新（1 小时前）都 < cutoff=now → 全删（无需特判分支，数学自然达成）
        writeFile(oldJsonl, Instant.now().minus(Duration.ofDays(40)));
        writeFile(freshJsonl, Instant.now().minus(Duration.ofHours(1)));

        Instant cutoff = SessionStorage.getCutoffDate(0); // now - 0 = now
        SessionStorage.CleanupResult result = SessionStorage.cleanupOldSessionFiles(cutoff);

        assertThat(oldJsonl).doesNotExist();
        assertThat(freshJsonl).doesNotExist();
        assertThat(result.messages()).isEqualTo(2);
        assertThat(result.errors()).isZero();
        // slug 目录因被清空而回收
        assertThat(slug).doesNotExist();
    }

    @Test
    @DisplayName(".cast 录制文件同样按 mtime 清理 · 对齐 CC cleanup.ts:183 扩展名双判")
    void cleanup_castFilesByMtime() throws IOException {
        // G5：清理根 = nexusai 自有根 → 唯一 appName 隔离（防删/写真实 ~/.nexusai/projects）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        Path slug = projectsDir().resolve("cast-proj");
        Path oldCast = slug.resolve("old.cast");
        Path freshCast = slug.resolve("fresh.cast");
        writeFile(oldCast, Instant.now().minus(Duration.ofDays(40)));
        writeFile(freshCast, Instant.now());

        SessionStorage.CleanupResult result = SessionStorage.cleanupOldSessionFiles(
            SessionStorage.getCutoffDate(30));

        assertThat(oldCast).doesNotExist();
        assertThat(freshCast).exists();
        assertThat(result.messages()).isEqualTo(1);
    }

    @Test
    @DisplayName("项目根非 transcript 文件（非 .jsonl/.cast）不被删（双通道边界）· 对齐 CC cleanup.ts:183")
    void cleanup_ignoresNonTranscriptFiles() throws IOException {
        // G5：清理根 = nexusai 自有根 → 唯一 appName 隔离（防删/写真实 ~/.nexusai/projects）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        Path slug = projectsDir().resolve("p");
        Path readme = slug.resolve("readme.md"); // 非 transcript 扩展名
        writeFile(readme, Instant.now().minus(Duration.ofDays(40)));

        SessionStorage.CleanupResult result = SessionStorage.cleanupOldSessionFiles(
            SessionStorage.getCutoffDate(30));

        assertThat(readme).exists(); // 扩展名过滤 → 不删（即便很老）
        assertThat(result.messages()).isZero();
    }

    @Test
    @DisplayName("projectsDir 不存在 → 返回空结果（对齐 CC readdir 失败 return result）· 不抛")
    void cleanup_missingProjectsDir_returnsEmpty() throws IOException {
        // G5：清理根 = nexusai 自有根 → 唯一 appName 隔离（防删/写真实 ~/.nexusai/projects）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        // projectsDir 从未创建
        SessionStorage.CleanupResult result = SessionStorage.cleanupOldSessionFiles(
            SessionStorage.getCutoffDate(30));
        assertThat(result.messages()).isZero();
        assertThat(result.errors()).isZero();
    }
}
