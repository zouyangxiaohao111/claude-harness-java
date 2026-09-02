package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.AgentState;
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

/**
 * TodoWrite AgentState 同步测试 · [todo-rest-stream] Step5.5 双写载体。
 *
 * <p><b>WHY 本测试验证意图</b>：前端 todo 面板有两个读通道——
 * STOMP 推流 TodoUpdateEvent（实时）+ REST GET /api/v1/sessions/{sessionId}/todos（快照兜底）。
 * REST 读侧载体 = SessionAgentStateRegistry 持有的主会话 {@code AgentState.todos}（V1 内存桶，
 * 由 {@code TodoWriteTool.execute} Step5.5 经 sessionStateResolver 写入）。本测试钉死：
 * <ul>
 *   <li>execute 成功后 AgentState.todos 与 appStateRef（loop 自身 todo reminder 读侧）<b>双写一致</b>
 *       ——两通道读到同一份 todo 状态，不漂移；</li>
 *   <li>主线程 todoKey = sessionId（与 REST 主桶读键 sessionId 收敛，防 EV-TDV3-TV1-033）；</li>
 *   <li>allDone 时 AgentState.todos 桶存空数组（对齐 CC TodoWriteTool.ts:70/:92 清空语义，前端
 *       面板清空）。</li>
 * </ul>
 *
 * <p>纯单元测试（无 Spring）：AgentState 直构 + sessionStateResolver 直接注入，会话 appState 用
 * {@link AtomicReference} 模拟（对齐 LlmAgentLoop.appStateRef 函数式更新语义）。
 */
class TodoWriteToolAgentStateSyncTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_ID = "22222222-2222-2222-2222-222222222222";

    /** 模拟一个会话级 appState（对齐 LlmAgentLoop.appStateRef :490 + setAppState :539-560）。 */
    private static final class SessionAppState {
        final AtomicReference<Map<String, Object>> state = new AtomicReference<>(Map.of());

        /** 主线程 ctx（agentId=null → resolveTodoKey 回退 sessionId，对齐 CC getSessionId()）。 */
        ToolUseContext mainCtx() {
            return ToolUseContext.of(
                null, SESSION_ID, PermissionMode.DEFAULT,
                List.of(), "", AbortController.NOOP, List.of(),
                null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, Map.of(), null,
                prev -> Map.copyOf(state.get()),
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

    @Test
    @DisplayName("双写一致：execute 成功后 AgentState.todos 与 appStateRef 同一份 todo（REST 读侧与 loop 读侧不漂移）")
    void dualWriteSyncsAgentStateTodos() throws Exception {
        // WHY: 前端 REST 读侧（registry.get(sessionId).todos()）与 loop 自身 reminder 读侧
        //   （appStateRef）必须读到同一份存储——否则推流/REST/提醒三通道不一致。
        TodoWriteTool tool = new TodoWriteTool();
        SessionAppState session = new SessionAppState();
        AgentState state = new AgentState("test");
        tool.setSessionStateResolver(sid -> state);

        ToolResult<?> r = tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
            {"B", "in_progress", "Doing B"},
        })), session.mainCtx());

        // TodoWrite success 走 successWithStructuredOutput → data 为 Map；error 走 error() → data 为 String
        assertThat(r.data())
            .as("execute 必须成功（Step5.5 同步/推流为旁路副作用，不改变执行结果）")
            .isInstanceOf(Map.class);

        // AgentState.todos 读侧（REST 主桶键 = sessionId，主线程 todoKey 收敛）
        List<TodoWriteTool.TodoItem> agentStateBucket = state.todos().get(SESSION_ID);
        assertThat(agentStateBucket)
            .as("AgentState.todos[sessionId] 必须包含 execute 写入的 2 项（REST 读侧载体）")
            .hasSize(2);
        assertThat(agentStateBucket.get(0).content()).isEqualTo("A");
        assertThat(agentStateBucket.get(1).content()).isEqualTo("B");
        assertThat(agentStateBucket.get(1).status()).isEqualTo(TodoWriteTool.TodoStatus.IN_PROGRESS);
        assertThat(agentStateBucket.get(0).activeForm()).isEqualTo("Doing A");

        // appStateRef 读侧（loop 自身 todo reminder 读侧）——双写一致
        @SuppressWarnings("unchecked")
        Map<String, Object> appStateTodos = (Map<String, Object>) session.snapshot().get("todos");
        assertThat(appStateTodos.get(SESSION_ID))
            .as("appState.todos[sessionId] 必须与 AgentState.todos 同一份（双写不漂移）")
            .isInstanceOf(List.class);
        assertThat((List<?>) appStateTodos.get(SESSION_ID)).hasSize(2);
    }

    @Test
    @DisplayName("顺序保持：AgentState.todos 列表顺序 = 输入顺序（前端面板渲染顺序稳定）")
    void agentStateBucketPreservesInputOrder() throws Exception {
        // WHY: CC appState.todos[key] 是数组，顺序即前端面板渲染序；若 Java 侧乱序
        //   前端会看到任务列表跳序（违背 TodoWrite 输入即列表语义）。
        TodoWriteTool tool = new TodoWriteTool();
        SessionAppState session = new SessionAppState();
        AgentState state = new AgentState("test");
        tool.setSessionStateResolver(sid -> state);

        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"First", "pending", "Doing First"},
            {"Second", "in_progress", "Doing Second"},
            {"Third", "pending", "Doing Third"},
        })), session.mainCtx());

        List<TodoWriteTool.TodoItem> bucket = state.todos().get(SESSION_ID);
        assertThat(bucket).extracting(TodoWriteTool.TodoItem::content)
            .as("AgentState.todos 列表必须保持输入顺序（First/Second/Third）")
            .containsExactly("First", "Second", "Third");
    }

    @Test
    @DisplayName("allDone 清空：3 条全 COMPLETED → AgentState.todos 桶空数组（对齐 CC :70/:92）")
    void allDoneClearsAgentStateBucket() throws Exception {
        // WHY: CC TodoWriteTool.ts:70 newTodos = allDone ? [] : todos —— 全完成即清空。
        //   前端面板据此清空；若 Java 侧仍保留 completed 项，面板会残留已完成任务。
        TodoWriteTool tool = new TodoWriteTool();
        SessionAppState session = new SessionAppState();
        AgentState state = new AgentState("test");
        tool.setSessionStateResolver(sid -> state);

        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "completed", "Doing A"},
            {"B", "completed", "Doing B"},
            {"C", "completed", "Doing C"},
        })), session.mainCtx());

        assertThat(state.todos().get(SESSION_ID))
            .as("allDone 时 AgentState.todos[sessionId] 必须为空数组（CC :70/:92 清空语义，前端面板清空）")
            .isEmpty();
    }

    @Test
    @DisplayName("resolver 未接线：execute 正常成功，AgentState 同步 skip（REST 读空，不破坏执行）")
    void unWiredResolverDoesNotBreakExecute() throws Exception {
        // WHY: 生产装配缺省（sessionAgentStateRegistry required=false 未接线）时必须降级 no-op——
        //   若同步块抛异常会破坏 TodoWrite 主流程（推流/存储已 done，同步仅旁路）。
        TodoWriteTool tool = new TodoWriteTool(); // 不注入 resolver
        SessionAppState session = new SessionAppState();

        ToolResult<?> r = tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
        })), session.mainCtx());

        // TodoWrite success 走 successWithStructuredOutput → data 为 Map；error 走 error() → data 为 String
        assertThat(r.data())
            .as("resolver 未接线时 execute 仍必须成功（null-safe 降级 skip）")
            .isInstanceOf(Map.class);
        // appState 正常写（存储本身不受影响），仅 AgentState.todos 无同步
        assertThat(session.snapshot().get("todos")).isNotNull();
    }

    @Test
    @DisplayName("会话不可达（resolver 返回 null）：AgentState 同步 skip，不抛异常")
    void unresolvableSessionSkipsSync() throws Exception {
        // WHY: SessionAgentStateRegistry.get 未命中（无活跃 run）返回 null——同步块必须 null-safe，
        //   否则并发工具线程上 resolver 命中空态会 NPE 翻盘整个 TodoWrite。
        TodoWriteTool tool = new TodoWriteTool();
        SessionAppState session = new SessionAppState();
        tool.setSessionStateResolver(sid -> null);   // 会话不可达 → null

        ToolResult<?> r = tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
        })), session.mainCtx());

        // TodoWrite success 走 successWithStructuredOutput → data 为 Map；error 走 error() → data 为 String
        assertThat(r.data())
            .as("resolver 命中 null（会话不可达）时 execute 仍必须成功（null-safe）")
            .isInstanceOf(Map.class);
    }
}
