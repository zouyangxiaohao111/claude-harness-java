package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TodoWrite reminder 注入读侧回归（S05 · OD-TDV1-1 读侧迁移）。
 *
 * <p><b>WHY 本测试验证意图</b>：CC attachments.ts:3304-3306 的 reminder 注入读侧直接读会话
 * appState：
 * <pre>
 * const todoKey = toolUseContext.agentId ?? getSessionId()
 * const todos = appState.todos[todoKey] ?? []
 * </pre>
 * Java 迁移前经 {@code TodoWriteTool.currentTodos}（实例级 CHM）读；S05 迁移后
 * {@code AgentLoopContext.maybeInjectTodoReminder} 经 {@code ctx.sessionState().appStateReader()}
 * 读会话 appState 桶（LlmAgentLoop.buildSessionStateFromInstance 注入）。
 *
 * <p><b>回归不变量</b>：注入行为与迁移前一致 —— turns 达标 + TodoWrite 可用时注入
 * TODO_REMINDER_TEXT，且携带当前 todo 列表（{@code N. [status] content} 格式，
 * 对齐 CC messages.ts:3664-3671）。迁移前该列表来自 CHM（无写入者 → 恒空）；迁移后来自
 * appState 桶——本测试断言有桶时文本注入、无桶/无通道时仅 base 文本（行为等价于迁移前）。
 */
class TodoWriteReminderInjectionTest {

    private static final String SESSION_ID = "22222222-2222-2222-2222-222222222222";
    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeAll
    static void enableTodoWriteV1() {
        // TodoWrite.isEnabled() = !isTodoV2Enabled()（TaskSystemConfig.java:133-142）。
        // Java 默认 interactive=true → V2（TodoWrite 不注册）；测试显式配置 V1 环境
        // （对齐 CC 默认非交互 → V1，tasks.ts:133-139），使 reminder 注入读侧路径可达。
        System.setProperty("nexusai.interactive", "false");
    }

    @AfterAll
    static void restoreInteractive() {
        System.clearProperty("nexusai.interactive");
    }

