package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.plugin.GitProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GitDiffFetcher} 单测 · 对齐 CC {@code fetchSingleFileGitDiff}
 * （utils/gitDiff.ts:405-441）+ {@code getDiffRef}（:486-495）+ synthetic diff（:479-520）。
 *
 * <p>WHY（规则九）：gitDiff 门控默认关（生产不触发），必须单测强制开 gate + 桩 git 执行器，
 * 覆盖 tracked（merge-base 基准 diff 解析）与 untracked（synthetic diff）两分支，
 * 防止"门控关 = 永远不被验证"的死代码漂移。
 */
@DisplayName("GitDiffFetcher · 门控 + tracked/untracked 两分支 + raw diff 解析（CC utils/gitDiff.ts）")
class GitDiffFetcherTest {

    private static final String RAW_DIFF =
        "diff --git a/src/file.ts b/src/file.ts\n" +
        "index 1111111..2222222 100644\n" +
        "--- a/src/file.ts\n" +
        "+++ b/src/file.ts\n" +
        "@@ -1,3 +1,3 @@\n" +
        " line1\n" +
        "-old\n" +
        "+new\n" +
        " line3\n";

    @BeforeEach
    @AfterEach
    void cleanState() {
        System.clearProperty("CLAUDE_CODE_REMOTE");
        System.clearProperty("tengu.quartz.lantern");
        GitDiffFetcher.clearExecutor();
    }

    @Test
    @DisplayName("门控默认关（无 env/property）→ isEnabled=false —— 生产不触发")
    void gateDisabledByDefault() {
        System.setProperty("CLAUDE_CODE_REMOTE", "false");
        System.setProperty("tengu.quartz.lantern", "false");
        assertThat(GitDiffFetcher.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("门控开（env truthy + property truthy）→ isEnabled=true；缺一即关")
    void gateEnabledRequiresBothFlags() {
        System.setProperty("CLAUDE_CODE_REMOTE", "true");
        System.setProperty("tengu.quartz.lantern", "true");
        assertThat(GitDiffFetcher.isEnabled()).isTrue();

        System.setProperty("CLAUDE_CODE_REMOTE", "true");
        System.setProperty("tengu.quartz.lantern", "false");
        assertThat(GitDiffFetcher.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("已跟踪文件 → merge-base 基准 diff，解析出 additions/deletions/patch")
    void trackedFileParsesMergeBaseDiff(@TempDir Path workspace) throws Exception {
        System.setProperty("CLAUDE_CODE_REMOTE", "true");
        System.setProperty("tengu.quartz.lantern", "true");
        Files.createDirectories(workspace.resolve(".git"));
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/file.ts"), "line1\nold\nline3\n");

        GitDiffFetcher.setExecutor((fullArgs, cwd, env, timeoutMs) -> {
            // fullArgs = [git, --no-optional-locks, <cmd>, ...]
            String cmd = fullArgs.get(2);
            switch (cmd) {
                case "ls-files":
                    return new GitProcessRunner.Result(0, "src/file.ts\n", "", null);
                case "symbolic-ref":
                case "config":
                    return new GitProcessRunner.Result(1, "", "", "not found");
                case "merge-base":
                    return new GitProcessRunner.Result(0, "abc123\n", "", null);
                case "diff":
                    return new GitProcessRunner.Result(0, RAW_DIFF, "", null);
                default:
                    throw new IllegalStateException("Unexpected git cmd: " + cmd);
            }
        });

        ToolUseDiff diff = GitDiffFetcher.fetch(workspace.resolve("src/file.ts"));

        assertThat(diff).isNotNull();
        // CC gitDiffSchema（types.ts:46-60）
        assertThat(diff.filename()).isEqualTo("src/file.ts");
        assertThat(diff.status()).isEqualTo("modified");
        assertThat(diff.additions()).isEqualTo(1);
        assertThat(diff.deletions()).isEqualTo(1);
        assertThat(diff.changes()).isEqualTo(2);
        assertThat(diff.patch()).contains("@@ -1,3 +1,3 @@");
    }

    @Test
    @DisplayName("未跟踪文件 → synthetic diff：@@ -0,0 +1,N @@ + 全行 '+'，status=added")
    void untrackedFileGeneratesSyntheticDiff(@TempDir Path workspace) throws Exception {
        System.setProperty("CLAUDE_CODE_REMOTE", "true");
        System.setProperty("tengu.quartz.lantern", "true");
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve("fresh.txt"), "a\nb\n");

        GitDiffFetcher.setExecutor((fullArgs, cwd, env, timeoutMs) -> {
            String cmd = fullArgs.get(2);
            if ("ls-files".equals(cmd)) {
                return new GitProcessRunner.Result(1, "", "untracked", null);
            }
            throw new IllegalStateException("Unexpected git cmd: " + cmd);
        });

        ToolUseDiff diff = GitDiffFetcher.fetch(workspace.resolve("fresh.txt"));

        assertThat(diff).isNotNull();
        assertThat(diff.status()).isEqualTo("added");
        assertThat(diff.filename()).isEqualTo("fresh.txt");
        assertThat(diff.additions()).isEqualTo(2);
        assertThat(diff.deletions()).isZero();
        assertThat(diff.patch()).isEqualTo("@@ -0,0 +1,2 @@\n+a\n+b");
    }

    @Test
    @DisplayName("非 git 仓库 → findGitRoot 找不到 .git → 返回 null")
    void nonGitRepoReturnsNull(@TempDir Path workspace) throws Exception {
        System.setProperty("CLAUDE_CODE_REMOTE", "true");
        System.setProperty("tengu.quartz.lantern", "true");
        Files.writeString(workspace.resolve("f.txt"), "x\n");

        GitDiffFetcher.setExecutor((fullArgs, cwd, env, timeoutMs) -> {
            throw new IllegalStateException("非 git 仓库不应执行 git 命令");
        });

        assertThat(GitDiffFetcher.fetch(workspace.resolve("f.txt"))).isNull();
    }

    @Test
    @DisplayName("raw diff 解析：排除 +++/--- 文件头行，统计 + / − 行")
    void parseRawDiffCountsExcludingFileHeaders() {
        ToolUseDiff diff = GitDiffFetcher.parseRawDiffToToolUseDiff(
            "src/file.ts", RAW_DIFF, "modified");
        assertThat(diff.additions()).isEqualTo(1);
        assertThat(diff.deletions()).isEqualTo(1);
        assertThat(diff.patch()).startsWith("@@ -1,3 +1,3 @@");
    }
}
