package com.nexusai.apis.permission;

import com.nexusai.application.agent.mcp.ElicitationHandler;
import com.nexusai.application.agent.messaging.PeerUiApprovalAuthority;
import com.nexusai.application.agent.permission.BridgePermissionCallbacks;
import com.nexusai.application.agent.permission.ChannelPermissionCallbacks;
import com.nexusai.application.agent.permission.LeaderPermissionConfirmBridge;
import com.nexusai.application.agent.permission.WebSocketPermissionPrompter;
import com.nexusai.eventbus.ws.BridgePermissionResponseEvent;
import com.nexusai.eventbus.ws.ChannelPermissionResponseEvent;
import com.nexusai.eventbus.ws.ElicitationFormResponseEvent;
import com.nexusai.eventbus.ws.MessagePermissionResponseEvent;
import com.nexusai.eventbus.ws.PermissionExplainRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

/**
 * 权限响应 STOMP 控制器 · 前端 → 后端。
 *
 * <h2>职责</h2>
 * <p>接收前端从 {@code /app/sessions/{sessionId}/permission-response} 发送的
 * {@link MessagePermissionResponseEvent}，转发到
 * {@link WebSocketPermissionPrompter#onResponse(String, String)} 完成异步 future，
 * 让 {@link com.nexusai.application.agent.LlmAgentLoop} 中阻塞的
 * {@code PermissionPrompter.prompt()} 返回。
 *
 * <h2>URL 约定</h2>
 * <pre>
 *   前端 SEND 目标: /app/sessions/{sessionId}/permission-response
 *   @MessageMapping: /sessions/{sessionId}/permission-response
 *   （{@code /app/} 是 STOMP SEND 前缀，由 Spring WebSocket 配置剥离）
 *
 *   [REV-FIX-5 缝隙3] 权限解释（对齐 CC PermissionExplanation.tsx Ctrl+E 惰性触发）：
 *   前端 SEND 目标: /app/sessions/{sessionId}/permission-explain
 *   @MessageMapping: /sessions/{sessionId}/permission-explain
 *   → 服务端推送: /topic/sessions/{sessionId}/permission-explanations
 *     （{@code WebSocketPermissionPrompter.explanationTopicFor}）
 * </pre>
 *
 * <h2>错误容忍</h2>
 * <p>本 controller 不返回 error frame —— 即使 {@code requestId} 无效（已超时 /
 * 被覆盖），{@code prompter.onResponse} 内部 log warn 后忽略。前端不会看到 500。
 * [REV-FIX-5] 权限解释请求同样容错：prompter 未注入 / event 空 → log 后忽略。
 *
 * <h2>为什么用 {@code @Controller} 而非 {@code @RestController}</h2>
 * <p>{@code @MessageMapping} 是 WebSocket 层的路由，与 HTTP REST 不同。
 * Spring 文档明确建议 WebSocket handler 用 {@code @Controller}（语义清晰：
 * "本类不直接处理 HTTP"）。
 *
 * @see WebSocketPermissionPrompter
 * @see MessagePermissionResponseEvent
 * @see PermissionExplainRequestEvent
 * @see com.nexusai.ws.events.MessagePermissionRequestEvent
 */
@Controller
public class PermissionController {

    private static final Logger log = LoggerFactory.getLogger(PermissionController.class);

    /**
     * Spring 注入的 STOMP prompter · 转发响应到对应 future。
     *
     * <p>{@code required = false} 容错：测试场景无 WebSocket bean 时不阻塞实例化。
     * 但生产环境必须有 bean（{@link WebSocketPermissionPrompter} 是 {@code @Component}）。
     */
    @Autowired(required = false)
    private WebSocketPermissionPrompter prompter;

    /**
     * [canUseTool v4] bridge 竞速回调（CCR/claude.ai 远程弹窗）· 生产 @Component
     * {@link com.nexusai.application.agent.permission.StompBridgePermissionCallbacks} 已接线。
     * 远程表面响应 → {@link #handleBridgePermissionResponse} resolve。
     */
    @Autowired(required = false)
    private BridgePermissionCallbacks bridgeCallbacks;

