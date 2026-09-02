package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.eventbus.ws.StreamEvent;

import java.time.Instant;
import java.util.List;

/**
 * 权限请求事件 · 服务端 → 前端 STOMP 推送。
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/permission-requests}
 *
 * <h2>触发流程</h2>
 * <ol>
 *   <li>{@link com.nexusai.application.agent.LlmAgentLoop} 检测到 tool_call</li>
 *   <li>{@link com.nexusai.application.agent.permission.PermissionPipeline} 返回
 *       {@link com.nexusai.application.agent.permission.PermissionResult.Ask}</li>
 *   <li>{@link com.nexusai.application.agent.permission.WebSocketPermissionPrompter} 推送本事件</li>
 *   <li>前端 {@code app.js} 订阅 topic，渲染 modal（tool name + reason + Y/N 按钮）</li>
 *   <li>用户点击 → 前端 {@code /app/sessions/{sessionId}/permission-response} 发送决策</li>
 * </ol>
 *
 * <h2>字段</h2>
 * <ul>
 *   <li>{@code type} = {@code "permission.request"}（继承自 {@link StreamEvent} 协议）</li>
 *   <li>{@code sessionId} — 用于前端 STOMP topic 路由</li>
 *   <li>{@code requestId} — 关联前端的 {@link com.nexusai.ws.events.MessagePermissionResponseEvent}（通常 =
 *       {@code ToolUseBlock.id}）</li>
 *   <li>{@code toolUseId} — [OD-WF1-01 G 族 / WF-11] 工具调用 ID（CC original: {@code tool_use_id}，
 *       前端响应据此关联到具体工具调用，PermissionPromptToolResultSchema.ts:15-127
 *       inputSchema.tool_use_id）</li>
 *   <li>{@code toolName} — 弹窗显示的工具名</li>
 *   <li>{@code toolInput} — 弹窗显示的参数（完整 JSON 对象）</li>
 *   <li>{@code reason} — 决策归因序列化（对齐 CC {@code serializeDecisionReason}，
 *       structuredIO.ts:64-91）</li>
 *   <li>{@code timestampMs} — 服务端推送时间戳（前端可显示倒计时）</li>
 * </ul>
 *
 * @see com.nexusai.ws.events.MessagePermissionResponseEvent
 * @see com.nexusai.application.agent.permission.WebSocketPermissionPrompter
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessagePermissionRequestEvent extends StreamEvent {

    /** 唯一请求 ID · 关联前端响应（通常 = {@code ToolUseBlock.id}） */
    private final String requestId;

    /**
     * [OD-WF1-01 G 族 / WF-11] 工具调用 ID · CC original: {@code tool_use_id}
     * （PermissionPromptToolResultSchema.ts:15-127 inputSchema.tool_use_id +
     * structuredIO.ts:590-606 can_use_tool payload {@code tool_use_id}）。
     * 前端据此把权限响应关联到具体工具调用；可为 null（对齐 CC optional 语义）。
     */
    private final String toolUseId;

    /** 工具名（弹窗显示） */
    private final String toolName;

    /** 工具参数（已解析 JSON 对象，弹窗显示） */
    private final JsonNode toolInput;

    /** 决策归因序列化 · 对齐 CC {@code serializeDecisionReason}（structuredIO.ts:64-91） */
    private final PermissionDecisionReasonDto reason;

    /**
     * [canUseTool v2] 弹窗展示的工具描述文案 · 对齐 CC useCanUseTool.tsx:56-60
     * {@code await tool.description(input, ...)}（队列展示 + 拒绝记录）。可为 null。
     */
    private final String description;

    /**
     * [canUseTool v2] 建议的权限更新 · 对齐 CC interactiveHandler.ts:252
     * {@code result.suggestions}（"Add allow rule" 一键授权建议）。可为 null。
     */
    private final java.util.List<com.nexusai.application.agent.permission.PermissionUpdate> suggestions;

    /**
     * [canUseTool v2] 被阻断的路径 · 对齐 CC interactiveHandler.ts:253
     * {@code result.blockedPath}（safetyCheck / content-specific ask 场景）。可为 null。
     */
    private final String blockedPath;

    /**
     * [G21] 权限弹窗渲染文本 · CC original: {@code destructiveWarning}
     * （BashPermissionRequest.tsx:274 {@code getFeatureValue('tengu_destructive_command_warning') ?
     * getDestructiveCommandWarning(command) : null}）+ sed 编辑渲染
     * （BashPermissionRequest.tsx:89 {@code parseSedEditCommand(command)} → SedEditPermissionRequest）。
     *
     * <p>由 {@code WebSocketPermissionPrompter} 在 Bash 权限提示时计算并注入：
     * <ul>
     *   <li><b>sed -i 编辑场景</b>（CC BashPermissionRequest.tsx:89-103 sedInfo 优先）→
     *       文件编辑渲染文本（SedEditParser）；</li>
     *   <li><b>否则破坏性命令</b>（CC BashPermissionRequest.tsx:274）→ 危险命令警告文本
     *       （DestructiveCommandWarning.getDestructiveCommandWarning）；</li>
     *   <li><b>非 Bash / 无匹配</b> → {@code null}（@JsonInclude NON_NULL 省略字段，前端向后兼容）。</li>
     * </ul>
     *
     * <p>G21 拍板：SedEditParser/DestructiveCommandWarning 渲染结果随本事件通过 STOMP/WebSocket
     * 推送给前端弹窗（非 API）。
     */
    private final String warning;

    /**
     * [perm-timeout #132] worker 徽标色 · CC original: {@code WorkerBadgeProps.color}
     * （WorkerBadge.tsx:8）+ useInboxPoller.ts:292（{@code entry.workerBadge.color}）。
     *
     * <p>leader inbox 权限弹窗前端据此渲染彩色徽标（区分 worker，PermissionRequest.tsx
     * ToolUseConfirm.workerBadge 透传）；null → @JsonInclude NON_NULL 省略字段
     * （主 loop 弹窗无 worker badge，前端向后兼容）。
     */
    private final String workerBadgeColor;

    /** 服务端推送时间戳（毫秒） */
    private final long timestampMs;

    /**
     * 构造权限请求事件。
     *
     * @param sessionId   会话 ID
     * @param requestId   唯一请求 ID（关联 ToolUseBlock.id）
     * @param toolUseId   [OD-WF1-01 G 族 / WF-11] 工具调用 ID（CC original: tool_use_id；可 null）
     * @param toolName    工具名
     * @param toolInput   工具参数（已解析 JSON）
     * @param reason      决策归因序列化 DTO（对齐 CC serializeDecisionReason；可为 null → 省略字段）
     * @param timestampMs 推送时间戳（毫秒）—— 通常 {@code Instant.now().toEpochMilli()}
     */
    public MessagePermissionRequestEvent(String sessionId,
                                         String requestId,
                                         String toolUseId,
                                         String toolName,
                                         JsonNode toolInput,
                                         PermissionDecisionReasonDto reason,
                                         long timestampMs) {
        this(sessionId, requestId, toolUseId, toolName, toolInput, reason, null, null, null, timestampMs);
    }

    /**
     * [canUseTool v2] 完整构造器 · 含 toolUseId / description / suggestions / blockedPath。
     * warning 缺省 = null（@JsonInclude NON_NULL 省略字段，前端向后兼容）。
     */
    public MessagePermissionRequestEvent(String sessionId,
                                         String requestId,
                                         String toolUseId,
                                         String toolName,
                                         JsonNode toolInput,
                                         PermissionDecisionReasonDto reason,
                                         String description,
                                         java.util.List<com.nexusai.application.agent.permission.PermissionUpdate> suggestions,
                                         String blockedPath,
                                         long timestampMs) {
        this(sessionId, requestId, toolUseId, toolName, toolInput, reason,
            description, suggestions, blockedPath, null, null, timestampMs);
    }

    /**
     * [G21] 完整构造器 · 含 warning（权限弹窗渲染文本）。
     */
    public MessagePermissionRequestEvent(String sessionId,
                                         String requestId,
                                         String toolUseId,
                                         String toolName,
                                         JsonNode toolInput,
                                         PermissionDecisionReasonDto reason,
                                         String description,
                                         java.util.List<com.nexusai.application.agent.permission.PermissionUpdate> suggestions,
                                         String blockedPath,
                                         String warning,
                                         long timestampMs) {
        this(sessionId, requestId, toolUseId, toolName, toolInput, reason,
            description, suggestions, blockedPath, warning, null, timestampMs);
    }

    /**
     * [perm-timeout #132] 完整构造器 · 含 warning + workerBadgeColor。
     */
    public MessagePermissionRequestEvent(String sessionId,
                                         String requestId,
                                         String toolUseId,
                                         String toolName,
                                         JsonNode toolInput,
                                         PermissionDecisionReasonDto reason,
                                         String description,
                                         java.util.List<com.nexusai.application.agent.permission.PermissionUpdate> suggestions,
                                         String blockedPath,
                                         String warning,
                                         String workerBadgeColor,
                                         long timestampMs) {
        // type="permission.request" · 区别于 message.* 流事件，避免和 message 流混淆
        super("permission.request", sessionId, null);
        this.requestId = requestId;
        this.toolUseId = toolUseId;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.reason = reason;
        this.description = description;
        this.suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        this.blockedPath = blockedPath;
        this.warning = warning;
        this.workerBadgeColor = workerBadgeColor;
        this.timestampMs = timestampMs;
    }

    public String getRequestId() { return requestId; }
    public String getToolUseId() { return toolUseId; }
    public String getToolName() { return toolName; }
    public JsonNode getToolInput() { return toolInput; }
    public PermissionDecisionReasonDto getReason() { return reason; }
    public String getDescription() { return description; }
    public java.util.List<com.nexusai.application.agent.permission.PermissionUpdate> getSuggestions() { return suggestions; }
    public String getBlockedPath() { return blockedPath; }
    public String getWarning() { return warning; }
    public String getWorkerBadgeColor() { return workerBadgeColor; }
    public long getTimestampMs() { return timestampMs; }

    /**
     * 决策归因序列化 DTO · 对齐 CC {@code serializeDecisionReason}（structuredIO.ts:64-91）。
     *
     * <p>CC 把 {@code PermissionDecisionReason} 序列化为<b>单个字符串</b>（或 {@code undefined}）：
     * <ul>
     *   <li>{@code rule} / {@code mode} / {@code subcommandResults} / {@code permissionPromptTool} → {@code undefined}</li>
     *   <li>{@code hook} / {@code asyncAgent} / {@code sandboxOverride} / {@code workingDir} /
     *       {@code safetyCheck} / {@code other} → {@code reason.reason}</li>
     *   <li>{@code classifier} → 门控开启（BASH_CLASSIFIER ‖ TRANSCRIPT_CLASSIFIER）时 {@code reason.reason}；
     *       门控关闭 → {@code undefined}</li>
     * </ul>
     *
     * <p><b>[DEL-WF8-⊕4 / OPD-WF8-01-T3]</b> 旧 {@code (type+detail)} 扁平契约已删除（CC 无此 DTO）；
     * {@code reason} 字段为 null 时事件整体省略该字段（@JsonInclude NON_NULL，等价 CC
     * {@code decision_reason: undefined} 不携带）。
     */
    public static class PermissionDecisionReasonDto {
        private final String reason;

        public PermissionDecisionReasonDto(String reason) {
            this.reason = reason;
        }

        public String getReason() { return reason; }
    }

    /**
     * CC serializeDecisionReason（structuredIO.ts:64-91）的 Java 等价。
     *
     * <p>CC 真源：
     * <pre>{@code
     * function serializeDecisionReason(reason) {
     *   if (!reason) return undefined
     *   if ((feature('BASH_CLASSIFIER') || feature('TRANSCRIPT_CLASSIFIER'))
     *       && reason.type === 'classifier') return reason.reason
     *   switch (reason.type) {
     *     case 'rule': case 'mode': case 'subcommandResults': case 'permissionPromptTool':
     *       return undefined
     *     case 'hook': case 'asyncAgent': case 'sandboxOverride':
     *     case 'workingDir': case 'safetyCheck': case 'other':
     *       return reason.reason
     *   }
     * }
     * }</pre>
     *
     * @param reason                  决策归因（null → null）
     * @param classifierFeatureEnabled CC {@code feature('BASH_CLASSIFIER') ||
     *                                 feature('TRANSCRIPT_CLASSIFIER')} 门控（Java 由调用方注入）
     * @return 序列化字符串；CC undefined 分支 → null
     */
    static String serializeDecisionReason(
            com.nexusai.application.agent.permission.PermissionDecisionReason reason,
            boolean classifierFeatureEnabled) {
        if (reason == null) {
            return null;
        }
        if (classifierFeatureEnabled
                && reason instanceof com.nexusai.application.agent.permission.PermissionDecisionReason.Classifier c) {
            return c.reason();
        }
        return switch (reason) {
            case com.nexusai.application.agent.permission.PermissionDecisionReason.Rule r -> null;
            case com.nexusai.application.agent.permission.PermissionDecisionReason.Mode m -> null;
            case com.nexusai.application.agent.permission.PermissionDecisionReason.SubcommandResults sr -> null;
            case com.nexusai.application.agent.permission.PermissionDecisionReason.PermissionPromptTool pt -> null;
            case com.nexusai.application.agent.permission.PermissionDecisionReason.Hook h -> h.reason();
            case com.nexusai.application.agent.permission.PermissionDecisionReason.AsyncAgent aa -> aa.reason();
            case com.nexusai.application.agent.permission.PermissionDecisionReason.SandboxOverride so ->
                so.reason() == null ? null : so.reason().ccLiteral();
            case com.nexusai.application.agent.permission.PermissionDecisionReason.WorkingDir wd -> wd.reason();
            case com.nexusai.application.agent.permission.PermissionDecisionReason.SafetyCheck sc -> sc.reason();
            case com.nexusai.application.agent.permission.PermissionDecisionReason.Other o -> o.reason();
            // classifier 门控关闭 → undefined（门控开启分支已在 switch 外处理）
            case com.nexusai.application.agent.permission.PermissionDecisionReason.Classifier c -> null;
        };
    }

    /**
     * 静态工厂 · 从 {@link com.nexusai.application.agent.permission.PermissionDecisionReason}
     * 构造序列化 DTO（对齐 CC serializeDecisionReason 门控）。
     *
     * <p>WHY 静态工厂：
     * <ul>
     *   <li>隔离 {@code application.agent.permission} 包到 {@code ws.events} 包的依赖
     *       （web 层不应直接依赖应用层类型）</li>
     *   <li>序列化规则单点收敛（CC serializeDecisionReason 契约，structuredIO.ts:64-91）</li>
     * </ul>
     *
     * @param reason                  决策归因（null → null）
     * @param classifierFeatureEnabled classifier 门控（见 {@link #serializeDecisionReason}）
     * @return 序列化结果 null → null（事件整体省略 reason 字段）
     */
    public static PermissionDecisionReasonDto toDto(
            com.nexusai.application.agent.permission.PermissionDecisionReason reason,
            boolean classifierFeatureEnabled) {
        String serialized = serializeDecisionReason(reason, classifierFeatureEnabled);
        return serialized == null ? null : new PermissionDecisionReasonDto(serialized);
    }

    /**
     * 静态工厂 · 用当前时间戳构造事件（toolUseId 缺省 = requestId，classifier 门控关闭）。
     */
    public static MessagePermissionRequestEvent of(String sessionId,
                                                    String requestId,
                                                    String toolName,
                                                    JsonNode toolInput,
                                                    com.nexusai.application.agent.permission.PermissionDecisionReason reason) {
        return new MessagePermissionRequestEvent(
            sessionId, requestId, requestId, toolName, toolInput,
            toDto(reason, false),
            Instant.now().toEpochMilli()
        );
    }

    /**
     * [canUseTool v2 + WF-11] 完整静态工厂 · 含 toolUseId / description / suggestions /
     * blockedPath / classifier 门控。warning 缺省 = null。
     *
     * <p>对齐 CC {@code useCanUseTool.tsx:56-60}（description）+ {@code interactiveHandler.ts:250-253}
     * （suggestions / blockedPath 传给 bridge）+ {@code structuredIO.ts:590-606}（tool_use_id +
     * decision_reason）。由
     * {@link com.nexusai.application.agent.permission.WebSocketPermissionPrompter} 在推送前填充。
     *
     * @param toolUseId               工具调用 ID（CC tool_use_id；可 null）
     * @param classifierFeatureEnabled serializeDecisionReason classifier 门控
     */
    public static MessagePermissionRequestEvent of(String sessionId,
                                                    String requestId,
                                                    String toolUseId,
                                                    String toolName,
                                                    JsonNode toolInput,
                                                    com.nexusai.application.agent.permission.PermissionDecisionReason reason,
                                                    String description,
                                                    List<com.nexusai.application.agent.permission.PermissionUpdate> suggestions,
                                                    String blockedPath,
                                                    boolean classifierFeatureEnabled) {
        return of(sessionId, requestId, toolUseId, toolName, toolInput, reason,
            description, suggestions, blockedPath, null, classifierFeatureEnabled);
    }

    /**
     * [G21] 完整静态工厂 · 含 warning（权限弹窗渲染文本）。workerBadgeColor 缺省 = null。
     *
     * <p>warning 由 {@link com.nexusai.application.agent.permission.WebSocketPermissionPrompter}
     * 对 Bash 工具 input.command 计算（sed 编辑渲染 / 破坏性命令警告），随 STOMP 事件推送给
     * 前端弹窗（非 API，G21 拍板）。
     *
     * @param toolUseId               工具调用 ID（CC tool_use_id；可 null）
     * @param warning                 [G21] 权限弹窗渲染文本（可 null → 省略字段）
     * @param classifierFeatureEnabled serializeDecisionReason classifier 门控
     */
    public static MessagePermissionRequestEvent of(String sessionId,
                                                    String requestId,
                                                    String toolUseId,
                                                    String toolName,
                                                    JsonNode toolInput,
                                                    com.nexusai.application.agent.permission.PermissionDecisionReason reason,
                                                    String description,
                                                    List<com.nexusai.application.agent.permission.PermissionUpdate> suggestions,
                                                    String blockedPath,
                                                    String warning,
                                                    boolean classifierFeatureEnabled) {
        return of(sessionId, requestId, toolUseId, toolName, toolInput, reason,
            description, suggestions, blockedPath, warning, null, classifierFeatureEnabled);
    }

    /**
     * [perm-timeout #132] 完整静态工厂 · 含 warning + workerBadgeColor。
     *
     * <p>workerBadgeColor 由 {@link com.nexusai.application.agent.permission.LeaderPermissionConfirmBridge}
     * 从 {@code ToolUseConfirmEntry.workerBadgeColor} 传入（对齐 CC useInboxPoller.ts:292
     * {@code entry.workerBadge = {name, color}} + WorkerBadge.tsx:8 color）；主 loop 弹窗不传 → null。
     *
     * @param toolUseId               工具调用 ID（CC tool_use_id；可 null）
     * @param warning                 [G21] 权限弹窗渲染文本（可 null → 省略字段）
     * @param workerBadgeColor        [perm-timeout #132] worker 徽标色（可 null → 省略字段）
     * @param classifierFeatureEnabled serializeDecisionReason classifier 门控
     */
    public static MessagePermissionRequestEvent of(String sessionId,
                                                    String requestId,
                                                    String toolUseId,
                                                    String toolName,
                                                    JsonNode toolInput,
                                                    com.nexusai.application.agent.permission.PermissionDecisionReason reason,
                                                    String description,
                                                    List<com.nexusai.application.agent.permission.PermissionUpdate> suggestions,
                                                    String blockedPath,
                                                    String warning,
                                                    String workerBadgeColor,
                                                    boolean classifierFeatureEnabled) {
        return new MessagePermissionRequestEvent(
            sessionId, requestId, toolUseId, toolName, toolInput,
            toDto(reason, classifierFeatureEnabled), description, suggestions, blockedPath, warning,
            workerBadgeColor,
            Instant.now().toEpochMilli()
        );
    }
}
