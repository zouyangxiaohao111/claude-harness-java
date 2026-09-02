package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TodoWrite 存储介质迁移 · 会话生命周期测试（S05 · OD-TDV1-1）。
 *
 * <p><b>WHY 本测试验证意图</b>（CC 行为，不信注释）：CC TodoWriteTool.ts:65-94 的存储是
 * 会话级 {@code appState.todos[todoKey]} —— 跨 turn 存活、会话（进程重启）清空：
 * <pre>
 * const oldTodos = appState.todos[todoKey] ?? []      // :68 缺省空列表
 * const newTodos = allDone ? [] : todos               // :70 全 completed → 存空数组
 * context.setAppState(prev => ({...prev, todos: {...prev.todos, [todoKey]: newTodos}}))  // :88-94
 * </pre>
 *
 * <p><b>RED 核心</b>：会话生命周期语义 —— 同一 TodoWriteTool 实例、不同会话（模拟新 appState
 * 快照）之间不得共享 todo 桶。旧实现（单例 Bean 实例级 ConcurrentHashMap）跨会话存活，
 * 本测试的 {@link #newSessionStartsWithEmptyTodos} 必须失败。
 *
 * <p>会话 appState 用 {@link AtomicReference} 模拟（对齐 LlmAgentLoop.appStateRef +
 * setAppState/getAppStateSnapshot 函数式更新语义，ToolUseContext.java:86-87 桥接字段）。
 * 纯单元测试（无 Spring），参照 TaskToolsExpandedViewTest 捕获模式。
 */
class TodoWriteToolSessionLifecycleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_ID = "22222222-2222-2222-2222-222222222222";

    /** 模拟一个会话级 appState（对齐 LlmAgentLoop.appStateRef :490 + setAppState :539-560）。 */
    private static final class SessionAppState {
        final AtomicReference<Map<String, Object>> state = new AtomicReference<>(Map.of());

        ToolUseContext ctx(UUID agentId) {
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

        /** 会话快照 · 对齐 LlmAgentLoop.getAppStateSnapshot()。 */
        Map<String, Object> snapshot() {
            return Map.copyOf(state.get());
        }
    }

    private static ToolUseBlock call(String id, JsonNode input) {
        return new ToolUseBlock(id, "TodoWrite", input);
    }

    /** 构造 CC inputSchema 形状：{todos: [{content, status, activeForm}, ...]}。 */
    private static JsonNode inputWithTodos(String[][] items) {
        ObjectNode input = JSON.createObjectNode();
        ArrayNode arr = input.putArray("todos");
        for (String[] item : items) {
            ObjectNode n = arr.addObject();
            n.put("content", item[0]);
            n.put("status", item[1]);
            n.put("activeForm", item[2]);
        }
        return input;
    }

    /** 从 ToolResult.structuredOutput 解析 CC data 三字段（oldTodos/newTodos/verificationNudgeNeeded）。 */
    private static JsonNode structured(JsonNode node, String field) {
        return node.get(field);
    }

    @Test
    @DisplayName("同一会话内两次 execute 共享 todo 桶（对齐 CC appState.todos[key] 跨 turn 存活）")
    void sameSessionSharesTodosAcrossExecutes() throws Exception {
        TodoWriteTool tool = new TodoWriteTool();
        SessionAppState session = new SessionAppState();
        ToolUseContext ctx = session.ctx(UUID.randomUUID());

        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
            {"B", "in_progress", "Doing B"},
        })), ctx);

        ToolResult<?> r2 = tool.execute(call("c2", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
            {"B", "in_progress", "Doing B"},
            {"C", "pending", "Doing C"},
        })), ctx);

        JsonNode so = JSON.readTree((String) ToolResult.presentationMeta(r2).get("structured_output"));
        // CC TodoWriteTool.ts:68: oldTodos = appState.todos[todoKey] ?? [] —— 第二次执行应读到第一次写入的桶
        assertThat(structured(so, "oldTodos"))
            .as("同一会话第二次 execute 的 oldTodos 必须包含第一次写入的 todo（CC appState 跨 turn 存活）")
            .hasSize(2);
        assertThat(structured(so, "oldTodos").get(0).get("content").asText()).isEqualTo("A");
        assertThat(structured(so, "newTodos"))
            .as("data.newTodos 必须是输入原样（非清空后列表，CC TodoWriteTool.ts:99）")
            .hasSize(3);
    }

    @Test
    @DisplayName("同一会话内不同 todoKey（agentId 桶）互不串扰（对齐 CC [todoKey] 分桶）")
    void differentTodoKeysDoNotInterfere() throws Exception {
        TodoWriteTool tool = new TodoWriteTool();
        SessionAppState session = new SessionAppState();
        UUID mainAgent = UUID.randomUUID();
        UUID subAgent = UUID.randomUUID();

        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
            {"B", "in_progress", "Doing B"},
        })), session.ctx(mainAgent));

        // 子 Agent 桶（不同 todoKey）读不到主线程桶 —— CC: appState.todos[agentId ?? sessionId]
        ToolResult<?> r2 = tool.execute(call("c2", inputWithTodos(new String[][]{
            {"C", "pending", "Doing C"},
        })), session.ctx(subAgent));

        JsonNode so = JSON.readTree((String) ToolResult.presentationMeta(r2).get("structured_output"));
        assertThat(structured(so, "oldTodos"))
            .as("子 Agent 桶（todoKey=agentId）必须与主线程桶隔离（CC TodoWriteTool.ts:67-68 分桶）")
            .hasSize(0);

        // 桶级隔离：主线程桶保留 [A,B]，子 Agent 桶为 [C]
        Object todos = session.snapshot().get("todos");
        assertThat(todos).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> todosMap = (Map<String, Object>) todos;
        assertThat(todosMap.get(mainAgent.toString()))
            .as("主线程桶必须保持第一次写入的 2 项（不被子 Agent 写入干扰）")
            .isInstanceOf(List.class);
        assertThat(((List<?>) todosMap.get(mainAgent.toString()))).hasSize(2);
        assertThat(((List<?>) todosMap.get(subAgent.toString()))).hasSize(1);
    }

    @Test
    @DisplayName("allDone 清空语义：全 completed → 桶存空数组，但 data.newTodos 返回输入原样（CC :70/:99）")
    void allDoneClearsStoredBucket() throws Exception {
        TodoWriteTool tool = new TodoWriteTool();
        SessionAppState session = new SessionAppState();
        UUID agent = UUID.randomUUID();

        ToolResult<?> r1 = tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "completed", "Doing A"},
            {"B", "completed", "Doing B"},
        })), session.ctx(agent));

        JsonNode so = JSON.readTree((String) ToolResult.presentationMeta(r1).get("structured_output"));
        // CC TodoWriteTool.ts:99: data.newTodos = todos（输入原样，非清空后）
        assertThat(structured(so, "newTodos"))
            .as("data.newTodos 必须是输入原样（CC :99 返回输入 todos）")
            .hasSize(2);

        // CC TodoWriteTool.ts:70 + :88-94: stored = allDone ? [] : todos → appState.todos[key] = []
        Object todos = session.snapshot().get("todos");
        @SuppressWarnings("unchecked")
        Map<String, Object> todosMap = (Map<String, Object>) todos;
        assertThat(todosMap.get(agent.toString()))
            .as("allDone 时桶必须存空数组（CC :70 newTodos = allDone ? [] : todos）")
            .isInstanceOf(List.class);
        assertThat((List<?>) todosMap.get(agent.toString())).isEmpty();
    }

    @Test
    @DisplayName("会话重启（新 appState）→ oldTodos 缺省空列表，不残留旧会话数据（CC 会话级生命周期）")
    void newSessionStartsWithEmptyTodos() throws Exception {
        TodoWriteTool tool = new TodoWriteTool(); // 同一单例 Bean 实例
        UUID agent = UUID.randomUUID();

        // 会话 1：写入 todo 桶
        SessionAppState session1 = new SessionAppState();
        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
            {"B", "in_progress", "Doing B"},
        })), session1.ctx(agent));

        // 会话 2（进程重启语义：全新 appState）：oldTodos 必须为空 —— 旧实现（实例级 CHM）跨会话存活，此处 FAIL
        SessionAppState session2 = new SessionAppState();
        ToolResult<?> r2 = tool.execute(call("c2", inputWithTodos(new String[][]{
            {"C", "pending", "Doing C"},
        })), session2.ctx(agent));

        JsonNode so = JSON.readTree((String) ToolResult.presentationMeta(r2).get("structured_output"));
        assertThat(structured(so, "oldTodos"))
            .as("新会话（重启）的 oldTodos 必须为空列表（CC appState 会话级生命周期：跨 turn 存活、重启清空；旧实例级 CHM 违反）")
            .hasSize(0);
    }

    @Test
    @DisplayName("DC-1: 无 context 调用显式失败 IllegalStateException（对齐 CC call 恒有 context）")
    void executeWithoutContext_throwsIllegalStateException() {
        // WHY: CC TodoWriteTool.ts:65 call({todos}, context) 恒有 context，todoKey 唯一来源是
        //   运行时 context（:67 context.agentId ?? getSessionId()）。旧 Java 实现在 ctx==null 时
        //   静默回退构造器 sessionId（"default" 桶）——本轮 DC-1 删除 sessionId 字段，无 ctx 必须
        //   显式失败（规则十二 Fail loud），不得再用 "default" 兜底导致跨会话串桶。
        TodoWriteTool tool = new TodoWriteTool();
        ToolUseBlock block = call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
        }));

        assertThatThrownBy(() -> tool.execute(block))
            .as("单参 execute（委托 execute(call, null)）必须抛 IllegalStateException（DC-1：无 context 显式失败）")
            .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> tool.execute(block, (ToolUseContext) null))
            .as("二参 execute 传 null ctx 必须抛 IllegalStateException（DC-1：CC call 恒有 context）")
            .isInstanceOf(IllegalStateException.class);
    }
}