    /**
     * [canUseTool v4] channel 竞速回调（Telegram/iMessage/Discord）· 生产 @Component
     * {@link com.nexusai.application.agent.permission.StompChannelPermissionCallbacks} 已接线。
     * 通道 server 解析用户回复 → {@link #handleChannelPermissionResponse} resolve。
     */
    @Autowired(required = false)
    private ChannelPermissionCallbacks channelCallbacks;

    /**
     * [P0.1] 渠道适配器身份校验 · 未配置口令 ⇒ 所有 STOMP 渠道回执一律拒绝（fail-closed）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.ChannelAdapterAuth channelAdapterAuth;

    /**
     * [IMP-SS-01] Elicitation form 弹窗响应处理器 · 对齐 CC elicitationHandler.ts:138-146
     * {@code respond(result)} 回调。注入 {@link ElicitationHandler} 完成 form 挂起 future；
     * required=false 容错（MCP 未装配/测试无 WebSocket bean 时不阻塞实例化）。
     */
    @Autowired(required = false)
    private ElicitationHandler elicitationHandler;

    /**
     * [Batch2 C1] leader inbox 权限请求桥 · 生产注册 {@link LeaderPermissionBridge} setter 的
     * STOMP 表面（{@code LeaderPermissionConfirmBridge}）。本 controller 收到前端响应时先分流给
     * 本桥（命中 → 消费 entry 回调，不落 prompter 的 pending map）；未命中 → 交
     * {@link WebSocketPermissionPrompter}。required=false 容错（测试无 bean 时不阻塞实例化）。
     */
    @Autowired(required = false)
    private LeaderPermissionConfirmBridge leaderConfirmBridge;

