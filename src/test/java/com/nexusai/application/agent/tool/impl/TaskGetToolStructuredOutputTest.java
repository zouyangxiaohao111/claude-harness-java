package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-v2-tools-structured-output · TaskGetTool 结构化 data 输出定向测试.
 *
 * <p>WHY 本测试验证意图（CC 双通道契约）：TaskGet 未找到返回 {@link TaskGetTool.TaskGetOutput}
 * 且 task=null 的<b>结构化成功</b>（非字符串 "Task not found"，对齐 CC TaskGetTool.ts:78-83），
 * 找到返回完整 {@link TaskGetTool.TaskData}（对齐 CC TaskGetTool.ts:86-97）；渲染多行文本
 * 下沉 {@link TaskGetTool#renderToolResultText}（对齐 CC TaskGetTool.ts:99-127）。
 */
class TaskGetToolStructuredOutputTest {

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // TaskGetTool call() 内逐次 getTaskListId()：mock getTask("tl-1", ...) 要命中必须返回 "tl-1"
        System.setProperty("nexusai.taskListId", "tl-1");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.taskListId");
    }

    @Test
    @DisplayName("未找到返回 TaskGetOutput(task=null) 结构化成功，非 'Task not found' 字符串")
    void notFound_returnsStructuredTaskGetOutputWithNullTask() {
        // WHY: CC TaskGetTool.ts:78-83 未找到返回 { data: { task: null } }（结构化成功）。
        // 旧 Java 用字符串 "Task not found" 替代结构，data.task 消费方读到字符串而非 null 对象。
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTask("tl-1", "missing")).thenReturn(Optional.empty());

        TaskGetTool tool = new TaskGetTool(taskService);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskGet",
            json.createObjectNode().put("taskId", "missing"));

        @SuppressWarnings("unchecked")
        ToolResult<TaskGetTool.TaskGetOutput> result =
            (ToolResult<TaskGetTool.TaskGetOutput>) tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse(); // 未找到是结构化成功非 error
        assertThat(result.data().task()).isNull(); // { task: null }
        // 'Task not found' 文本归 mapper（对齐 CC TaskGetTool.ts:105）
        assertThat(TaskGetTool.renderToolResultText(result.data())).isEqualTo("Task not found");
    }

    @Test
    @DisplayName("找到返回完整 TaskData（id/subject/description/status/blocks/blockedBy）")
    void found_returnsFullTaskData() {
        // WHY: CC TaskGetTool.ts:86-97 返回 { data: { task: { id, subject, description, status, blocks, blockedBy } } }。
        // data 承载全部结构化字段，渲染多行文本归 mapper（CC TaskGetTool.ts:99-127）。
        TaskService taskService = mock(TaskService.class);
        Task task = new Task("t-1", "Write docs", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of("t-3"), List.of("t-2"), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));

        TaskGetTool tool = new TaskGetTool(taskService);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskGet",
            json.createObjectNode().put("taskId", "t-1"));

        @SuppressWarnings("unchecked")
        ToolResult<TaskGetTool.TaskGetOutput> result =
            (ToolResult<TaskGetTool.TaskGetOutput>) tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        TaskGetTool.TaskData data = result.data().task();
        assertThat(data).isNotNull();
        assertThat(data.id()).isEqualTo("t-1");
        assertThat(data.subject()).isEqualTo("Write docs");
        assertThat(data.description()).isEqualTo("desc");
        assertThat(data.status()).isEqualTo("in_progress");
        assertThat(data.blocks()).containsExactly("t-3");
        assertThat(data.blockedBy()).containsExactly("t-2");

        // mapper 多行文本逐字对齐 CC TaskGetTool.ts:109-120
        assertThat(TaskGetTool.renderToolResultText(result.data()))
            .isEqualTo("Task #t-1: Write docs\n"
                + "Status: in_progress\n"
                + "Description: desc\n"
                + "Blocked by: #t-2\n"
                + "Blocks: #t-3");
    }

    @Test
    @DisplayName("空 taskId 走查找路径未找到 → 结构化成功 {task:null}（对齐 CC TaskGetTool.ts:73-97，TG-15）")
    void emptyTaskId_returnsStructuredNullTask() {
        // WHY: CC inputSchema taskId 为 z.string()（TaskGetTool.ts:15，无 min）→ 空串通过校验，
        // call() 直接 getTask(taskListId, "") → 未找到 → { data: { task: null } } 结构化成功
        // （TaskGetTool.ts:78-83）。旧 Java blank-taskId 拦截（TaskGetTool.java:292-294）
        // 返回 error 改变可观察行为（TG-15/D-1 家族），删除后空串必须落 CC 良性路径。
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTask("tl-1", "")).thenReturn(Optional.empty());

        TaskGetTool tool = new TaskGetTool(taskService);
        ToolUseBlock call = new ToolUseBlock("call-2", "TaskGet",
            json.createObjectNode().put("taskId", ""));

        @SuppressWarnings("unchecked")
        ToolResult<TaskGetTool.TaskGetOutput> result =
            (ToolResult<TaskGetTool.TaskGetOutput>) tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse(); // 结构化成功，非 error
        assertThat(result.data().task()).isNull(); // { task: null }
        // 'Task not found' 文本归 mapper（对齐 CC TaskGetTool.ts:105）
        assertThat(TaskGetTool.renderToolResultText(result.data())).isEqualTo("Task not found");
    }
}
