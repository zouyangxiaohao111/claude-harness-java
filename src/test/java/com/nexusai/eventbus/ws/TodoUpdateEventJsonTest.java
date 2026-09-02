package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import com.nexusai.application.agent.tool.impl.TodoWriteTool.TodoItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TodoUpdateEvent 出站 JSON 契约测试 · [todo-rest-stream] 前端 STOMP 反序列化锚点。
 *
 * <p><b>WHY 本测试验证意图</b>：前端 todo 面板订阅 {@code /topic/sessions/{sessionId}/todos}
 * 后按事件 JSON 解析——契约必须满足：
 * <ul>
 *   <li>{@code type="todo.update"} / {@code sessionId} / {@code todoKey}（CC todoKey 语义）；</li>
 *   <li>{@code todos[].status} 小写 {@code in_progress}（CC utils/todo/types.ts:4-6 值域，
 *       TodoStatus 无 @JsonValue 时 Jackson 直出大写 IN_PROGRESS → 前端解析失败）；</li>
 *   <li>{@code ts}（StreamEvent 自带服务端时间戳）+ {@code updatedAt}（设计载荷）双时间戳；</li>
 *   <li>{@code userMessageId} 省略（session 级 topic，NON_NULL）——若序列化出 null 字段，
 *       前端按 message 级事件误判归属。</li>
 * </ul>
 */
class TodoUpdateEventJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("序列化：type/sessionId/todoKey/ts/updatedAt + todos[].status 小写，无 userMessageId")
    void serializesTodoUpdateEvent() throws Exception {
        // WHY: 出站 JSON 是前端解析契约（唯一可信真源）——任一字段错位前端面板即断。
        ArrayNode todos = JSON.createArrayNode();
        ObjectNode n = todos.addObject();
        n.put("content", "Run tests");
        n.put("status", "in_progress");
        n.put("activeForm", "Running tests");

        TodoUpdateEvent evt = new TodoUpdateEvent("sess-abc", "sess-abc", todos, 1755916800000L);
        String json = JSON.writeValueAsString(evt);
        JsonNode node = JSON.readTree(json);

        assertThat(node.get("type").asText()).isEqualTo("todo.update");
        assertThat(node.get("sessionId").asText()).isEqualTo("sess-abc");
        assertThat(node.get("todoKey").asText()).isEqualTo("sess-abc");
        assertThat(node.get("ts")).as("StreamEvent 自带 ts 必须存在").isNotNull();
        assertThat(node.get("updatedAt").asLong()).isEqualTo(1755916800000L);
        assertThat(node.get("todos").isArray()).as("todos 必须是数组").isTrue();
        assertThat(node.get("todos").get(0).get("content").asText()).isEqualTo("Run tests");
        assertThat(node.get("todos").get(0).get("status").asText())
            .as("status 必须小写 in_progress（CC types.ts:4-6 值域，防大写泄漏）")
            .isEqualTo("in_progress");
        assertThat(node.get("todos").get(0).get("activeForm").asText()).isEqualTo("Running tests");
        assertThat(node.has("userMessageId"))
            .as("session 级 topic 事件 userMessageId 必须省略（NON_NULL）")
            .isFalse();
    }

    @Test
    @DisplayName("经 todoListToArray 序列化：status 小写（in_progress 而非 IN_PROGRESS）")
    void todoListToArrayProducesLowercaseStatus() throws Exception {
        // WHY: TodoStatus 枚举无 @JsonValue——直接塞 List<TodoItem> 会让 Jackson 出大写；
        //   事件与 REST 必须统一走 TodoWriteTool.todoListToArray（status.toValue() 小写）。
        List<TodoItem> items = List.of(
            new TodoItem("A", TodoWriteTool.TodoStatus.PENDING, "Doing A"),
            new TodoItem("B", TodoWriteTool.TodoStatus.IN_PROGRESS, "Doing B"),
            new TodoItem("C", TodoWriteTool.TodoStatus.COMPLETED, "Doing C"));
        ArrayNode arr = TodoWriteTool.todoListToArray(items);

        assertThat(arr.get(0).get("status").asText()).isEqualTo("pending");
        assertThat(arr.get(1).get("status").asText()).isEqualTo("in_progress");
        assertThat(arr.get(2).get("status").asText()).isEqualTo("completed");
    }

    @Test
    @DisplayName("allDone 空列表 → todos 序列化为空数组（前端面板清空）")
    void emptyTodosSerializesAsEmptyArray() throws Exception {
        // WHY: CC TodoWriteTool.ts:70 allDone → 空数组；出站 JSON 必须为 [] 而非 null/缺失，
        //   否则前端面板清空逻辑判定失败。
        TodoUpdateEvent evt = new TodoUpdateEvent(
            "sess-abc", "sess-abc", TodoWriteTool.todoListToArray(List.of()), 1755916800000L);
        String json = JSON.writeValueAsString(evt);
        JsonNode node = JSON.readTree(json);

        assertThat(node.get("todos").isArray())
            .as("allDone 空列表必须序列化为空数组（[]），非 null/缺失")
            .isTrue();
        assertThat(node.get("todos").isEmpty()).isTrue();
    }
}