    /**
     * 接收前端权限响应。
     *
     * <p>处理流程：
     * <ol>
     *   <li>校验 prompter 已注入（无 → log error 但不抛）</li>
     *   <li>校验 event/requestId/decision 非空（无 → log warn 但不抛）</li>
     *   <li>调 {@link WebSocketPermissionPrompter#onResponse} 完成 future</li>
     * </ol>
     *
     * <p>为什么不抛异常：弹窗响应是用户交互，可能因为刷新页面 / 多 tab / 网络重连
     * 导致"延迟到达"或"重复到达"。抛异常 → STOMP error frame → 前端断连 → 体验差。
     * 容错 + log 是正确选择。
     *
     * @param sessionId 路径变量（实际未使用 —— {@code requestId} 已含 session 上下文）
     * @param event     前端发送的响应事件
     */
    @MessageMapping("/sessions/{sessionId}/permission-response")
    public void handlePermissionResponse(@DestinationVariable String sessionId,
                                          MessagePermissionResponseEvent event) {
        if (event == null) {
            log.warn("PERMISSION response: event body is null (sessionId={})", sessionId);
            return;
        }
        if (prompter == null) {
            log.error("PERMISSION response: prompter is null (Spring DI failed), "
                + "ignoring event sessionId={} requestId={}", sessionId, event.getRequestId());
            return;
        }
        log.info("PERMISSION response: sessionId={} requestId={} decision={} feedback={} blocks={} updatedPermissions={} hasAnswers={} hasAnnotations={}",
            sessionId, event.getRequestId(), event.getDecision(),
            event.getAcceptFeedback() != null,
            event.getContentBlocks() != null ? event.getContentBlocks().size() : 0,
            event.getUpdatedPermissions() != null ? event.getUpdatedPermissions().size() : 0,
            event.getAnswers() != null,
            event.getAnnotations() != null);
        // [R32-b9] 透传 acceptFeedback + contentBlocks (CC addToolResult allow 路径)
        // [S16] 透传 updatedPermissions（用户批准的建议；原始 JSON 数组 → prompter 内解析）
        // [FIX-E askuser-answers] 透传 answers + annotations（AskUserQuestion 答案收集通道，
        //   对齐 CC AskUserQuestionPermissionRequest.tsx:398-407 submitAnswers → onAllow(updatedInput, ...)）
        // ── [C批 · 跨会话安全] 任何 pending.remove 之前先校验归属 / 来源归因 ──
        //   审批主体 = 默认本地用户（userId 缺省 "default"）→ 正常 UI 响应可信；
        //   ⛔ 但「可归因为 peer」的请求一律不消耗、不解除 pending：peer 消息不能充当用户批准。
        //   错归属同样保留 pending（拒绝这一个响应，不替攻击者完成取消动作）。
        if (PeerUiApprovalAuthority.isPeerAttributed(
                PeerUiApprovalAuthority.Source.STOMP_MESSAGE, sessionId, event.getRequestId())) {
            log.warn("PERMISSION response: 请求可归因为 peer 来源 → 丢弃该响应且保留 pending "
                + "sessionId={} requestId={} decision={}", sessionId, event.getRequestId(),
                event.getDecision());
            return;
        }
        WebSocketPermissionPrompter.ResponseAdmission admission =
            prompter.admitMessageResponse(sessionId, event.getRequestId());
        if (admission == WebSocketPermissionPrompter.ResponseAdmission.WRONG_SESSION) {
            log.warn("PERMISSION response: 会话归属不匹配，拒绝且保留 pending sessionId={} requestId={}",
                sessionId, event.getRequestId());
            return;
        }
        if (admission != WebSocketPermissionPrompter.ResponseAdmission.ACCEPT
                && admission != WebSocketPermissionPrompter.ResponseAdmission.UNKNOWN_REQUEST) {
            // 默认 local-user 验证器下不会出现 UNTRUSTED_PEER_ORIGIN；若未来安装更严的验证器
            // 使得该响应不可信，仍只丢弃「这一个响应」，⛔ 不 denyPending（不替攻击者取消合法待审批项）。
            log.warn("PERMISSION response: 响应未通过可信 UI 准入 → 丢弃且保留 pending "
                + "sessionId={} requestId={} admission={}", sessionId, event.getRequestId(), admission);
            return;
        }
        log.info("PERMISSION response accepted: sessionId={} requestId={} user={} admission={}",
            sessionId, event.getRequestId(), PeerUiApprovalAuthority.getUserId(), admission);
        // 归属正确（ACCEPT）→ 直接交 prompter；未知 requestId（UNKNOWN）→ 先尝试 leader inbox 分流
        //（leader 权限的 requestId 空间 = worker toolUseId，不落 prompter pending map）。
        if (admission == WebSocketPermissionPrompter.ResponseAdmission.UNKNOWN_REQUEST) {
            if (leaderConfirmBridge != null) {
                // [C批 · 修正] leader 分支自带 session 归属校验；UNKNOWN 只是「不在 prompter
                //   pending map」的正常情况，**不得**因此回落成无归属校验的放行。
                //   归属不符 → REJECTED_WRONG_SESSION（entry 保留），本 handler 直接返回，
                //   不再继续下发到 prompter.onResponse。
                LeaderPermissionConfirmBridge.ResponseResult leaderOutcome =
                    leaderConfirmBridge.onResponse(sessionId, event.getRequestId(),
                        event.getDecision(), event.getUpdatedPermissions(),
                        event.getAcceptFeedback(), event.getContentBlocks(),
                        event.getAnswers(), event.getAnnotations());
                if (leaderOutcome != LeaderPermissionConfirmBridge.ResponseResult.NOT_FOUND) {
                    return;
                }
            }
        }
        prompter.onResponse(event.getRequestId(), event.getDecision(),
            event.getUpdatedPermissions(),
            event.getAcceptFeedback(), event.getContentBlocks(),
            event.getAnswers(), event.getAnnotations(),
            event.getUpdatedInput(),
            // [V333 AFK] 倒计时超时提交：时长进 updatedInput，isByCountdown 只做归因日志
            event.getAfkTimeoutMs(), event.getIsByCountdown());
    }

