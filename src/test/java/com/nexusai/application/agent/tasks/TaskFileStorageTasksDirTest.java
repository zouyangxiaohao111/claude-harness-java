package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * storage-root 定向测试 · 对齐 CC getTasksDir 存储根（configHome/tasks/{taskListId}）
 *
 * <p>本测试验证任务存储根从旧 {@code {workspaceDir}/.claude/tasks/{id}}（workspaceDir 默认
 * user.dir/.nexusai → {cwd}/.nexusai/.claude/tasks/{id}）迁移到
 * {@code {configHome}/tasks/{id}}（configHome = NexusaiPaths 自有根 {user.home}/.{appName}，
 * 决策 D1；Java 侧 nexusai.task.config-dir sysprop 可覆盖）。
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC Open-ClaudeCode/src/utils/tasks.ts:221-227 getTasksDir()
 * 的目录语义。防回归旧 {@code {cwd}/.nexusai/.claude} 根（该根为脏代码，CC 无 workspace/.nexusai 概念）。
 *
 * <p>参考 CC 源码（grep 自验，非注释）：
 * <ul>
 *   <li>{@code getTasksDir = join(getClaudeConfigHomeDir(), 'tasks', sanitizePathComponent(taskListId))}
 *       — tasks.ts:221-227</li>
 *   <li>{@code getClaudeConfigHomeDir = memoize(() => process.env.CLAUDE_CONFIG_DIR ?? join(homedir(), '.claude')).normalize('NFC')}
 *       — envUtils.ts:7-14（Java 侧决策 D1 改写 nexusai 自有根，见 {@link TaskSystemConfig#getClaudeConfigHomeDir()}）</li>
 *   <li>{@code sanitizePathComponent = input.replace(/[^a-zA-Z0-9_-]/g, '-')} — tasks.ts:217-219</li>
 * </ul>
 *
 * <p>注意：CC 原生 CLAUDE_CONFIG_DIR env 已弃用（决策 D1），测试只做 nexusai 自有根形状断言。
 */
class TaskFileStorageTasksDirTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        // 清除 nexusai.task.config-dir + NexusaiPaths appName 覆写，避免跨用例污染
        TaskSystemConfig.clearForTest();
        NexusaiPaths.setAppNameOverride(null);
    }

    @Test
    @DisplayName("TaskService(临时 configHome) → 任务根 = {configHome}/tasks/{taskListId}，无 .claude/.nexusai 层")
    void tasksDirFromInjectedConfigHome() {
        TaskService service = new TaskService(tempDir);

        Path dir = service.getTasksDir("tasklist-1");

        // CC getTasksDir = join(getClaudeConfigHomeDir(), 'tasks', sanitize(taskListId))
        assertThat(dir).isEqualTo(tempDir.resolve("tasks").resolve("tasklist-1"));
        // 旧根 {cwd}/.nexusai/.claude 已删除：不得含 .claude / .nexusai
        assertThat(dir.toString())
            .doesNotContain(".claude")
            .doesNotContain(".nexusai");
    }

    @Test
    @DisplayName("taskListId 含特殊字符 → sanitizePathComponent 清理（对齐 CC tasks.ts:217-219）")
    void tasksDirSanitizesTaskListId() {
        TaskService service = new TaskService(tempDir);

        Path dir = service.getTasksDir("My List/团队");

        assertThat(dir).isEqualTo(tempDir.resolve("tasks").resolve("My-List---"));
    }

    @Test
    @DisplayName("nexusai.task.config-dir sysprop 覆盖 → getClaudeConfigHomeDir + getTasksDir 均指向该目录")
    void syspropOverrideWinsOverDefault() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());

        Path home = TaskSystemConfig.getClaudeConfigHomeDir();
        assertThat(home).isEqualTo(tempDir);

        TaskService service = new TaskService(home);
        assertThat(service.getTasksDir("abc")).isEqualTo(tempDir.resolve("tasks").resolve("abc"));
    }

    @Test
    @DisplayName("默认分支：无 sysprop 时 config home = NexusaiPaths 自有根 {user.home}/.{appName}")
    void defaultConfigHomeShape() {
        TaskSystemConfig.clearForTest();
        NexusaiPaths.setAppNameOverride("nexusai");

        Path home = TaskSystemConfig.getClaudeConfigHomeDir();

        // 决策 D1：写根统一切 nexusai 自有根（~/.{appName}，appName 默认 nexusai），
        // 弃用 CC 原生 CLAUDE_CONFIG_DIR env 与 ~/.claude 默认（TaskSystemConfig.getClaudeConfigHomeDir Javadoc）。
        assertThat(home).isAbsolute();
        assertThat(home.getFileName().toString()).isEqualTo(NexusaiPaths.getProjectDirName());
        assertThat(home.getParent()).isEqualTo(Path.of(System.getProperty("user.home")));
        // 自有根即 .{appName}，不再回落旧 .claude 根
        assertThat(home.toString()).contains(NexusaiPaths.getProjectDirName());
    }
}
