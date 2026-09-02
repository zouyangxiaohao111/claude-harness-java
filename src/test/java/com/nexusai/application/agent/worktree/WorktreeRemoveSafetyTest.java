package com.nexusai.application.agent.worktree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [gap3-discarded] WorktreeService.removeWorktree 数据丢失安全闸 · 真实 git 子进程集成测试。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: 旧实现 countChanges 用 {@code git log @{push}..HEAD
 * --oneline} 计数未推送 commit。当 worktree 分支无 upstream 时该命令恒 exit 128，而
 * {@link WorktreeService#countNonEmptyLines} 把失败静默归 0 → {@code hasAny()=false} →
 * removeWorktree 的 {@code git branch -D} 会静默删掉用户未推送的 commit（数据永久丢失）。
 * CC 用 {@code git rev-list --count <base>..HEAD}（ExitWorktreeTool.ts:100-106），并在缺基线 /
 * rev-list 失败时 fail-closed（:94-98 / :107-109 return null → 拒绝）。本测试锁定：
 * 有未推送 commit 时 removeWorktree(false) 必须拒绝；干净 worktree 不误拒；discardChanges=true 强制。
 *
 * @see WorktreeService#createWorktree
 * @see WorktreeService#countChanges
 * @see WorktreeService#removeWorktree
 */
@DisplayName("[gap3-discarded] removeWorktree 未推送 commit 安全闸")
class WorktreeRemoveSafetyTest {

    @TempDir
    Path tmp;

    private WorktreeService newService() {
        // eventLog=null 走 debug 分支，不写 events.jsonl（纯内存登记，测试不落盘）
        return new WorktreeService((WorktreeEventLog) null);
    }

    /** 初始化 git 仓库：init + config user + 初 commit（空 repo 无法 git worktree add） */
    private void initRepo(Path repo) {
        run(repo, "init");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test User");
        writeFile(repo.resolve("README.md"), "hello");
        run(repo, "add", ".");
        run(repo, "commit", "-m", "initial commit");
    }

    /** 在 worktree 内新增一个 commit（模拟用户未推送的产物） */
    private void commitInWorktree(Path worktreePath, String fileName) {
        writeFile(worktreePath.resolve(fileName), "work done");
        run(worktreePath, "add", ".");
        run(worktreePath, "commit", "-m", "worktree commit " + fileName);
    }

    @Test
    @DisplayName("RED 核心：worktree 有未推送 commit → removeWorktree(false) 抛 WorktreeException（防 git branch -D 数据丢失）")
    void remove_refuses_whenUnpushedCommitsExist() {
        // WHY: 旧 @{push} 在无 upstream 分支 exit 128 → 静默归 0 → hasAny()=false → branch -D 删 commit。
        initRepo(tmp);
        WorktreeService service = newService();

        service.createWorktree(tmp, "feature-x");
        Path worktreePath = WorktreePaths.worktreePathFor(tmp, "feature-x");
        commitInWorktree(worktreePath, "out.txt");

        assertThatThrownBy(() -> service.removeWorktree(tmp, "feature-x", false))
            .as("有未推送 commit 时必须拒绝删除（安全闸 fail-closed）")
            .isInstanceOf(WorktreeService.WorktreeException.class);
    }

    @Test
    @DisplayName("GREEN 伴生：干净 worktree → removeWorktree(false) 不抛（不误拒）")
    void remove_succeeds_whenClean() {
        // WHY: 基线存在且 rev-list --count = 0 → hasAny()=false，必须正常删除（不得 fail-closed 误伤）。
        initRepo(tmp);
        WorktreeService service = newService();

        service.createWorktree(tmp, "clean-slug");

        assertThatCode(() -> service.removeWorktree(tmp, "clean-slug", false))
            .as("无变更 worktree 应正常删除")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GREEN 伴生：有 commit + discardChanges=true → removeWorktree 成功（用户明确确认强制）")
    void remove_succeeds_whenDiscardChangesTrue() {
        // WHY: discardChanges=true 是用户显式确认，绕过安全闸对齐 CC discard_changes 语义。
        initRepo(tmp);
        WorktreeService service = newService();

        service.createWorktree(tmp, "force-slug");
        Path worktreePath = WorktreePaths.worktreePathFor(tmp, "force-slug");
        commitInWorktree(worktreePath, "out.txt");

        assertThatCode(() -> service.removeWorktree(tmp, "force-slug", true))
            .as("discardChanges=true 应强制删除")
            .doesNotThrowAnyException();
    }

    // ── 测试辅助（复用生产 GitCommandRunner 同包路径，真实 git 子进程） ──

    private static GitCommandRunner.Result run(Path cwd, String... args) {
        GitCommandRunner.Result r = GitCommandRunner.run(cwd, args);
        if (!r.isSuccess()) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " failed (exit=" + r.exitCode() + "): " + r.stderr());
        }
        return r;
    }

    private static void writeFile(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (Exception e) {
            throw new IllegalStateException("write failed: " + file, e);
        }
    }
}
