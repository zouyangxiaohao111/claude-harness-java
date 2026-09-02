package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SubagentExecutor 清理链回归（S05 · OD-TDV1-1 清理侧迁移）。
 *
 * <p><b>WHY 本测试验证意图</b>：CC runAgent.ts:843-849 在子 Agent 退出时经 rootSetAppState
 * 从会话 appState.todos 移除该 agentId 桶：
 * <pre>
 * rootSetAppState(prev => { const { [agentId]: _, ...todos } = prev.todos; return { ...prev, todos } })
 * </pre>
 * Java 迁移前经 {@code TodoWriteTool.clearTodosForAgent}（实例级 CHM）；S05 迁移后
 * {@code SubagentExecutor.cleanupAgentTodos} 经父会话 ToolUseContext 的 setAppState 通道移除桶。
 *
 * <p>static seam（对齐 executeSubagentStartHooks :1578 抽法）：直接验证
 * {@link SubagentExecutor#cleanupAgentTodosFromAppState} 清理语义 —— 目标桶移除、其他桶保留、
 * 桶不存在/无 todos 键时 no-op（对齐 CC 解构后同值）。
 */
class SubagentExecutorCleanupAppStateTest {

    private static final UUID AGENT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SESSION_ID = "33333333-3333-3333-3333-333333333333";

    /** 模拟父会话 appState（对齐 LlmAgentLoop.appStateRef + setAppState 语义）。 */
    private static final class AppStateHolder {
        final AtomicReference<Map<String, Object>> state;

        AppStateHolder(Map<String, Object> initial) {
            this.state = new AtomicReference<>(initial == null ? Map.of() : Map.copyOf(initial));
        }

        ToolUseContext parentTuc(UUID agentId) {
            return ToolUseContext.of(
                agentId, SESSION_ID, PermissionMode.DEFAULT,
                List.of(), "", AbortController.NOOP, List.of(),
                null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, Map.of(), null,
                // getAppState: 返回不可变快照（对齐 LlmAgentLoop.getAppStateSnapshot）
                prev -> Map.copyOf(state.get()),
                // setAppState: 函数式 updater 应用到内部 state（对齐 LlmAgentLoop.setAppState）
                updater -> {
                    Map<String, Object> next = updater.apply(Map.copyOf(state.get()));
                    if (next != null) {
                        state.set(Map.copyOf(next));
                    }
                },
                m -> {}, s -> {});
        }

        Map<String, Object> snapshot() {
            return Map.copyOf(state.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> todosMap(Map<String, Object> snapshot) {
        Object todos = snapshot.get("todos");
        assertThat(todos).as("清理后快照必须保留 todos 键").isInstanceOf(Map.class);
        return (Map<String, Object>) todos;
    }

    private static List<?> bucket(Map<String, Object> snapshot, String agentKey) {
        return (List<?>) todosMap(snapshot).get(agentKey);
    }

    @Test
    @DisplayName("清理链：移除目标 agentId 桶，其他桶保留（对齐 CC runAgent.ts:843-849 解构移除）")
    void removesTargetBucketOnly() {
        AppStateHolder holder = new AppStateHolder(Map.of(
            "todos", Map.of(
                AGENT_A.toString(), List.of(new TodoWriteTool.TodoItem("A", TodoWriteTool.TodoStatus.PENDING, "Doing A")),
                AGENT_B.toString(), List.of(new TodoWriteTool.TodoItem("B", TodoWriteTool.TodoStatus.PENDING, "Doing B")))));
        ToolUseContext parentTuc = holder.parentTuc(AGENT_A);

        SubagentExecutor.cleanupAgentTodosFromAppState(parentTuc, AGENT_A.toString());

        Map<String, Object> snapshot = holder.snapshot();
        assertThat(todosMap(snapshot))
            .as("目标 agentId 桶必须被移除（CC: const { [agentId]: _, ...todos }）")
            .doesNotContainKey(AGENT_A.toString());
        assertThat(bucket(snapshot, AGENT_B.toString()))
            .as("其他 agentId 桶必须保留")
            .hasSize(1);
    }

    @Test
    @DisplayName("清理链：桶不存在 → no-op（快照不变，对齐 CC 解构后同值）")
    void noOpWhenBucketMissing() {
        AppStateHolder holder = new AppStateHolder(Map.of(
            "todos", Map.of(
                AGENT_B.toString(), List.of(new TodoWriteTool.TodoItem("B", TodoWriteTool.TodoStatus.PENDING, "Doing B")))));
        ToolUseContext parentTuc = holder.parentTuc(AGENT_A);
        Map<String, Object> before = holder.snapshot();

        SubagentExecutor.cleanupAgentTodosFromAppState(parentTuc, AGENT_A.toString());

        assertThat(holder.snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("清理链：appState 无 todos 键 → no-op（快照不变）")
    void noOpWhenNoTodosKey() {
        AppStateHolder holder = new AppStateHolder(Map.of("expandedView", "tasks"));
        ToolUseContext parentTuc = holder.parentTuc(AGENT_A);

        SubagentExecutor.cleanupAgentTodosFromAppState(parentTuc, AGENT_A.toString());

        assertThat(holder.snapshot()).isEqualTo(Map.of("expandedView", "tasks"));
    }

    @Test
    @DisplayName("清理链：parentTuc/agentId null → 不抛异常（standalone 兼容）")
    void noOpOnNullArguments() {
        AppStateHolder holder = new AppStateHolder(Map.of("todos", Map.of(
            AGENT_A.toString(), List.of(new TodoWriteTool.TodoItem("A", TodoWriteTool.TodoStatus.PENDING, "Doing A")))));
        ToolUseContext parentTuc = holder.parentTuc(AGENT_A);

        // null agentId / null parentTuc 均不抛异常
        SubagentExecutor.cleanupAgentTodosFromAppState(null, AGENT_A.toString());
        SubagentExecutor.cleanupAgentTodosFromAppState(parentTuc, null);
        SubagentExecutor.cleanupAgentTodosFromAppState(null, null);
    }
}
