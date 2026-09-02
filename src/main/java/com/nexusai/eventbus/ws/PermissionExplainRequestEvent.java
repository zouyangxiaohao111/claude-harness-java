package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 权限解释请求事件 · 前端 → 服务端 STOMP 发送。
 *
 * <p>destination: {@code /app/sessions/{sessionId}/permission-explain}
 * （{@code /app/...} 是 STOMP SEND 前缀，由 {@code @MessageMapping("/sessions/{sessionId}/permission-explain")}
 * 映射到 {@link com.nexusai.apis.permission.PermissionController#handlePermissionExplain}）。
 *
 * <h2>触发流程</h2>
 * <p>前端 {@code app.js} 收到 {@link MessagePermissionRequestEvent} 弹窗后，用户按"解释"
 * （对齐 CC PermissionExplanation.tsx Ctrl+E 惰性触发，permissionExplainer.ts 生成的
 * 权限解释）→ 前端组装本事件 STOMP SEND → 后端 {@code PermissionController} 调
 * {@link com.nexusai.application.agent.permission.WebSocketPermissionPrompter#explainAndSend}
 * → 服务端经 {@code /topic/sessions/{sessionId}/permission-explanations} 推送
 * {@link PermissionExplanationEvent}（四字段或 unavailable）。
 *
 * <h2>字段</h2>
 * <ul>
 *   <li>{@code requestId} — 关联 {@link MessagePermissionRequestEvent#getRequestId()}
 *       （通常 = {@code ToolUseBlock.id}），后端透传回 explanation 事件供弹窗内联展示</li>
 *   <li>{@code toolName} — 待解释的工具名（对齐 CC {@code GenerateExplanationParams.toolName}，
 *       permissionExplainer.ts:36）</li>
 *   <li>{@code toolInput} — 工具输入（已解析 JSON 对象，对齐 CC {@code toolInput: unknown}，
 *       permissionExplainer.ts:37）</li>
 *   <li>{@code toolDescription} — 工具描述（可选，对齐 CC {@code toolDescription?: string}，
 *       permissionExplainer.ts:38）</li>
 * </ul>
 *
 * <p>WHY 不从前端透传 {@code messages}：对话历史由后端
 * {@link com.nexusai.domain.session.MessageService#listBySession} 取（explainer 内部
 * {@code extractConversationContext} 自动取最近 3 条 assistant），防伪造上下文。
 *
 * @see PermissionExplanationEvent
 * @see com.nexusai.apis.permission.PermissionController
 * @see com.nexusai.application.agent.permission.WebSocketPermissionPrompter
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionExplainRequestEvent {

    /** 关联 {@link MessagePermissionRequestEvent#getRequestId()}（通常 = {@code ToolUseBlock.id}）。 */
    private final String requestId;

    /** 待解释的工具名 · CC original: {@code toolName} (permissionExplainer.ts:36)。 */
    private final String toolName;

    /** 工具输入（已解析 JSON 对象）· CC original: {@code toolInput} (permissionExplainer.ts:37)。 */
    private final JsonNode toolInput;

    /** 工具描述（可选）· CC original: {@code toolDescription} (permissionExplainer.ts:38)。 */
    private final String toolDescription;

    /**
     * Jackson 反序列化入口（对齐 STOMP inbound 真实链路）。
     *
     * <p>{@link JsonCreator} + {@link JsonProperty} 让 Jackson 在没有 default constructor 的
     * 情况下也能反序列化本类 —— Spring STOMP {@code MappingJackson2MessageConverter} 反序列化
     * 前端 {@code /app/sessions/{sessionId}/permission-explain} 消息依赖此入口。
     */
    @JsonCreator
    public PermissionExplainRequestEvent(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("toolName") String toolName,
            @JsonProperty("toolInput") JsonNode toolInput,
            @JsonProperty("toolDescription") String toolDescription) {
        this.requestId = requestId;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.toolDescription = toolDescription;
    }

    public String getRequestId() { return requestId; }
    public String getToolName() { return toolName; }
    public JsonNode getToolInput() { return toolInput; }
    public String getToolDescription() { return toolDescription; }
}