    /**
     * [IMP-SS-01] 接收前端 Elicitation form 弹窗响应 · 对齐 CC elicitationHandler.ts:138-146
     * {@code respond({action, content})} 回调 → resolve form 挂起 Promise。
     *
     * <p>destination: {@code /app/mcp/elicitation-response}（{@code /app/} 是 STOMP SEND 前缀，
     * {@code @MessageMapping} 收到的是 {@code /mcp/elicitation-response}）。
     * 前端订阅 {@code /topic/mcp/elicitation-requests}（
     * {@link ElicitationHandler#ELICITATION_TOPIC}）收到 {@link com.nexusai.eventbus.ws.ElicitationFormRequestEvent}
     * 弹表单 → 用户填写提交 / 取消 → SEND 本事件回传。
     *
     * <p>容错同 {@link #handlePermissionResponse}：elicitationHandler 未注入 / event /
     * requestId / serverName 缺失 → log 后忽略（不返回 error frame，避免前端断连）。
     * 未知 requestId（已超时 / 已 abort / 已 resolve）→ {@code resolveFormResponse} 内部 log warn
     * 后返回 false，本 handler 不抛。
     *
     * <p>注意：本映射不含 {@code {sessionId}} 路径变量（挂起 future 路由 key 是
     * requestId + serverName，非 session），故不声明 {@code @DestinationVariable} ——
     * 与同文件 4 个兄弟 handler（{@code /sessions/{sessionId}/permission-*}）的模式区分，
     * 避免 {@code @DestinationVariable} 在无路径变量的映射上解析为哨兵空白串（spring-messaging
     * DEFAULT_NONE 语义）。会话上下文经 {@code ElicitationFormResponseEvent} 内字段承载。
     *
     * @param event form 弹窗响应（requestId / serverName / action / content）
     */
    @MessageMapping("/mcp/elicitation-response")
    public void handleElicitationFormResponse(ElicitationFormResponseEvent event) {
        if (event == null) {
            log.warn("ELICITATION form response: event body is null");
            return;
        }
        if (event.getRequestId() == null || event.getRequestId().isBlank()
                || event.getServerName() == null || event.getServerName().isBlank()) {
            log.warn("ELICITATION form response: requestId/serverName 缺失，忽略 event requestId={} serverName={}",
                event.getRequestId(), event.getServerName());
            return;
        }
        if (elicitationHandler == null) {
            log.error("ELICITATION form response: elicitationHandler is null (Spring DI failed), "
                + "ignoring event requestId={} serverName={}",
                event.getRequestId(), event.getServerName());
            return;
        }
        log.info("ELICITATION form response: serverName={} requestId={} action={} hasContent={}",
            event.getServerName(), event.getRequestId(), event.getAction(),
            event.getContent() != null);
        boolean resolved = elicitationHandler.resolveFormResponse(
            event.getRequestId(), event.getServerName(), event.getAction(), event.getContent());
        log.info("ELICITATION form response resolved: requestId={} resolved={}",
            event.getRequestId(), resolved);
    }

    /**
     * [REV-FIX-5 缝隙3] 接收前端"权限解释"请求 · 对齐 CC PermissionExplanation.tsx
     * Ctrl+E 惰性触发（permissionExplainer.ts:147-250）。
     *
     * <p>destination: {@code /app/sessions/{sessionId}/permission-explain} → prompter
     * {@code explainAndSend} 在 RACERS daemon 线程生成解释并经
     * {@code /topic/sessions/{sessionId}/permission-explanations} 推送
     * {@link com.nexusai.eventbus.ws.PermissionExplanationEvent}（四字段或 unavailable）。
     *
     * <p>容错同 {@link #handlePermissionResponse}：prompter 未注入 / event /
     * requestId / toolName 空 → log 后忽略（不返回 error frame，避免前端断连）。
     * 本 handler 不阻塞 —— 生成由 prompter 异步执行，STOMP inbound 线程立即返回。
     *
     * @param sessionId 路径变量（透传给 prompter 做 topic 路由 + 消息源）
     * @param event     前端发送的解释请求（requestId / toolName / toolInput / toolDescription）
     */
    @MessageMapping("/sessions/{sessionId}/permission-explain")
    public void handlePermissionExplain(@DestinationVariable String sessionId,
                                        PermissionExplainRequestEvent event) {
        if (event == null) {
            log.warn("PERMISSION explain: event body is null (sessionId={})", sessionId);
            return;
        }
        if (prompter == null) {
            log.error("PERMISSION explain: prompter is null (Spring DI failed), "
                + "ignoring event sessionId={} requestId={}", sessionId, event.getRequestId());
            return;
        }
        if (event.getRequestId() == null || event.getRequestId().isBlank()
                || event.getToolName() == null || event.getToolName().isBlank()) {
            log.warn("PERMISSION explain: requestId/toolName 缺失，忽略 event sessionId={} requestId={} toolName={}",
                sessionId, event.getRequestId(), event.getToolName());
            return;
        }
        log.info("PERMISSION explain: sessionId={} requestId={} tool={} hasToolDescription={}",
            sessionId, event.getRequestId(), event.getToolName(),
            event.getToolDescription() != null);
        prompter.explainAndSend(sessionId, event.getRequestId(), event.getToolName(),
            event.getToolInput(), event.getToolDescription());
    }

