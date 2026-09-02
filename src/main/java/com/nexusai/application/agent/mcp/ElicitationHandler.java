package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.ElicitationResponse;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.eventbus.ws.ElicitationFormRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ElicitationHandler · 对齐 CC services/mcp/elicitationHandler.ts.
 *
 * <p>L1 语义: 处理 MCP 服务的 elicitation/create 请求 - 用户在 MCP UI 中确认/取消.
 * Java 端对齐方式: 不实现 MCP 协议栈 (那是 SDK 责任), 只 emit HookEvent 触发 27-事件 Hook 系统.
 *
 * <p>HOOK-WIRE: Elicitation + ElicitationResult — 此前只有 HookEvent 工厂, 0 emitter 调用.
 * 现在 handleRequest() / handleResponse() 真实触发.
 *
 * <p><b>[2026-08-12 探查 △-02] 决策消费闭环</b>（对齐 CC runElicitationHooks /
 * runElicitationResultHooks, elicitationHandler.ts:214-303）:
 * <ul>
 *   <li>CC runElicitationHooks 消费 executeElicitationHooks 返回的
 *       {@code {elicitationResponse, blockingError}}: blockingError → 返回
 *       {@code {action:'decline'}}; elicitationResponse → 返回
 *       {@code {action: elicitationResponse.action, content: elicitationResponse.content}};
 *       否则 undefined (elicitationHandler.ts:227-250)。</li>
 *   <li>CC runElicitationResultHooks 同理消费 elicitationResultResponse:
 *       blockingError → {@code {action:'decline'}}; 否则 hook 响应 override
 *       (action/content ?? 原 content), elicitationHandler.ts:272-295。</li>
 *   <li>旧实现 fire-and-forget（executeEvent 返回值丢弃）→ 配置 Elicitation hook 的
 *       accept/decline/cancel 决策不生效。现消费 {@link GenericHook.HookResult} 顶层
 *       {@code elicitationResponse} / {@code elicitationResultResponse} /
 *       {@code blockingError} 字段（HookOutputParser 解析回填, 探查 △-01 补齐）。
 *       blockingError 判定优先级最高（CC :227-233/:272-283 先查 block 再查 response）。</li>
 * </ul>
 *
 * <p><b>[2026-08-12 WF-B 传输层接线]</b>（对齐 CC elicitationHandler.ts + client.ts:2813-3027）:
 * <ul>
 *   <li><b>handleRequest 返回值被消费</b>：StdioMcpTransport 对 server→client
 *       {@code elicitation/create} <b>请求</b>（带 requestId）调用 handleRequest 并把决策
 *       作为 JSON-RPC result 回传（此前当作 notification 丢弃，△-6/△-7）。无决策（无 hook /
 *       fail-closed）→ 传输层回 decline。</li>
 *   <li><b>handleResponse 生产接线</b>：McpElicitationStateMachine URL elicitation 决策路径
 *       （-32042 → 用户 Retry now/Cancel）调用 handleResponse 消费 ElicitationResult hook
 *       override（✗-1）。</li>
 *   <li><b>elicitation_response 通知</b>：handleResponse 每次响应后发
 *       Notification hook（notification_type=elicitation_response），对齐 CC
 *       runElicitationResultHooks elicitationHandler.ts:283-301/307-310（✗-2）。</li>
 * </ul>
 */
@org.springframework.stereotype.Component
public class ElicitationHandler {

