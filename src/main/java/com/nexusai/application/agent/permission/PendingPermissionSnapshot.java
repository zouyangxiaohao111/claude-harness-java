package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;

import java.util.List;

/**
 * 会话状态重放 · 待审批请求快照（<b>三通道统一形状</b>）。
 *
 * <p><b>WHY 存在</b>：权限请求本体是<b>一次性 STOMP 推送</b> —— 请求到达时若用户不在该会话的
 * 订阅集合里（非当前会话 / 刷新页面后重连），事件被静默丢弃 ⇒ 用户看不到、也没法批准/拒绝
 * （实测：AskUserQuestion 在 {@code sess-df8b40c2} 等待 12 分钟后被 Deny）。
 * 修法 = 服务端保留「仍在等待用户」的请求快照，切会话 / 重连（F5）时经
 * {@code GET /api/v1/sessions/{id}/state} 重放（对齐 dsh 的 stable rpcId replay）。
 *
 * <h2>三通道（message / bridge / channel）</h2>
 * <p>本仓有<b>三条</b>权限通道把同一个待审批请求推给不同的表面：
 * <ul>
 *   <li><b>message</b> —— 主通道（{@code /permission-requests}），前端主弹窗；</li>
 *   <li><b>bridge</b> —— CCR / claude.ai 式远程弹窗（{@code /permission-bridge-requests}）；</li>
 *   <li><b>channel</b> —— Telegram / iMessage / Discord 中继（{@code /permission-channel-requests}）。</li>
 * </ul>
 * <p>⛔ <b>此前只重放 message 通道</b> ⇒ bridge / channel 通道的待审批请求在用户刷新后<b>消失</b>。
 * 本快照把三条通道统一成<b>同一个形状</b>，前端的三通道归一化函数
 * （{@code front/src/hooks/useChatSocket.ts} 的 {@code toPermissionItem}）都能消费。
 *
 * <h2>形状与前端契约（{@code front/src/api/sessions.ts} · {@code PermissionEventPayload}）</h2>
 * <pre>
 * type            "permission.request"（三条通道统一 · 前端按 permission 队列消费）
 * sessionId       会话 id
 * requestId       ⚠️ <b>owner requestId</b>（tool_use id）—— 前端响应据此命中服务端 pending future
 * channel         "message" | "bridge" | "channel"（来源通道；前端可据此路由/展示）
 * channelRequestId 通道级 requestId（bridge = 随机 UUID / channel = 5 字母短 id）；响应路由到
 *                 对应通道端点时用；message 通道为 null
 * toolUseId       工具调用 id
 * toolName        工具名
 * toolInput       <b>归一化</b>工具入参（三通道都填 ⇒ 任何 kind 的归一化都取得到）
 * displayInput    bridge 通道字段名（同 toolInput 内容）
 * inputPreview    channel 通道字段名（截断到 200 字符的 JSON 预览）
 * description     弹窗描述
 * reason          决策归因（{@code {"reason": "..."}}）；bridge / channel 通道无 ⇒ null
 * warning         权限弹窗渲染文本（sed 编辑 / 破坏性命令警告）
 * suggestions     建议的权限更新（"一键授权"）
 * blockedPath     被阻断路径
 * workerBadgeColor worker 徽标色
 * computerUse     桌面控制审批面板数据（<b>三通道 + 补拉路径都必须带</b>；null ⇒ 非 CU 请求）
 * timestampMs     快照时间戳
 * </pre>
 *
 * <h2>为什么 requestId 用 owner requestId（tool_use id）而不是通道级 id</h2>
 * <p>前端补拉路径（{@code replayPendingPermissions}）把快照按 <b>message 通道</b>归一化 ⇒ 用户
 * 的允许/拒绝经 {@code /permission-response} 回服务端 ⇒ 命中的是 prompter 的 pending future
 * （<b>按 owner requestId 登记</b>）。若快照改用通道级 id（bridge UUID / channel 短 id），
 * 该响应在 prompter 的 pending map 里<b>查不到</b> ⇒ 弹窗点了没反应、工具永久挂起。
 * ⇒ 快照的 {@code requestId} 必须是 owner requestId；通道级 id 另放 {@code channelRequestId}。
 *
 * <p>⚠️ 三条通道的快照<b>共用同一个 requestId</b>（同一次待审批 = 同一个 owner requestId）
 * ⇒ 前端按 requestId 去重后只会显示<b>一个</b>弹窗（message 通道快照在前，字段最全）。
 *
 * @see WebSocketPermissionPrompter#listPendingSnapshots(String)
 * @see com.nexusai.apis.permission.SessionStateController
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingPermissionSnapshot(
        /** 事件类型 · 三通道统一为 {@code permission.request}（前端按权限队列消费，不读此字段）。 */
        String type,
        /** 会话 id（= 投递会话 = teammate 请求归因到的 Leader 会话）。 */
        String sessionId,
        /** ⚠️ owner requestId（tool_use id）—— 前端响应据此命中服务端 pending future。 */
        String requestId,
        /** 来源通道：{@code message} / {@code bridge} / {@code channel}。 */
        String channel,
        /** 通道级 requestId（bridge UUID / channel 短 id）；message 通道为 null。 */
        String channelRequestId,
        /** 工具调用 id（前端回答后给对应工具卡打「已回答」标记）。 */
        String toolUseId,
        /** 工具名。 */
        String toolName,
        /** 归一化工具入参（三通道都填 ⇒ 前端任一 kind 的归一化都取得到）。 */
        JsonNode toolInput,
        /** bridge 通道字段名（{@code BridgePermissionRequestEvent.displayInput}）。 */
        JsonNode displayInput,
        /** channel 通道字段名（{@code ChannelPermissionRequestEvent.inputPreview} · 截断预览）。 */
        String inputPreview,
        /** 弹窗描述。 */
        String description,
        /** 决策归因（bridge / channel 通道无 ⇒ null）。 */
        MessagePermissionRequestEvent.PermissionDecisionReasonDto reason,
        /** 权限弹窗渲染文本（sed 编辑 / 破坏性命令警告）。 */
        String warning,
        /** 建议的权限更新（"一键授权"）。 */
        List<PermissionUpdate> suggestions,
        /** 被阻断路径。 */
        String blockedPath,
        /** worker 徽标色（leader inbox）。 */
        String workerBadgeColor,
        /** 桌面控制审批面板数据（null ⇒ 非 CU 请求 ⇒ 前端走普通弹窗分支）。 */
        ComputerUseApprovalPayload computerUse,
        /** 快照时间戳（毫秒）。 */
        long timestampMs) {

    /** 通道标识 · message（主通道）。 */
    public static final String CHANNEL_MESSAGE = "message";
    /** 通道标识 · bridge（CCR / claude.ai 式远程弹窗）。 */
    public static final String CHANNEL_BRIDGE = "bridge";
    /** 通道标识 · channel（Telegram / iMessage / Discord 中继）。 */
    public static final String CHANNEL_CHANNEL = "channel";
    /** 事件类型 · 三通道统一（前端按权限队列消费）。 */
    public static final String TYPE_PERMISSION_REQUEST = "permission.request";

    /** 构造时防御性拷贝（suggestions 不可变）。 */
    public PendingPermissionSnapshot {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /**
     * message 通道快照 · 由已推送的 {@link MessagePermissionRequestEvent} 逐字段转换。
     *
     * <p>⚠️ 流协议字段（{@code ts} / {@code seq} / {@code userMessageId}）不属权限弹窗契约
     * （前端 {@code PermissionEventPayload} 不消费）⇒ 快照不再随下发。
     *
     * @param event 主通道已推送的事件（字段与本快照一一对应）
     * @return 快照（channel = message）
     */
    public static PendingPermissionSnapshot fromMessage(MessagePermissionRequestEvent event) {
        return new PendingPermissionSnapshot(
            TYPE_PERMISSION_REQUEST,
            event.getSessionId(),
            event.getRequestId(),
            CHANNEL_MESSAGE,
            null,
            event.getToolUseId(),
            event.getToolName(),
            event.getToolInput(),
            null,
            null,
            event.getDescription(),
            event.getReason(),
            event.getWarning(),
            event.getSuggestions(),
            event.getBlockedPath(),
            event.getWorkerBadgeColor(),
            event.getComputerUse(),
            event.getTimestampMs());
    }

    /**
     * bridge 通道快照 · 远程表面（CCR / claude.ai 式弹窗）待审批请求。
     *
     * @param sessionId        投递会话
     * @param requestId        owner requestId（tool_use id）
     * @param channelRequestId bridge 请求 id（随机 UUID · 出站 topic 的关联键）
     * @param toolUseId        工具调用 id
     * @param toolName         工具名
     * @param displayInput     通道入参（= 工具入参同一实例）
     * @param description      弹窗描述
     * @param suggestions      建议的权限更新
     * @param blockedPath      被阻断路径
     * @param warning          权限弹窗渲染文本
     * @param computerUse      桌面控制审批面板数据（null ⇒ 非 CU 请求）
     * @param timestampMs      快照时间戳
     * @return 快照（channel = bridge；{@code displayInput} 与 {@code toolInput} 同内容）
     */
    public static PendingPermissionSnapshot fromBridge(String sessionId,
                                                       String requestId,
                                                       String channelRequestId,
                                                       String toolUseId,
                                                       String toolName,
                                                       JsonNode displayInput,
                                                       String description,
                                                       List<PermissionUpdate> suggestions,
                                                       String blockedPath,
                                                       String warning,
                                                       ComputerUseApprovalPayload computerUse,
                                                       long timestampMs) {
        return new PendingPermissionSnapshot(
            TYPE_PERMISSION_REQUEST,
            sessionId,
            requestId,
            CHANNEL_BRIDGE,
            channelRequestId,
            toolUseId,
            toolName,
            displayInput,
            displayInput,
            null,
            description,
            null,
            warning,
            suggestions,
            blockedPath,
            null,
            computerUse,
            timestampMs);
    }

    /**
     * channel 通道快照 · 通道表面（Telegram / iMessage / Discord）待审批请求。
     *
     * @param sessionId        投递会话
     * @param requestId        owner requestId（tool_use id）
     * @param channelRequestId channel 请求 id（5 字母短 id · 通道 server 解析「yes tbxkq」用）
     * @param toolUseId        工具调用 id
     * @param toolName         工具名
     * @param toolInput        完整工具入参（channel 通道只推截断预览；快照带全量 ⇒ 弹窗可渲染）
     * @param inputPreview     截断预览（channel 通道字段名）
     * @param description      弹窗描述
     * @param suggestions      建议的权限更新
     * @param blockedPath      被阻断路径
     * @param warning          权限弹窗渲染文本
     * @param computerUse      桌面控制审批面板数据（null ⇒ 非 CU 请求）
     * @param timestampMs      快照时间戳
     * @return 快照（channel = channel）
     */
    public static PendingPermissionSnapshot fromChannel(String sessionId,
                                                        String requestId,
                                                        String channelRequestId,
                                                        String toolUseId,
                                                        String toolName,
                                                        JsonNode toolInput,
                                                        String inputPreview,
                                                        String description,
                                                        List<PermissionUpdate> suggestions,
                                                        String blockedPath,
                                                        String warning,
                                                        ComputerUseApprovalPayload computerUse,
                                                        long timestampMs) {
        return new PendingPermissionSnapshot(
            TYPE_PERMISSION_REQUEST,
            sessionId,
            requestId,
            CHANNEL_CHANNEL,
            channelRequestId,
            toolUseId,
            toolName,
            toolInput,
            null,
            inputPreview,
            description,
            null,
            warning,
            suggestions,
            blockedPath,
            null,
            computerUse,
            timestampMs);
    }
}