    /**
     * [canUseTool v4] 接收 bridge 远程表面（CCR / claude.ai 式弹窗）响应。
     *
     * <p>destination: {@code /app/sessions/{sessionId}/permission-bridge-response}
     * （对齐 CC bridgePermissionCallbacks.ts {@code sendResponse} — server → CC 回传通道）。
     * 远程表面用户 allow/deny 后 SEND 本事件 → {@link BridgePermissionCallbacks#resolve}
     * 完成竞速 future（claim 守卫在 prompter 侧，首个 racer 胜出）。
     *
     * <p>容错同 {@link #handlePermissionResponse}：bridge 未接线 / 未知 requestId / 已 resolve
     * → log 后忽略（不返回 error frame，避免远程表面断连）。
     *
     * @param sessionId 路径变量（仅日志；requestId 是跨 session 唯一的 bridge 随机 UUID）
     * @param event     bridge 响应事件（requestId / behavior / message / updatedInput）
     */
    @MessageMapping("/sessions/{sessionId}/permission-bridge-response")
    public void handleBridgePermissionResponse(@DestinationVariable String sessionId,
                                               BridgePermissionResponseEvent event) {
        if (event == null || event.getRequestId() == null) {
            log.warn("BRIDGE response: event or requestId is null (sessionId={})", sessionId);
            return;
        }
        if (bridgeCallbacks == null) {
            log.error("BRIDGE response: bridgeCallbacks is null (Spring DI failed), "
                + "ignoring event sessionId={} requestId={}", sessionId, event.getRequestId());
            return;
        }
        // ── [C批 · 跨会话安全] resolve（remove pending）之前校验归属 / 来源归因 ──
        if (PeerUiApprovalAuthority.isPeerAttributed(
                PeerUiApprovalAuthority.Source.STOMP_BRIDGE, sessionId, event.getRequestId())) {
            log.warn("BRIDGE response: 请求可归因为 peer 来源 → 丢弃该响应且保留 pending "
                + "bridgeRequestId={} sessionId={}", event.getRequestId(), sessionId);
            return;
        }
        WebSocketPermissionPrompter.ResponseAdmission admission =
            prompter != null
                ? prompter.admitBridgeResponse(sessionId, event.getRequestId())
                : WebSocketPermissionPrompter.ResponseAdmission.UNKNOWN_REQUEST;
        if (admission == WebSocketPermissionPrompter.ResponseAdmission.WRONG_SESSION) {
            log.warn("BRIDGE response: 会话归属不匹配，拒绝 bridgeRequestId={} sessionId={}",
                event.getRequestId(), sessionId);
            return;
        }
        if (admission != WebSocketPermissionPrompter.ResponseAdmission.ACCEPT
                && admission != WebSocketPermissionPrompter.ResponseAdmission.UNKNOWN_REQUEST) {
            log.warn("BRIDGE response: 响应未通过可信 UI 准入 → 丢弃且保留 pending "
                + "bridgeRequestId={} sessionId={} admission={}", event.getRequestId(), sessionId, admission);
            return;
        }
        // [S16] 远程表面批准的 updatedPermissions（原始 JSON 数组）→ prompter 转换器解析
        java.util.List<com.nexusai.application.agent.permission.PermissionUpdate> parsedUpdates =
            com.nexusai.application.agent.permission.WebSocketPermissionPrompter
                .parseUpdatedPermissions(event.getUpdatedPermissions());
        boolean resolved = bridgeCallbacks.resolve(event.getRequestId(),
            new BridgePermissionCallbacks.BridgeResponse(
                event.getBehavior(), event.getMessage(), event.getUpdatedInput(), parsedUpdates));
        log.info("BRIDGE response: sessionId={} requestId={} behavior={} resolved={} updatedPermissions={}",
            sessionId, event.getRequestId(), event.getBehavior(), resolved, parsedUpdates.size());
    }

