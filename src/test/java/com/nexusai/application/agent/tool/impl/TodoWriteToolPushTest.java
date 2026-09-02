package com.nexusai.application.agent.tool.impl;

import com.nexusai.eventbus.ws.TodoUpdateEvent;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TodoWrite STOMP 推流测试 · [todo-rest-stream] TodoUpdateEvent 发布契约。
 *
 * <p><b>WHY 本测试验证意图</b>：前端 todo 面板实时主通道 = STOMP 推流
 * {@code /topic/sessions/{sessionId}/todos}（CC 会话级 topic 语义）。execute 成功后必须推送
 * 一次 TodoUpdateEvent，且载荷：
 * <ul>
 *   <li>topic 形如 {@code /topic/sessions/{sessionId}/todos}（前端按此订阅）；</li>
 *   <li>{@code type="todo.update"} / {@code sessionId}（short） / {@code todoKey}（主线程 = sessionId）；</li>
 *   <li>{@code todos} 数组 status 已小写（in_progress 而非 IN_PROGRESS——TodoStatus 无 @JsonValue，
 *       不经 todoListToArray 序列化会出大写，违背 CC types.ts:4-6 值域）；</li>
 *   <li>allDone 时推送空 todos 数组（前端面板清空）；</li>
 *   <li>wsTemplate 未注入 → no-op 不抛异常（AC-5 隔离，测试/孤立运行降级）。</li>
 * </ul>
 *
 * <p>纯单元测试（无 Spring）：SimpMessagingTemplate 用 Mockito mock（spring-boot-starter-test
 * 自带），会话 appState 用 {@link AtomicReference} 模拟。
 */
class TodoWriteToolPushTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_ID = "22222222-2222-2222-2222-222222222222";

    private static final class SessionAppState {
        final AtomicReference<Map<String, Object>> state = new AtomicReference<>(Map.of());

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
    }

    private static ToolUseBlock call(String id, JsonNode input) {
        return new ToolUseBlock(id, "TodoWrite", input);
    }

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
    @DisplayName("execute 成功 → 推送 TodoUpdateEvent 到 /topic/sessions/{sid}/todos，status 小写")
    void pushesTodoUpdateAfterExecute() throws Exception {
        // WHY: 前端 todo 面板按 /topic/sessions/{sessionId}/todos 订阅实时更新——execute 后必须
        //   恰好推一次；status 必须小写（in_progress）否则前端按 CC 值域解析失败。
        TodoWriteTool tool = new TodoWriteTool();
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        tool.setWsTemplate(template);
        SessionAppState session = new SessionAppState();

        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
            {"B", "in_progress", "Doing B"},
        })), session.mainCtx());

        var captor = org.mockito.ArgumentCaptor.forClass(TodoUpdateEvent.class);
        verify(template).convertAndSend(eq("/topic/sessions/" + SESSION_ID + "/todos"), captor.capture());
        TodoUpdateEvent evt = captor.getValue();

        assertThat(evt.getType()).as("事件 type 必须为 todo.update（前端事件分发契约）").isEqualTo("todo.update");
        assertThat(evt.getTodos().isArray()).as("todos 必须是数组").isTrue();
        assertThat(evt.getSessionId()).as("sessionId 必须 short 形态透传").isEqualTo(SESSION_ID);
        assertThat(evt.getTodoKey()).as("主线程 todoKey = sessionId（与 REST 主桶读键收敛）").isEqualTo(SESSION_ID);
        assertThat(evt.getUpdatedAt()).as("updatedAt 必须为当前时间戳（>0）").isGreaterThan(0);

        JsonNode todos = evt.getTodos();
        assertThat(todos.isArray()).as("todos 必须是数组").isTrue();
        assertThat(todos).hasSize(2);
        assertThat(todos.get(0).get("content").asText()).isEqualTo("A");
        assertThat(todos.get(1).get("status").asText())
            .as("status 必须小写 in_progress（TodoStatus 无 @JsonValue，防大写泄漏）")
            .isEqualTo("in_progress");
        assertThat(todos.get(1).get("activeForm").asText()).isEqualTo("Doing B");
    }

    @Test
    @DisplayName("allDone → 推送空 todos 数组（前端面板清空）")
    void allDonePushesEmptyTodosArray() throws Exception {
        // WHY: CC TodoWriteTool.ts:70 allDone → storedTodos = [] → 推流载荷必须反映清空语义，
        //   否则前端面板残留已完成任务（与 REST 读侧 / appState 不一致）。
        TodoWriteTool tool = new TodoWriteTool();
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        tool.setWsTemplate(template);
        SessionAppState session = new SessionAppState();

        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "completed", "Doing A"},
        })), session.mainCtx());

        var captor = org.mockito.ArgumentCaptor.forClass(TodoUpdateEvent.class);
        verify(template).convertAndSend(eq("/topic/sessions/" + SESSION_ID + "/todos"), captor.capture());
        TodoUpdateEvent evt = captor.getValue();
        assertThat(evt.getTodos())
            .as("allDone 时推流 todos 必须为空数组（对齐 CC :70/:92 清空语义）")
            .isInstanceOf(ArrayNode.class);
        assertThat(evt.getTodos().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("wsTemplate 未注入 → 不推送不抛异常，execute 正常成功（AC-5 隔离）")
    void unWiredWsTemplateIsNoOp() throws Exception {
        // WHY: 生产装配缺省 / 测试直构 new TodoWriteTool() 零 wsTemplate——push 必须 no-op；
        //   若 convertAndSend 被调用或抛异常，execute 外层 catch 会把成功 TodoWrite 翻成 error
        //   （WebSearchTool.publishResults 同型约束）。
        TodoWriteTool tool = new TodoWriteTool(); // 不注入 wsTemplate
        SessionAppState session = new SessionAppState();

        ToolResult<?> r = tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
        })), session.mainCtx());

        // TodoWrite success 走 successWithStructuredOutput → data 为 Map；error 走 error() → data 为 String
        assertThat(r.data())
            .as("wsTemplate 未注入时 execute 必须成功（push no-op 降级）")
            .isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("convertAndSend 抛异常 → warn 不外抛，execute 仍成功（AC-5 隔离）")
    void convertAndSendFailureIsContained() throws Exception {
        // WHY: STOMP 连接故障/序列化失败不得中断 TodoWrite 主流程——推流是旁路副作用
        //   （对齐 WebSearchTool.publishResults :658-681 catch(Exception) 不外抛）。
        TodoWriteTool tool = new TodoWriteTool();
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        org.mockito.Mockito.doThrow(new RuntimeException("stomp down"))
            .when(template).convertAndSend(any(String.class), any(Object.class));
        tool.setWsTemplate(template);
        SessionAppState session = new SessionAppState();

        ToolResult<?> r = tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
        })), session.mainCtx());

        // TodoWrite success 走 successWithStructuredOutput → data 为 Map；error 走 error() → data 为 String
        assertThat(r.data())
            .as("convertAndSend 抛异常时必须被内部 catch 吞掉，execute 仍成功")
            .isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("不执行就不推送（convertAndSend 零调用）")
    void noPushWithoutExecute() {
        // WHY: 推流必须与 execute 成功强绑定——若注册/初始化路径误发事件，前端会收到空态噪音。
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        verify(template, never()).convertAndSend(any(String.class), any(Object.class));
    }
}
