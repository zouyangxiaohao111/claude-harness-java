package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.hook.FileChangedWatcher;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * [H14 v3 Gap②] ExitWorktreeTool 传真实 newCwd · 对齐 CC Shell.ts:409 onCwdChangedForHooks(cwd, newCwd).
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: v2 对抗复验残留缺口② "ExitWorktreeTool 传 newCwd=null
 * （退出后 watcher 不重解析）"。CC 退出 worktree 时 cwd 切回工作区，Shell.ts:409 把真实
 * newCwd 传给 onCwdChangedForHooks → watcher 基于新 cwd 重解析监听路径。Java 端 keep/remove
 * 分支传 {@code null} → watcher 的 onCwdChangedForHooks 无法基于新 cwd 重解析，.envrc/.env
 * 监听停留在 worktree 目录。
 *
 * <p><b>本测试验证</b>: keep / remove 两个分支调用 {@code onCwdChangedForHooks} 时，
 * newCwd 必须为非 null 的真实工作区目录（当前进程 workspaceDir，即 user.dir），而非 null。
 *
 * @see ExitWorktreeTool
 * @since H14 v3 残留缺口修复
 */
@DisplayName("[H14 v3 Gap②] ExitWorktreeTool 传真实 newCwd (非 null)")
class H14V3_ExitWorktreeNewCwdTest {

    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final UUID AGENT = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearCwd(SESSION.toString());
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearWorktreeSession(SESSION.toString());
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearOriginalCwd(SESSION.toString());
    }

    /**
     * 注册当前会话 worktree 会话对象 — IMP-F1 会话作用域守卫：ExitWorktreeTool 只操作
     * {@code WorktreeCwdTracker.getWorktreeSession(sessionKey)} 返回的会话 worktree（对齐 CC
     * {@code getCurrentWorktreeSession()}）。直调 execute 前必须先登记，否则守卫返回 no-op。
     */
    private static void registerSession() {
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.setWorktreeSession(SESSION.toString(),
            new com.nexusai.application.agent.worktree.WorktreeCwdTracker.WorktreeSession(
                Path.of(System.getProperty("user.dir"), NexusaiPaths.getProjectDirName(), "worktrees", "sample").toString(),
                "worktree-sample", "sample", false, SESSION.toString()));
    }

    private static ToolUseBlock exitAction(String action) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("action", action);
        return new ToolUseBlock("exit-call-" + action, ExitWorktreeTool.NAME, input);
    }

    private static ToolUseContext ctx() {
        return new ToolUseContext(AGENT, SESSION,
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            java.util.Map.of(), java.util.List.of(), "",
            com.nexusai.application.agent.tool.AbortController.NOOP,
            java.util.List.of(), null,
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            java.util.Map.of(), false, "", null,
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    private static void injectWatcher(ExitWorktreeTool tool, FileChangedWatcher watcher) throws Exception {
        Field f = ExitWorktreeTool.class.getDeclaredField("fileChangedWatcher");
        f.setAccessible(true);
        f.set(tool, watcher);
    }

    @Test
    @DisplayName("action=keep → onCwdChangedForHooks 传非 null newCwd (工作区目录)")
    void keepBranch_passesRealNewCwd() throws Exception {
        // WHY: 退出 worktree 即 cwd 切回工作区 (CC Shell.ts:409)。若 newCwd=null，
        //       watcher 无法基于新 cwd 重解析监听路径 → 残留缺口② 恒在。
        WorktreeService service = new WorktreeService((com.nexusai.application.agent.worktree.WorktreeEventLog) null);
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        FileChangedWatcher watcher = mock(FileChangedWatcher.class);
        AtomicReference<String> capturedNewCwd = new AtomicReference<>();
        doAnswer(inv -> {
            capturedNewCwd.set(inv.getArgument(1));
            return null;
        }).when(watcher).onCwdChangedForHooks(anyString(), anyString());
        injectWatcher(tool, watcher);

        // 先进入 worktree，让 tracker 有 oldCwd（保持 keep/remove 前置一致）
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.setCwd(
            SESSION.toString(), Path.of(System.getProperty("user.dir"), NexusaiPaths.getProjectDirName(), "worktrees", "sample"));
        // IMP-F1 会话作用域守卫：登记当前会话 worktree 对象（对齐 CC getCurrentWorktreeSession）
        registerSession();

        AgentToolResult result = tool.execute(exitAction("keep"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("keep 应成功").isFalse();
        assertThat(capturedNewCwd.get())
            .as("keep 分支 newCwd 必须非 null — 退出 worktree 后 cwd 切回工作区 (CC Shell.ts:409)")
            .isNotNull()
            .isNotEmpty();
        assertThat(Path.of(capturedNewCwd.get()).isAbsolute())
            .as("newCwd 必须是绝对路径 (工作区目录)")
            .isTrue();
    }

    @Test
    @DisplayName("action=remove → onCwdChangedForHooks 传非 null newCwd (工作区目录)")
    void removeBranch_passesRealNewCwd() throws Exception {
        // WHY: remove 分支同样要把 cwd 切回工作区 (CC keepWorktree/cleanupWorktree 后 setCwd(originalCwd))。
        //       只传 oldCwd + null newCwd → watcher 不重解析，残留缺口② 恒在。
        WorktreeService service = new WorktreeService((com.nexusai.application.agent.worktree.WorktreeEventLog) null);
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        FileChangedWatcher watcher = mock(FileChangedWatcher.class);
        AtomicReference<String> capturedNewCwd = new AtomicReference<>();
        doAnswer(inv -> {
            capturedNewCwd.set(inv.getArgument(1));
            return null;
        }).when(watcher).onCwdChangedForHooks(anyString(), anyString());
        injectWatcher(tool, watcher);

        com.nexusai.application.agent.worktree.WorktreeCwdTracker.setCwd(
            SESSION.toString(), Path.of(System.getProperty("user.dir"), NexusaiPaths.getProjectDirName(), "worktrees", "sample"));
        // IMP-F1 会话作用域守卫：登记当前会话 worktree 对象（对齐 CC getCurrentWorktreeSession）
        registerSession();

        AgentToolResult result = tool.execute(exitAction("remove"), ctx());

        assertThat(capturedNewCwd.get())
            .as("remove 分支 newCwd 必须非 null — 退出 worktree 后 cwd 切回工作区 (CC Shell.ts:409)")
            .isNotNull()
            .isNotEmpty();
        assertThat(Path.of(capturedNewCwd.get()).isAbsolute())
            .as("newCwd 必须是绝对路径 (工作区目录)")
            .isTrue();
    }
}
