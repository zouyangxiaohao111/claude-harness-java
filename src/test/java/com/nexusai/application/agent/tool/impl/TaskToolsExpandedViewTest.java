package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * appstate-expandedview · TaskCreate / TaskUpdate 的 setAppState 副作用定向测试.
 *
 * <p>WHY 本测试验证意图（CC 行为，不信注释）：TaskCreateTool.ts:116-119 与
 * TaskUpdateTool.ts:139-143 在工具调用成功后执行
 * {@code context.setAppState(prev => { if (prev.expandedView === 'tasks') return prev;
 * return { ...prev, expandedView: 'tasks' } })} —— 自动展开任务列表。
 *
 * <p>Java 侧基础设施（R32-b15 Stage 3.2 C2）：LlmAgentLoop.appStateRef +
 * setAppState/getAppStateSnapshot 经 ToolUseContext.getAppState/setAppState 桥接字段注入
 * （LlmAgentLoop.java:3442-3445、ToolUseContext.java:86-87）。本测试捕获
 * {@code ctx.setAppState()} 收到的 updater 并手工应用，断言 guard（expandedView 已是
 * 'tasks' 则 no-op 返回原引用）与置位（否则拷贝 prev + 置 'tasks'）语义，同时断言
 * execute 返回值不受副作用影响。
 *
 * <p>hookRegistry 传 null（TaskCreateTool.java:413 有 null 保护）——本测试聚焦
 * setAppState 副作用，不涉 hook 聚合。
 */
class TaskToolsExpandedViewTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SESSION_ID = "22222222-2222-2222-2222-222222222222";

    // ════════════════════════════════════════════════════════════════════
    // 辅助：构造带 setAppState 捕获的 ToolUseContext（对齐 R32B15Stage3_2_AppStateSDKStatusTest
    //   c2FourFieldsFallbackToNoop 捕获模式 —— 但这里注入真实桥接，捕获 updater 供断言）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 构造 ToolUseContext · setAppState 捕获 updater 到 {@code captured}，getAppState 返回
     * 当前快照（初始 {@code initial}）。对齐 LlmAgentLoop.java:3442-3445 桥接注入形态。
     */
    private ToolUseContext ctxWithSetAppState(
            Map<String, Object> initial,
            AtomicReference<Function<Map<String, Object>, Map<String, Object>>> captured) {
        AtomicReference<Map<String, Object>> state =
            new AtomicReference<>(initial == null ? Map.of() : Map.copyOf(initial));
        return ToolUseContext.of(
            AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, Map.of(), null,
            // getAppState: 返回不可变快照（对齐 LlmAgentLoop.getAppStateSnapshot :484-486）
            prev -> Map.copyOf(state.get()),
            // setAppState: 捕获 updater + 应用到内部 state（对齐 LlmAgentLoop.setAppState :501-522）
            updater -> {
                captured.set(updater);
                Map<String, Object> next = updater.apply(Map.copyOf(state.get()));
                if (next != null) {
                    state.set(Map.copyOf(next));
                }
            },
            m -> {}, s -> {});
    }

    // ════════════════════════════════════════════════════════════════════
    // TaskCreateTool
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TaskCreate 成功后 setAppState：prev 无 expandedView → 应用后含 'tasks'（对齐 CC :116-119）")
    void taskCreate_expandsViewWhenAbsent() {
        // WHY: CC TaskCreateTool.ts:116-119 在 blocking-error 回滚后、返回前调用
        // setAppState(prev => expandedView='tasks')。测试 prev 无 expandedView 时
        // updater 置 'tasks'，验证对齐 CC :118 return { ...prev, expandedView: 'tasks' }。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-1");

        TaskCreateTool tool = new TaskCreateTool(taskService, null); // null hookRegistry → 跳过 hooks
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskCreate",
            JSON.createObjectNode().put("subject", "Write docs").put("description", "desc"));

        AtomicReference<Function<Map<String, Object>, Map<String, Object>>> captured =
            new AtomicReference<>();
        ToolUseContext ctx = ctxWithSetAppState(Map.of("verbose", false), captured);

        @SuppressWarnings("unchecked")
        ToolResult<TaskCreateTool.TaskCreateOutput> result =
            (ToolResult<TaskCreateTool.TaskCreateOutput>) tool.execute(call, ctx);

        // 副作用：updater 已捕获，prev 无 expandedView → 应用后含 'tasks'，且不丢原键
        Function<Map<String, Object>, Map<String, Object>> updater = captured.get();
        assertThat(updater).isNotNull();
        Map<String, Object> applied = updater.apply(Map.of("verbose", false));
        assertThat(applied).containsEntry("expandedView", "tasks").containsEntry("verbose", false);

        // execute 返回值不受副作用影响（创建成功消息）
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(result.data()).isInstanceOf(TaskCreateTool.TaskCreateOutput.class);
        assertThat(result.data().task().id()).isEqualTo("t-1");
        assertThat(result.data().task().subject()).isEqualTo("Write docs");
    }

    @Test
    @DisplayName("TaskCreate guard：prev.expandedView 已是 'tasks' → no-op 返回原引用（对齐 CC :117）")
    void taskCreate_noOpWhenAlreadyTasks() {
        // WHY: CC TaskCreateTool.ts:117 `if (prev.expandedView === 'tasks') return prev`——
        // 已展开时不重复拷贝，返回原引用（no-op），避免多余状态变更。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-1");

        TaskCreateTool tool = new TaskCreateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskCreate",
            JSON.createObjectNode().put("subject", "Write docs"));

        AtomicReference<Function<Map<String, Object>, Map<String, Object>>> captured =
            new AtomicReference<>();
        ToolUseContext ctx = ctxWithSetAppState(Map.of("expandedView", "tasks"), captured);

        @SuppressWarnings("unchecked")
        ToolResult<TaskCreateTool.TaskCreateOutput> result =
            (ToolResult<TaskCreateTool.TaskCreateOutput>) tool.execute(call, ctx);

        Function<Map<String, Object>, Map<String, Object>> updater = captured.get();
        assertThat(updater).isNotNull();
        Map<String, Object> prev = Map.of("expandedView", "tasks");
        assertThat(updater.apply(prev)).isSameAs(prev); // guard 返回原引用

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(result.data().task().id()).isEqualTo("t-1");
    }

    // ════════════════════════════════════════════════════════════════════
    // TaskUpdateTool
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TaskUpdate 一进来就 setAppState：prev 无 expandedView → 应用后含 'tasks'（对齐 CC :139-143）")
    void taskUpdate_expandsViewWhenAbsent() {
        // WHY: CC TaskUpdateTool.ts:139-143 在 taskId 解析后、getTask 存在性检查前调用
        // setAppState(prev => expandedView='tasks')——与 create 不同，更新一进来就展开。
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTask(any(), any())).thenReturn(Optional.of(
            new Task("t-1", "subject", "desc", null, null, Task.TaskStatus.PENDING,
                List.of(), List.of(), Map.of())));

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            JSON.createObjectNode().put("taskId", "t-1"));

        AtomicReference<Function<Map<String, Object>, Map<String, Object>>> captured =
            new AtomicReference<>();
        ToolUseContext ctx = ctxWithSetAppState(Map.of(), captured);

        ToolResult<String> result = tool.execute(call, ctx);

        Function<Map<String, Object>, Map<String, Object>> updater = captured.get();
        assertThat(updater).isNotNull();
        Map<String, Object> applied = updater.apply(Map.of());
        assertThat(applied).containsEntry("expandedView", "tasks");

        // execute 返回值不受副作用影响（更新成功消息）
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(summary(result)).contains("Updated task #t-1");
    }

    @Test
    @DisplayName("TaskUpdate guard：prev.expandedView 已是 'tasks' → no-op 返回原引用（对齐 CC :141）")
    void taskUpdate_noOpWhenAlreadyTasks() {
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTask(any(), any())).thenReturn(Optional.of(
            new Task("t-1", "subject", "desc", null, null, Task.TaskStatus.PENDING,
                List.of(), List.of(), Map.of())));

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            JSON.createObjectNode().put("taskId", "t-1"));

        AtomicReference<Function<Map<String, Object>, Map<String, Object>>> captured =
            new AtomicReference<>();
        ToolUseContext ctx = ctxWithSetAppState(Map.of("expandedView", "tasks"), captured);

        ToolResult<String> result = tool.execute(call, ctx);

        Function<Map<String, Object>, Map<String, Object>> updater = captured.get();
        assertThat(updater).isNotNull();
        Map<String, Object> prev = Map.of("expandedView", "tasks");
        assertThat(updater.apply(prev)).isSameAs(prev); // guard 返回原引用

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(summary(result)).contains("Updated task #t-1");
    }

    /** [IMP-C2] successWithStructuredOutput 折入 data(Map) 后，模型侧渲染文本在 "summary" 键。 */
    private static String summary(ToolResult<?> result) {
        Object data = result.data();
        if (data instanceof Map<?, ?> m && m.containsKey("summary")) {
            return String.valueOf(m.get("summary"));
        }
        return String.valueOf(data);
    }
}
