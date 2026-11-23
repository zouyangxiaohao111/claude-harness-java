package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * ACP 传输层 · newline-delimited JSON-RPC 2.0 over stdio。
 *
 * <p>形状照 {@code mcp/StdioMcpTransport}（分帧 + 独立 reader + server→client 请求应答），
 * 但<b>不复用其实例、不共享其语义</b>：ACP 的方法集（{@code initialize}/{@code session/*}）
 * 与 MCP 完全不同。
 *
 * <p>三个硬要求：① 读写分离（{@code request_permission} 在独立线程处理，⛔ 不在读循环里
 * 同步等用户）；② 显式 UTF-8（⛔ 平台默认字符集）；③ 坏帧只跳过、EOF 才让在飞请求失败。
 *
 * <p>构造器直接吃一对流，单测可用管道流起内存假 server，不需要真进程。
 */
public final class AcpTransport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcpTransport.class);

    /** server→client 通知处理器（{@code session/update} 等）。 */
    @FunctionalInterface
    public interface NotificationHandler {
        void onNotification(String method, JsonNode params);
    }

    /** server→client 请求处理器（{@code session/request_permission} 等）；返回值即 result。 */
    @FunctionalInterface
    public interface RequestHandler {
        JsonNode onRequest(String method, JsonNode params) throws AcpRpcException;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ExecutorService notifyExec = Executors.newSingleThreadExecutor(r -> daemon(r, "acp-notify"));
    private final ExecutorService requestExec = Executors.newCachedThreadPool(r -> daemon(r, "acp-server-request"));

    private volatile Thread readerThread;
    private volatile boolean closed;
    private volatile NotificationHandler notificationHandler;
    private volatile RequestHandler requestHandler;
    private volatile Consumer<Throwable> failureListener;

    public AcpTransport(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** 从已启动子进程的管道构造。 */
    public static AcpTransport forProcess(AcpProcess.Started started) {
        return new AcpTransport(started.process().getInputStream(), started.process().getOutputStream());
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void setNotificationHandler(NotificationHandler handler) {
        this.notificationHandler = handler;
    }

    public void setRequestHandler(RequestHandler handler) {
        this.requestHandler = handler;
    }

    /** 连接意外断开（EOF / 读异常）时回调，供 runner 提前收尾。 */
    public void setFailureListener(Consumer<Throwable> listener) {
        this.failureListener = listener;
    }

    /** 启动 reader 线程（幂等）。 */
    public AcpTransport start() {
        if (readerThread != null) {
            return this;
        }
        readerThread = daemon(this::readLoop, "acp-reader");
        readerThread.start();
        return this;
    }

    /**
     * 发一个请求并等待响应。
     *
     * @param timeoutMs 等待上限（必填 · ⛔ 不许无限等）
     * @return result 节点（可能为 null）
     */
    public JsonNode request(String method, Object params, long timeoutMs) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ObjectNode frame = JSON.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.put("method", method);
        if (params != null) {
            frame.set("params", JSON.valueToTree(params));
        }
        try {
            writeFrame(frame);
        } catch (RuntimeException e) {
            pending.remove(id);
            throw e;
        }
        try {
            return future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new AcpException("ACP 请求超时（" + timeoutMs + "ms）: " + method);
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new AcpException("ACP 请求被中断: " + method);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new AcpException("ACP 请求失败: " + method + " — " + cause.getMessage(), cause);
        }
    }

    /** 发一个通知（无 id、不等待响应）。 */
    public void notify(String method, Object params) {
        ObjectNode frame = JSON.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("method", method);
        if (params != null) {
            frame.set("params", JSON.valueToTree(params));
        }
        writeFrame(frame);
    }

    /** 是否已关闭（EOF / 显式 close）。 */
    public boolean isClosed() {
        return closed;
    }

    private void writeFrame(JsonNode frame) {
        byte[] bytes;
        try {
            bytes = (JSON.writeValueAsString(frame) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AcpException("ACP 帧序列化失败: " + e.getMessage(), e);
        }
        synchronized (writeLock) {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                throw new AcpException("ACP 帧写入失败（进程可能已退出）: " + e.getMessage(), e);
            }
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode frame;
                try {
                    frame = JSON.readTree(line);
                } catch (Exception e) {
                    // 非 JSON 行（日志混进 stdout）：跳过，不杀连接
                    log.warn("[AcpTransport] 跳过坏帧（非 JSON）: {}", abbreviate(line, 200));
                    continue;
                }
                if (frame == null || !frame.isObject()) {
                    log.warn("[AcpTransport] 跳过坏帧（非对象）: {}", abbreviate(line, 200));
                    continue;
                }
                dispatch(frame);
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("[AcpTransport] stdout 读取异常: {}", e.getMessage());
            }
        } finally {
            onConnectionLost();
        }
    }

    private void dispatch(JsonNode frame) {
        JsonNode idNode = frame.get("id");
        JsonNode methodNode = frame.get("method");
        boolean hasMethod = methodNode != null && methodNode.isTextual();
        boolean hasId = idNode != null && !idNode.isNull() && idNode.isIntegralNumber();

        if (hasMethod) {
            String method = methodNode.asText();
            JsonNode params = frame.get("params");
            if (hasId) {
                long id = idNode.asLong();
                requestExec.submit(() -> handleServerRequest(id, method, params));
            } else {
                notifyExec.submit(() -> handleNotification(method, params));
            }
            return;
        }
        if (hasId) {
            long id = idNode.asLong();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) {
                log.warn("[AcpTransport] 收到未知 id 的响应，忽略: id={}", id);
                return;
            }
            if (frame.has("error") && !frame.get("error").isNull()) {
                JsonNode err = frame.get("error");
                int code = err.path("code").asInt(AcpRpcException.INTERNAL_ERROR);
                String message = err.path("message").asText("ACP 对端返回错误");
                future.completeExceptionally(new AcpRpcException(code, message, err.get("data")));
            } else {
                future.complete(frame.get("result"));
            }
        }
    }

    private void handleNotification(String method, JsonNode params) {
        NotificationHandler handler = this.notificationHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.onNotification(method, params);
        } catch (Throwable t) {
            log.warn("[AcpTransport] 通知处理失败 method={}: {}", method, t.toString());
        }
    }

    private void handleServerRequest(long id, String method, JsonNode params) {
        RequestHandler handler = this.requestHandler;
        if (handler == null) {
            writeError(id, AcpRpcException.METHOD_NOT_FOUND, "未注册的 client 方法: " + method);
            return;
        }
        JsonNode result;
        try {
            result = handler.onRequest(method, params);
        } catch (AcpRpcException e) {
            writeError(id, e.code(), e.getMessage());
            return;
        } catch (Throwable t) {
            log.warn("[AcpTransport] server 请求处理失败 method={}: {}", method, t.toString());
            writeError(id, AcpRpcException.INTERNAL_ERROR, "client 处理失败: " + t.getMessage());
            return;
        }
        ObjectNode frame = JSON.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.set("result", result == null ? JSON.nullNode() : result);
        try {
            writeFrame(frame);
        } catch (RuntimeException e) {
            log.warn("[AcpTransport] 回写请求响应失败 id={}: {}", id, e.getMessage());
        }
    }

    private void writeError(long id, int code, String message) {
        ObjectNode frame = JSON.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        ObjectNode error = frame.putObject("error");
        error.put("code", code);
        error.put("message", message == null ? "ACP client error" : message);
        try {
            writeFrame(frame);
        } catch (RuntimeException e) {
            log.warn("[AcpTransport] 回写错误响应失败 id={}: {}", id, e.getMessage());
        }
    }

    private void onConnectionLost() {
        if (closed) {
            return;
        }
        closed = true;
        AcpException failure = new AcpException("ACP 连接已断开（stdout EOF）");
        failAllPending(failure);
        Consumer<Throwable> listener = this.failureListener;
        if (listener != null) {
            try {
                listener.accept(failure);
            } catch (Throwable ignore) {
                // 监听器失败不得影响收尾
            }
        }
    }

    private void failAllPending(Throwable cause) {
        for (Long id : pending.keySet().toArray(new Long[0])) {
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null) {
                f.completeExceptionally(cause);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        failAllPending(new AcpException("ACP 传输已关闭"));
        notifyExec.shutdownNow();
        requestExec.shutdownNow();
        // ⛔ 只关写端：Windows 上 close 一个仍有线程阻塞在 readLine 的进程 stdout 流
        //   可能与读线程互相等（reader 等数据、close 等 reader）⇒ 收尾死锁。
        //   读端由子进程退出（quiesce 杀树）自然 EOF；reader 是 daemon，不会吊住 JVM。
        try {
            out.close();
        } catch (IOException ignore) {
            // 已关闭
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
