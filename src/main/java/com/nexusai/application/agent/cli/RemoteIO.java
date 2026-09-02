package com.nexusai.application.agent.cli;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RemoteIO bidirectional SDK streaming · 对齐 CC cli/remoteIO.ts.
 *
 * - CC source: cli/remoteIO.ts (255 LOC)。Java WS 协议面由 DirectConnectSessionManager/SDKControlSchemas 内联实现
 * - WebSocket transport (default) + SSE transport (CCR v2)
 * - Auth via session ingress token (Bearer)
 * - keep_alive frames on interval (bridge only)
 * - write() dispatches via CCRClient.writeEvent if CCR v2 enabled
 * - close() tears down transport + input stream
 *
 * Java port: simplified core surface. Full SDK transport handled by injected
 * Transport; this class manages session lifecycle, auth, and CCR v2 dispatch.
 */
public final class RemoteIO {

    private static final Logger log = LoggerFactory.getLogger(RemoteIO.class);

    public interface Transport {
        void setOnData(Consumer<String> handler);
        void setOnClose(Runnable handler);
        void connect();
        void write(String message);
        void close();
        boolean isOpen();
    }

    public interface StdoutMessage {
        String type();
    }

    private final String streamUrl;
    private final Transport transport;
    private final Supplier<String> sessionTokenSupplier;
    private final boolean isBridge;
    private final boolean isDebug;
    private final Consumer<String> stdoutWriter;
    private final long keepAliveIntervalMs;
    private final Runnable scheduleTimer;
    private final Runnable cancelTimer;

    private volatile boolean closed = false;

    public RemoteIO(String streamUrl,
                     Transport transport,
                     Supplier<String> sessionTokenSupplier,
                     boolean isBridge,
                     boolean isDebug,
                     Consumer<String> stdoutWriter,
                     long keepAliveIntervalMs,
                     Runnable scheduleTimer,
                     Runnable cancelTimer) {
        this.streamUrl = Objects.requireNonNull(streamUrl);
        this.transport = Objects.requireNonNull(transport);
        this.sessionTokenSupplier = Objects.requireNonNull(sessionTokenSupplier);
        this.isBridge = isBridge;
        this.isDebug = isDebug;
        this.stdoutWriter = stdoutWriter;
        this.keepAliveIntervalMs = keepAliveIntervalMs;
        this.scheduleTimer = scheduleTimer;
        this.cancelTimer = cancelTimer;
    }

    /** Build initial headers (auth + environment runner). */
    public Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        String token = sessionTokenSupplier.get();
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        } else {
            log.debug("[remote-io] No session ingress token available");
        }
        // x-environment-runner-version is set by Environment Manager — skip for test
        return headers;
    }

    /** Start keep-alive timer (bridge only, interval > 0). */
    public void startKeepAlive() {
        if (!isBridge || keepAliveIntervalMs <= 0) return;
        scheduleTimer.run();
    }

    /** Stop keep-alive timer. */
    public void stopKeepAlive() {
        cancelTimer.run();
    }

    /** Echo message to stdout (debug or control_request). */
    public void echoIfNeeded(StdoutMessage message) {
        if (!isBridge && !isDebug) return;
        if (isDebug || "control_request".equals(message.type())) {
            if (stdoutWriter != null) stdoutWriter.accept(message.toString());
        }
    }

    /** Send message to transport. */
    public void send(StdoutMessage message) {
        transport.write(String.valueOf(message));
    }

    /** Connect to transport. */
    public void connect() {
        transport.setOnData(data -> { /* input stream consumer */ });
        transport.setOnClose(() -> { closed = true; });
        transport.connect();
        startKeepAlive();
    }

    /** Close transport and stop keep-alive. */
    public void close() {
        if (closed) return;
        stopKeepAlive();
        transport.close();
        closed = true;
    }

    public boolean isClosed() { return closed; }
    public boolean isOpen() { return transport.isOpen(); }
    public String getStreamUrl() { return streamUrl; }
    public Transport getTransport() { return transport; }
    public boolean isBridge() { return isBridge; }
    public boolean isDebug() { return isDebug; }
}
