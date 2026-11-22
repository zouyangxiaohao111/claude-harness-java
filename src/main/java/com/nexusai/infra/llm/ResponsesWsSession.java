package com.nexusai.infra.llm;

import com.openai.client.OpenAIClient;
import com.openai.core.http.ResponseConnection;
import com.openai.models.responses.ResponsesClientEvent;
import com.openai.models.responses.ResponsesServerEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单会话 Responses over WebSocket 连接（对齐 codex `ModelClientSession` 的连接缓存语义）。
 *
 * <p><b>为什么每会话一条连接</b>：codex 侧连接按 turn/session 缓存并复用（`ModelClientSession`），
 * 复用同一连接才能让下一次 `response.create` 走增量续接（`previous_response_id` 的会话状态
 * 依附于连接）。本仓按 `provider|sessionId` 一条，隔离性好、无需处理 lane 复用语义。
 *
 * <p><b>读循环</b>：连接建立后起一条虚拟线程持续 `receive()`，事件进有界队列；
 * 调用侧用 {@link #next(long)} 带超时取 —— 这样空闲超时（codex `stream_idle_timeout` 默认 300s）
 * 不依赖 OkHttp readTimeout，也不会被长推理误杀。
 *
 * <p><b>握手超时</b>：SDK `connect()` 阻塞且无超时参数 ⇒ 用虚拟线程 + {@link CompletableFuture#get(long, TimeUnit)}
 * 施加 15s（codex `DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS`）。
 */
final class ResponsesWsSession implements AutoCloseable {

    /** codex `DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS`（model-provider-info/src/lib.rs:78）。 */
    static final long CONNECT_TIMEOUT_MS = 15_000L;

    /** 队列哨兵：连接已关闭/读循环已退出。 */
    private static final Object CLOSED = new Object();

    private final ResponseConnection connection;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(1024);
    private volatile Thread pump;
    private volatile Throwable failure;
    private volatile long lastUsedAt = System.currentTimeMillis();
    private volatile boolean closed;

    private ResponsesWsSession(ResponseConnection connection) {
        this.connection = connection;
    }

    /**
     * 建立连接（15s 超时）。
     *
     * @param client    已带齐 baseUrl / 认证头 / 代理的 SDK 客户端
     * @param timeoutMs 握手超时毫秒（&lt;=0 用默认 15s）
     */
    static ResponsesWsSession connect(OpenAIClient client, long timeoutMs) {
        long timeout = timeoutMs > 0 ? timeoutMs : CONNECT_TIMEOUT_MS;
        CompletableFuture<ResponseConnection> future = new CompletableFuture<>();
        Thread.ofVirtual().name("responses-ws-connect").start(() -> {
            try {
                future.complete(client.responses().connect());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return new ResponsesWsSession(future.get(timeout, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("responses websocket connect timeout " + timeout + "ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("responses websocket connect failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("responses websocket connect interrupted", e);
        }
    }

    /** 发送一次 `response.create`。 */
    void send(ResponsesClientEvent.ResponseCreate request) {
        touch();
        connection.send(ResponsesClientEvent.ofResponseCreate(request));
    }

    /**
     * 取下一个服务端事件。
     *
     * @param timeoutMs 空闲超时（&lt;=0 = 无限等待）
     * @throws TimeoutException 超时未收到任何事件（调用侧应按 codex stream_idle_timeout 处理）
     */
    ResponsesServerEvent next(long timeoutMs) throws TimeoutException {
        startPump();
        Object o;
        try {
            o = timeoutMs > 0 ? queue.poll(timeoutMs, TimeUnit.MILLISECONDS) : queue.take();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("responses websocket receive interrupted", ie);
        }
        if (o == null) {
            throw new TimeoutException("responses websocket idle " + timeoutMs + "ms");
        }
        if (o == CLOSED) {
            Throwable f = failure;
            if (f != null) {
                throw new IllegalStateException("responses websocket closed: " + f.getMessage(), f);
            }
            throw new IllegalStateException("responses websocket closed");
        }
        touch();
        return (ResponsesServerEvent) o;
    }

    private synchronized void startPump() {
        if (pump != null || closed) {
            return;
        }
        pump = Thread.ofVirtual().name("responses-ws-recv").start(() -> {
            try {
                while (!closed) {
                    ResponsesServerEvent ev = connection.receive();
                    if (ev == null) {
                        queue.offer(CLOSED);
                        return;
                    }
                    queue.put(ev);
                }
            } catch (Throwable e) {
                if (!closed) {
                    failure = e;
                    queue.offer(CLOSED);
                }
            }
        });
    }

    long lastUsedAt() {
        return lastUsedAt;
    }

    boolean isClosed() {
        return closed;
    }

    private void touch() {
        lastUsedAt = System.currentTimeMillis();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (Throwable ignore) {
            // 关闭失败不影响调用方（连接已不可用）
        }
        Thread p = pump;
        if (p != null) {
            p.interrupt();
        }
        queue.offer(CLOSED);
    }
}
