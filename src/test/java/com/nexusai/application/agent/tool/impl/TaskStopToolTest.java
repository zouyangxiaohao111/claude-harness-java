package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [IMP-G3] TaskStopTool 对齐 CC TaskStopTool.ts（组 6-3）。
 *
 * <p>WHY（规则九，验证意图）：CC TaskStopTool.ts:44 aliases ['KillShell'] 反查 --resume 断链
 * （EV-G2-034）；isEnabled 恒启用 vs isTodoV2Enabled 门控（△-12）；maxResultSizeChars=100_000（△-10）；
 * isConcurrencySafe=true（△-11）；outputSchema {message, task_id, task_type, command}（TaskStopTool.ts:22-34）；
 * 纯委托 stopTask 单点分发（TR-G2-⊕-2：in-process 分发迁至 BackgroundTaskRunner，本工具无 registry 分支）。
 */
@DisplayName("[IMP-G3] TaskStopTool 对齐 CC（aliases + 恒启用 + 契约字段 + 纯委托 stopTask）")
class TaskStopToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);

    private TaskStopTool newTool() {
        TaskStopTool tool = new TaskStopTool();
        ReflectionTestUtils.setField(tool, "backgroundTaskRunner", runner);
        return tool;
    }

    @Test
    @DisplayName("aliases 包含 KillShell（CC TaskStopTool.ts:44，--resume 反查）")
    void aliases_containsKillShell() {
        // WHY: EV-G2-034 —— CC aliases ['KillShell'] 供历史 transcript 反查；Java 未 override
        // aliases → ToolRegistry findToolByName 断链（Tool.java:756）。
        assertThat(newTool().aliases()).containsExactly("KillShell");
    }

    @Test
    @DisplayName("恒启用：isEnabled()=true，即使 isTodoV2Enabled()=false（V1 模式不可误禁）")
    void isEnabled_alwaysTrue_regardlessOfTodoV2() {
        // WHY: CC TaskStopTool 无 isEnabled override → buildTool 默认 true（△-12）；Java 基类
        // AbstractTaskTool.isEnabled()=isTodoV2Enabled()，V1 默认下 TaskStop 被误禁。
        System.clearProperty("nexusai.tasks.enabled");
        TaskSystemConfig.clearForTest();
        assertThat(newTool().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("maxResultSizeChars=100_000（CC TaskStopTool.ts:45，旧 10k 截断长输出）")
    void maxResultSizeChars_is100k() {
        // WHY: EV-G2-035 △-10 —— CC maxResultSizeChars: 100_000，旧实现 10_000 截断长输出。
        assertThat(newTool().maxResultSizeChars()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("isConcurrencySafe=true（CC TaskStopTool.ts:54-56，旧 false 调度并发误判）")
    void isConcurrencySafe_isTrue() {
        // WHY: EV-G2-035 △-11 —— CC isConcurrencySafe() → true，旧实现 false。
        assertThat(newTool().isConcurrencySafe(null)).isTrue();
    }

    @Test
    @DisplayName("outputSchema 含 message/task_id/task_type/command（CC TaskStopTool.ts:22-34）")
    void outputSchema_containsCcFields() throws Exception {
        // WHY: CC outputSchema 四字段；Java 旧 schema 仅 {message}，SDK 消费方拿不到 task_id/type/command。
        JsonNode schema = newTool().outputSchema();
        JsonNode props = schema.path("properties");
        assertThat(props.has("message")).isTrue();
        assertThat(props.has("task_id")).isTrue();
        assertThat(props.has("task_type")).isTrue();
        assertThat(props.has("command")).isTrue();
    }

    @Test
    @DisplayName("execute 纯委托 stopTask（对齐 CC TaskStopTool.ts:107-130，无 registry 分支）")
    void execute_delegatesToStopTask() throws Exception {
        // WHY: TR-G2-⊕-2 映射 —— in-process 分发迁至 BackgroundTaskRunner.stopTask 统一路径，
        // TaskStopTool 只调 stopTask（CC TaskStopTool.ts:117-120）；返回 {message, task_id, task_type, command}。
        when(runner.stopTask("b-task-1")).thenReturn(
            new BackgroundTaskRunner.StopTaskResult("b-task-1", "local_bash", "echo hi", null));

        ToolResult<?> result = newTool().execute(new ToolUseBlock("call-1", "TaskStop",
            MAPPER.readTree("{\"task_id\":\"b-task-1\"}")));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        verify(runner).stopTask("b-task-1");
        JsonNode out = MAPPER.readTree(String.valueOf(result.data()));
        assertThat(out.path("task_type").asText()).isEqualTo("local_bash");
        assertThat(out.path("task_id").asText()).isEqualTo("b-task-1");
        assertThat(out.path("command").asText()).isEqualTo("echo hi");
        assertThat(out.path("message").asText()).contains("b-task-1");
    }

    @Test
    @DisplayName("NOT_FOUND → 错误消息（CC validateInput errorCode 1）")
    void execute_notFound_returnsError() throws Exception {
        when(runner.stopTask(anyString())).thenReturn(
            new BackgroundTaskRunner.StopTaskResult("x", null, null,
                BackgroundTaskRunner.StopTaskErrorCode.NOT_FOUND));

        ToolResult<?> result = newTool().execute(new ToolUseBlock("call-1", "TaskStop",
            MAPPER.readTree("{\"task_id\":\"x\"}")));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(String.valueOf(result.data())).contains("No task found with ID: x");
    }
}