    private static final Logger log = LoggerFactory.getLogger(ElicitationHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** form 模式弹窗 STOMP topic · 对齐 CC setAppState elicitation.queue 入队（elicitationHandler.ts:127-150）。 */
    public static final String ELICITATION_TOPIC = "/topic/mcp/elicitation-requests";

    private final HookRegistry hookRegistry;

    /**
     * form 模式挂起中的 elicitation · requestId（serverName:jsonrpcId）→ 挂起 future。
     * 对齐 CC elicitationHandler.ts:114-153 的 Promise 挂起（入队 AppState → 用户响应 → resolve）。
     */
    private final Map<String, PendingFormElicitation> pendingForms = new ConcurrentHashMap<>();

    /**
     * [IMP-SS-01] form 模式弹窗通道 · 对齐 CC setAppState（AppState.elicitation.queue push）。
     * null（测试/前端未接线）→ form 模式无用户响应链，fail-closed decline（对齐既有传输层约定）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SimpMessagingTemplate ws;

    /**
     * [IMP-SS-01] form 模式等待用户响应超时 · fail-closed 兜底（前端不响应不悬挂）。
     * 对齐 McpElicitationStateMachine decisionTimeoutMs（60s）约定；测试可注入短超时。
     */
    private long formDecisionTimeoutMs = 60_000L;

    public ElicitationHandler(@org.springframework.beans.factory.annotation.Autowired(required = false) HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    /** [IMP-SS-01] 注入 form 模式弹窗通道（测试/装配用；null → fail-closed）。 */
    public void setWebSocket(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    /** [IMP-SS-01] 注入 form 模式等待超时（ms）· 测试用短超时验证 fail-closed；生产默认 60s。 */
    public void setFormDecisionTimeoutMs(long formDecisionTimeoutMs) {
        this.formDecisionTimeoutMs = formDecisionTimeoutMs > 0 ? formDecisionTimeoutMs : 60_000L;
    }

    /** 挂起中 form elicitation · CC queue event 等价（serverName + respond 回调）。 */
    record PendingFormElicitation(String serverName, CompletableFuture<ElicitationResponse> future) {}

    /**
     * MCP 服务发起 elicitation 请求时调用 · 返回 hook 决策 (对齐 CC runElicitationHooks
     * elicitationHandler.ts:227-250). 缺省 mode/url/elicitationId（form 模式简写，StdioMcpTransport
     * elicitation/create 请求路径使用）.
     *
     * @param mcpServerName MCP 服务器名 (匹配 query)
     * @param message       elicitation 消息
     * @return hook 决策响应; blockingError → {@code {action:'decline'}}; hook 无决策 → null
     */
    public ElicitationResponse handleRequest(String mcpServerName, String message) {
        return handleRequest(mcpServerName, message, null, null, null, null);
    }

    /**
     * MCP 服务发起 elicitation 请求时调用 · 返回 hook 决策 (对齐 CC runElicitationHooks
     * elicitationHandler.ts:227-250). 完整上下文重载（URL 模式 URL elicitation 预解析使用，
     * 对齐 CC runElicitationHooks 传全量 params: elicitationHandler.ts:227-239）.
     *
     * @param mcpServerName   MCP 服务器名 (匹配 query)
     * @param message         elicitation 消息
     * @param mode            elicitation 模式 ('form' | 'url')，可 null
     * @param url             URL 模式链接，可 null
     * @param elicitationId   URL 模式 elicitation id，可 null
     * @param requestedSchema form 模式请求 schema，可 null
     * @return hook 决策响应; blockingError → {@code {action:'decline'}}; hook 无决策 → null
     */
    public ElicitationResponse handleRequest(String mcpServerName, String message, String mode,
                                              String url, String elicitationId,
                                              Map<String, Object> requestedSchema) {
        if (hookRegistry == null) {
            return null;
        }
        try {
            HookEvent event = HookEvent.elicitation(mcpServerName, message, null,
                mode, url, elicitationId, requestedSchema);
            GenericHook.HookResult result = hookRegistry.executeEvent(event);
            log.debug("HOOK Elicitation emitted: server={} msg={}", mcpServerName,
                message != null ? message.substring(0, Math.min(50, message.length())) : "");
            return resolveDecision(result, true);
        } catch (Exception e) {
            log.warn("HOOK Elicitation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 用户对 elicitation 响应 (accept/decline) 时调用 · 返回 hook override 决策
     * (对齐 CC runElicitationResultHooks elicitationHandler.ts:272-295).
     *
     * <p>[WF-B] 每次响应后发 {@code elicitation_response} Notification hook（observability，
     * CC elicitationHandler.ts:283-301/307-310）：blockingError → decline；hook override →
     * override action；无 override → 用户原始 action；异常 → 用户原始 action。通知消息
     * 恒用 <b>最终 action</b>（finalResult.action，对齐 CC）。
     *
     * @param mcpServerName MCP 服务器名 (匹配 query)
     * @param action        用户原始 action ('accept'|'decline'|'cancel')
     * @return hook override 决策; blockingError → {@code {action:'decline'}}; hook 无决策 → null
     */
    public ElicitationResponse handleResponse(String mcpServerName, String action) {
        if (hookRegistry == null) {
            return null;
        }
        String finalAction = action;
        try {
            HookEvent event = HookEvent.elicitationResult(mcpServerName, action, null);
            GenericHook.HookResult result = hookRegistry.executeEvent(event);
            ElicitationResponse decision = resolveDecision(result, false);
            finalAction = decision != null ? decision.action() : action;
            log.debug("HOOK ElicitationResult emitted: server={} action={}", mcpServerName, action);
            return decision;
        } catch (Exception e) {
            log.warn("HOOK ElicitationResult failed: {}", e.getMessage());
            return null;
        } finally {
            // CC runElicitationResultHooks :283-301/307-310 — 每次响应后发 elicitation_response 通知
            fireNotification("elicitation_response",
                "Elicitation response for server \"" + mcpServerName + "\": " + finalAction);
        }
    }

    /**
     * [IMP-SS-01] form 模式用户响应链挂起入口 · 对齐 CC elicitationHandler.ts:77-171
     * （无 hook 决策时 form 模式入队 AppState 由用户填表单响应，elicitationHandler.ts:114-153）。
     *
     * <p>流程：
     * <ol>
     *   <li>先跑 Elicitation hook（对齐 CC runElicitationHooks :227-239）——hook 决策 → 返回已完成的
     *       future（传输层同步回传，对齐 CC :245-250 有 elicitationResponse 直接返回）</li>
     *   <li>无 hook 决策 → form 模式挂起：注册 {@link #pendingForms} + 经 {@link #ELICITATION_TOPIC}
     *       推 {@link ElicitationFormRequestEvent} 弹窗 → 返回未完成 future（对齐 CC :114-153
     *       {@code setAppState(queue.push)} + {@code await response}）</li>
     *   <li>用户响应（{@link #resolveFormResponse}）→ future 完成 → 传输层写 JSON-RPC result；
     *       超时（{@link #formDecisionTimeoutMs}）→ fail-closed decline；abort（
     *       {@link #abortFormElicitation} / {@link #abortAllPendingForServer}）→ cancel</li>
     * </ol>
     *
     * @param serverName      MCP 服务器名（匹配 query + 弹窗路由）
     * @param message         elicitation 消息
     * @param mode            elicitation 模式（'form' | 'url'，可 null）
     * @param url             URL 模式链接（可 null）
     * @param elicitationId   URL 模式 elicitation id（可 null）
     * @param requestedSchema form 模式请求 schema（可 null）
     * @param requestId       传输层 JSON-RPC 请求 id（跨 server 由 serverName 限定）
     * @return 恒完成的 future：hook 决策 → 已完成的决策；form 挂起 → 用户响应/超时/abort 后完成
     */
    public CompletableFuture<ElicitationResponse> beginFormElicitation(String serverName, String message,
                                                                        String mode, String url, String elicitationId,
                                                                        Map<String, Object> requestedSchema,
                                                                        String requestId) {
        // 1) 先跑 Elicitation hook（对齐 CC runElicitationHooks :227-239）
        ElicitationResponse hookDecision = handleRequest(serverName, message, mode, url, elicitationId, requestedSchema);
        if (hookDecision != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ElicitationHandler] form 挂起前 hook 已决策 server={} requestId={} action={}",
                    serverName, requestId, hookDecision.action());
            }
            return CompletableFuture.completedFuture(hookDecision);
        }
        // 2) 无 hook 决策 → form 模式挂起（对齐 CC :114-153）
        if (ws == null) {
            // 无 WebSocket 弹窗通道（前端未接线/测试）→ fail-closed decline（对齐既有传输层 fail-closed 约定）
            log.warn("[ElicitationHandler] form 模式无弹窗通道（ws 未注入），fail-closed decline server={} requestId={}",
                serverName, requestId);
            return CompletableFuture.completedFuture(new ElicitationResponse("decline", null));
        }
        String key = formKey(serverName, requestId);
        CompletableFuture<ElicitationResponse> future = new CompletableFuture<>();
        PendingFormElicitation prev = pendingForms.put(key, new PendingFormElicitation(serverName, future));
        if (prev != null) {
            // 同 key 重复挂起（requestId 冲突）→ 旧请求 resolve cancel（对齐 CC 每个 requestId 独立 Promise）
            prev.future().complete(new ElicitationResponse("cancel", null));
        }
        try {
            ws.convertAndSend(ELICITATION_TOPIC, ElicitationFormRequestEvent.of(
                requestId, serverName, message, mode, url, elicitationId, requestedSchemaJson(requestedSchema)));
            log.info("[ElicitationHandler] form 模式挂起，弹窗已推送 server={} requestId={}", serverName, requestId);
        } catch (Exception e) {
            // STOMP 推送失败 → 立即 fail-closed decline（不能让 MCP server 请求悬挂）
            pendingForms.remove(key);
            future.complete(new ElicitationResponse("decline", null));
            log.warn("[ElicitationHandler] form 弹窗推送失败 server={} requestId={}: {}", serverName, requestId, e.getMessage());
            return future;
        }
        // 3) 超时 fail-closed（前端不响应不悬挂）
        scheduleFormTimeout(key, future);
        return future;
    }

    /**
     * [IMP-SS-01] 用户对 form 弹窗的响应 · 对齐 CC elicitationHandler.ts:138-146
     * {@code respond(result)} 回调 resolve Promise。
     *
     * <p>响应后跑 ElicitationResult hook（对齐 CC :159-165 runElicitationResultHooks）：
     * hook override 决策优先；无 override → 用户原始 action+content（对齐 CC :290-295
     * {@code content: elicitationResultResponse.content ?? result.content}）。
     *
     * @param requestId  弹窗请求 id（= 传输层 JSON-RPC id）
     * @param serverName MCP 服务器名（跨 server 路由 key）
     * @param action     用户决策（'accept' | 'decline' | 'cancel'）
     * @param content    用户填写的表单载荷（可 null）
     * @return true = 已消费（挂起请求存在并已 resolve）；false = 未知/已超时/已 abort
     */
    public boolean resolveFormResponse(String requestId, String serverName, String action,
                                       Map<String, Object> content) {
        String key = formKey(serverName, requestId);
        PendingFormElicitation pending = pendingForms.remove(key);
        if (pending == null) {
            log.warn("[ElicitationHandler] form 响应未知/已超时 requestId={} server={}（忽略）", requestId, serverName);
            return false;
        }
        try {
            // 对齐 CC runElicitationResultHooks :264-313 — 用户响应后跑 ElicitationResult hook（可 override/reject）
            ElicitationResponse finalDecision = finalizeWithResultHook(serverName, action, content);
            pending.future().complete(finalDecision);
            log.info("[ElicitationHandler] form 响应已 resolve server={} requestId={} action={} finalAction={}",
                serverName, requestId, action, finalDecision.action());
        } catch (Exception e) {
            // [IMP-SS-01 返工 4-6] ElicitationResponse 构造/执行异常（如 content 含 null 值未被清洗兜底）→
            // 降级 fail-closed decline：pendingForms.remove(key) 已先执行，若此处不 complete，
            // scheduleFormTimeout 的 remove 亦为空 → future 永不 complete → MCP server 请求无限期悬挂。
            // 必须在此兜底 complete，保证「前端不响应不悬挂」不变量。
            log.warn("[ElicitationHandler] form 响应处理异常，降级 decline server={} requestId={} action={}: {}",
                serverName, requestId, action, e.getMessage());
            pending.future().complete(new ElicitationResponse("decline", null));
        }
        return true;
    }

    /**
     * [IMP-SS-01 返工] resolve/abort 后统一跑 ElicitationResult hook · 对齐 CC
     * runElicitationResultHooks（elicitationHandler.ts:264-313）——CC 在用户响应 Promise
     * resolve（含 abort→cancel）后仍执行 {@code runElicitationResultHooks}（:159-165），
     * result hook 可 override action 或经 blockingError reject 为 decline（:282-288）。
     * Java {@code handleResponse} 等价实现该语义；无 override → 原始 action+content（:290-295）。
     *
     * @param serverName MCP 服务器名
     * @param action     原始决策 action（'accept'|'decline'|'cancel'）
     * @param content    原始决策内容（可 null）
     * @return 最终决策（result hook override 优先，否则原始 action+清洗后 content）
     */
    private ElicitationResponse finalizeWithResultHook(String serverName, String action,
                                                       Map<String, Object> content) {
        ElicitationResponse override = handleResponse(serverName, action);
        // [IMP-SS-01 返工 4-6] 构造 ElicitationResponse 前清洗 content 剔除 null 值：
        // ElicitationResponse record 构造器对非 null content 执行 Map.copyOf(content)，
        // Map.copyOf 对含 null value 的 map 抛 NPE（javac 探针复现）——前端表单空字段
        // （{"field":null}）常见，必须剔除后再构造，否则 NPE 后 future 永不 complete。
        return override != null ? override : new ElicitationResponse(action, sanitizeContent(content));
    }

    /**
     * [IMP-SS-01] abort → cancel · 对齐 CC elicitationHandler.ts:115-117
     * {@code onAbort → resolve({action:'cancel'})}（+ :119-122 signal.aborted 早返）。
     * 传输层连接关闭 / 前端取消弹窗时调用。
     *
     * @param requestId  弹窗请求 id
     * @param serverName MCP 服务器名（跨 server 路由 key）
     * @return true = 已消费；false = 未知/已超时/已 resolve
     */
    public boolean abortFormElicitation(String requestId, String serverName) {
        String key = formKey(serverName, requestId);
        PendingFormElicitation pending = pendingForms.remove(key);
        if (pending == null) {
            return false;
        }
        // 对齐 CC :159-165 — abort→cancel 后 Promise resolve 仍跑 runElicitationResultHooks
        // （result hook 可 override/reject cancel，如阻断降级 decline）
        ElicitationResponse finalDecision = finalizeWithResultHook(serverName, "cancel", null);
        pending.future().complete(finalDecision);
        log.info("[ElicitationHandler] form elicitation abort→cancel server={} requestId={} finalAction={}",
            serverName, requestId, finalDecision.action());
        return true;
    }

    /**
     * [IMP-SS-01] 传输层关闭时 abort 该 server 全部挂起 form elicitation → cancel
     * （对齐 CC onAbort resolve({action:'cancel'})，client.ts:1869/:2958-2962 连接关闭语义）。
     *
     * @param serverName MCP 服务器名
     * @return 被 abort 的挂起请求数
     */
    public int abortAllPendingForServer(String serverName) {
        int n = 0;
        for (var it = pendingForms.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().serverName().equals(serverName)) {
                // 对齐 CC :159-165 — abort→cancel 后仍跑 runElicitationResultHooks
                // （result hook 可 override/reject cancel；传输层 close 时 hooks 同步执行）
                ElicitationResponse finalDecision = finalizeWithResultHook(e.getValue().serverName(), "cancel", null);
                e.getValue().future().complete(finalDecision);
                it.remove();
                n++;
            }
        }
        if (n > 0) {
            log.info("[ElicitationHandler] 传输层关闭 abort {} 个挂起 form elicitation server={}", n, serverName);
        }
        return n;
    }

    /** form 挂起 key · serverName + ":" + requestId（跨 server 隔离 JSON-RPC id 命名空间）。 */
    private static String formKey(String serverName, String requestId) {
        return serverName + ":" + requestId;
    }

    /**
     * [IMP-SS-01 返工 4-6] 清洗 content 剔除 null 值 · 对齐 CC content 类型
     * {@code z.record(z.string(), z.unknown())}（types/hooks.ts:138/143）：CC 允许 null 值
     * 透传，但 Java {@link ElicitationResponse} record 构造器对非 null content 执行
     * {@code Map.copyOf(content)}，{@code Map.copyOf} 对含 null value 的 map 抛 NPE（javac
     * 探针复现）——前端表单空字段（{"field":null}）是常见输入，构造前必须剔除 null 值，
     * 否则 {@link #resolveFormResponse} 中 {@code pendingForms.remove(key)} 已先执行、
     * future 永不 complete、超时兜底失效 → MCP server elicitation/create 请求无限期悬挂。
     *
     * <p>实现用 {@code LinkedHashMap} 逐项拷贝剔除 null value（含 null key 亦剔除，防御
     * {@code Map.copyOf} 的 null key NPE），保持插入序，空结果 → null（对齐 content 可 null）。
     *
     * @param content 用户表单载荷（可 null）
     * @return 剔除 null 值后的不可变内容 map；content 为 null 或全部为 null → null
     */
    private static Map<String, Object> sanitizeContent(Map<String, Object> content) {
        if (content == null) {
            return null;
        }
        Map<String, Object> cleaned = new java.util.LinkedHashMap<>();
        content.forEach((k, v) -> {
            if (k != null && v != null) {
                cleaned.put(k, v);
            }
        });
        return cleaned.isEmpty() ? null : java.util.Collections.unmodifiableMap(cleaned);
    }

    /** requestedSchema Map → JsonNode（弹窗事件载荷；null → null）。 */
    private static com.fasterxml.jackson.databind.JsonNode requestedSchemaJson(Map<String, Object> requestedSchema) {
        return requestedSchema != null ? MAPPER.valueToTree(requestedSchema) : null;
    }

    /** form 模式超时 fail-closed · 对齐 McpElicitationStateMachine 60s 约定。 */
    private void scheduleFormTimeout(String key, CompletableFuture<ElicitationResponse> future) {
        CompletableFuture.delayedExecutor(formDecisionTimeoutMs, TimeUnit.MILLISECONDS).execute(() -> {
            PendingFormElicitation pending = pendingForms.remove(key);
            if (pending != null) {
                pending.future().complete(new ElicitationResponse("decline", null));
                log.warn("[ElicitationHandler] form elicitation 等待用户响应超时（{}ms）fail-closed decline key={}",
                    formDecisionTimeoutMs, key);
            }
        });
    }

    /**
     * [WF-B △-11] 发 {@code elicitation_complete} 通知 · 对齐 CC elicitationHandler.ts:183-186
     * （server 完成通知处理时同时 executeNotificationHooks('elicitation_complete')）。
     * McpElicitationStateMachine.markElicitationCompleted 调用。
     *
     * @param serverName    MCP 服务器名（通知消息用）
     * @param elicitationId 完成通知携带的 elicitation id
     */
    public void fireElicitationComplete(String serverName, String elicitationId) {
        fireNotification("elicitation_complete",
            "MCP server \"" + serverName + "\" confirmed elicitation " + elicitationId + " complete");
    }

    /**
     * 发 Notification hook 事件 · 委托 {@link HookRegistry#executeNotificationHooks}（对齐 CC
     * executeNotificationHooks，hooks.ts:3570-3592；本类 [IMP-LC-02] 起统一走 HookRegistry 通用
     * 发射点，消除旧内联双轨）。hook_event_name=Notification，按 notification_type 匹配
     * （HookMatcherEngine NOTIFICATION case）。hookRegistry 未接线 → 静默跳过（无消费方）。
     *
     * @param notificationType CC original: notification_type（matcher 匹配 key）
     * @param message          CC original: message（通知正文）
     */
    private void fireNotification(String notificationType, String message) {
        if (hookRegistry == null) {
            return;
        }
        try {
            hookRegistry.executeNotificationHooks(message, null, notificationType);
            if (log.isDebugEnabled()) {
                log.debug("HOOK Elicitation notification emitted: type={}", notificationType);
            }
        } catch (Exception e) {
            log.warn("HOOK Elicitation notification failed: type={}: {}", notificationType, e.getMessage());
        }
    }

    /**
     * 从 executeEvent 聚合结果解析 hook 决策 · 对齐 CC runElicitationHooks /
     * runElicitationResultHooks 消费语义 (elicitationHandler.ts:227-250 / :272-295):
     * blockingError 优先 → decline; 否则 elicitationResponse / elicitationResultResponse
     * (action + content); 否则 null.
     *
     * @param result      executeEvent 聚合结果 (可 null)
     * @param isRequest   true=Elicitation 事件 (消费 elicitationResponse);
     *                    false=ElicitationResult 事件 (消费 elicitationResultResponse)
     * @return 决策响应; 无决策 → null
     */
    private ElicitationResponse resolveDecision(GenericHook.HookResult result, boolean isRequest) {
        if (result == null) {
            return null;
        }
        // CC :227-233 / :272-283 — blockingError 优先 (deny 语义)
        if (result.blockingError() != null) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK Elicitation{} 决策: blockingError → decline ({})",
                    isRequest ? "" : "Result",
                    result.blockingError().blockingError());
            }
            return new ElicitationResponse("decline", null);
        }
        ElicitationResponse response = isRequest
            ? result.elicitationResponse()
            : result.elicitationResultResponse();
        if (response != null && log.isDebugEnabled()) {
            log.debug("HOOK Elicitation{} hook 决策: action={}",
                isRequest ? "" : "Result", response.action());
        }
        return response;
    }
}
