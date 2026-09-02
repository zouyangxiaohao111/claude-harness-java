package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Todo 更新推送事件</b> · session 级 topic {@code /topic/sessions/{sessionId}/todos}。
 *
 * <p><b>触发</b>：{@link com.nexusai.application.agent.tool.impl.TodoWriteTool} 每次执行成功后
 * （Step5 存储 + AgentState 同步后）推送（{@code TodoWriteTool.pushTodoUpdate}）。
 * {@code userMessageId = null}（工具侧无该 id，@JsonInclude NON_NULL 自动省略）——
 * session 级 topic 对齐 {@code permission-requests} / {@code websearch-results} 先例。
 *
 * <p><b>载荷</b>：
 * <pre>
 * {
 *   "type": "todo.update",
 *   "sessionId": "sess-xxx",
 *   "ts": 1755916800000,                 // StreamEvent 自带服务端时间戳
 *   "todoKey": "sess-xxx",               // CC appState.todos[todoKey] 键（agentId ?? sessionId）
 *   "todos": [ { "content": "Run tests", "status": "in_progress", "activeForm": "Running tests" } ],
 *   "updatedAt": 1755916800000           // 推送时刻（设计载荷，与 ts 同步存在）
 * }
 * </pre>
 *
 * <p><b>status 值域</b>：{@code pending / in_progress / completed}（小写，CC utils/todo/types.ts:4-6
 * {@code z.enum}）——由 {@code TodoWriteTool.todoListToArray} 经 {@code TodoStatus.toValue()} 产出，
 * 禁止直接序列化 {@code TodoItem}（TodoStatus 无 @JsonValue，Jackson 直出大写 IN_PROGRESS）。
 * allDone 场景 {@code todos} 为空数组（CC TodoWriteTool.ts:70/:92 清空语义）。
 *
 * <p>本类为<b>普通 class 而非 record</b>：Java record 隐式继承 {@code java.lang.Record}，不能显式
 * {@code extends StreamEvent}（编译错误）；既有 15 个 StreamEvent 子类全部为普通 class
 * （MessagePermissionRequestEvent / WebSearchResultEvent 同型）。
 *
 * @see com.nexusai.application.agent.tool.impl.TodoWriteTool
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TodoUpdateEvent extends StreamEvent {

    /** CC original: {@code todoKey}（TodoWriteTool.ts:67 {@code context.agentId ?? getSessionId()}）· 前端凭此合并分桶。 */
    private final String todoKey;

    /** todos 数组 · CC original: {@code todos}（appState.todos[todoKey]）；ArrayNode，status 已 toValue() 小写。 */
    private final JsonNode todos;

    /** 更新时间戳（毫秒）· 推送时刻 {@code System.currentTimeMillis()}（设计载荷；StreamEvent 自带 ts 同步存在）。 */
    private final long updatedAt;

    /**
     * @param sessionId 会话 ID（short 形态 sess-xxx；非 null）
     * @param todoKey   todoKey（agentId ?? sessionId 解析结果）
     * @param todos     todos 数组（ArrayNode，由 {@code TodoWriteTool.todoListToArray} 产出，status 已小写）
     * @param updatedAt 更新时间戳（毫秒，推送时刻）
     */
    public TodoUpdateEvent(String sessionId, String todoKey, JsonNode todos, long updatedAt) {
        super("todo.update", sessionId, null);   // userMessageId = null（session 级 topic）
        this.todoKey = todoKey;
        this.todos = todos;
        this.updatedAt = updatedAt;
    }

    public String getTodoKey() {
        return todoKey;
    }

    public JsonNode getTodos() {
        return todos;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }
}
