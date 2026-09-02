package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * In-process linked MCP transport · 对齐 CC services/mcp/InProcessTransport.ts.
 *
 * <p>L1 语义: 同进程 linked pair (a, b), a.sendRequest 异步投递到 b 的 requestHandler
 *             (CC queueMicrotask 语义), handler 返回值自动作为 JSON-RPC response.result
 *             回投到 a. 一侧 close() 关闭两侧 (CC onclose 双向触发).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: sendRequest 输出 JSON-RPC 帧格式 {jsonrpc,id,method,params} 与 stdio 一致</li>
 *   <li><b>A2 Golden Trace</b>: sendRequest → peer handler → response → future 完成</li>
 *   <li><b>A3</b>: NOT_CONNECTED → CONNECTED → CLOSED; close 同时关闭 peer</li>
 *   <li><b>A4</b>: 顺序约束 (沿用 McpToolPool: assemble → list → call)</li>
 *   <li><b>A5</b>: handler 返回值映射到 response.result, 异常映射到 response.error</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CompletableFuture.runAsync 替代 queueMicrotask;
 *                    BiFunction handler 替代回调链; LinkedHashMap 保 JSON 字段顺序.
 */
public class InProcessMcpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(InProcessMcpTransport.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<McpTransport.State> state =
        new AtomicReference<>(McpTransport.State.NOT_CONNECTED);
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private InProcessMcpTransport peer;
    private BiFunction<String, JsonNode, Object> requestHandler;
    private Consumer<String> frameInspector; // 测试/A1 校验使用

    /**
     * P2-15: server→client 通知处理器注册表 · 对齐 CC {@code client.setNotificationHandler}
     * （useManageMCPConnections.ts:619/:669/:707）。key = JSON-RPC 通知 method，value = 处理器列表。
     */
    private final Map<String, List<McpNotificationHandler>> notificationHandlers = new ConcurrentHashMap<>();

    @Override
    public void start(TransportConfig config) {
        // L1: in-process 无 subprocess 启动开销, start 即 CONNECTED
        state.set(McpTransport.State.CONNECTED);
    }

    @Override
    public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
        if (state.get() != McpTransport.State.CONNECTED) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("transport not connected"));
        }
        long id = nextId.getAndIncrement();
        var fut = new CompletableFuture<JsonNode>();
        pending.put(id, fut);
        try {
            // A1: 帧格式与 stdio transport 一致 (LinkedHashMap 保字段顺序)
            var rpc = new LinkedHashMap<String, Object>();
            rpc.put("jsonrpc", "2.0");
            rpc.put("id", id);
            rpc.put("method", method);
            rpc.put("params", params == null ? Map.of() : params);
            String frame = mapper.writeValueAsString(rpc);
            log.debug("[InProcessMcpTransport] sendRequest id={} method={}", id, method);
            deliverRequest(frame, id);
        } catch (Exception e) {
            pending.remove(id);
            fut.completeExceptionally(e);
        }
        return fut;
    }

    @Override
    public void sendNotification(String method, Object params) {
        if (state.get() != McpTransport.State.CONNECTED) {
            throw new IllegalStateException("transport not connected");
        }
        var rpc = new LinkedHashMap<String, Object>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("method", method);
        rpc.put("params", params == null ? Map.of() : params);
        try {
            String frame = mapper.writeValueAsString(rpc);
            log.debug("[InProcessMcpTransport] sendNotification method={}", method);
            deliverNotification(frame);
        } catch (Exception e) {
            throw new IllegalStateException("sendNotification failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (state.get() == McpTransport.State.CLOSED) return;
        state.set(McpTransport.State.CLOSED);
        failPending(this);
        // A3: 关闭 peer (CC close 双侧触发 onclose)
        if (peer != null && peer.state.get() != McpTransport.State.CLOSED) {
            peer.state.set(McpTransport.State.CLOSED);
            failPending(peer);
        }
        log.info("[InProcessMcpTransport] closed (peer also closed)");
    }

    @Override
    public McpTransport.State getState() {
        return state.get();
    }

    /** 安装 server 侧请求处理器: handler(method, params) → result 对象 (Map/List/基本类型等). */
    public void setRequestHandler(BiFunction<String, JsonNode, Object> handler) {
        this.requestHandler = handler;
    }

    /** 测试/A1 用: 在 frame 投递到 handler 前调用 inspector 抓取原始帧. */
    public void setFrameInspector(Consumer<String> inspector) {
        this.frameInspector = inspector;
    }

    @Override
    public void setNotificationHandler(String method, McpNotificationHandler handler) {
        notificationHandlers.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(handler);
        if (log.isDebugEnabled()) {
            log.debug("[InProcessMcpTransport] 注册通知处理器 method={}", method);
        }
    }

    /**
     * 创建 linked pair [a, b]. a.sendRequest → b.requestHandler → 自动回投到 a.
     *
     * <p>对应 CC createLinkedTransportPair().
     */
    public static InProcessMcpTransport[] createLinkedPair() {
        var a = new InProcessMcpTransport();
        var b = new InProcessMcpTransport();
        a.peer = b;
        b.peer = a;
        return new InProcessMcpTransport[]{a, b};
    }

    // ────────────────────── 内部 ──────────────────────

    private void deliverRequest(String frame, long requestId) {
        // L1: queueMicrotask → CompletableFuture.runAsync 异步投递, 避免栈深度问题
        CompletableFuture.runAsync(() -> {
            if (peer == null || peer.state.get() != McpTransport.State.CONNECTED) {
                CompletableFuture<JsonNode> fut = pending.remove(requestId);
                if (fut != null) {
                    fut.completeExceptionally(new IllegalStateException("peer closed before response"));
                }
                return;
            }
            // A1 校验钩子
            if (peer.frameInspector != null) {
                peer.frameInspector.accept(frame);
            }
            try {
                JsonNode node = mapper.readTree(frame);
                if (peer.requestHandler == null) {
                    log.warn("[InProcessMcpTransport] peer has no requestHandler, dropping id={}", requestId);
                    return;
                }
                String method = node.path("method").asText();
                JsonNode params = node.path("params");
                Object result = peer.requestHandler.apply(method, params);
                // A5: handler 返回值即 response.result
                sendResponseBack(requestId, result);
            } catch (Exception e) {
                // A5 错误路径: handler 抛错 → response.error
                sendErrorBack(requestId, e);
            }
        });
    }

    private void deliverNotification(String frame) {
        CompletableFuture.runAsync(() -> {
            if (peer == null || peer.state.get() != McpTransport.State.CONNECTED) return;
            try {
                JsonNode node = mapper.readTree(frame);
                String method = node.path("method").asText();
                if (method.isEmpty()) return;
                if (log.isDebugEnabled()) {
                    log.debug("[InProcessMcpTransport] 通知投递到 peer method={}", method);
                }
                // P2-15: 按 method 投递到 peer 注册的通知处理器（对齐 CC setNotificationHandler
                // useManageMCPConnections.ts:619-751 双向通知语义）。此处同步分发——
                // 本方法已运行于 runAsync 池线程，处理器内 sendRequest 经 peer.deliverRequest
                // 走独立 runAsync，不会阻塞本线程。
                peer.dispatchNotification(method, node.path("params"));
            } catch (Exception e) {
                log.warn("[InProcessMcpTransport] 通知解析失败: {}", e.getMessage());
            }
        });
    }

    /**
     * P2-15: 按 method 分发 server→client 通知到本侧已注册处理器 · 对齐 CC
     * {@code client.setNotificationHandler}（useManageMCPConnections.ts:619-751）。
     *
     * <p>未注册该 method 时静默忽略。InProcess 下本方法从 runAsync 池线程调用
     * （deliverNotification），故同步执行处理器（处理器内 sendRequest 不依赖本线程读响应）。
     *
     * @param method JSON-RPC 通知 method
     * @param params 通知 params（list_changed 恒为 {}）
     */
    private void dispatchNotification(String method, JsonNode params) {
        List<McpNotificationHandler> handlers = notificationHandlers.get(method);
        if (handlers == null || handlers.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[InProcessMcpTransport] 收到通知但无处理器 method={}", method);
            }
            return;
        }
        for (McpNotificationHandler handler : handlers) {
            try {
                handler.handle(params);
            } catch (Exception e) {
                log.warn("[InProcessMcpTransport] 通知处理器执行失败 method={}: {}", method, e.getMessage());
            }
        }
    }

    private void sendResponseBack(long requestId, Object result) {
        try {
            var response = new LinkedHashMap<String, Object>();
            response.put("jsonrpc", "2.0");
            response.put("id", requestId);
            response.put("result", result == null ? Map.of() : result);
            handleIncomingFrame(mapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("[InProcessMcpTransport] failed to send response back: {}", e.getMessage());
        }
    }

    private void sendErrorBack(long requestId, Exception handlerError) {
        try {
            var errorResponse = new LinkedHashMap<String, Object>();
            errorResponse.put("jsonrpc", "2.0");
            errorResponse.put("id", requestId);
            errorResponse.put("error", Map.of(
                "code", -32603,
                "message", handlerError.getMessage() == null ? "internal error" : handlerError.getMessage()
            ));
            handleIncomingFrame(mapper.writeValueAsString(errorResponse));
        } catch (Exception e) {
            log.warn("[InProcessMcpTransport] failed to send error response: {}", e.getMessage());
        }
    }

    private void handleIncomingFrame(String frame) {
        try {
            JsonNode node = mapper.readTree(frame);
            if (node.has("id") && !node.get("id").isNull()) {
                long id = node.get("id").asLong();
                CompletableFuture<JsonNode> fut = pending.remove(id);
                if (fut != null) {
                    if (node.has("error")) {
                        fut.completeExceptionally(new IllegalStateException(
                            "JSON-RPC error: " + node.get("error")));
                    } else {
                        fut.complete(node.get("result"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[InProcessMcpTransport] failed to handle incoming frame: {}", e.getMessage());
        }
    }

    private static void failPending(InProcessMcpTransport t) {
        for (var entry : t.pending.entrySet()) {
            entry.getValue().completeExceptionally(
                new IllegalStateException("transport closed"));
        }
        t.pending.clear();
    }
}