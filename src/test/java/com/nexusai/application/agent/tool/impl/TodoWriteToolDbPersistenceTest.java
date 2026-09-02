package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TodoWrite sessions.todos DB 持久化测试 · [R3 持久升级] execute Step5.6 写 sessions.todos 列。
 *
 * <p><b>WHY 本测试验证意图</b>：sessions.todos（V43 列）是跨 send/重启的会话 todo <b>真源</b>
 * （doRun 回读注入 / SessionDto.todos / Controller REST 都读它）。execute 成功后必须把 appState
 * 全 map 规范形写入列：
 * <ul>
 *   <li><b>status 小写</b>——DB 是跨 send 真源，写错 status 大小写（IN_PROGRESS）读回即坏
 *       （TodoStatus 无 @JsonValue，必须经 todosMapToJson 规范化，CC types.ts:4-6 值域）；</li>
 *   <li><b>allDone 清空语义</b>——storedTodos=[] 时桶键保留、值为空数组（CC TodoWriteTool.ts:70/:92）；</li>
 *   <li><b>含子 agent 桶</b>——全 map 入列（appState.todos 现有子 agent 桶不被覆盖丢失）；</li>
 *   <li><b>AC-5 隔离</b>——sessionMapper 未注入 / selectOneById 返回 null → warn+skip，execute 仍成功。</li>
 * </ul>
 *
 * <p>纯单元测试（无 Spring）：SessionMapper 用 Mockito mock，会话 appState 用
 * {@link AtomicReference} 模拟（照抄 TodoWriteToolPushTest 基建）。
 */
class TodoWriteToolDbPersistenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_ID = "22222222-2222-2222-2222-222222222222";

    private static final class SessionAppState {
        final AtomicReference<Map<String, Object>> state = new AtomicReference<>(Map.of());

        SessionAppState() {
        }

        SessionAppState(Map<String, Object> initial) {
            state.set(Map.copyOf(initial));
        }

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
    @DisplayName("execute 成功 → sessions.todos 列写入规范形 JSON，status 小写（DB 是跨 send 真源）")
    void executePersistsTodosToDb() throws Exception {
        // WHY: DB 是跨 send/重启真源——status 必须小写（in_progress 而非 IN_PROGRESS），否则
        //   doRun 回读 / SessionDto / Controller 全链按 CC 值域解析失败。
        TodoWriteTool tool = new TodoWriteTool();
        SessionMapper sessionMapper = mock(SessionMapper.class);
        SessionRecord record = new SessionRecord();
        record.setId(SESSION_ID);
        when(sessionMapper.selectOneById(SESSION_ID)).thenReturn(record);
        tool.setSessionMapper(sessionMapper);
        SessionAppState session = new SessionAppState();

        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
            {"B", "in_progress", "Doing B"},
        })), session.mainCtx());

        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        verify(sessionMapper).update(captor.capture());
        String todosJson = captor.getValue().getTodos();
        assertThat(todosJson).as("sessions.todos 列必须写入非空 JSON").isNotBlank();

        JsonNode root = JSON.readTree(todosJson);
        JsonNode bucket = root.get(SESSION_ID);
        assertThat(bucket).as("主桶键必须 = sessionId（todoKey 收敛）").isNotNull();
        assertThat(bucket).hasSize(2);
        assertThat(bucket.get(1).get("content").asText()).isEqualTo("B");
        assertThat(bucket.get(1).get("status").asText())
            .as("status 必须小写 in_progress（TodoStatus 无 @JsonValue，防大写泄漏）")
            .isEqualTo("in_progress");
        assertThat(bucket.get(0).get("activeForm").asText()).isEqualTo("Doing A");
        assertThat(todosJson).doesNotContain("IN_PROGRESS");
    }

    @Test
    @DisplayName("allDone → 存 {\"sess-xxx\":[]}（桶键保留 + 空数组，CC allDone 清空语义）")
    void allDonePersistsEmptyBucket() throws Exception {
        // WHY: CC TodoWriteTool.ts:70 allDone → storedTodos=[] → 桶键保留、值为空数组；若键丢失，
        //   doRun 回读 / REST 读不到该桶 → 前端残留已完成任务。
        TodoWriteTool tool = new TodoWriteTool();
        SessionMapper sessionMapper = mock(SessionMapper.class);
        SessionRecord record = new SessionRecord();
        record.setId(SESSION_ID);
        when(sessionMapper.selectOneById(SESSION_ID)).thenReturn(record);
        tool.setSessionMapper(sessionMapper);
        SessionAppState session = new SessionAppState();

        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "completed", "Doing A"},
        })), session.mainCtx());

        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        verify(sessionMapper).update(captor.capture());
        JsonNode root = JSON.readTree(captor.getValue().getTodos());
        JsonNode bucket = root.get(SESSION_ID);
        assertThat(bucket).as("allDone 清空语义：桶键必须保留").isNotNull();
        assertThat(bucket.isArray()).as("桶值必须为数组").isTrue();
        assertThat(bucket.isEmpty()).as("allDone 后桶值必须为空数组").isTrue();
    }

    @Test
    @DisplayName("含子 agent 桶 → 整 map 入列（子桶不被覆盖丢失）")
    void subagentBucketBothKeysPersisted() throws Exception {
        // WHY: appState.todos 是会话级全 map（含子 agent agentId 桶）；Step5.6 必须整 map 入列，
        //   否则子 agent 桶在 DB 侧丢失 → 重开会话子 agent todo 聚合信号消失。
        TodoWriteTool tool = new TodoWriteTool();
        SessionMapper sessionMapper = mock(SessionMapper.class);
        SessionRecord record = new SessionRecord();
        record.setId(SESSION_ID);
        when(sessionMapper.selectOneById(SESSION_ID)).thenReturn(record);
        tool.setSessionMapper(sessionMapper);
        SessionAppState session = new SessionAppState(Map.of(
            "todos", new java.util.HashMap<>(Map.of(
                "agent-uuid-0001", List.of(new TodoWriteTool.TodoItem(
                    "sub", TodoWriteTool.TodoStatus.PENDING, "Doing sub"))))));

        tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
        })), session.mainCtx());

        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        verify(sessionMapper).update(captor.capture());
        JsonNode root = JSON.readTree(captor.getValue().getTodos());
        assertThat(root.has("agent-uuid-0001")).as("子 agent 桶必须整 map 入列").isTrue();
        assertThat(root.has(SESSION_ID)).as("主桶必须同时入列").isTrue();
    }

    @Test
    @DisplayName("sessionMapper 未注入 → execute 成功不抛（AC-5 隔离）")
    void sessionMapperNullIsNoOp() throws Exception {
        // WHY: 测试/孤立运行缺省不注入 SessionMapper——Step5.6 必须 no-op（DB 持久化是旁路副作用，
        //   若抛异常 execute 外层 catch 会把成功 TodoWrite 翻成 ToolResult.error）。
        TodoWriteTool tool = new TodoWriteTool();   // 不注入 sessionMapper
        SessionAppState session = new SessionAppState();

        ToolResult<?> r = tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
        })), session.mainCtx());

        assertThat(r.data())
            .as("sessionMapper 未注入时 execute 必须成功（Step5.6 no-op 降级）")
            .isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("selectOneById 返回 null → warn skip 不抛，execute 仍成功")
    void selectOneByIdNullSkips() throws Exception {
        // WHY: 会话不存在（DB 行缺失）时不得把 TodoWrite 翻成失败——warn+skip 保 execute 成功，
        //   对齐 pushTodoUpdate 的 AC-5 隔离模式。
        TodoWriteTool tool = new TodoWriteTool();
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(SESSION_ID)).thenReturn(null);
        tool.setSessionMapper(sessionMapper);
        SessionAppState session = new SessionAppState();

        ToolResult<?> r = tool.execute(call("c1", inputWithTodos(new String[][]{
            {"A", "pending", "Doing A"},
        })), session.mainCtx());

        assertThat(r.data())
            .as("selectOneById null → 跳过 DB 写，execute 必须成功")
            .isInstanceOf(Map.class);
        verify(sessionMapper, never()).update(any(SessionRecord.class));
    }
}
