package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaskNotificationBuilder#buildMainSessionNotification} 聚焦测试 —— W5-04b。
 *
 * <p><b>WHY（规则九）</b>：主会话后台化完成通知（'Background session'）是模型侧唯一感知
 * 后台查询结束的 XML 通道（CC LocalMainSessionTask.ts:224-263 enqueueMainSessionNotification）。
 * 若该格式出错（tag 名/摘要/tool-use-id 行），模型看不到「Background session completed」，
 * 后台查询对用户静默结束 —— 行为重要性：通知格式 = 完成通知契约。
 *
 * <p>目标 = CC 实际 TS 源码行为（LocalMainSessionTask.ts:255-260，已 sed -n cat -A 自验）：
 * <pre>
 * &lt;task-notification&gt;
 *   &lt;task-id&gt;{taskId}&lt;/task-id&gt;
 *   [&lt;tool-use-id&gt;{toolUseId}&lt;/tool-use-id&gt;]
 *   &lt;output-file&gt;{outputFile}&lt;/output-file&gt;
 *   &lt;status&gt;{status}&lt;/status&gt;
 *   &lt;summary&gt;Background session "{description}" completed|failed&lt;/summary&gt;
 * &lt;/task-notification&gt;
 * </pre>
 * 5-6 TAG（无 task-type，CC :255-260 无 TASK_TYPE_TAG），summary 与 MainSessionBackgroundService
 * enqueueMainSessionNotification 内联版字节一致（2 空格缩进、不转义 —— Java 侧对齐基线）。
 */
class TaskNotificationBuilderTest {

    @Test
    @DisplayName("buildMainSessionNotification：completed + toolUseId → 6 TAG，含 tool-use-id 行")
    void buildMainSessionNotification_completed_withToolUseId() {
        String xml = TaskNotificationBuilder.buildMainSessionNotification(
            "sabc1234", "Background session", "completed", "tool_use_abc", "/tmp/sess/tasks/sabc1234.output");

        assertThat(xml).isEqualTo(
            "<task-notification>\n"
            + "  <task-id>sabc1234</task-id>\n"
            + "  <tool-use-id>tool_use_abc</tool-use-id>\n"
            + "  <output-file>/tmp/sess/tasks/sabc1234.output</output-file>\n"
            + "  <status>completed</status>\n"
            + "  <summary>Background session \"Background session\" completed</summary>\n"
            + "</task-notification>");
    }

    @Test
    @DisplayName("buildMainSessionNotification：failed + 无 toolUseId → 5 TAG，无 tool-use-id 行")
    void buildMainSessionNotification_failed_noToolUseId() {
        String xml = TaskNotificationBuilder.buildMainSessionNotification(
            "sxyz9876", "Background session", "failed", null, null);

        assertThat(xml).isEqualTo(
            "<task-notification>\n"
            + "  <task-id>sxyz9876</task-id>\n"
            + "  <output-file></output-file>\n"
            + "  <status>failed</status>\n"
            + "  <summary>Background session \"Background session\" failed</summary>\n"
            + "</task-notification>");
    }

    @Test
    @DisplayName("buildMainSessionNotification：自定义 description → 摘要含 description（CC :245-248）")
    void buildMainSessionNotification_customDescription() {
        String xml = TaskNotificationBuilder.buildMainSessionNotification(
            "sabc1234", "bg query", "completed", null, "/tmp/o");

        assertThat(xml).contains("<summary>Background session \"bg query\" completed</summary>");
        assertThat(xml).doesNotContain("task-type");
    }

