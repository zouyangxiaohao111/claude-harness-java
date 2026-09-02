package com.nexusai.application.agent.mcp;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SDK MCP Transport Bridge · 对齐 CC services/mcp/SdkControlTransport.ts.
 *
 * <p>L1 语义: SDK MCP servers 跨进程通信桥.
 *            - SdkControlClientTransport (CLI 侧): send message → 通过 sendMcpMessage callback 发往 SDK → 等 response → 触发 onmessage.
 *            - SdkControlServerTransport (SDK 侧): 简单 pass-through,send → sendMcpMessage callback.
 *            双向 onclose/onerror/onmessage 事件 + closed 守卫.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 classes (Client/Server);onmessage/onclose/onerror 3 callbacks;
 *       start/send/close 3 methods;closed 守卫.</li>
 *   <li><b>A2 Golden Trace</b>: Client.send → sendMcpMessage(server, msg) → response → onmessage;
 *       Server.send → sendMcpMessage(msg);close → isClosed=true + onclose.</li>
 *   <li><b>A3</b>: 状态: OPEN → CLOSED (isClosed=true);重复 close 幂等.</li>
 *   <li><b>A4</b>: send 时已 closed → throw;close 重复调用 → 不重复 onclose.</li>
 *   <li><b>A5</b>: 真实场景 — CLI 调 SDK MCP tool → send request → SDK response → 触发 client onmessage;</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `@modelcontextprotocol/sdk Transport` → Java interface;
 *                    TS async → Java CompletableFuture;
 *                    TS `(server, msg) => Promise<msg>` → Java BiFunction.
 */
public final class SdkControlTransport {

    private static final Logger log = LoggerFactory.getLogger(SdkControlTransport.class);

    /** MCP Transport interface (CC @modelcontextprotocol/sdk/shared/transport Transport). */
    public interface Transport {
        void onmessage(java.util.function.Consumer<JsonRpcMessage> callback);
        void onclose(Runnable callback);
        void onerror(Consumer<Throwable> callback);
        CompletableFuture<Void> start();
        CompletableFuture<Void> send(JsonRpcMessage message);
        CompletableFuture<Void> close();
    }

    /** JSON-RPC message (CC JSONRPCMessage 最小子集). */
    public record JsonRpcMessage(String method, Object params, Object id) {}

    /** CLI 侧 transport. */
    public static final class SdkControlClientTransport implements Transport {
        private final String serverName;
        private final BiFunction<String, JsonRpcMessage, CompletableFuture<JsonRpcMessage>> sendMcpMessage;
        private volatile boolean isClosed = false;
        private Consumer<JsonRpcMessage> onmessage;
        private Runnable onclose;
        private Consumer<Throwable> onerror;

        public SdkControlClientTransport(String serverName,
                                          BiFunction<String, JsonRpcMessage, CompletableFuture<JsonRpcMessage>> sendMcpMessage) {
            this.serverName = serverName;
            this.sendMcpMessage = Objects.requireNonNull(sendMcpMessage);
        }

        public String serverName() { return serverName; }

        @Override public void onmessage(java.util.function.Consumer<JsonRpcMessage> callback) { this.onmessage = callback; }
        @Override public void onclose(Runnable callback) { this.onclose = callback; }
        @Override public void onerror(Consumer<Throwable> callback) { this.onerror = callback; }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> send(JsonRpcMessage message) {
            if (isClosed) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("Transport is closed"));
                return failed;
            }
            return sendMcpMessage.apply(serverName, message).thenAccept(response -> {
                if (onmessage != null) onmessage.accept(response);
            }).exceptionally(ex -> {
                if (onerror != null) onerror.accept(ex);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> close() {
            if (isClosed) return CompletableFuture.completedFuture(null);
            isClosed = true;
            if (onclose != null) onclose.run();
            return CompletableFuture.completedFuture(null);
        }
    }

    /** SDK 侧 transport. */
    public static final class SdkControlServerTransport implements Transport {
        private final Consumer<JsonRpcMessage> sendMcpMessage;
        private volatile boolean isClosed = false;
        private Consumer<JsonRpcMessage> onmessage;
        private Runnable onclose;
        private Consumer<Throwable> onerror;

        public SdkControlServerTransport(Consumer<JsonRpcMessage> sendMcpMessage) {
            this.sendMcpMessage = Objects.requireNonNull(sendMcpMessage);
        }

        @Override public void onmessage(java.util.function.Consumer<JsonRpcMessage> callback) { this.onmessage = callback; }
        @Override public void onclose(Runnable callback) { this.onclose = callback; }
        @Override public void onerror(Consumer<Throwable> callback) { this.onerror = callback; }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> send(JsonRpcMessage message) {
            if (isClosed) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("Transport is closed"));
                return failed;
            }
            sendMcpMessage.accept(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> close() {
            if (isClosed) return CompletableFuture.completedFuture(null);
            isClosed = true;
            if (onclose != null) onclose.run();
            return CompletableFuture.completedFuture(null);
        }
    }
}
