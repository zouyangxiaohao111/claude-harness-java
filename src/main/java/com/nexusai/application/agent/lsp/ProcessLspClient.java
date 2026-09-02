package com.nexusai.application.agent.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实 LSP server 子进程客户端 · 对齐 CC LSPClient.ts:51-447.
 *
 * <p>L1 语义: 通过 ProcessBuilder 启动外部 LSP server (e.g. typescript-language-server --stdio),
 * 用 stdin/stdout 通过 JSON-RPC over LSP framed 协议 (Content-Length header + body) 通信.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li>帧格式: {@code Content-Length: N\r\n\r\n<body>}</li>
 *   <li>start → 进程启动 → state=STARTING → READY (stdout reader 启动) / FAILED</li>
 *   <li>sendRequest 必分配递增 id, 异步读响应 → future complete</li>
 *   <li>stop 幂等, 关闭 stdin + destroy 进程 + fail 所有 pending</li>
 * </ul>
 *
 * <p>L3 (Java idiom): ProcessBuilder + BufferedReader + ConcurrentHashMap<id, future>.
 */
@Component
public class ProcessLspClient implements LspClient {

    private static final Logger log = LoggerFactory.getLogger(ProcessLspClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private Process process;
    private Map<String, Object> capabilitiesValue;

    enum State { NOT_STARTED, STARTING, READY, FAILED, STOPPED }

    @Override
    public Map<String, Object> capabilities() {
        return capabilitiesValue;
    }

    @Override
    public boolean isInitialized() {
        return capabilitiesValue != null;
    }

    @Override
    public void start(String command, String[] args, Map<String, String> env, String cwd) {
        Objects.requireNonNull(command, "command required");
        state.set(State.STARTING);
        try {
            ProcessBuilder pb = new ProcessBuilder(concat(command, args));
            pb.redirectErrorStream(false);
            if (cwd != null) pb.directory(new java.io.File(cwd));
            if (env != null) pb.environment().putAll(env);
            this.process = pb.start();
            log.info("[ProcessLspClient] started cmd={} args={} pid={}",
                command, java.util.Arrays.toString(args), process.pid());
            startStdoutReader();
            state.set(State.READY);
        } catch (IOException e) {
            state.set(State.FAILED);
            throw new IllegalStateException("LSP server start failed: " + e.getMessage(), e);
        }
    }

    @Override
    public LspClient.LspInitializeResult initialize(String rootUri) {
        if (state.get() != State.READY) {
            throw new IllegalStateException("LSP server not READY (state=" + state.get() + ")");
        }
        Map<String, Object> params = Map.of(
            "processId", ProcessHandle.current().pid(),
            "rootUri", rootUri == null ? "" : rootUri,
            "capabilities", Map.of(),
            "trace", "off"
        );
        try {
            JsonNode result = sendRequestInternal("initialize", params);
            Map<String, Object> caps = mapper.convertValue(result.path("capabilities"), Map.class);
            this.capabilitiesValue = caps == null ? Map.of() : caps;
            String protocolVersion = result.path("protocolVersion").asText("2024-11-05");
            JsonNode serverInfoNode = result.path("serverInfo");
            LspClient.LspServerInfo serverInfo = new LspClient.LspServerInfo(
                serverInfoNode.path("name").asText("unknown"),
                serverInfoNode.path("version").asText("unknown")
            );
            sendNotificationInternal("initialized", Map.of());
            log.info("[ProcessLspClient] initialized protocol={} caps={}", protocolVersion, capabilitiesValue.keySet());
            return new LspClient.LspInitializeResult(protocolVersion, capabilitiesValue, serverInfo);
        } catch (Exception e) {
            throw new IllegalStateException("LSP initialize failed: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T sendRequest(String method, Object params, Class<T> resultType) {
        if (state.get() != State.READY) {
            throw new IllegalStateException("LSP server not READY (state=" + state.get() + ")");
        }
        if (capabilitiesValue == null) {
            throw new IllegalStateException("LSP not initialized; call initialize() first");
        }
        JsonNode result = sendRequestInternal(method, params);
        return result == null ? null : mapper.convertValue(result, resultType);
    }

    @Override
    public void sendNotification(String method, Object params) {
        if (state.get() != State.READY) {
            throw new IllegalStateException("LSP server not READY");
        }
        if (capabilitiesValue == null) {
            throw new IllegalStateException("LSP not initialized");
        }
        sendNotificationInternal(method, params);
    }

    @Override
    public void stop() {
        if (state.get() == State.STOPPED) {
            return;
        }
        state.set(State.STOPPED);
        if (process != null && process.isAlive()) {
            process.destroy();
            log.info("[ProcessLspClient] destroyed pid={}", process.pid());
        }
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IllegalStateException("LSP client stopped"));
        }
        pending.clear();
    }

    // ────────────── 内部辅助 ──────────────

    private JsonNode sendRequestInternal(String method, Object params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        Map<String, Object> rpc = new HashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", id);
        rpc.put("method", method);
        rpc.put("params", params == null ? Map.of() : params);
        try {
            writeFramed(rpc);
            log.debug("[ProcessLspClient] sent id={} method={}", id, method);
            return fut.join(); // 阻塞; 真实 UI 应异步
        } catch (Exception e) {
            pending.remove(id);
            throw new IllegalStateException("LSP request failed: " + e.getMessage(), e);
        }
    }

    private void sendNotificationInternal(String method, Object params) {
        Map<String, Object> rpc = new HashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("method", method);
        rpc.put("params", params == null ? Map.of() : params);
        try {
            writeFramed(rpc);
        } catch (Exception e) {
            throw new IllegalStateException("LSP notification failed: " + e.getMessage(), e);
        }
    }

    private void writeFramed(Object obj) throws IOException {
        byte[] body = mapper.writeValueAsBytes(obj);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        process.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
        process.getOutputStream().write(body);
        process.getOutputStream().flush();
    }

    private void startStdoutReader() {
        Thread reader = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (state.get() != State.STOPPED) {
                    String contentLengthLine = br.readLine();
                    if (contentLengthLine == null) break;
                    if (!contentLengthLine.toLowerCase().startsWith("content-length:")) continue;
                    int len = Integer.parseInt(contentLengthLine.split(":")[1].trim());
                    br.readLine(); // 空行
                    char[] buf = new char[len];
                    int read = 0;
                    while (read < len) {
                        int n = br.read(buf, read, len - read);
                        if (n < 0) break;
                        read += n;
                    }
                    String json = new String(buf, 0, read);
                    handleIncoming(json);
                }
            } catch (Exception e) {
                if (state.get() != State.STOPPED) {
                    log.warn("[ProcessLspClient] reader terminated: {}", e.getMessage());
                }
            }
        }, "lsp-stdout-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void handleIncoming(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node.has("id") && !node.get("id").isNull()) {
                long id = node.get("id").asLong();
                CompletableFuture<JsonNode> fut = pending.remove(id);
                if (fut != null) {
                    if (node.has("error")) {
                        fut.completeExceptionally(new IllegalStateException(
                            "LSP error: " + node.get("error")));
                    } else {
                        fut.complete(node.get("result"));
                    }
                }
            }
            // notification 暂不处理 (A3 Gate: 状态机收完就完)
        } catch (Exception e) {
            log.warn("[ProcessLspClient] parse failed: {}", e.getMessage());
        }
    }

    /** 测试用: 当前状态. */
    public State getState() { return state.get(); }

    private static String[] concat(String cmd, String[] args) {
        String[] r = new String[1 + (args == null ? 0 : args.length)];
        r[0] = cmd;
        if (args != null) System.arraycopy(args, 0, r, 1, args.length);
        return r;
    }
}