package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Consumer;

/**
 * Bridge（CCR / claude.ai 远程弹窗）权限竞速回调 · 对齐 CC
 * {@code Open-ClaudeCode/src/bridge/bridgePermissionCallbacks.ts}。
 *
 * <p>[canUseTool v3] interactive 分支四路竞速（hook/classifier/bridge/channel）的第 4 路 ·
 * 对齐 CC {@code handleInteractivePermission}（interactiveHandler.ts:244-298）：
 * <pre>
 *   bridgeCallbacks.sendRequest(bridgeRequestId, tool.name, displayInput, toolUseID,
 *                               description, result.suggestions, result.blockedPath)
 *   const unsubscribe = bridgeCallbacks.onResponse(bridgeRequestId, response => { ... })
 * </pre>
 *
 * <p>[canUseTool v4] 生产实现 {@link StompBridgePermissionCallbacks}（@Component）：远程表面
 * 经 STOMP/WebSocket 通道订阅出站请求并回传 inbound 响应，让 bridge 竞速真正参与生产
 * （v3 对抗复验缺口① — 无 @Component 实现 → 注入 null → startBridgeRace 直接 return）。
 * 远程响应 {@code behavior} 为 {@code allow} / {@code deny}。
 *
 * @see WebSocketPermissionPrompter#startBridgeRace
 * @see StompBridgePermissionCallbacks
 * @since canUseTool v3
 */
public interface BridgePermissionCallbacks {

    /**
     * 向 CCR 发送权限请求 · 对齐 CC bridgeCallbacks.sendRequest（interactiveHandler.ts:245-253）。
     *
     * <p>[canUseTool v4] 新增 {@code sessionId} 参数 — CC 中回调是 session 级闭包（sessionId
     * 由 replBridgePermissionCallbacks 捕获），Java @Component 是单例 bean，sessionId 必须显式
     * 传入以路由出站请求到正确 session 的 topic。
     *
     * @param sessionId   目标 session ID（出站请求 topic 路由，对齐 CC 回调的 session 闭包）
     * @param requestId   本 bridge 请求 ID（随机 UUID，对齐 CC randomUUID()）
     * @param toolName    工具名
     * @param displayInput 展示用输入（CC displayInput = result.updatedInput ?? ctx.input）
     * @param toolUseId   工具调用 ID（关联取消）
     * @param description 弹窗描述（CC tool.description(input) 产物）
     * @param suggestions 建议的权限更新（"Add allow rule" 等）
     * @param blockedPath 被阻断路径（可为 null）
     */
    void sendRequest(String sessionId, String requestId, String toolName, JsonNode displayInput,
                     String toolUseId, String description,
                     List<PermissionUpdate> suggestions, String blockedPath);

    /**
     * 注册远程响应回调 · 返回退订 Runnable（对齐 CC unsubscribe）。
     *
     * @param requestId bridge 请求 ID
     * @param handler   远程响应处理器（只调用一次，首个 claim 生效）
     * @return 退订回调（map 清理 + abort listener 摘除）
     */
    Runnable onResponse(String requestId, Consumer<BridgeResponse> handler);

    /**
     * 取消远程请求 · 对齐 CC bridgeCallbacks.cancelRequest（interactiveHandler.ts:144/168）。
     */
    void cancelRequest(String requestId);

    /**
     * [RV-07] 双工回传 · 对齐 CC {@code bridgePermissionCallbacks.sendResponse(requestId, response)}
     * （bridgePermissionCallbacks.ts:20）。
     *
     * <p>CC 语义：CLI 本地 racer 胜出（onAbort/onAllow/onReject）时向 CCR 回传
     * {@code BridgePermissionResponse}，让远程表面 dismiss 其弹窗（interactiveHandler.ts:140-144/
     * 162-168/186-192）。Java 方向相反（server → web 远程表面），由生产实现
     * {@link StompBridgePermissionCallbacks#sendResponse} 出站推送到 dismiss topic。
     *
     * @param requestId bridge 请求 ID（随机 UUID，对齐 CC randomUUID()）
     * @param response  本地决策回传（behavior / message / updatedInput / updatedPermissions）
     */
    void sendResponse(String requestId, BridgeResponse response);

    /**
     * [canUseTool v4] STOMP inbound 响应解析 · 对齐 CC bridgePermissionCallbacks.ts
     * {@code sendResponse}（server → CC 的回传通道）语义。
     *
     * <p>delete-before-call（对齐 channelPermissions.ts:228-238）：找到 resolver 先移除再调用，
     * 重复事件 / 网络 dup 第二次返回 false 被忽略。由
     * {@link com.nexusai.apis.permission.PermissionController} 在收到
     * {@code /app/sessions/{sessionId}/permission-bridge-response} 时调用。
     *
     * @param requestId bridge 请求 ID
     * @param response  远程响应（behavior / message / updatedInput）
     * @return true = 命中 pending resolver 并已调用；false = 未知/已 resolve
     */
    boolean resolve(String requestId, BridgeResponse response);

    /**
     * 远程响应载荷 · 对齐 CC interactiveHandler.ts:258-293 response
     * （{@code behavior} / {@code message} / {@code updatedInput}）+ bridgePermissionCallbacks.ts:3-8
     * {@code BridgePermissionResponse}（含 {@code updatedPermissions?: PermissionUpdate[]}）。
     *
     * @param behavior            allow / deny
     * @param message             拒绝/附言（可为 null）
     * @param updatedInput        远程专用渲染器返回的修改后 input（可为 null；CC :280
     *                            {@code response.updatedInput ?? displayInput}）
     * @param updatedPermissions  [S16] 远程表面批准的权限更新列表（CC
     *                            bridgePermissionCallbacks.ts:6 {@code updatedPermissions}；
     *                            可为 null/空 = 未批准任何规则变更）。批准后由
     *                            {@link WebSocketPermissionPrompter} 按 CC
     *                            interactiveHandler.ts:266-269 语义 apply + persist。
     */
    record BridgeResponse(String behavior, String message, JsonNode updatedInput,
                          List<PermissionUpdate> updatedPermissions) {
        public BridgeResponse {
            if (updatedPermissions != null) {
                updatedPermissions = List.copyOf(updatedPermissions);
            }
        }

        /**
         * [S16] 兼容构造器 · 旧调用（无 updatedPermissions）→ null。
         */
        public BridgeResponse(String behavior, String message, JsonNode updatedInput) {
            this(behavior, message, updatedInput, null);
        }
    }
}
