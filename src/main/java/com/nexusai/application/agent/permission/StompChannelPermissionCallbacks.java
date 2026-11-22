package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.security.ChannelPermission;
import com.nexusai.eventbus.ws.ChannelPermissionRequestEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Channel 竞速回调的 STOMP/WebSocket 生产实现 · 对齐 CC services/mcp/channelPermissions.ts
 * （Telegram / iMessage / Discord 权限中继）。
 *
 * <p><b>WHY</b>（[canUseTool v4] 修复 v3 对抗复验缺口①）：v3 的
 * {@link ChannelPermissionCallbacks} 是接口、生产无 @Component 实现 → WebSocketPermissionPrompter
 * {@code @Autowired(required=false)} 注入 null → startChannelRace 直接 return，通道中继竞速永不
 * 参与。用户明确要求"限制就开放"。本类把通道表面接到 Java 既有的 STOMP/WebSocket 通道：
 * <ol>
 *   <li>{@link #sendRequest} → 出站推送到
 *       {@code /topic/sessions/{sessionId}/permission-channel-requests}（通道表面展示）</li>
 *   <li>用户通道回复 "yes tbxkq" → 通道 server 解析 → SEND
 *       {@code /app/sessions/{sessionId}/permission-channel-response} →
 *       {@link com.nexusai.apis.permission.PermissionController} 调 {@link #resolve} → 竞速 future
 *       complete（claim 守卫在 prompter 侧）</li>
 * </ol>
 *
 * <p>短 ID 生成（{@link #shortRequestId}）复用 {@link ChannelPermission}（security 包既有 CC
 * channelPermissions.ts 移植：FNV-1a 5 字母 a-z 去 'l' + blocklist 重 hash），避免双实现漂移。
 *
 * @see ChannelPermissionCallbacks
 * @see WebSocketPermissionPrompter#startChannelRace
 * @since canUseTool v4
 *
 * <p><b>[impl-I-3 T6 · Q-36]</b> STOMP 是 Java web 传输面替换（行为对齐 CC channel 权限中继、
 * 传输载体用 STOMP 代替 MCP notification）——非删除项（deletion-manifest KEEP-05），
 * 不做 STOMP→MCP 传输面迁移。channel 竞速门控 = settings 两列（V334，默认开，DB 唯一权威）
 * + 本会话已绑定渠道 + 合格 channel server，判定见 {@link WebSocketPermissionPrompter#startChannelRace}。
 */
@Component
public class StompChannelPermissionCallbacks implements ChannelPermissionCallbacks {

    private static final Logger log = LoggerFactory.getLogger(StompChannelPermissionCallbacks.class);

    private final SimpMessagingTemplate ws;
    private final ChannelPermission channelPermission;
    private final Map<String, Consumer<ChannelResponse>> pending = new ConcurrentHashMap<>();

    /**
     * [P2] 短 ID → 会话（小写键）· 结算/取消时按会话释放「会话内唯一」登记。
     */
    private final Map<String, String> requestSessionOf = new ConcurrentHashMap<>();

    /**
     * [P4] 已终结短 ID 的终态（小写键）· 迟到回执据此给出「已处理 / 已过期」的结构化终态。
     *
     * <p>WHY：桌面先答后渠道再回，{@code pending} 里已无该 id ⇒ 旧实现只打 debug
     * 「resolve miss」，无法区分「已被处理」与「短 id 已失效」。这里保留终态与原因，
     * 迟到回执日志带 sessionId / 渠道 / 短 ID / 原因（终端只读，不向渠道回执）。
     */
    private final Map<String, Terminal> terminals = new ConcurrentHashMap<>();
    private static final int MAX_TERMINALS = 512;

    /** [P4] 短 ID 终态快照（可测试）。 */
    record Terminal(String sessionId, String state, String reason) {}

    /**
     * [P0.1] requestId → 允许回执的来源连接集合（key = {@code instanceId|peerId}）。
     *
     * <p>为什么需要：STOMP/IM 消息里的 {@code fromServer} 是自证字段；只有「这条回执来自本会话
     * 绑定的那些连接」才可信。未登记（旧路径/测试）→ 不做连接校验，仍走口径校验。
     */
    private final Map<String, java.util.Set<String>> origins = new ConcurrentHashMap<>();

    /**
     * @param ws STOMP 推送模板 · {@code @Autowired(required=false)} 容错 — 无 WebSocket 场景
     *           （测试 / 纯后端）注入 null，sendRequest 退化为 warn + 不推送。
     */
    @Autowired
    public StompChannelPermissionCallbacks(@Autowired(required = false) SimpMessagingTemplate ws) {
        this.ws = ws;
        // 本类只复用 shortRequestId / pending map / resolve（对齐 CC createChannelPermissionCallbacks）。
        // [V334] 真正的门已改为 settings 两列（DB 唯一权威）；{@code ChannelPermission.isEnabled}
        // 在本仓无调用方，这里给 false 以免未来有人误以为「new 了就是开着的」。
        this.channelPermission = new ChannelPermission(() -> false);
    }

    /** 出站 channel 请求 topic · {@code /topic/sessions/{sessionId}/permission-channel-requests}。 */
    public static String topicFor(String sessionId) {
        return "/topic/sessions/" + sessionId + "/permission-channel-requests";
    }

    @Override
    public String shortRequestId(String toolUseId) {
        return channelPermission.shortRequestId(toolUseId);
    }

    /**
     * [P2] 会话内唯一短 ID：随机 5 位（25 字母去 l）+ 脏词重试 + 会话内撞号重试；
     * 随机失败回落确定性哈希（保留回退路径）。
     */
    @Override
    public String shortRequestId(String sessionId, String toolUseId) {
        return channelPermission.shortRequestIdForSession(sessionId, toolUseId);
    }

    @Override
    public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
        // key 转小写 — 对齐 CC channelPermissions.ts:216-226（onResponse lowercase key，
        // resolve 同侧 lowercase，使 mixed-case 调用方也不会静默 miss）
        pending.put(requestId.toLowerCase(), handler);
        return () -> {
            pending.remove(requestId.toLowerCase());
            // [P4] 桌面先答 / abort ⇒ 记终态，迟到回执据此报告「已处理」而非静默 miss
            markTerminal(requestId, "already_processed", "released_before_receipt");
            // [P2] 取消（abort / 超时）也释放「会话内唯一」登记，避免短号永久被占
            releaseIssued(requestId);
        };
    }

    @Override
    public void sendRequest(String sessionId, String requestId, String toolName,
                            String description, JsonNode displayInput) {
        sendRequest(sessionId, requestId, toolName, description, displayInput, null);
    }

    @Override
    public void sendRequest(String sessionId, String requestId, String toolName,
                            String description, JsonNode displayInput, String toolUseId) {
        if (ws == null) {
            log.warn("CHANNEL sendRequest skipped: SimpMessagingTemplate 未注入 (无 WebSocket 通道) requestId={}",
                requestId);
            // [P2] 未发出 ⇒ 释放会话内短号（否则该号永久被占）
            releaseIssued(requestId);
            return;
        }
        // input_preview 截断到 200 字符 — CC truncateForPreview（channelPermissions.ts:160-167），
        // phone-sized JSON 预览（Write(5KB-file) 不刷屏）。
        // [Phase 1d] computer-use 审批面板数据：**必须由发布方直传** —— 事件里只有
        //   inputPreview（截断到 200 字符的 JSON 串）⇒ 无法反解 resolvedApps。
        //   数据源 = displayInput（工具入参同一实例，含 Phase 1a 就地注入的 resolvedApps）。
        com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload computerUse =
            com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload.fromToolInput(
                toolName, displayInput);
        // [P0] toolUseId 必须与同一次请求的 message / bridge 事件同值 —— 前端以
        //   `toolUseId ?? requestId` 作唯一键去重/结算，否则三条通道各留一张卡。
        ChannelPermissionRequestEvent event = ChannelPermissionRequestEvent.of(
            sessionId, requestId, toolUseId, toolName, description,
            ChannelPermissionCallbacks.truncateForPreview(displayInput), computerUse);
        try {
            ws.convertAndSend(topicFor(sessionId), event);
            // [P2] 记录短号归属会话 —— 结算/取消时按会话释放「会话内唯一」登记
            if (requestId != null) {
                String key = requestId.toLowerCase(java.util.Locale.ROOT);
                requestSessionOf.put(key, sessionId);
                // [P4] 新请求复用该短号 ⇒ 清掉上一次的终态（否则迟到回执会读到旧终态）
                terminals.remove(key);
            }
            if (log.isInfoEnabled()) {
                log.info("CHANNEL sendRequest → topic={} requestId={} tool={} sessionId={} toolUseId={}",
                    topicFor(sessionId), requestId, toolName, sessionId, toolUseId);
            }
        } catch (Exception e) {
            // [P2] 推送失败 = 该请求未参与竞速 ⇒ 释放短号
            releaseIssued(requestId);
            // fail-loud: 推送失败让 prompter 的 catch 按 graceful degradation 处理
            log.error("CHANNEL sendRequest STOMP push failed: requestId={} err={}",
                requestId, e.toString());
            throw e;
        }
    }

    @Override
    public void registerOrigin(String requestId, java.util.Set<String> originKeys) {
        if (requestId == null || originKeys == null || originKeys.isEmpty()) {
            return;
        }
        origins.put(requestId.toLowerCase(java.util.Locale.ROOT), java.util.Set.copyOf(originKeys));
    }

    /**
     * [B4] 某会话当前仍待回执的短编号（小写，按字典序稳定排序）。
     *
     * <p>WHY：裸 {@code yes/no} 在多条待答时无法确定批哪一条，必须能数出「本会话还有几条」
     * 并把唯一那条的编号交给入站路由；否则只能退化成「当普通聊天送进 agent」（实测事故）。
     * 口径只认**已成功发出且仍在 pending** 的请求：{@code requestSessionOf} 在 sendRequest
     * 成功后才写入，resolve/退订时移除，所以两边都命中才算真实待答。
     *
     * @param sessionId 会话 id（与 sendRequest 的投递会话同口径）
     * @return 待答短编号列表（无/参数空 ⇒ 空列表，永不 null）
     */
    public java.util.List<String> pendingShortIdsForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : requestSessionOf.entrySet()) {
            if (sessionId.equals(e.getValue()) && pending.containsKey(e.getKey())) {
                out.add(e.getKey());
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** [P2] 释放会话内短号（结算 / 取消 / 出站失败共用；未登记过 ⇒ 无操作）。 */
    private void releaseIssued(String shortId) {
        if (shortId == null) return;
        String key = shortId.toLowerCase(java.util.Locale.ROOT);
        String sid = requestSessionOf.remove(key);
        if (sid != null) {
            channelPermission.releaseShortId(sid, key);
        }
    }

    /** [P4] 记录短号终态（含 sessionId + 原因），供迟到回执日志归因。 */
    private void markTerminal(String shortId, String state, String reason) {
        if (shortId == null) return;
        String key = shortId.toLowerCase(java.util.Locale.ROOT);
        String sid = requestSessionOf.get(key);
        if (terminals.size() >= MAX_TERMINALS) {
            // 简单上限：终态只用于日志归因，丢弃后降级为 expired（方向安全）
            terminals.clear();
        }
        terminals.put(key, new Terminal(sid, state, reason));
    }

    /** [P4] 测试只读：短号终态（null = 无记录）。 */
    Terminal terminalForTesting(String shortId) {
        return shortId == null ? null : terminals.get(shortId.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public boolean resolveFrom(String requestId, String behavior, String fromServer,
                               String instanceId, String peerId) {
        java.util.Set<String> expected = requestId == null ? null
            : origins.get(requestId.toLowerCase(java.util.Locale.ROOT));
        if (expected != null) {
            String key = instanceId + "|" + peerId;
            if (!expected.contains(key)) {
                log.warn("CHANNEL resolve rejected: 来源连接不属于本请求（terminal=rejected_untrusted_origin）"
                        + " requestId={} instance={} peer={}", requestId, instanceId, peerId);
                return false;
            }
        }
        return resolve(requestId, behavior, fromServer);
    }

    @Override
    public boolean resolve(String requestId, String rawBehavior, String fromServer) {
        // [P1.5] 入站统一成语义 {request_id, behavior: allow|deny}（大小写不敏感）。
        //   非法 behavior **不消耗** pending（否则一条脏消息能把真实请求作废）。
        String behavior = rawBehavior == null ? "" : rawBehavior.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"allow".equals(behavior) && !"deny".equals(behavior)) {
            log.warn("CHANNEL resolve rejected: invalid behavior（不消耗 pending）requestId={} behavior={}",
                requestId, rawBehavior);
            return false;
        }
        String key = requestId != null ? requestId.toLowerCase() : null;
        Consumer<ChannelResponse> resolver = key == null ? null : pending.remove(key);
        if (resolver == null) {
            // [P4] 迟到/重复回执：明确终态（已处理 vs 已过期），带 sessionId / 渠道 / 短 ID / 原因。
            //   ⛔ 不向渠道回复任何内容（桌面 UI 与真实状态已在别处收口）。
            Terminal t = key == null ? null : terminals.get(key);
            if (t != null) {
                log.info("PERMISSION channel receipt terminal: terminal={} reason={} sessionId={}"
                        + " channel={} shortId={}",
                    t.state(), t.reason(), t.sessionId(), fromServer, requestId);
            } else {
                log.info("PERMISSION channel receipt terminal: terminal=expired reason=not_pending"
                        + " sessionId={} channel={} shortId={}", null, fromServer, requestId);
            }
            return false;
        }
        // delete-before-call — resolver 抛错/重入时条目已移除（CC channelPermissions.ts:232-236）
        try {
            resolver.accept(new ChannelResponse(behavior, fromServer));
        } finally {
            // [P4] 渠道先答 ⇒ 终态=已处理（原因=渠道裁决）；[P2] 同时释放会话内短号
            markTerminal(key, "already_processed", "resolved_by_channel");
            releaseIssued(key);
        }
        if (log.isInfoEnabled()) {
            log.info("CHANNEL resolve: requestId={} behavior={} fromServer={}",
                requestId, behavior, fromServer);
        }
        return true;
    }

    /** CC truncateForPreview — JSON 预览 200 字符（channelPermissions.ts:160-167）。
     *  单点实现见 {@link ChannelPermissionCallbacks#truncateForPreview(JsonNode)}（出站事件与
     *  会话状态重放快照共用同一套截断规则 ⇒ 不会两处漂移）。 */
}
