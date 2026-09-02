package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tasks.AsyncAgentFinalizer;
import com.nexusai.application.agent.tasks.AsyncAgentResult;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskNotificationBuilder;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
/**
 * [S4] runAsyncAgentLifecycle 三态通知 RED-GREEN 双证测试 (P1 差异项 6).
 *
 * <p>规则九 (验证意图): CC {@code runAsyncAgentLifecycle} (agentToolUtils.ts:508-686) 三态:
 * completed (:624) / killed (:659) / failed (:673). 旧 Java executeAsync 简化版无三态 —
 * aborted 结果被当 success 写 COMPLETED (状态错乱). killed 路径 (CC :640-668 AbortError →
 * killAsyncAgent + extractPartialResult) 保留部分结果.
 *
 * <p>测试方式: AsyncAgentResult 工厂 + AsyncAgentFinalizer 路由是核心三态逻辑, 用 Mockito mock
 * BackgroundTaskRunner 验证路由. RED 依据: killed 工厂 / finalizeKilled 在 S4 前不存在.
 */
@DisplayName("[S4] runAsyncAgentLifecycle 三态 (completed/killed/failed + usage payload)")
class RunAsyncAgentLifecycleTest {

    @Test
    @DisplayName("AsyncAgentResult.killed 携带部分结果 + usage + totalTokens (CC extractPartialResult :658)")
    void asyncAgentResult_killed_shouldCarryPartialResultAndUsage() {
        // WHY: killed 路径必须保留子 Agent 已产出内容 (CC extractPartialResult 逆序找首个有 text
        //   assistant 消息), 否则用户 abort 后成果全丢.
        AgentUsage usage = AgentUsage.fromInputOutput(80, 20);
        AsyncAgentResult killed = AsyncAgentResult.killed("已完成一半的部分结果", usage, 100L, 5000L, "agent-9");

        assertThat(killed.summary()).as("部分结果承载于 summary (对齐 CC finalMessage=partialResult)")
            .isEqualTo("已完成一半的部分结果");
        assertThat(killed.totalTokens()).isEqualTo(100L);
        assertThat(killed.usage().inputTokens()).isEqualTo(80L);
        assertThat(killed.totalDurationMs()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("AsyncAgentResult.success 携带 usage + totalTokens (CC completed 通知 usage{totalTokens})")
    void asyncAgentResult_success_shouldCarryUsage() {
        AgentUsage usage = AgentUsage.fromInputOutput(200, 50);
        AsyncAgentResult success = AsyncAgentResult.success("完整结论", 7, 3000L, "agent-1", 250L, usage);

        assertThat(success.totalTokens()).isEqualTo(250L);
        assertThat(success.usage().inputTokens()).isEqualTo(200L);
        assertThat(success.usage().outputTokens()).isEqualTo(50L);
        assertThat(success.totalToolUseCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("AsyncAgentFinalizer.finalizeKilled 路由到 killAsyncAgent (CC :645 killAsyncAgent)")
    void asyncAgentFinalizer_finalizeKilled_shouldCallKillAsyncAgent() {
        // WHY: killed 三态必须推进 task 到 KILLED (CC killAsyncAgent 原子 CAS) — 若路由到
        //   completeAsyncAgent, 用户 abort 的 agent 反被标记 COMPLETED (状态错乱).
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        AsyncAgentFinalizer finalizer = new AsyncAgentFinalizer(runner);
        AsyncAgentResult killed = AsyncAgentResult.killed("部分结果", AgentUsage.EMPTY, 0L, 0L, "agent-9");

        finalizer.finalizeKilled("agent-9", killed);
        // [S4-1 残差 ②] 2 参重载: killed 通知需携带部分结果 (CC :659-667 finalMessage=partialResult)
        verify(runner).killAsyncAgent("agent-9", killed);
    }

    @Test
    @DisplayName("completed 终态通知 XML 含 usage payload (CC agentToolUtils.ts:630-634 + LocalAgentTask.tsx:250)")
    void completedNotification_shouldCarryUsagePayload() {
        // WHY: 父 Agent 依赖终态通知 usage{totalTokens, toolUses, durationMs} 做 token budget 决策 —
        //   旧 buildEnqueueShellNotification XML 仅 5 TAG 无 usage 段 = 信息丢失 (S4 残差 ①).
        //   CC 真源: enqueueAgentNotification({status:'completed', usage:{totalTokens:getTokenCountFromTracker,
        //   toolUses:totalToolUseCount, durationMs:totalDurationMs}}) (agentToolUtils.ts:624-637);
        //   XML usageSection (LocalAgentTask.tsx:250).
        BackgroundTask task = new BackgroundTask("agent-1", TaskType.LOCAL_AGENT,
            BackgroundTaskStatus.COMPLETED, "调研任务", "tool_use_1",
            System.currentTimeMillis(), System.currentTimeMillis(), null,
            "/tmp/agent-1.out", 0L, true);
        AsyncAgentResult success = AsyncAgentResult.success(
            "完整结论", 7, 3000L, "agent-1", 250L, AgentUsage.fromInputOutput(200, 50));

        String xml = TaskNotificationBuilder.buildEnqueueAgentNotification(task, success);

        // OPD-TS-20 (D1): CC 7 个标签全连字符 (xml.ts:28-34) — 父 Agent / print.ts:2020-2060
        //   连字符正则解析, 下划线标签 match 全空 → SDK 事件失真.
        assertThat(xml)
            .contains("<task-notification>")
            .contains("</task-notification>")
            .contains("<task-id>agent-1</task-id>")
            .contains("<output-file>/tmp/agent-1.out</output-file>")
            .contains("<status>completed</status>")
            .contains("Agent \"调研任务\" completed")
            .contains("<result>完整结论</result>")
            .contains("<usage><total_tokens>250</total_tokens><tool_uses>7</tool_uses>"
                + "<duration_ms>3000</duration_ms></usage>");
    }

    @Test
    @DisplayName("killed 终态通知 XML 含部分结果 <result> 段 (CC :658-667 + LocalAgentTask.tsx:249)")
    void killedNotification_shouldCarryPartialResult() {
        // WHY: 用户 abort 后已产出内容必须保留在通知里 (CC extractPartialResult :658 →
        //   finalMessage=partialResult → resultSection). 若通知丢部分结果, 父 Agent 看不到
        //   kill 前子 Agent 完成了什么 (S4 残差 ②).
        BackgroundTask task = new BackgroundTask("agent-9", TaskType.LOCAL_AGENT,
            BackgroundTaskStatus.KILLED, "调研任务", null,
            System.currentTimeMillis(), System.currentTimeMillis(), null,
            "/tmp/agent-9.out", 0L, true);
        AsyncAgentResult killed = AsyncAgentResult.killed(
            "已完成一半的部分结果", AgentUsage.fromInputOutput(80, 20), 100L, 5000L, "agent-9");

        String xml = TaskNotificationBuilder.buildEnqueueAgentNotification(task, killed);

        assertThat(xml)
            .contains("<task-notification>")
            .contains("<task-id>agent-9</task-id>")
            .contains("<status>killed</status>")
            .contains("Agent \"调研任务\" was stopped")
            .contains("<result>已完成一半的部分结果</result>")
            .as("killed 无 usage 段 (CC LocalAgentTask.tsx:250 仅 completed)")
            .doesNotContain("<usage>");
    }

    @Test
    @DisplayName("framework 格式通知: 7 标签全连字符 + 状态文本对齐 CC getStatusText (OPD-TS-20)")
    void taskNotification_frameworkFormat_shouldUseHyphenTagsAndStatusText() {
        // WHY: CC enqueueTaskNotification (framework.ts:274-289) 6 TAG 全连字符 (xml.ts:28-34),
        //   print.ts:2015-2060 只按连字符正则解析 — 下划线标签 match 全空 → 父 Agent 拿不到
        //   task_id/task_type/output_file, SDK task_notification 事件失真 (D1「最重要偏移」).
        //   状态文本对齐 CC getStatusText (framework.ts:295-304): completed→'completed successfully',
        //   failed→'failed', killed→'was stopped' (旧实现恒拼 " successfully" 导致 failed 出
        //   "failed successfully" 语义错误, Rule 9 意图验证).
        BackgroundTask completed = new BackgroundTask("task-1", TaskType.LOCAL_BASH,
            BackgroundTaskStatus.COMPLETED, "批量脚本", "tool_use_1",
            System.currentTimeMillis(), System.currentTimeMillis(), null,
            "/tmp/task-1.out", 0L, true);
        String xmlCompleted = TaskNotificationBuilder.buildEnqueueTaskNotification(completed);
        assertThat(xmlCompleted)
            .contains("<task-notification>")
            .contains("<task-id>task-1</task-id>")
            .contains("<tool-use-id>tool_use_1</tool-use-id>")
            .contains("<task-type>local_bash</task-type>")
            .contains("<output-file>/tmp/task-1.out</output-file>")
            .contains("<status>completed</status>")
            // appendTag 走 escapeXml → 引号转义为 &quot; (Java 既有行为, 本任务范围外)
            .contains("<summary>Task &quot;批量脚本&quot; completed successfully</summary>");

        BackgroundTask failed = new BackgroundTask("task-2", TaskType.LOCAL_BASH,
            BackgroundTaskStatus.FAILED, "失败脚本", null,
            System.currentTimeMillis(), System.currentTimeMillis(), null,
            "/tmp/task-2.out", 0L, true);
        String xmlFailed = TaskNotificationBuilder.buildEnqueueTaskNotification(failed);
        assertThat(xmlFailed)
            .contains("<status>failed</status>")
            .contains("<summary>Task &quot;失败脚本&quot; failed</summary>");

        BackgroundTask killed = new BackgroundTask("task-3", TaskType.LOCAL_BASH,
            BackgroundTaskStatus.KILLED, "被终止脚本", null,
            System.currentTimeMillis(), System.currentTimeMillis(), null,
            "/tmp/task-3.out", 0L, true);
        String xmlKilled = TaskNotificationBuilder.buildEnqueueTaskNotification(killed);
        assertThat(xmlKilled)
            .contains("<status>killed</status>")
            .contains("<summary>Task &quot;被终止脚本&quot; was stopped</summary>");
    }

    @Test
    @DisplayName("AsyncAgentFinalizer.finalize 成功 → completeAsyncAgent (CC :603 completeAsyncAgent)")
    void asyncAgentFinalizer_finalize_shouldCallCompleteAsyncAgent() {
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        AsyncAgentFinalizer finalizer = new AsyncAgentFinalizer(runner);
        AsyncAgentResult success = AsyncAgentResult.success("结论", 3, 1000L, "agent-1", 150L, AgentUsage.EMPTY);

        finalizer.finalize("agent-1", success);

        verify(runner).completeAsyncAgent("agent-1", success);
    }

    @Test
    @DisplayName("AsyncAgentFinalizer.finalize null → failAsyncAgent (CC :671 failAsyncAgent)")
    void asyncAgentFinalizer_finalize_null_shouldCallFailAsyncAgent() {
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        AsyncAgentFinalizer finalizer = new AsyncAgentFinalizer(runner);

        finalizer.finalize("agent-1", null);

        verify(runner).failAsyncAgent("agent-1", "null result");
    }
}
