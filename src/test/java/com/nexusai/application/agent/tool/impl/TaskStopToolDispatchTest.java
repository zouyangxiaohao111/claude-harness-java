package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskStopTool.execute 按 type 分发 · 对齐 CC TaskStopTool.ts:107-129（call → stopTask →
 * {message, task_id, task_type, command}）。
 *
 * <p><b>WHY（意图验证，规则九）</b>：
 * <ul>
 *   <li><b>R1 孤儿进程</b>：旧实现 TaskStopTool.execute 无条件先 killAsyncAgent（:158）→ bash
 *       任务标 KILLED 但子进程未杀。修复后 execute 必须委托 stopTask 单点分发（type 判定在
 *       BackgroundTaskRunner.stopTask），不得再直接调用 killAsyncAgent/cancel。</li>
 *   <li><b>task_type 用实际类型</b>：返回 JSON 的 task_type 必须来自 stopTask 结果（CC :126），
 *       非旧硬编码 "bash"（TaskStopTool.java:167）。</li>
 *   <li><b>错误码映射</b>：not_found → errorCode 1 / not_running → errorCode 3 语义
 *       （CC TaskStopTool.ts:63-88 validateInput）。</li>
 * </ul>
 */
@DisplayName("[OPD-TS-23] TaskStopTool.execute 按 type 分发（委托 stopTask + task_type 实际类型 + 错误映射）")
class TaskStopToolDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);

    private TaskStopTool newTool() {
        TaskStopTool tool = new TaskStopTool();
        ReflectionTestUtils.setField(tool, "backgroundTaskRunner", runner);
        return tool;
    }

    private ToolUseBlock call(String taskId) throws Exception {
        return new ToolUseBlock("toolu_1", "TaskStop",
            MAPPER.readTree("{\"task_id\":\"" + taskId + "\"}"));
    }

    @Test
    @DisplayName("execute 委托 stopTask 单点分发，不直接调 killAsyncAgent/cancel（R1）")
    void execute_delegatesToStopTask_notDirectKill() throws Exception {
        // WHY: 旧实现无条件 killAsyncAgent 是 R1 孤儿进程根因；execute 必须收敛为 stopTask 一次调用。
        TaskStopTool tool = newTool();
        when(runner.stopTask(eq("b-task-1"))).thenReturn(
            new BackgroundTaskRunner.StopTaskResult("b-task-1", "local_bash", "echo hi", null));

        ToolResult<String> res = tool.execute(call("b-task-1"));

        assertThat(LlmAgentLoop.isToolErrorData(res.data())).isFalse();
        verify(runner).stopTask("b-task-1");
        verify(runner, never()).killAsyncAgent(anyString());
        verify(runner, never()).cancel(anyString());

        JsonNode out = MAPPER.readTree(res.data());
        assertThat(out.path("task_type").asText()).isEqualTo("local_bash");
        assertThat(out.path("task_id").asText()).isEqualTo("b-task-1");
        assertThat(out.path("command").asText()).isEqualTo("echo hi");
        assertThat(out.path("message").asText()).contains("b-task-1");
    }

    @Test
    @DisplayName("local_agent 任务 → task_type=local_agent，command=description（CC :126）")
    void execute_agentTask_returnsLocalAgentType() throws Exception {
        TaskStopTool tool = newTool();
        when(runner.stopTask(eq("agent-9"))).thenReturn(
            new BackgroundTaskRunner.StopTaskResult("agent-9", "local_agent", "调研任务", null));

        ToolResult<String> res = tool.execute(call("agent-9"));

        JsonNode out = MAPPER.readTree(res.data());
        assertThat(out.path("task_type").asText()).isEqualTo("local_agent");
        assertThat(out.path("command").asText()).isEqualTo("调研任务");
    }

    @Test
    @DisplayName("NOT_FOUND → 错误消息（CC validateInput errorCode 1）")
    void execute_notFound_returnsError() throws Exception {
        TaskStopTool tool = newTool();
        when(runner.stopTask(anyString())).thenReturn(
            new BackgroundTaskRunner.StopTaskResult("x", null, null,
                BackgroundTaskRunner.StopTaskErrorCode.NOT_FOUND));

        ToolResult<String> res = tool.execute(call("x"));

        assertThat(LlmAgentLoop.isToolErrorData(res.data())).isTrue();
        assertThat(res.data()).contains("No task found with ID: x");
    }

    @Test
    @DisplayName("NOT_RUNNING → 错误消息（CC validateInput errorCode 3）")
    void execute_notRunning_returnsError() throws Exception {
        TaskStopTool tool = newTool();
        when(runner.stopTask(anyString())).thenReturn(
            new BackgroundTaskRunner.StopTaskResult("x", "local_bash", "cmd",
                BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING));

        ToolResult<String> res = tool.execute(call("x"));

        assertThat(LlmAgentLoop.isToolErrorData(res.data())).isTrue();
        assertThat(res.data()).contains("is not running");
    }

    @Test
    @DisplayName("UNSUPPORTED_TYPE → 错误消息（CC StopTaskError unsupported_type）")
    void execute_unsupportedType_returnsError() throws Exception {
        TaskStopTool tool = newTool();
        when(runner.stopTask(anyString())).thenReturn(
            new BackgroundTaskRunner.StopTaskResult("x", "remote_agent", "desc",
                BackgroundTaskRunner.StopTaskErrorCode.UNSUPPORTED_TYPE));

        ToolResult<String> res = tool.execute(call("x"));

        assertThat(LlmAgentLoop.isToolErrorData(res.data())).isTrue();
        assertThat(res.data()).contains("Unsupported task type: remote_agent");
    }

    @Test
    @DisplayName("缺 task_id/shell_id → 错误（CC :63-68）")
    void execute_missingId_returnsError() throws Exception {
        TaskStopTool tool = newTool();
        ToolUseBlock call = new ToolUseBlock("toolu_1", "TaskStop", MAPPER.readTree("{}"));

        ToolResult<String> res = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(res.data())).isTrue();
        assertThat(res.data()).contains("task_id");
    }
}
