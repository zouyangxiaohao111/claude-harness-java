package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [IMP-G3] TaskOutputTool 对齐 CC TaskOutputTool.tsx（组 6-3）。
 *
 * <p>WHY（规则九，验证意图）：CC TaskOutputTool.tsx:150 aliases ['AgentOutputTool','BashOutputTool']
 * 反查 --resume 断链（EV-G2-021）；isEnabled 恒启用 vs isTodoV2Enabled 门控（△-2）；输出契约
 * 嵌套 {retrieval_status, task} + XML mapper（EV-G2-025 输出契约漂移 HIGH）。本测试验证：
 * aliases/isEnabled/inputSchema 删 synchronous|wait/outputSchema 嵌套/execute 嵌套输出。
 */
@DisplayName("[IMP-G3] TaskOutputTool 对齐 CC（aliases + 恒启用 + 嵌套输出契约）")
class TaskOutputToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);

    private TaskOutputTool newTool() {
        TaskOutputTool tool = new TaskOutputTool();
        ReflectionTestUtils.setField(tool, "backgroundTaskRunner", runner);
        return tool;
    }

    private ToolUseBlock call(String json) throws Exception {
        return new ToolUseBlock("toolu_1", "TaskOutput", MAPPER.readTree(json));
    }

    @Test
    @DisplayName("aliases 包含 AgentOutputTool/BashOutputTool（CC TaskOutputTool.tsx:150，--resume 反查）")
    void aliases_containsCcBackCompatNames() {
        // WHY: EV-G2-021 —— CC aliases ['AgentOutputTool','BashOutputTool'] 供历史 transcript 反查；
        // Java 未 override aliases → ToolRegistry findToolByName 断链（Tool.java:756）。
        assertThat(newTool().aliases())
            .containsExactly("AgentOutputTool", "BashOutputTool");
    }

    @Test
    @DisplayName("恒启用：isEnabled()=true，即使 isTodoV2Enabled()=false（V1 模式不可误禁）")
    void isEnabled_alwaysTrue_regardlessOfTodoV2() {
        // WHY: CC TaskOutputTool.tsx:163-165 isEnabled() 生产态恒 true，不受 isTodoV2Enabled 门控（△-2）；
        // Java 基类 AbstractTaskTool.isEnabled()=isTodoV2Enabled()，V1 默认下 TaskOutput 被误禁。
        System.clearProperty("nexusai.tasks.enabled");
        TaskSystemConfig.clearForTest();
        assertThat(newTool().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("inputSchema 仅 task_id/block/timeout（TR-G2-⊕-1 synchronous/wait 已删）")
    void inputSchema_noSynchronousOrWait() throws Exception {
        // WHY: TR-G2-⊕-1 —— CC 仅 task_id/block/timeout（TaskOutputTool.tsx:30-34）；Java 旧 schema
        // 有 synchronous/wait 额外属性（Java-only，synchronous 读取未分发），删除对齐 CC。
        JsonNode schema = newTool().inputSchema();
        JsonNode props = schema.path("properties");
        assertThat(props.has("task_id")).isTrue();
        assertThat(props.has("block")).isTrue();
        assertThat(props.has("timeout")).isTrue();
        assertThat(props.has("synchronous")).as("synchronous 属性必须删除（TR-G2-⊕-1）").isFalse();
        assertThat(props.has("wait")).as("wait 属性必须删除（TR-G2-⊕-1）").isFalse();
    }

    @Test
    @DisplayName("outputSchema 嵌套 {retrieval_status, task}（TR-G2-⊕-3 重构 CC shape）")
    void outputSchema_nestedCcShape() throws Exception {
        // WHY: EV-G2-025 输出契约漂移 HIGH —— CC TaskOutputToolOutput={retrieval_status, task}，
        // 消费方按 CC 契约解析 data.task.*；旧 flat 7 字段 schema 拿不到嵌套。
        JsonNode schema = newTool().outputSchema();
        JsonNode props = schema.path("properties");
        assertThat(props.has("retrieval_status")).isTrue();
        assertThat(props.path("task").isObject()).as("task 必须为嵌套对象（CC TaskOutput）").isTrue();
        JsonNode taskProps = props.path("task").path("properties");
        assertThat(taskProps.has("task_id")).isTrue();
        assertThat(taskProps.has("task_type")).isTrue();
        assertThat(taskProps.has("status")).isTrue();
        assertThat(taskProps.has("description")).isTrue();
        assertThat(taskProps.has("output")).isTrue();
    }

    @Test
    @DisplayName("终态任务 → retrieval_status=success + XML mapper 嵌套渲染（CC TaskOutputTool.tsx:283-308）")
    void execute_terminal_returnsNestedSuccessXml() throws Exception {
        // WHY: CC mapper 产 XML（<retrieval_status>/<task_id>/<task_type>/<status>/<output>），
        // 非旧 flat 文本后缀 [retrieval_status=...]。
        when(runner.getOutput(eq("t1"), anyBoolean(), anyLong())).thenReturn(
            new BackgroundTaskRunner.TaskOutput("t1", "local_bash", "echo hi",
                "/tmp/agent-t1.out", BackgroundTaskStatus.COMPLETED,
                "hello world", false, true));

        AgentToolResult<?> result = newTool().execute(call("{\"task_id\":\"t1\",\"block\":false}"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        String data = String.valueOf(result.data());
        assertThat(data).contains("<retrieval_status>success</retrieval_status>");
        assertThat(data).contains("<task_id>t1</task_id>");
        assertThat(data).contains("<task_type>local_bash</task_type>");
        assertThat(data).contains("<status>completed</status>");
        assertThat(data).contains("<output>\nhello world\n</output>");
    }

    @Test
    @DisplayName("非阻塞查 running → retrieval_status=not_ready（CC TaskOutputTool.tsx:234-239）")
    void execute_runningNonBlocking_returnsNotReady() throws Exception {
        when(runner.getOutput(eq("t2"), anyBoolean(), anyLong())).thenReturn(
            new BackgroundTaskRunner.TaskOutput("t2", "local_agent", "调研",
                "/tmp/agent-t2.out", BackgroundTaskStatus.RUNNING,
                "partial", false, true));

        AgentToolResult<?> result = newTool().execute(call("{\"task_id\":\"t2\",\"block\":false}"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(String.valueOf(result.data())).contains("<retrieval_status>not_ready</retrieval_status>");
    }

    @Test
    @DisplayName("阻塞超时 → retrieval_status=timeout（CC TaskOutputTool.tsx:254-268）")
    void execute_blockingTimeout_returnsTimeout() throws Exception {
        when(runner.getOutput(eq("t3"), anyBoolean(), anyLong())).thenReturn(
            new BackgroundTaskRunner.TaskOutput("t3", "local_bash", "sleep",
                "/tmp/agent-t3.out", BackgroundTaskStatus.RUNNING,
                "", true, true));

        AgentToolResult<?> result = newTool().execute(call("{\"task_id\":\"t3\",\"block\":true,\"timeout\":100}"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(String.valueOf(result.data())).contains("<retrieval_status>timeout</retrieval_status>");
    }

    @Test
    @DisplayName("任务不存在 → 错误 'No task found with ID'（CC call 抛 No task found，errorCode 2）")
    void execute_notFound_returnsError() throws Exception {
        when(runner.getOutput(eq("missing"), anyBoolean(), anyLong())).thenReturn(
            new BackgroundTaskRunner.TaskOutput("missing", null, null, null,
                BackgroundTaskStatus.PENDING, "", true, false));

        AgentToolResult<?> result = newTool().execute(call("{\"task_id\":\"missing\"}"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("不存在任务应报错（CC validateInput errorCode 2）").isTrue();
        assertThat(String.valueOf(result.data())).contains("No task found with ID: missing");
    }

    @Test
    @DisplayName("缺 task_id → 错误（CC validateInput errorCode 1）")
    void execute_missingTaskId_returnsError() throws Exception {
        AgentToolResult<?> result = newTool().execute(call("{}"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(String.valueOf(result.data())).contains("Task ID is required");
    }

    @Test
    @DisplayName("长输出截断 → formatTaskOutput 头注 + 保留末尾（CC outputFormatting.ts formatTaskOutput）")
    void formatTaskOutput_truncatesWithHeader() {
        // WHY: CC TASK_MAX_OUTPUT_LENGTH=32_000 超长截断，头注 [Truncated. Full output: path]，
        // 保留末尾 N 字符（outputFormatting.ts:26-38）。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            sb.append('x');
        }
        String out = TaskOutputTool.formatTaskOutput(sb.toString(), "t9", "/tmp/agent-t9.out");
        assertThat(out).startsWith("[Truncated. Full output: /tmp/agent-t9.out]");
        assertThat(out).endsWith("x");
    }
}