    /**
     * [canUseTool v4] 接收 channel 表面（Telegram / iMessage / Discord）响应。
     *
     * <p>destination: {@code /app/sessions/{sessionId}/permission-channel-response}
     * （对齐 CC channelPermissions.ts {@code resolve} — structured event
     * notifications/claude/channel/permission 的匹配通道）。通道 server 解析用户 "yes tbxkq"
     * 后 SEND 本事件 → {@link ChannelPermissionCallbacks#resolve} 完成竞速。
     *
     * <p>容错同 {@link #handlePermissionResponse}：channel 未接线 / 未知 requestId / 已 resolve
     * → log 后忽略。
     *
     * @param sessionId 路径变量（仅日志）
     * @param event     channel 响应事件（requestId / behavior / fromServer）
     */
    @MessageMapping("/sessions/{sessionId}/permission-channel-response")
    public void handleChannelPermissionResponse(@DestinationVariable String sessionId,
                                                ChannelPermissionResponseEvent event) {
        if (event == null || event.getRequestId() == null) {
            log.warn("CHANNEL response: event or requestId is null (sessionId={})", sessionId);
            return;
        }
        if (channelCallbacks == null) {
            log.error("CHANNEL response: channelCallbacks is null (Spring DI failed), "
                + "ignoring event sessionId={} requestId={}", sessionId, event.getRequestId());
            return;
        }
        // ── [C批 · 跨会话安全] resolve（remove pending）之前校验归属 / 来源归因 ──
        if (PeerUiApprovalAuthority.isPeerAttributed(
                PeerUiApprovalAuthority.Source.STOMP_CHANNEL, sessionId, event.getRequestId())) {
            log.warn("CHANNEL response: 请求可归因为 peer 来源 → 丢弃该响应且保留 pending "
                + "channelRequestId={} sessionId={}", event.getRequestId(), sessionId);
            return;
        }
        WebSocketPermissionPrompter.ResponseAdmission admission =
            prompter != null
                ? prompter.admitChannelResponse(sessionId, event.getRequestId())
                : WebSocketPermissionPrompter.ResponseAdmission.UNKNOWN_REQUEST;
        if (admission == WebSocketPermissionPrompter.ResponseAdmission.WRONG_SESSION) {
            log.warn("CHANNEL response: 会话归属不匹配，拒绝 channelRequestId={} sessionId={}",
                event.getRequestId(), sessionId);
            return;
        }
        if (admission != WebSocketPermissionPrompter.ResponseAdmission.ACCEPT
                && admission != WebSocketPermissionPrompter.ResponseAdmission.UNKNOWN_REQUEST) {
            log.warn("CHANNEL response: 响应未通过可信 UI 准入 → 丢弃且保留 pending "
                + "channelRequestId={} sessionId={} admission={}", event.getRequestId(), sessionId, admission);
            return;
        }
        // ── [P0.1] 身份校验：fromServer 是消息体里可伪造的字符串，不能当身份用 ──
        //   回执必须带适配器共享口令（nexusai.mcp.channel-adapter-token）；未配置口令 ⇒ 全拒。
        if (channelAdapterAuth == null || !channelAdapterAuth.verify(event.getAdapterToken())) {
            log.warn("CHANNEL response rejected (untrusted adapter identity): terminal=rejected_untrusted_adapter "
                    + "sessionId={} requestId={} behavior={} fromServer={} hasToken={}",
                sessionId, event.getRequestId(), event.getBehavior(), event.getFromServer(),
                event.getAdapterToken() != null);
            return;
        }
        boolean resolved = channelCallbacks.resolve(
            event.getRequestId(), event.getBehavior(), event.getFromServer());
        log.info("CHANNEL response: sessionId={} requestId={} behavior={} fromServer={} resolved={}",
            sessionId, event.getRequestId(), event.getBehavior(), event.getFromServer(), resolved);
    }
}
