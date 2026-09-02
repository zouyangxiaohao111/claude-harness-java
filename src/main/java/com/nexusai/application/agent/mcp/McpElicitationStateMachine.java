package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.ElicitationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * MCP URL elicitation 两阶段状态机 · 对齐 CC {@code callMCPToolWithUrlElicitationRetry}
 * （client.ts:2813-3027）+ {@code elicitationHandler.ts}（:77-207）。
 *
 * <p>[impl-I-4 T6] 语义（自验 CC）：
 * <ul>
 *   <li>识别：JSON-RPC error code = {@code ErrorCode.UrlElicitationRequired(-32042)}（CC :2864-2867）</li>
 *   <li>校验：error.data.elicitations 每项 mode==='url' && url && elicitationId && message（CC :2876-2897）</li>
 *   <li>重试上限 {@code MAX_URL_ELICITATION_RETRIES=3}（CC :2850）</li>
 * </ul>
 *
 * <p>[impl-I-4 F6 rework] 两阶段（完整对齐 CC client.ts:2946-2996 + elicitationHandler.ts:186-199）：
 * <ul>
 *   <li><b>Phase 1 同意</b>：{@code respond} 回调返回 accept —— CC 注释「Phase 1 consent: accept is a
 *       no-op (doesn't resolve retry Promise)」（client.ts:2980-2984）。accept 只表示用户同意打开 URL，
 *       <b>不触发重试</b>。</li>
 *   <li><b>完成通知只启用「Retry now」</b>：{@code notifications/elicitation/complete}（elicitationHandler.ts:186-199）
 *       置 queue event {@code completed:true} —— 仅激活「Retry now」按钮，<b>不 resolve 重试 Promise</b>。
 *       {@code pendingElicitations()} 返回 completed 标记供前端启用按钮。</li>
 *   <li><b>用户门重试</b>：重试由用户点「Retry now」按钮（CC {@code onWaitingDismiss('retry')} :2990-2995）
 *       → {@link #retryConfirm} → loop 重试；等待期 {@code showCancel:true}（:2947）→ {@link #cancel} →
 *       decline 文本。</li>
 *   <li><b>fail-closed</b>：等待用户 Retry now/Cancel 有 {@link #decisionTimeoutMs} 超时（CC 无超时等用户，
 *       Java 后端前端未接线防悬挂）→ 返回 decline 文本；responder 未接线默认 auto-decline。</li>
 * </ul>
 *
 * <p>响应通道：Phase 1 = {@code setResponder(respond)}；Phase 2 = {@link #retryConfirm}/{@link #cancel}
 * （retry-confirm 二次通道，前端「Retry now」「Cancel」按钮消费，登记 {@code 探查/mcp_plugins/待前端实现.md}）。
 * fail-closed（Q-16 前端未实现）：responder 未接线默认 auto-decline → 返回 CC decline 文本，不悬挂工具调用。
 */
public class McpElicitationStateMachine {

    private static final Logger log = LoggerFactory.getLogger(McpElicitationStateMachine.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** CC ErrorCode.UrlElicitationRequired = -32042（client.ts:2864-2867）。 */
    public static final int URL_ELICITATION_REQUIRED = -32042;

    /** CC MAX_URL_ELICITATION_RETRIES=3（client.ts:2850）。 */
    public static final int MAX_URL_ELICITATION_RETRIES = 3;

    /** 工具调用函数 · CC {@code callToolFn}。 */
    @FunctionalInterface
    public interface ToolCallFn {
        CompletableFuture<JsonNode> call();
    }

    /**
     * elicitation 响应器 · Phase 1 同意通道（前端/钩子消费面，待前端实现.md 登记弹窗）。
     * 返回 {@code "accept"}（Phase 1 同意，打开 URL；不触发重试）/ {@code "decline"} / {@code "cancel"}。
     */
    @FunctionalInterface
    public interface ElicitationResponder {
        String respond(String serverName, PendingElicitation elicitation);
    }

    /** 待确认 elicitation · CC ElicitRequestURLParams + elicitationHandler queue event。 */
    public record PendingElicitation(String serverName, String elicitationId, String url,
                                     String message, boolean completed) {
        public PendingElicitation withCompleted() {
            return new PendingElicitation(serverName, elicitationId, url, message, true);
        }
    }

    /** 处理结果 · declined 时返回 decline 文本，否则 result 有效。 */
    public record ElicitationOutcome(JsonNode result, String declineMessage) {
        public boolean declined() {
            return declineMessage != null;
        }
    }

    private final ConcurrentLinkedQueue<PendingElicitation> pendingQueue = new ConcurrentLinkedQueue<>();
    private ElicitationResponder responder;

    /**
     * [WF-B] Elicitation hook 处理器 · 对齐 CC runElicitationHooks / runElicitationResultHooks
     * （client.ts:2924-2940 / :3000-3006）。null（默认，测试/未接线）→ 走原有 responder 路径，
     * 不改变既有行为。由 {@link McpToolPool} 生产接线（@Autowired(required=false) + @PostConstruct）。
     */
    private ElicitationHandler elicitationHandler;

    /** [WF-B] 注入 Elicitation hook 处理器（null → 不触发 hook 预解析/override）。 */
    public void setElicitationHandler(ElicitationHandler elicitationHandler) {
        this.elicitationHandler = elicitationHandler;
        if (log.isDebugEnabled()) {
            log.debug("[McpElicitationStateMachine] 注入 ElicitationHandler（{}）", elicitationHandler != null);
        }
    }

    /** [WF-B] 当前 ElicitationHandler（供 {@link McpToolPool} 惰性接线判断）。 */
    ElicitationHandler elicitationHandler() {
        return elicitationHandler;
    }

    /**
     * [impl-I-4 F6 rework] Phase 2 用户决策等待器：elicitationId → {@link #retryConfirm}/{@link #cancel}
     * 时 complete，值为 {@code "retry"} / {@code "cancel"}。对齐 CC 中 {@code userResult = await new Promise(
     * resolve => respond/onWaitingDismiss)}（client.ts:2956-2996）。ConcurrentHashMap 支持多 elicitation 并发等待。
     */
    private final Map<String, CompletableFuture<String>> decisionWaiters = new ConcurrentHashMap<>();

    /**
     * [impl-I-4 F6 rework] 决策先于等待器注册到达（retryConfirm/cancel 早到）的缓存：elicitationId → 决策值。
     * 消除「awaitRetryDecision 注册等待器前用户已点按钮」竞态（对应 F3 completedElicitationIds 模式）。
     * 每次 awaitRetryDecision 消费后移除（finally），不累积。
     */
    private final Map<String, String> earlyDecisions = new ConcurrentHashMap<>();

    /**
     * [impl-I-4 F6 rework] 已收到完成通知的 elicitationId 集合 · 完成通知只置 {@code completed:true}
     * （elicitationHandler.ts:186-199 queue event completed），<b>不 resolve 决策等待器</b>——重试由用户
     * {@link #retryConfirm} 驱动。供 {@link #pendingElicitations()} 返回 completed 标记（前端启用 Retry now）。
     */
    private final Set<String> completedElicitationIds = ConcurrentHashMap.newKeySet();

    /**
     * [impl-I-4 F6 rework] 等待用户 Retry now/Cancel 超时 · 对齐 CC 无超时（等用户）但 Java 后端 fail-closed：
     * 前端未接线、用户不操作时不能悬挂工具调用。默认 60s，测试可注入短超时。
     */
    private long decisionTimeoutMs = 60_000L;

    /**
     * [impl-I-4 F6 rework] 注入用户决策等待超时（ms）· 测试用短超时验证 fail-closed；生产默认 60s。
     */
    public void setDecisionTimeoutMs(long decisionTimeoutMs) {
        this.decisionTimeoutMs = decisionTimeoutMs > 0 ? decisionTimeoutMs : 60_000L;
    }

    /** 注入响应器（Phase 1 同意通道，前端/钩子）；null → auto-decline（fail-closed，前端未实现前默认）。 */
    public void setResponder(ElicitationResponder responder) {
        this.responder = responder;
    }

    /**
     * 待确认 elicitation 查询（前端消费面）。completed 标记由 {@link #completedElicitationIds} 派生
     * （对齐 CC queue event {@code completed:true}，前端据 flag 启用「Retry now」按钮）。
     */
    public List<PendingElicitation> pendingElicitations() {
        List<PendingElicitation> result = new ArrayList<>(pendingQueue.size());
        for (PendingElicitation p : pendingQueue) {
            result.add(completedElicitationIds.contains(p.elicitationId()) ? p.withCompleted() : p);
        }
        return result;
    }

    /**
     * 完成通知处理 · CC ElicitationCompleteNotificationSchema 置 queue event {@code completed:true}
     * （elicitationHandler.ts:186-199）。<b>只启用「Retry now」按钮，不 resolve 决策等待器、不自动重试</b>
     * （CC 注释「the dialog reacts to this flag」）。
     *
     * <p>[impl-I-4 F6 rework] 与 F3 差异：F3 完成通知直接 complete 等待器（accept 后自动重试）——与 CC
     * 用户门重试相悖；现改只置 completed 标记。未知/已出队 elicitation（retryConfirm/cancel 已消费）→ 忽略
     * （对齐 CC 「Ignoring completion notification for unknown elicitation」）。
     *
     * @param elicitationId 完成通知携带的 elicitation id
     */
    public void markElicitationCompleted(String elicitationId) {
        // [WF-B △-11] 对齐 CC elicitationHandler.ts:183-186 — 完成通知同时发
        // elicitation_complete Notification hook（observability），不依赖是否在队列
        // （CC 先 executeNotificationHooks 再查 queue event 置 completed）。
        String serverName = "unknown";
        for (PendingElicitation p : pendingQueue) {
            if (p.elicitationId().equals(elicitationId)) {
                serverName = p.serverName();
                break;
            }
        }
        if (elicitationHandler != null) {
            elicitationHandler.fireElicitationComplete(serverName, elicitationId);
        }
        boolean inQueue = pendingQueue.stream().anyMatch(p -> p.elicitationId().equals(elicitationId));
        if (!inQueue) {
            log.debug("[McpElicitationStateMachine] 忽略未知 elicitation 完成通知 id={}", elicitationId);
            return;
        }
        completedElicitationIds.add(elicitationId);
        log.info("[McpElicitationStateMachine] 收到完成通知 elicitationId={}（置 completed:true，启用 Retry now 按钮）",
            elicitationId);
    }

    /**
     * [impl-I-4 F6 rework] 「Retry now」按钮 · 对齐 CC {@code onWaitingDismiss('retry')}
     * （client.ts:2990-2995）→ resolve 决策等待器 {@code "retry"} → {@link #callWithElicitationRetry}
     * loop 重试。retry-confirm 二次通道：完成通知（completed:true）只启用按钮，重试仍由本方法（用户点击）驱动。
     *
     * @param elicitationId 用户点「Retry now」的 elicitation id
     */
    public void retryConfirm(String elicitationId) {
        pendingQueue.removeIf(p -> p.elicitationId().equals(elicitationId));
        CompletableFuture<String> waiter = decisionWaiters.remove(elicitationId);
        if (waiter != null) {
            waiter.complete("retry");
        } else {
            // 等待器注册前已点（awaitRetryDecision 未阻塞到）→ 缓存，await 注册后消费
            earlyDecisions.put(elicitationId, "retry");
        }
        log.info("[McpElicitationStateMachine] 用户点 Retry now elicitationId={}（对齐 CC onWaitingDismiss('retry')）",
            elicitationId);
    }

    /**
     * [impl-I-4 F6 rework] 等待期取消 · 对齐 CC {@code showCancel:true}（client.ts:2947）+ {@code
     * onWaitingDismiss('cancel')}（:2990-2995）→ resolve 决策等待器 {@code "cancel"} → decline 文本。
     *
     * @param elicitationId 用户点「Cancel」的 elicitation id
     */
    public void cancel(String elicitationId) {
        pendingQueue.removeIf(p -> p.elicitationId().equals(elicitationId));
        CompletableFuture<String> waiter = decisionWaiters.remove(elicitationId);
        if (waiter != null) {
            waiter.complete("cancel");
        } else {
            earlyDecisions.put(elicitationId, "cancel");
        }
        log.info("[McpElicitationStateMachine] 用户取消 elicitationId={}（showCancel:true，对齐 CC onWaitingDismiss('cancel')）",
            elicitationId);
    }

    /**
     * [impl-I-4 F6 rework] 阻塞等待用户决策（Retry now / Cancel / 超时）· 对齐 CC 中
     * {@code userResult = await new Promise(resolve => { respond / onWaitingDismiss })}
     * （client.ts:2956-2996）——等待的是<b>用户点击</b>，而非完成通知（完成通知只启用按钮）。
     *
     * @param elicitationId 待等待用户决策的 elicitation id
     * @return {@code "retry"} = 用户点 Retry now；{@code "cancel"} = 用户点 Cancel；{@code "timeout"} = 超时
     */
    private String awaitRetryDecision(String elicitationId) {
        // 决策先于等待器注册到达（retryConfirm/cancel 已缓存）→ 直接消费，消除竞态
        String early = earlyDecisions.remove(elicitationId);
        if (early != null) {
            return early;
        }
        CompletableFuture<String> waiter = new CompletableFuture<>();
        CompletableFuture<String> effective = decisionWaiters.computeIfAbsent(elicitationId, k -> waiter);
        // computeIfAbsent 后再查一次（竞态窗口：注册与消费之间早到决策）
        String early2 = earlyDecisions.remove(elicitationId);
        if (early2 != null) {
            effective.complete(early2);
        }
        try {
            return effective.get(decisionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[McpElicitationStateMachine] 等待用户 Retry now/Cancel 超时（{}ms）elicitationId={}",
                decisionTimeoutMs, elicitationId);
            return "timeout";
        } finally {
            decisionWaiters.remove(elicitationId, effective);
        }
    }

    /**
     * 带 URL elicitation 重试的工具调用 · 对齐 CC {@code callMCPToolWithUrlElicitationRetry}。
     *
     * <p>[impl-I-4 F6 rework] 两阶段：-32042 → 校验 elicitations → 逐项进 PENDING 队列 → Phase 1
     * {@code respond}（accept = 同意打开 URL，no-op）→ Phase 2 {@link #awaitRetryDecision}（用户点
     * Retry now → 重试；Cancel/超时 → decline 文本）。重试超 {@link #MAX_URL_ELICITATION_RETRIES} → 抛原错误。
     *
     * @param serverName MCP server 名
     * @param tool       工具名（decline 文本用）
     * @param fn         工具调用函数（触发 -32042 时抛异常）
     * @return 结果或 decline 文本
     */
    public ElicitationOutcome callWithElicitationRetry(String serverName, String tool, ToolCallFn fn) {
        for (int attempt = 0; ; attempt++) {
            try {
                JsonNode result = fn.call().join();
                return new ElicitationOutcome(result, null);
            } catch (Exception e) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null
                    ? e.getCause() : e;
                Integer code = extractUrlElicitationCode(cause);
                if (code == null) {
                    throw rethrow(cause);
                }
                if (attempt >= MAX_URL_ELICITATION_RETRIES) {
                    log.warn("[McpElicitationStateMachine] {}.{} URL elicitation 重试超过 {} 次，抛原错误",
                        serverName, tool, MAX_URL_ELICITATION_RETRIES);
                    throw rethrow(cause);
                }
                List<PendingElicitation> elicitations = parseElicitations(cause, serverName);
                if (elicitations.isEmpty()) {
                    log.warn("[McpElicitationStateMachine] {}.{} 返回 -32042 但无有效 elicitations，抛原错误",
                        serverName, tool);
                    throw rethrow(cause);
                }
                for (PendingElicitation elicitation : elicitations) {
                    // [WF-B 对齐 CC client.ts:2924-2940] Elicitation hook 预解析：accept → 跳过
                    // UI 直接重试；decline/cancel → decline 文本返回。
                    if (elicitationHandler != null) {
                        ElicitationResponse hookDecision = elicitationHandler.handleRequest(
                            serverName, elicitation.message(), "url", elicitation.url(),
                            elicitation.elicitationId(), null);
                        if (hookDecision != null) {
                            if (!"accept".equals(hookDecision.action())) {
                                String decline = hookDeclineText(tool, hookDecision.action(), "by a hook");
                                log.info("[McpElicitationStateMachine] {}.{} elicitation {} → hook {}（decline 文本返回）",
                                    serverName, tool, elicitation.elicitationId(), hookDecision.action());
                                return new ElicitationOutcome(null, decline);
                            }
                            log.info("[McpElicitationStateMachine] {}.{} elicitation {} → hook accept（跳过 UI，直接重试）",
                                serverName, tool, elicitation.elicitationId());
                            continue;
                        }
                    }
                    // 两阶段：进入 PENDING 队列 → Phase 1 同意通道（responder）
                    pendingQueue.add(elicitation);
                    String action = respond(serverName, elicitation);
                    if (!"accept".equals(action)) {
                        String decline = "URL elicitation was "
                            + ("decline".equals(action) ? "declined" : action + "ed")
                            + ". The tool \"" + tool + "\" could not complete because it requires "
                            + "the user to open a URL.";
                        log.info("[McpElicitationStateMachine] {}.{} elicitation {} → {}（decline 文本返回）",
                            serverName, tool, elicitation.elicitationId(), action);
                        return new ElicitationOutcome(null, decline);
                    }
                    // [impl-I-4 F6 rework] accept 为 Phase 1 同意（对齐 CC client.ts:2980-2984
                    // respond 注释「accept is a no-op, doesn't resolve retry Promise」）——不自动重试。
                    // 完成通知只启用「Retry now」按钮；重试由用户点 Retry now（retryConfirm）驱动，
                    // Cancel（showCancel:true）或超时 → decline 文本（fail-closed 不悬挂）。
                    log.info("[McpElicitationStateMachine] {}.{} elicitation {} → accept（Phase 1 同意），等待用户 Retry now / Cancel",
                        serverName, tool, elicitation.elicitationId());
                    String decision = awaitRetryDecision(elicitation.elicitationId());
                    if ("cancel".equals(decision)) {
                        String decline = "URL elicitation was canceled by the user. The tool \""
                            + tool + "\" could not complete because it requires the user to open a URL.";
                        log.info("[McpElicitationStateMachine] {}.{} elicitation {} → 用户 Cancel（等待期 showCancel:true）",
                            serverName, tool, elicitation.elicitationId());
                        return new ElicitationOutcome(null, decline);
                    }
                    if ("timeout".equals(decision)) {
                        // fail-closed：用户不点 Retry now/Cancel（前端未实现/无操作）→ 出队 + decline 文本
                        pendingQueue.removeIf(p -> p.elicitationId().equals(elicitation.elicitationId()));
                        String decline = "URL elicitation timed out waiting for user confirmation. The tool \""
                            + tool + "\" could not complete because it requires the user to open a URL.";
                        log.warn("[McpElicitationStateMachine] {}.{} elicitation {} 等待用户决策超时，decline 文本返回",
                            serverName, tool, elicitation.elicitationId());
                        return new ElicitationOutcome(null, decline);
                    }
                    // "retry"（用户点 Retry now → CC userResult={action:'accept'}，client.ts:2986-2988）
                    // → ElicitationResult hook 可 override（对齐 CC client.ts:3000-3006：finalResult.action
                    // 非 accept → decline 文本）。
                    if (elicitationHandler != null) {
                        ElicitationResponse override = elicitationHandler.handleResponse(serverName, "accept");
                        if (override != null && !"accept".equals(override.action())) {
                            String decline = hookDeclineText(tool, override.action(), "by the user");
                            log.info("[McpElicitationStateMachine] {}.{} elicitation {} → ElicitationResult hook "
                                + "override {}（decline 文本返回）",
                                serverName, tool, elicitation.elicitationId(), override.action());
                            return new ElicitationOutcome(null, decline);
                        }
                    }
                    // "retry"（用户点 Retry now）→ 处理下一个 elicitation，全部同意后 loop 重试
                }
                // Loop back to retry the tool call
            }
        }
    }

    /**
     * [WF-B] 构建 hook 驱动的 decline 文本 · 对齐 CC client.ts:2934-2938/:3013-3015
     * （「by a hook」预解析 vs 「by the user」ElicitationResult 结果）。
     */
    private static String hookDeclineText(String tool, String action, String source) {
        String verb = "decline".equals(action) ? "declined" : action + "ed";
        return "URL elicitation was " + verb + " " + source + ". The tool \""
            + tool + "\" could not complete because it requires the user to open a URL.";
    }

    /** 响应：responder 未接线 → auto-decline（fail-closed）。 */
    private String respond(String serverName, PendingElicitation elicitation) {
        if (responder == null) {
            log.warn("[McpElicitationStateMachine] 无 elicitation 响应器（前端未接线），auto-decline server={} id={}",
                serverName, elicitation.elicitationId());
            return "decline";
        }
        return responder.respond(serverName, elicitation);
    }

    /** 原样重抛（unwrap CompletionException → 原始 cause，对齐 CC 抛原错误）。 */
    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(cause);
    }

    /** 从异常提取 -32042 code · 兼容 CompletionException 包装 + "JSON-RPC error: {...}" 消息。 */
    static Integer extractUrlElicitationCode(Throwable e) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        if (message == null) {
            return null;
        }
        // transport 层：IllegalStateException("JSON-RPC error: {code:-32042,...}")
        int prefix = message.indexOf("JSON-RPC error:");
        String jsonPart = prefix >= 0 ? message.substring(prefix + "JSON-RPC error:".length()).trim() : null;
        try {
            JsonNode node = jsonPart != null && !jsonPart.isEmpty() ? mapper.readTree(jsonPart) : null;
            if (node != null && node.has("code") && node.path("code").asInt() == URL_ELICITATION_REQUIRED) {
                return URL_ELICITATION_REQUIRED;
            }
        } catch (Exception ignored) {
            // 非 JSON → 尝试文本提取
        }
        // 兜底：文本含 "-32042"
        if (message.contains("-32042")) {
            return URL_ELICITATION_REQUIRED;
        }
        return null;
    }

    /** 从错误 data.elicitations 解析有效 elicitation · 对齐 CC :2876-2897 校验。 */
    static List<PendingElicitation> parseElicitations(Throwable e, String serverName) {
        List<PendingElicitation> result = new ArrayList<>();
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        if (message == null) {
            return result;
        }
        int prefix = message.indexOf("JSON-RPC error:");
        if (prefix < 0) {
            return result;
        }
        String jsonPart = message.substring(prefix + "JSON-RPC error:".length()).trim();
        try {
            JsonNode node = mapper.readTree(jsonPart);
            JsonNode elicitArr = node.path("data").path("elicitations");
            if (!elicitArr.isArray()) {
                return result;
            }
            for (JsonNode eItem : elicitArr) {
                // CC :2877-2897 mode==='url' && url && elicitationId && message
                if ("url".equals(eItem.path("mode").asText())
                    && eItem.has("url") && eItem.path("url").isTextual()
                    && eItem.has("elicitationId") && eItem.path("elicitationId").isTextual()
                    && eItem.has("message") && eItem.path("message").isTextual()) {
                    result.add(new PendingElicitation(serverName,
                        eItem.path("elicitationId").asText(),
                        eItem.path("url").asText(),
                        eItem.path("message").asText(),
                        false));
                }
            }
        } catch (Exception ignored) {
            // 解析失败 → 空（调用方抛原错误）
        }
        return result;
    }
}