    // ════════════════════════════════════════════════════════════════
    // T1: enqueueShellNotification + detail（size-watchdog kill 消息并入 summary）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("buildEnqueueShellNotification + detail：size-watchdog kill 消息并入 summary（T1）")
    void buildEnqueueShellNotification_appendsSizeWatchdogDetail() {
        // WHY（规则九 · T1）: size-watchdog 杀进程（输出文件超 5GB）后，模型可见的通知 summary 必须
        //   直接说明"输出超 5GB 被杀"（对齐 CC prependStderr 的模型可见语义, ShellCommand.ts:318-322）。
        //   若不并入，模型只见 "failed with exit code 137"，无法区分是 size-kill 还是普通失败
        //   —— 磁盘打满被杀对用户静默（防护失效）。
        BackgroundTask failed = new BackgroundTask(
            "t1-size-kill", TaskType.LOCAL_BASH, BackgroundTaskStatus.FAILED,
            "yes > bigfile", "tu-t1", System.currentTimeMillis(), null, null,
            "/tmp/o", 0L, true);

        String xml = TaskNotificationBuilder.buildEnqueueShellNotification(
            failed, 137, LocalBashTaskRunner.KILLED_FOR_SIZE_MESSAGE);

        assertThat(xml).as("kill 消息必须并入 summary（模型可见，对齐 CC prependStderr）")
            .contains("<summary>Background command &quot;yes &gt; bigfile&quot; failed with exit code 137\n"
                + LocalBashTaskRunner.KILLED_FOR_SIZE_MESSAGE + "</summary>");
        assertThat(xml).contains("<status>failed</status>");
    }

    @Test
    @DisplayName("buildEnqueueShellNotification 无 detail：2 参委托 3 参输出一致（零行为变化）")
    void buildEnqueueShellNotification_withoutDetail_sameAsTwoArg() {
        // WHY（规则九）: 3 参新增 detail 为可选（T1 专用），detail=null 必须与旧 2 参输出字节一致
        //   —— 其他调用方（stub/kill/catch 路径）通知文本零回归。
        BackgroundTask failed = new BackgroundTask(
            "t1-no-detail", TaskType.LOCAL_BASH, BackgroundTaskStatus.FAILED,
            "false", "tu-t2", System.currentTimeMillis(), null, null,
            "/tmp/o", 0L, true);

        assertThat(TaskNotificationBuilder.buildEnqueueShellNotification(failed, 1))
            .isEqualTo(TaskNotificationBuilder.buildEnqueueShellNotification(failed, 1, null));
        assertThat(TaskNotificationBuilder.buildEnqueueShellNotification(failed, 1))
            .doesNotContain("exceeded 5GB");
    }

    // ════════════════════════════════════════════════════════════════
    // FORK-02: agent 通知顶格 + <worktree> 段（CC LocalAgentTask.tsx:251-258）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("buildEnqueueAgentNotification + worktree：顶格 tag + <worktree> 段含 branch (CC :251-258)")
    void buildEnqueueAgentNotification_withWorktree_emitsFlushLeftAndWorktreeSection() {
        // WHY（规则九 · FORK-02）: fork / isolation=worktree 子代理保留 worktree 后，父 Agent 必须
        //   从终态通知收到产物路径（CC getWorktreeResult AgentTool.tsx:644-685 保留才返回
        //   {worktreePath, worktreeBranch}）——否则模型拿到「任务完成」却找不到隔离副本改了什么。
        //   CC 真源 LocalAgentTask.tsx:252-258 tag 全部顶格（无缩进）+ worktreeSection 位于
        //   </task-notification> 前；worktreeBranch 存在时输出 <worktreeBranch> 子 tag（CC :251 三元）。
        BackgroundTask task = new BackgroundTask(
            "agent-wt", TaskType.LOCAL_AGENT, BackgroundTaskStatus.COMPLETED, "调研任务", null,
            System.currentTimeMillis(), System.currentTimeMillis(), null,
            "/tmp/agent-wt.out", 0L, true)
            .withWorktree("/worktrees/agent-wt", "feature-fork");
        AsyncAgentResult success = AsyncAgentResult.success(
            "完整结论", 3, 1000L, "agent-wt", 150L, AgentUsage.fromInputOutput(100, 50));

        // 生产调用方（BackgroundTaskRunner :891/:1665）显式传 task.worktreePath()/worktreeBranch()
        String xml = TaskNotificationBuilder.buildEnqueueAgentNotification(task, success,
            task.worktreePath(), task.worktreeBranch());

        assertThat(xml).isEqualTo(
            "<task-notification>\n"
            + "<task-id>agent-wt</task-id>\n"
            + "<output-file>/tmp/agent-wt.out</output-file>\n"
            + "<status>completed</status>\n"
            + "<summary>Agent \"调研任务\" completed</summary>\n"
            + "<result>完整结论</result>\n"
            + "<usage><total_tokens>150</total_tokens><tool_uses>3</tool_uses>"
            + "<duration_ms>1000</duration_ms></usage>\n"
            + "<worktree><worktreePath>/worktrees/agent-wt</worktreePath>"
            + "<worktreeBranch>feature-fork</worktreeBranch></worktree>\n"
            + "</task-notification>");
    }

