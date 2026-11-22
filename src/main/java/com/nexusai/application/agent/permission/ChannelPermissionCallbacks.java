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
     * [P2] 会话内唯一的短请求 ID。
     *
     * <p>默认委托 {@link #shortRequestId(String)}（旧实现/测试零改动）；生产实现
     * {@link StompChannelPermissionCallbacks} 覆盖：随机 5 位 + 会话内撞号重试 + 哈希回退。
     *
     * @param sessionId 会话 id（唯一性作用域）
     * @param toolUseId 工具调用 id（哈希回退种子）
     */
    default String shortRequestId(String sessionId, String toolUseId) {
        return shortRequestId(toolUseId);
    }

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
     * [P0] 带 {@code toolUseId} 的出站请求 · 与同一次请求的 message / bridge 事件同值。
     *
     * <p><b>WHY 用 default 而不是改既有抽象方法</b>：旧实现（测试 stub / 其它回调）只实现
     * 5 参签名；新增抽象方法会让它们全部编译失败。这里默认退化为 5 参调用
     * （{@code toolUseId} 丢失 ⇒ 前端退回按 {@code requestId} 处理），生产实现
     * {@link StompChannelPermissionCallbacks} 覆盖本方法来真正携带该值。
     *
     * @param toolUseId 工具调用 ID（与 message / bridge 事件同值；可 null）
     */
    default void sendRequest(String sessionId, String requestId, String toolName,
                             String description, JsonNode displayInput, String toolUseId) {
        sendRequest(sessionId, requestId, toolName, description, displayInput);
    }

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
     * [P0.1] 登记本次请求「允许从哪些连接回执」· key = {@code instanceId|peerId}。
     *
     * <p>默认空实现（旧实现/测试无需关心）；生产实现据此拒绝「知道编号但不是该会话渠道」的回执。
     */
    default void registerOrigin(String requestId, java.util.Set<String> originKeys) { }

    /**
     * [P0.1] 带来源连接的回执解析（IM 入站走这条）。
     *
     * <p>默认委托 {#resolve}（旧实现兼容）；生产实现会先校验来源连接是否属于本请求登记的集合。
     */
    default boolean resolveFrom(String requestId, String behavior, String fromServer,
                                String instanceId, String peerId) {
        return resolve(requestId, behavior, fromServer);
    }

    /**
     * 通道响应载荷 · 对齐 CC interactiveHandler.ts:363-397 response
     * （{@code behavior} / {@code fromServer}）。
     */
    record ChannelResponse(String behavior, String fromServer) {}

    /**
     * CC truncateForPreview — JSON 预览截断到 200 字符（channelPermissions.ts:160-167）·
     * phone-sized（Write(5KB-file) 不刷屏）。
     *
     * <p><b>WHY 放在接口上（static）</b>：出站事件（{@code inputPreview}）与会话状态重放快照
     * （{@link PendingPermissionSnapshot#inputPreview()}）必须用<b>同一套</b>截断规则 ⇒ 单点实现，
     * 避免两处漂移（生产实现与快照各写一份 ⇒ 预览长度不一致）。
     *
     * @param input 工具入参（可 null）
     * @return 截断后的 JSON 字符串（不可序列化时返回 {@code "(unserializable)"}）
     */
    static String truncateForPreview(JsonNode input) {
        try {
            if (input == null) {
                return "";
            }
            // [P0] 与 IM 文本共用同一个脱敏函数：STOMP 出站同样会落到外部渠道订阅者手里，
            //   不能只脱 IM 那条路。先脱敏再截断（截断不能把密钥截成安全样、再露出后半段）。
            String masked = ChannelPermissionPayload.maskSecrets(input.toString());
            return masked.length() > 200 ? masked.substring(0, 200) + "…" : masked;
        } catch (Exception e) {
            return "(unserializable)";
        }
    }
}
