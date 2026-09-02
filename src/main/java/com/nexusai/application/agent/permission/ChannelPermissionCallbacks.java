package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Consumer;

/**
 * Channel（Telegram / iMessage / Discord）权限竞速回调 · 对齐 CC
 * {@code Open-ClaudeCode/src/services/mcp/channelPermissions.ts}。
 *
 * <p>[canUseTool v3] interactive 分支四路竞速（hook/classifier/bridge/channel）的通道路 ·
 * 对齐 CC {@code handleInteractivePermission}（interactiveHandler.ts:316-407）：
 * <pre>
 *   const channelRequestId = shortRequestId(toolUseID)
 *   const mapUnsub = channelCallbacks.onResponse(channelRequestId, response => { ... })
 * </pre>
 *
 * <p>[canUseTool v4] 生产实现 {@link StompChannelPermissionCallbacks}（@Component）：通道表面
 * 经 STOMP/WebSocket 订阅出站请求，用户回复 "yes tbxkq" 由通道 server 解析 → inbound
 * {@link #resolve} → 竞速真正参与生产（v3 对抗复验缺口① — 无 @Component 实现 → 注入 null →
 * startChannelRace 直接 return）。远程响应 {@code behavior} 为 {@code allow} / {@code deny}，
 * {@code fromServer} 标识来源通道名。
 *
 * @see WebSocketPermissionPrompter#startChannelRace
 * @see StompChannelPermissionCallbacks
 * @since canUseTool v3
 */
public interface ChannelPermissionCallbacks {

    /**
     * 短请求 ID · 对齐 CC channelPermissions.ts {@code shortRequestId}（5 字母 FNV-1a hash）。
     * 用户通道回复 "yes abcde" 由 channel server 拦截并按此 ID resolve。
     */
    String shortRequestId(String toolUseId);

    /**
     * 注册通道响应回调 · 返回退订 Runnable（对齐 CC channelCallbacks.onResponse）。
     *
     * @param requestId 短请求 ID
     * @param handler   通道响应处理器（只调用一次，首个 claim 生效）
     * @return 退订回调（map 清理 + abort listener 摘除）
     */
    Runnable onResponse(String requestId, Consumer<ChannelResponse> handler);

    /**
     * 向各通道推送权限请求 · 对齐 CC interactiveHandler.ts:334-354
     * （outbound structured message，server 按平台格式化）。
     *
     * <p>[canUseTool v4] 新增 {@code sessionId} 参数 — CC 回调按 session 闭包持有 MCP clients，
     * Java @Component 是单例 bean，sessionId 必须显式传入以路由出站请求到正确 session 的 topic。
     *
     * @param sessionId   目标 session ID（出站请求 topic 路由）
     * @param requestId   短请求 ID（shortRequestId(toolUseId) 产物）
     * @param toolName    工具名
     * @param description 弹窗描述（CC tool.description(input) 产物）
     * @param displayInput 展示用输入
     */
    void sendRequest(String sessionId, String requestId, String toolName,
                     String description, JsonNode displayInput);

    /**
     * [canUseTool v4] STOMP inbound 响应解析 · 对齐 CC channelPermissions.ts {@code resolve}
     * （structured event notifications/claude/channel/permission 的匹配通道）。
     *
     * <p>delete-before-call（channelPermissions.ts:232-236）：找到 resolver 先移除再调用，
     * 重复事件第二次返回 false 被忽略。由
     * {@link com.nexusai.apis.permission.PermissionController} 在收到
     * {@code /app/sessions/{sessionId}/permission-channel-response} 时调用。
     *
     * @param requestId  短请求 ID（key 转小写匹配）
     * @param behavior   allow / deny
     * @param fromServer 来源通道名（如 "plugin:telegram:tg"）
     * @return true = 命中 pending resolver 并已调用；false = 未知/已 resolve
     */
    boolean resolve(String requestId, String behavior, String fromServer);

    /**
     * 通道响应载荷 · 对齐 CC interactiveHandler.ts:363-397 response
     * （{@code behavior} / {@code fromServer}）。
     */
    record ChannelResponse(String behavior, String fromServer) {}
}