    @Test
    @DisplayName("buildEnqueueAgentNotification + worktree 无 branch：省略 <worktreeBranch> 子 tag (CC :251 三元)")
    void buildEnqueueAgentNotification_withWorktreeNoBranch_omitsBranchTag() {
        // WHY（规则九 · FORK-02）: CC worktreeSection 内三元 {@code worktreeBranch ? `<worktreeBranch>...` : ''}
        //   —— branch 缺失（如 resume 复用路径 CC resumeAgent.ts:254 仅传 worktreePath）时
        //   <worktree> 仍输出，但省略 branch 子 tag；否则字节偏离 CC。
        BackgroundTask task = new BackgroundTask(
            "agent-wt2", TaskType.LOCAL_AGENT, BackgroundTaskStatus.COMPLETED, "调研", null,
            System.currentTimeMillis(), System.currentTimeMillis(), null,
            "/tmp/agent-wt2.out", 0L, true)
            .withWorktree("/worktrees/agent-wt2", null);
        AsyncAgentResult success = AsyncAgentResult.success(
            "结论", 1, 500L, "agent-wt2", 80L, AgentUsage.fromInputOutput(60, 20));

        // 生产调用方显式传 task.worktreePath()/worktreeBranch()（branch=null → 省略子 tag）
        String xml = TaskNotificationBuilder.buildEnqueueAgentNotification(task, success,
            task.worktreePath(), task.worktreeBranch());

        assertThat(xml)
            .contains("<worktree><worktreePath>/worktrees/agent-wt2</worktreePath></worktree>")
            .doesNotContain("worktreeBranch");
    }

    @Test
    @DisplayName("buildEnqueueAgentNotification 无 worktree：2 参委托 4 参（null worktree）→ 无 worktree 段（零行为变化）")
    void buildEnqueueAgentNotification_withoutWorktree_sameAsFourArgNull() {
        // WHY（规则九 · FORK-02）: 无隔离 worktree（非 isolation=worktree 的 async agent）时通知
        //   字节必须与旧 2 参一致（不输出 worktree 段）——既有测试/父 Agent 零回归。
        BackgroundTask task = new BackgroundTask(
            "agent-nwt", TaskType.LOCAL_AGENT, BackgroundTaskStatus.COMPLETED, "调研", null,
            System.currentTimeMillis(), System.currentTimeMillis(), null,
            "/tmp/agent-nwt.out", 0L, true);
        AsyncAgentResult success = AsyncAgentResult.success(
            "结论", 1, 500L, "agent-nwt", 80L, AgentUsage.fromInputOutput(60, 20));

        assertThat(TaskNotificationBuilder.buildEnqueueAgentNotification(task, success))
            .isEqualTo(TaskNotificationBuilder.buildEnqueueAgentNotification(task, success, null, null))
            .doesNotContain("<worktree>");
    }
}
