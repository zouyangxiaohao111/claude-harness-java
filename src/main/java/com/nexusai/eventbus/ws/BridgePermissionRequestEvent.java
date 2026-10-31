package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionUpdate;
import java.time.Instant;
import java.util.List;

/**
 * Bridge 权限请求事件 · 服务端 → 远程表面（CCR / claude.ai 式远程弹窗）STOMP 推送。
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/permission-bridge-requests}
 *
 * <p><b>WHY</b>（[canUseTool v4] bridge 竞速生产参与）：v3 对抗复验缺口① — bridge 回调接口
 * 生产无 @Component 实现，注入 null → startBridgeRace 直接 return，CCR 远程弹窗竞速永不参与。
 * 本事件是 {@link com.nexusai.application.agent.permission.StompBridgePermissionCallbacks#sendRequest}
 * 的出站载荷 — 远程表面订阅 topic 收到请求，弹窗允许/拒绝后回传
 * {@link BridgePermissionResponseEvent}（STOMP inbound）。
 *
 * <p>字段对齐 CC bridgePermissionCallbacks.ts sendRequest 参数（requestId / toolName / input /
 * toolUseId / description / permissionSuggestions / blockedPath）+ interactiveHandler.ts:245-253。
 *
 * @see BridgePermissionResponseEvent
 * @see com.nexusai.application.agent.permission.StompBridgePermissionCallbacks
 * @see com.nexusai.application.agent.permission.WebSocketPermissionPrompter#startBridgeRace
 * @since canUseTool v4
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BridgePermissionRequestEvent {

    private final String sessionId;
    private final String requestId;
    private final String toolName;
    private final JsonNode displayInput;
    private final String toolUseId;
    private final String description;
    private final List<PermissionUpdate> suggestions;
    private final String blockedPath;

    /**
     * [Phase 1d] computer-use 审批面板数据（<b>可空</b>）· 与
     * {@link MessagePermissionRequestEvent#getComputerUse()} <b>同形状</b>。
     *
     * <p><b>WHY 需要它</b>：Phase 1b 只给主通道（{@code /permission-requests}）加了
     * {@code computerUse} ⇒ bridge 通道（CCR / claude.ai 式远程弹窗）收到 CU 审批请求时
     * <b>拿不到面板数据</b> ⇒ 前端命中 fail-loud 错误面板（安全但体验差）。
     *
     * <p><b>null ⇒ 不是 CU 请求</b> ⇒ 远程表面走普通权限弹窗分支
     * （{@code @JsonInclude(NON_NULL)} ⇒ 字段被省略，前端向后兼容）。
     *
     * <p><b>数据来源</b>：发布方（{@code StompBridgePermissionCallbacks}）从
     * {@code displayInput}（= 工具入参同一实例，含 Phase 1a 就地注入的 {@code resolvedApps}）
     * 经 {@code ComputerUseApprovalPayload.fromToolInput(toolName, displayInput)} 提取。
     *
     * @see com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload
     */
    private final com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload computerUse;

    private final long timestampMs;

    /**
     * ⛔ <b>既有构造器逐字保留</b>（向后兼容）⇒ 既有调用点零改动；
     * {@code computerUse} 缺省 ⇒ null（@JsonInclude NON_NULL 省略字段）。
     *
     * @param sessionId   目标 session（出站 topic 路由）
     * @param requestId   bridge 请求 ID（随机 UUID）
     * @param toolName    工具名
     * @param displayInput 展示用输入（CC displayInput = updatedInput ?? ctx.input）
     * @param toolUseId   工具调用 ID（关联取消）
     * @param description 弹窗描述（CC tool.description(input) 产物）
     * @param suggestions 建议的权限更新（可为 null）
     * @param blockedPath 被阻断路径（可为 null）
     */
    public BridgePermissionRequestEvent(String sessionId,
                                        String requestId,
                                        String toolName,
                                        JsonNode displayInput,
                                        String toolUseId,
                                        String description,
                                        List<PermissionUpdate> suggestions,
                                        String blockedPath,
                                        long timestampMs) {
        this(sessionId, requestId, toolName, displayInput, toolUseId,
            description, suggestions, blockedPath, null, timestampMs);
    }

    /**
     * [Phase 1d] 完整构造器 · 含 computerUse。
     *
     * @param computerUse computer-use 审批面板数据（可 null ⇒ 非 CU 请求 ⇒ 省略字段）
     */
    public BridgePermissionRequestEvent(String sessionId,
                                        String requestId,
                                        String toolName,
                                        JsonNode displayInput,
                                        String toolUseId,
                                        String description,
                                        List<PermissionUpdate> suggestions,
                                        String blockedPath,
                                        com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload computerUse,
                                        long timestampMs) {
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.toolName = toolName;
        this.displayInput = displayInput;
        this.toolUseId = toolUseId;
        this.description = description;
        this.suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        this.blockedPath = blockedPath;
        this.computerUse = computerUse;
        this.timestampMs = timestampMs;
    }

    /** 当前时间戳便捷工厂（既有 8 参形态 · 逐字保留）。 */
    public static BridgePermissionRequestEvent of(String sessionId,
                                                  String requestId,
                                                  String toolName,
                                                  JsonNode displayInput,
                                                  String toolUseId,
                                                  String description,
                                                  List<PermissionUpdate> suggestions,
                                                  String blockedPath) {
        return new BridgePermissionRequestEvent(
            sessionId, requestId, toolName, displayInput, toolUseId,
            description, suggestions, blockedPath, Instant.now().toEpochMilli());
    }

    /**
     * [Phase 1d] 当前时间戳便捷工厂 · 含 computerUse。
     *
     * @param computerUse computer-use 审批面板数据（可 null ⇒ 非 CU 请求 ⇒ 省略字段）
     */
    public static BridgePermissionRequestEvent of(String sessionId,
                                                  String requestId,
                                                  String toolName,
                                                  JsonNode displayInput,
                                                  String toolUseId,
                                                  String description,
                                                  List<PermissionUpdate> suggestions,
                                                  String blockedPath,
                                                  com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload computerUse) {
        return new BridgePermissionRequestEvent(
            sessionId, requestId, toolName, displayInput, toolUseId,
            description, suggestions, blockedPath, computerUse, Instant.now().toEpochMilli());
    }

    public String getSessionId() { return sessionId; }
    public String getRequestId() { return requestId; }
    public String getToolName() { return toolName; }
    public JsonNode getDisplayInput() { return displayInput; }
    public String getToolUseId() { return toolUseId; }
    public String getDescription() { return description; }
    public List<PermissionUpdate> getSuggestions() { return suggestions; }
    public String getBlockedPath() { return blockedPath; }
    /**
     * [Phase 1d] computer-use 审批面板数据（null ⇒ 非 CU 请求 ⇒ 字段被省略）。
     *
     * @return CU 面板载荷（可 null）
     */
    public com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload getComputerUse() {
        return computerUse;
    }
    public long getTimestampMs() { return timestampMs; }
}