    /** 构造最小 AgentLoopContext：toolRegistry 含 TodoWrite + sessionState 注入 appState 读通道。 */
    private static AgentLoopContext ctxWithAppState(Map<String, Object> appStateSnapshot) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TodoWriteTool());

        AgentLoopContext.LoopSessionState session = new AgentLoopContext.LoopSessionState();
        if (appStateSnapshot != null) {
            // 对齐 LlmAgentLoop.buildSessionStateFromInstance: session.setAppStateReader(prev -> getAppStateSnapshot())
            session.setAppStateReader(ignored -> Map.copyOf(appStateSnapshot));
        }
        // [WF-3 融合] record 34 组件: 1=toolRegistry, 31=sessionState, 34=sdkEventQueue
        //   (DEL-14 删 CommandQueue 组件 → sessionState 位 32→31；CompactContext / PromptTooLongHandler
        //    位已被 ContextCompact 对齐移除；sdkEventQueue 尾置 34)
        return new AgentLoopContext(
            registry, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, // 8-18
            null, null, null, null, null, null, null, // 19-25
            null, null, null, null, null, session, null, null, null, null, null); // 26-34 · 35 queueEventPublisher · 36 modelCostCalculator（新增）
    }

    /** turns 达标（≥ turnsSinceWrite=10）：10 个无 TodoWrite 的 assistant turn。 */
    private static AgentState stateTurnsEligible() {
        AgentState state = new AgentState("system", SESSION_ID, AGENT_ID);
        for (int i = 0; i < 10; i++) {
            state.recordAssistantTurnForTodoReminder(false);
        }
        return state;
    }

    /** 主线程 turns 达标 state：agentId==null（主线程语义，对齐 CC toolUseContext.agentId=undefined）。 */
    private static AgentState mainThreadStateTurnsEligible() {
        AgentState state = new AgentState("system", SESSION_ID, null);
        for (int i = 0; i < 10; i++) {
            state.recordAssistantTurnForTodoReminder(false);
        }
        return state;
    }

    private static List<ChatMessageDto> messages() {
        return new ArrayList<>();
    }

    private static String reminderContent(List<ChatMessageDto> withReminder) {
        return withReminder.get(withReminder.size() - 1).content();
    }

    @Test
    @DisplayName("读侧迁移：appState 桶有 todo → reminder 注入携带 todo 列表文本（对齐 CC attachments.ts:3304-3306）")
    void injectsTodosFromAppStateBucket() {
        String agentKey = AGENT_ID.toString();
        Map<String, Object> appState = Map.of(
            "todos", Map.of(agentKey, List.of(
                new TodoWriteTool.TodoItem("A", TodoWriteTool.TodoStatus.PENDING, "Doing A"),
                new TodoWriteTool.TodoItem("B", TodoWriteTool.TodoStatus.IN_PROGRESS, "Doing B"))));
        AgentLoopContext ctx = ctxWithAppState(appState);

        List<ChatMessageDto> withReminder =
            AgentLoopContext.maybeInjectTodoReminder(ctx, stateTurnsEligible(), messages());

        assertThat(withReminder)
            .as("turns 达标 + TodoWrite 可用 → 必须注入 reminder 消息")
            .hasSize(1);
        String content = reminderContent(withReminder);
        assertThat(content)
            .as("注入文本必须包含 base reminder（对齐 CC messages.ts:3668 逐字文本）")
            .startsWith("The TodoWrite tool hasn't been used recently.");
        assertThat(content)
            .as("注入文本必须携带 appState.todos[agentId] 桶内容（对齐 CC messages.ts:3664-3671: N. [status] content）")
            .contains("\n\nHere are the existing contents of your todo list:\n\n[")
            .contains("1. [pending] A")
            .contains("2. [in_progress] B");
    }

    @Test
    @DisplayName("IM1 主线程读侧键归一：agentId==null 时读 sessionId 键（对齐 CC getSessionId() 双侧同键）")
    void mainThreadReadsTodosFromSessionIdKey() {
        // WHY（规则九）：CC attachments.ts:3304-3306 todoKey = toolUseContext.agentId ?? getSessionId()。
        // 写侧 TodoWriteTool.resolveTodoKey = ctx.agentId() = effectiveAgentId = state.sessionId()（sessionUuid）；
        // 读侧若回退 ctx.streamSessionId()（原始 "sess-xxx"）→ 读键 ≠ 写键 → 主线程 reminder 读不到 todo 列表
        // （IM1 / EV-TDV3-TV1-033）。本测试断言主线程（agentId==null）读侧按 sessionId 键命中 appState 桶。
        String sessionKey = SESSION_ID.toString();
        Map<String, Object> appState = Map.of(
            "todos", Map.of(sessionKey, List.of(
                new TodoWriteTool.TodoItem("A", TodoWriteTool.TodoStatus.PENDING, "Doing A"))));
        AgentLoopContext ctx = ctxWithAppState(appState);

        List<ChatMessageDto> withReminder =
            AgentLoopContext.maybeInjectTodoReminder(ctx, mainThreadStateTurnsEligible(), messages());

        assertThat(withReminder)
            .as("主线程 turns 达标 + TodoWrite 可用 → 必须注入 reminder 消息")
            .hasSize(1);
        assertThat(reminderContent(withReminder))
            .as("主线程读侧键必须命中 appState.todos[sessionId] 桶（对齐 CC getSessionId() 双侧同键）")
            .contains("1. [pending] A");
    }

    @Test
    @DisplayName("读侧回归：appState 桶为空 → 注入仅 base 文本（与迁移前 CHM 恒空行为一致）")
    void injectsBaseOnlyWhenBucketEmpty() {
        String agentKey = AGENT_ID.toString();
        Map<String, Object> appState = Map.of("todos", Map.of(agentKey, List.of()));
        AgentLoopContext ctx = ctxWithAppState(appState);

        List<ChatMessageDto> withReminder =
            AgentLoopContext.maybeInjectTodoReminder(ctx, stateTurnsEligible(), messages());

        assertThat(withReminder).hasSize(1);
        assertThat(reminderContent(withReminder))
            .as("空桶 → 无 todo 列表段落（对齐 CC todos.length===0 时不拼接列表）")
            .doesNotContain("Here are the existing contents of your todo list");
    }

    @Test
    @DisplayName("读侧回归：无 appState 读通道（单测/standalone 构造路径）→ 注入仅 base 文本，不抛异常")
    void injectsBaseOnlyWithoutAppStateReader() {
        AgentLoopContext ctx = ctxWithAppState(null);

        List<ChatMessageDto> withReminder =
            AgentLoopContext.maybeInjectTodoReminder(ctx, stateTurnsEligible(), messages());

        assertThat(withReminder).hasSize(1);
        assertThat(reminderContent(withReminder))
            .as("无通道 → 无 todo 历史，仅 base 文本")
            .doesNotContain("Here are the existing contents of your todo list");
    }

    @Test
    @DisplayName("读侧回归：turns 未达标 → 不注入（行为与迁移前一致）")
    void skipsWhenTurnsNotEligible() {
        AgentLoopContext ctx = ctxWithAppState(Map.of("todos", Map.of(
            AGENT_ID.toString(), List.of(new TodoWriteTool.TodoItem("A", TodoWriteTool.TodoStatus.PENDING, "Doing A")))));
        AgentState state = new AgentState("system", SESSION_ID, AGENT_ID);

        List<ChatMessageDto> withReminder =
            AgentLoopContext.maybeInjectTodoReminder(ctx, state, messages());

        assertThat(withReminder)
            .as("turns 未达标 → 原样返回（CC attachments.ts:3300-3303 门控）")
            .isEmpty();
    }
}
