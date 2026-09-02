package com.nexusai.application.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * nexusai-in-chrome 浏览器扩展 WebSocket 通信桥 · {@link BrowserChannel} 的真实实现。
 *
 * <p>自研 Chrome 扩展经原生 WebSocket 端点 {@code /ws/browser}（见
 * {@link com.nexusai.infra.config.BrowserWebSocketConfig}）连接后端，握手 {@code hello}
 * 建立<b>全局单连接</b>后，所有会话的浏览器工具调用经 {@link #send(String, String, Map)}
 * 转发给扩展执行并等待结果。
 *
 * <h2>多会话并行模型（对齐 CCB tabs_context_mcp「每个对话创建自己的新 tab」）</h2>
 * <p><b>一个扩展连接服务所有会话</b>：扩展 popup 一次连接（{@code hello}），后端把该连接
 * 视为全局连接（{@link AtomicReference}），不再按 {@code sessionId} 路由连接。每次工具调用的
 * {@code sessionId} 由调用方（{@link BrowserMcpTool} 读 {@link com.nexusai.common.RequestContext}）
 * 传入，透传在 {@code tool_call} 消息里 —— 扩展按 {@code sessionId} 定位/创建对应的 tab 组
 * （对齐 CCB tabs_context_mcp 语义：每个会话自己的 tab 组，互不干扰）。
 *
 * <h2>消息协议（写进前端扩展对接契约 · 见类尾 Javadoc）</h2>
 * <pre>
 *   扩展 → 后端（连接握手，建连后第一条消息）:
 *     {"type":"hello"}
 *     （sessionId 不再必需 —— 全局连接；扩展可选携带 sessionId 仅作诊断日志）
 *
 *   后端 → 扩展（工具调用转发）:
 *     {"type":"tool_call","id":"&lt;uuid&gt;","sessionId":"&lt;会话ID&gt;","tool":"read_page","args":{...}}
 *     （sessionId 供扩展定位/创建该会话的 tab 组）
 *
 *   扩展 → 后端（工具成功结果）:
 *     {"type":"tool_result","id":"&lt;同 tool_call.id&gt;","result":{...}}
 *
 *   扩展 → 后端（工具失败）:
 *     {"type":"tool_error","id":"&lt;同 tool_call.id&gt;","error":"&lt;错误文案&gt;"}
 * </pre>
 *
 * <p><b>全局连接</b>：{@link #register} 把新连接 {@code getAndSet} 进 {@link AtomicReference}，
 * 旧连接被关闭并标记 {@code CLOSE_CODE_REPLACED}（扩展多 tab 重连 → 新连接覆盖旧连接）；
 * {@link #send} 直接读全局连接，不再按会话路由 —— 会话隔离由扩展侧 tab 组承担。
 *
 * <p><b>结果匹配仍按 callId</b>：{@code sessionId} 仅透传给扩展定位 tab 组，结果回传
 * （{@code tool_result}/{@code tool_error}）经 {@code callId} 匹配 {@code pending} future，
 * 与 {@code sessionId} 无关，回给发起调用的请求线程（并发安全：多会话并行时各 send 线程
 * 各自阻塞等待自己的 callId）。
 *
 * <p><b>超时</b>：{@link #send} 同步阻塞等待对应 {@code tool_result}/{@code tool_error}，
 * 默认 30s（{@link #DEFAULT_TIMEOUT_MS}），超时抛 {@link IOException} fail loud。
 *
 * <p><b>清理</b>：连接断开（{@code afterConnectionClosed} / 传输错误）经
 * {@link #unregisterByWsSession} 从全局引用移除；send 超时/失败从 {@code pending} 移除对应 future，
 * 迟到的结果经 {@link #resolve} 幂等忽略。
 *
 * <p><b>fail loud（规则十二）</b>：无连接 / 会话上下文缺失 / 超时 / 扩展返回 tool_error 均抛异常，
 * 由 {@link BrowserMcpTool#execute} catch 转 {@code ToolResult.error}，不让模型静默假成功。
 *
 * @see BrowserChannel
 * @see BrowserWebSocketHandler
 * @see com.nexusai.infra.config.BrowserWebSocketConfig
 */
@Component
public class BrowserWsChannel implements BrowserChannel {

    private static final Logger log = LoggerFactory.getLogger(BrowserWsChannel.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** send 默认超时 30 秒 · 可通过 {@link #BrowserWsChannel(long)} 注入（测试用短超时）。 */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** 扩展替换旧连接时的关闭码 · 4000-4999 为应用自定义码（RFC 6455 §7.4.2）。 */
    static final int CLOSE_CODE_REPLACED = 4000;

    /** 全局连接引用 · 一个扩展连接服务所有会话（新连接覆盖旧连接）。 */
    private final AtomicReference<WebSocketSession> connection = new AtomicReference<>();

    /**
     * 待响应映射 · {@code callId → CompletableFuture<JsonNode>}。
     *
     * <p>WHY {@link CompletableFuture}：send 线程阻塞等待；扩展响应线程（WS inbound）完成 future。
     * {@link ConcurrentHashMap} 保证两类线程并发读写安全；多会话并行时各 callId 独立匹配。
     */
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private final long timeoutMs;

    /** Spring 构造 · 默认超时 30s。 */
    public BrowserWsChannel() {
        this(DEFAULT_TIMEOUT_MS);
    }

    /**
     * 测试/自定义超时构造。
     *
     * @param timeoutMs send 阻塞等待上限（毫秒，必须 &gt; 0）
     */
    public BrowserWsChannel(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be > 0, got " + timeoutMs);
        }
        this.timeoutMs = timeoutMs;
    }

    // ════════════════════════════════════════════════════════════════════════
    // BrowserChannel 实现
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 转发一次浏览器工具调用给全局 Chrome 扩展（经 WebSocket），并同步等待结果。
     *
     * <p><b>流程</b>：
     * <ol>
     *   <li>校验 {@code sessionId} 非空（扩展按它定位/创建该会话的 tab 组）→ 空则 fail loud；</li>
     *   <li>查全局连接引用 → 无扩展连接则 fail loud「浏览器扩展未连接」；</li>
     *   <li>分配 {@code callId}（UUID）→ 注册 {@code pending} future；</li>
     *   <li>发 {@code {"type":"tool_call","id":...,"sessionId":...,"tool":...,"args":{...}}} 给扩展；</li>
     *   <li>阻塞等对应 {@code tool_result}/{@code tool_error}（带超时）；</li>
     *   <li>{@code tool_result} → 返回 result 文本；{@code tool_error} → 抛异常。</li>
     * </ol>
     *
     * <p><b>sessionId 语义</b>：仅透传给扩展定位 tab 组（对齐 CCB tabs_context_mcp「每个会话
     * 自己的 tab 组」）；结果回传经 callId 匹配（{@link #resolve}），与 sessionId 无关。
     *
     * @param sessionId 当前会话 ID（调用方 {@link BrowserMcpTool} 读 {@code RequestContext.sessionId()}；
     *                  扩展按它定位/创建该会话的 tab 组）
     * @param tool      工具原名（无 {@code mcp__nexusai-in-chrome__} 前缀，如 {@code "read_page"}）
     * @param args      工具入参（CCB inputSchema 语义的扁平 Map）
     * @return 扩展执行结果文本（result 为字符串原样返回；为对象则紧凑 JSON）
     * @throws Exception 会话上下文缺失 / 无连接 / 发送失败 / 超时 / 扩展返回 tool_error（调用方
     *                   {@link BrowserMcpTool#execute} catch 转 {@code ToolResult.error}）
     */
    @Override
    public String send(String sessionId, String tool, Map<String, Object> args) throws Exception {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException(
                "无法确定当前会话（sessionId 为空）——浏览器工具调用需在会话上下文中执行，扩展按 sessionId 定位 tab 组");
        }
        WebSocketSession ws = connection.get();
        if (ws == null || !ws.isOpen()) {
            if (log.isWarnEnabled()) {
                log.warn("BrowserWsChannel: sessionId={} 无已连接扩展 → fail loud「{}」",
                    sessionId, BrowserMcpTool.EXTENSION_NOT_CONNECTED_MESSAGE);
            }
            throw new IllegalStateException(BrowserMcpTool.EXTENSION_NOT_CONNECTED_MESSAGE);
        }

        String callId = UUID.randomUUID().toString();
        ObjectNode payload = JSON.createObjectNode();
        payload.put("type", "tool_call");
        payload.put("id", callId);
        payload.put("sessionId", sessionId);
        payload.put("tool", tool);
        payload.set("args", JSON.valueToTree(args == null ? Map.of() : args));

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        CompletableFuture<JsonNode> existing = pending.put(callId, future);
        if (existing != null) {
            // 理论不可达（UUID 碰撞概率可忽略），防御性处理
            existing.completeExceptionally(new IOException("callId 重复: " + callId));
        }

        try {
            ws.sendMessage(new TextMessage(payload.toString()));
            if (log.isDebugEnabled()) {
                log.debug("BrowserWsChannel: 已转发 tool_call sessionId={} tool={} callId={}",
                    sessionId, tool, callId);
            }
        } catch (IOException e) {
            pending.remove(callId);
            unregisterByWsSession(ws);
            throw new IOException("浏览器扩展消息发送失败: " + e.getMessage(), e);
        }

        JsonNode response;
        try {
            response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(callId);
            if (log.isWarnEnabled()) {
                log.warn("BrowserWsChannel: sessionId={} tool={} callId={} 扩展响应超时（{}ms）→ fail loud",
                    sessionId, tool, callId, timeoutMs);
            }
            throw new IOException("浏览器扩展响应超时（" + timeoutMs + "ms）tool=" + tool);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(callId);
            throw new IOException("浏览器扩展响应等待被中断", e);
        } catch (ExecutionException e) {
            pending.remove(callId);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException("浏览器扩展执行异常: " + cause.getMessage(), cause);
        }

        // response 为扩展回包（tool_result 或 tool_error 全量消息）
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String errText = error.isTextual() ? error.asText() : error.toString();
            if (log.isWarnEnabled()) {
                log.warn("BrowserWsChannel: sessionId={} tool={} callId={} 扩展返回 tool_error → fail loud: {}",
                    sessionId, tool, callId, errText);
            }
            throw new IOException("浏览器扩展工具执行错误: " + errText);
        }
        JsonNode result = response.path("result");
        if (result.isMissingNode() || result.isNull()) {
            return "";
        }
        if (result.isTextual()) {
            return result.asText();
        }
        return JSON.writeValueAsString(result);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 全局连接注册 / 结果路由（BrowserWebSocketHandler 调用）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 注册全局扩展连接 · 握手 {@code hello} 消息触发（{@link BrowserWebSocketHandler#handleTextMessage}）。
     *
     * <p><b>1 全局连接</b>：新连接覆盖旧连接（扩展多 tab 重连 → 旧连接被关闭并标记
     * {@code CLOSE_CODE_REPLACED}），避免工具调用发往陈旧连接。不再要求 {@code sessionId}
     * —— 一个扩展连接服务所有会话，会话隔离由扩展侧 tab 组承担。
     *
     * @param session 原生 WebSocket 会话
     */
    public void register(WebSocketSession session) {
        if (session == null) {
            log.warn("BrowserWsChannel.register: session 为 null，忽略");
            return;
        }
        WebSocketSession old = connection.getAndSet(session);
        if (old != null && old != session && old.isOpen()) {
            try {
                old.close(new CloseStatus(CLOSE_CODE_REPLACED, "replaced by newer connection"));
                if (log.isInfoEnabled()) {
                    log.info("BrowserWsChannel: 旧扩展连接被替换并关闭 wsSessionId={}", old.getId());
                }
            } catch (IOException e) {
                log.warn("BrowserWsChannel: 关闭旧扩展连接失败: {}", e.getMessage());
            }
        }
        if (log.isInfoEnabled()) {
            log.info("BrowserWsChannel: 全局扩展已连接 wsSessionId={}", session.getId());
        }
    }

    /**
     * 按连接注销 · 连接断开/传输错误触发（{@link BrowserWebSocketHandler#afterConnectionClosed}）。
     *
     * <p><b>身份感知</b>：只清空与传入连接<b>同一实例</b>的全局引用
     * （{@code AtomicReference.compareAndSet} 原子身份匹配）—— 防止「旧连接断开却误删新连接」的竞态。
     *
     * @param session 断开的 WebSocket 会话
     */
    public void unregisterByWsSession(WebSocketSession session) {
        if (session == null) {
            return;
        }
        if (connection.compareAndSet(session, null)) {
            if (log.isInfoEnabled()) {
                log.info("BrowserWsChannel: 全局扩展连接已注销 wsSessionId={}", session.getId());
            }
        }
    }

    /**
     * 路由扩展响应 · 收到 {@code tool_result}/{@code tool_error} 时完成对应 {@code pending} future
     * （{@link BrowserWebSocketHandler#handleTextMessage} 调用）。
     *
     * <p><b>幂等</b>：未知 / 已超时移除的 {@code callId} → log warn 后忽略（迟到响应不抛，不影响
     * 已返回的 send 调用方）。<b>与 sessionId 无关</b>：callId 全局唯一，多会话并行时各 send
     * 线程只收到自己 callId 的结果。
     *
     * @param callId   tool_call 的 id（UUID）
     * @param response 扩展回包全量消息（{@code {"type":"tool_result"/"tool_error", ...}}）
     */
    public void resolve(String callId, JsonNode response) {
        if (callId == null || callId.isBlank()) {
            log.warn("BrowserWsChannel.resolve: callId 为空，忽略");
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(callId);
        if (future == null) {
            if (log.isWarnEnabled()) {
                log.warn("BrowserWsChannel.resolve: 收到未知 callId 响应（可能已超时/已消费）callId={}",
                    callId);
            }
            return;
        }
        future.complete(response);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 测试辅助（包内可见）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 全局是否有已连接的 Chrome 扩展 · /chrome 命令 + nexusai-in-chrome skill 门控共用。
     *
     * <p><b>全局语义</b>：扩展 popup 一次连接服务所有会话 —— 有连接即 true，与当前会话无关
     * （不再按 {@link com.nexusai.common.RequestContext#sessionId()} 查连接注册表）。
     *
     * @return true = 存在 open 的全局扩展连接
     */
    public boolean hasSessionConnection() {
        return isSessionConnected(null);
    }

    /**
     * 全局是否有已连接的 Chrome 扩展 · 保留 {@code sessionId} 形参仅为签名兼容。
     *
     * <p><b>全局语义（browser-mcp-align 多会话并行）</b>：一个扩展连接服务所有会话，
     * {@code sessionId} 不再参与路由判定（历史版本按会话查连接注册表，对齐 CC chrome.tsx:56
     * {@code isConnected = chromeClient?.type === "connected"} 的会话级连接判定 —— 现已升级为
     * 全局连接模型，会话隔离由扩展侧 tab 组承担）。形参保留避免破坏既有调用方，取值被忽略。
     *
     * @param sessionId 忽略（全局连接，与具体会话无关；保留形参仅为兼容）
     * @return true = 存在 open 的全局扩展连接
     */
    public boolean isSessionConnected(String sessionId) {
        WebSocketSession ws = connection.get();
        return ws != null && ws.isOpen();
    }

    /** 全局已连接扩展连接数（测试断言用）· 0 或 1（单连接模型）。 */
    int connectedCount() {
        return connection.get() != null ? 1 : 0;
    }

    /** 当前挂起的 tool_call 数（测试断言用）。 */
    int pendingCount() {
        return pending.size();
    }
}
