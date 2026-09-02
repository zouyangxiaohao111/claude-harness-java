package com.nexusai.application.agent;

import com.nexusai.application.agent.tool.impl.TodoWriteTool.TodoItem;
import com.nexusai.application.agent.tool.impl.TodoWriteTool.TodoStatus;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [R3] 会话 todos doRun 回读注入测试 · 对齐 CC appState.todos 全 map 形态（TodoWriteTool.ts:65-94）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>回读注入是跨 send 唯一通道</b>——LlmAgentLoop prototype 每 send 新实例 → appStateRef
 *       恒空；sessions.todos（V43 列，TodoWriteTool Step5.6 写入）在 doRun 入口回读注入。消费方 =
 *       AgentLoopContext.maybeInjectTodoReminder（重启后 todo reminder）+ TodoWriteTool Step2
 *       oldTodos（:783-784）。若注入回归丢失，重启后 todo reminder 失效，本测试 RED。</li>
 *   <li><b>todos 列 null 空态</b>——从未 TodoWrite 的会话列 null → 不注入 todos 键不崩（对齐 CC
 *       新会话空态）。</li>
 *   <li><b>无 SessionMapper 容错</b>——POJO 单测 / 单体工具场景不注入 SessionMapper →
 *       sessionMapper==null 跳过（对齐 s19-P1-6 / effort 继承容错模式）。</li>
 * </ol>
 *
 * <p><b>测试基建</b>: 复用 LlmAgentLoopEffortInheritanceTest.java:48-78 同款真实 run 模式
 * （裸 {@code new LlmAgentLoop(factory)} + mocked provider 首调 stop）；SessionMapper 经
 * {@code setSessionMapper} setter 注入。会话键用 short "sess-abcdef01"（[session-id-short] 直键）。
 */
class LlmAgentLoopTodosReadbackInjectionTest {

    /** provider 首调返回 stop 纯文本 → loop 正常退出（对齐既有真实 run 测试）。 */
    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /** 真实 LlmAgentLoop + 注入 mock SessionMapper + mocked provider（返回 loop 以读 appStateRef）。 */
    private static LlmAgentLoop loopWithSessionMapper(SessionMapper sessionMapper) {
        LlmProvider provider = stopProvider("r3 todos response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        if (sessionMapper != null) {
            loop.setSessionMapper(sessionMapper);
        }
        return loop;
    }

    /**
     * 【R3 核心】sessions.todos 列已持久化（TodoWrite Step5.6 写）→ 新/恢复 run 的 doRun 入口
     * 回读注入 appStateRef.todos 主桶（跨 send reminder/oldTodos 唯一通道）。若回读回归丢失，
     * 重启后 todo reminder / oldTodos 全失。
     */
    @Test
    @DisplayName("[R3] sessions.todos 列已持久化 → doRun 回读注入 appStateRef.todos 主桶 List<TodoItem>")
    void persistedTodosReadbackInjectedIntoAppState() {
        SessionRecord session = new SessionRecord();
        session.setTodos("{\"sess-abcdef01\":[{\"content\":\"A\",\"status\":\"in_progress\",\"activeForm\":\"Doing A\"}]}");
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById("sess-abcdef01")).thenReturn(session);
        LlmAgentLoop loop = loopWithSessionMapper(sessionMapper);

        loop.run(RunRequest.session("r3 query", "sess-abcdef01", null,
            ProviderConfig.empty(), "test-model", null, null));

        Map<String, Object> snapshot = loop.getAppStateSnapshot();
        Object todosObj = snapshot.get("todos");
        assertThat(todosObj)
            .as("doRun 回读必须注入 appStateRef.todos（跨 send reminder/oldTodos 唯一通道）")
            .isInstanceOf(Map.class);
        Map<?, ?> todos = (Map<?, ?>) todosObj;
        Object bucket = todos.get("sess-abcdef01");
        assertThat(bucket).as("主桶键必须 = sessionId").isInstanceOf(List.class);
        List<?> items = (List<?>) bucket;
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).isInstanceOf(TodoItem.class);
        TodoItem item = (TodoItem) items.get(0);
        assertThat(item.content()).isEqualTo("A");
        assertThat(item.status()).as("回读 TodoItem.status 必须解析为枚举 IN_PROGRESS").isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(item.activeForm()).isEqualTo("Doing A");
    }

    /**
     * 【负向】sessions.todos 列 null（新会话 / 从未 TodoWrite）→ appStateRef 无 todos 键，不崩
     * （对齐 CC 新会话空态：doRun 回读 skip）。
     */
    @Test
    @DisplayName("[R3] sessions.todos 列 null → appStateRef 无 todos 键不崩（空态）")
    void nullTodosColumnNoCrash() {
        SessionRecord session = new SessionRecord();   // todos null
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById("sess-abcdef01")).thenReturn(session);
        LlmAgentLoop loop = loopWithSessionMapper(sessionMapper);

        loop.run(RunRequest.session("r3 query", "sess-abcdef01", null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(loop.getAppStateSnapshot().get("todos"))
            .as("todos 列 null → 不注入 todos 键（空态，不崩）")
            .isNull();
    }

    /**
     * 【容错】未注入 SessionMapper（POJO 单测 / 单体工具场景）→ 跳过回读不 NPE。若漏判 null，
     * 所有 new LlmAgentLoop 单测崩溃。
     */
    @Test
    @DisplayName("[R3] 未注入 SessionMapper → 跳过回读不 NPE（sessionMapper null 容错）")
    void sessionMapperNullNoCrash() {
        LlmAgentLoop loop = loopWithSessionMapper(null);

        loop.run(RunRequest.session("r3 query", "sess-abcdef01", null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(loop.getAppStateSnapshot())
            .as("sessionMapper==null → 跳过回读，run 正常完成不崩")
            .isNotNull();
    }
}
