package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.hook.ElicitationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP stdio transport · 对齐 CC InProcessTransport.ts + MCPConnectionManager stdio path.
 *
 * <p>L1 语义: 通过子进程 stdin/stdout 与 MCP server 通信, 帧分隔为换行 (JSON-RPC over stdio).
 * OAuth 不在本模块 (用户要求跳过).
 *
 * <p>L2 契约:
 * <ul>
 *   <li>{@link #start} 启动子进程, 成功 → state=CONNECTED, 失败抛</li>
 *   <li>{@link #sendRequest} 分配递增 id, 写 JSON-RPC request 到 stdin, 阻塞等响应</li>
 *   <li>{@link #sendNotification} 同上但无 id/响应</li>
 *   <li>{@link #close} 幂等, 关闭子进程</li>
 * </ul>
 *
 * <p>L3 (Java idiom): 用 AtomicLong 分配 id, ConcurrentHashMap 缓存 pending request, CompletableFuture 取代 Promise.
 */
@Component
public class StdioMcpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpTransport.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicReference<McpTransport.State> state = new AtomicReference<>(McpTransport.State.NOT_CONNECTED);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    // FIX-HK4: 注入 ElicitationHandler 路由 server→client elicitation/create 请求
    // 到 HookEvent.elicitation. @Autowired(required=false) 保证 ElicitationHandler bean
    // 不存在时也能正常启动.
    @Autowired(required = false)
    private ElicitationHandler elicitationHandler;
    /**
     * [S02 X-5] stderr 累积日志（64MB cap）· 对齐 CC client.ts:966-983（stderrOutput 累积
     * + {@code stderrOutput.length < 64*1024*1024} cap）。启动 stderr reader 消费管道防
     * 洪泛阻塞子进程（X-5 脏代码修复：redirectErrorStream(false) 但 getErrorStream() 从不
     * 读取 → 管道满阻塞）。
     */
    private static final int STDERR_CAP_BYTES = 64 * 1024 * 1024;
    private final StringBuilder stderrOutput = new StringBuilder();
    private volatile Thread stderrReaderThread;
    /**
     * [S02 X-9] 保存的 transport 配置 · start(config) 时保存，供 server→client roots/list
     * 请求回传 cwd（config.cwd() ?? CwdResolution.getOriginalCwdLayer()，CC client.ts:1009-1018 getOriginalCwd 语义）。
     */
    private volatile TransportConfig config;
    private Process process;

    /**
     * [WF-B] 本 transport 所属 MCP server 名 · 由 {@link #start(TransportConfig)} 从
     * {@link TransportConfig#serverName()} 提取（CC elicitationHandler.ts 的 serverName 来自
     * registerElicitationHandler 闭包，不来自 params）。elicitation/create 请求回传决策时
     * 作为 Elicitation hook 的 {@code mcp_server_name} 匹配 key。默认 "unknown"。
     */
    private volatile String serverName = "unknown";

    /** [WF-B] 注入 ElicitationHandler（测试/装配用；null → elicitation/create 回传 fail-closed decline）。 */
    public void setElicitationHandler(ElicitationHandler elicitationHandler) {
        this.elicitationHandler = elicitationHandler;
    }

    /**
     * [WF-B] 测试钩子：绕过 {@link #start} 直接挂接假 Process 并把状态置为 CONNECTED。
     * 用于在无子进程的单元测试中驱动 {@link #handleLine(String)}（读 stdout 线程不启动）。
     *
     * @param fakeProcess 假 Process（getOutputStream 返回可捕获的 ByteArrayOutputStream）
     */
    void attachProcessForTesting(Process fakeProcess) {
        this.process = fakeProcess;
        this.state.set(McpTransport.State.CONNECTED);
    }

    /**
     * P2-15: server→client 通知处理器注册表 · 对齐 CC {@code client.setNotificationHandler}
     * （useManageMCPConnections.ts:619/:669/:707）。key = JSON-RPC 通知 method
     * （notifications/{tools,prompts,resources}/list_changed），value = 处理器列表
     * （同 method 多 handler 按注册序分发）。
     */
    private final Map<String, List<McpNotificationHandler>> notificationHandlers = new ConcurrentHashMap<>();

    @Override
    public void start(TransportConfig config) {
        this.config = config;
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(config.command(), "command required for stdio transport");
        try {
            ProcessBuilder pb = new ProcessBuilder(concatCommand(config.command(), config.args()));
            pb.redirectErrorStream(false);
            if (config.cwd() != null) {
                pb.directory(new java.io.File(config.cwd()));
            }
            if (config.env() != null) {
                pb.environment().putAll(config.env());
            }
            this.process = pb.start();
            this.state.set(McpTransport.State.CONNECTED);
            if (config.serverName() != null && !config.serverName().isBlank()) {
                this.serverName = config.serverName();
            }
            log.info("[StdioMcpTransport] started command={} args={}", config.command(), config.args());
            // [S02 X-5] 启动 stderr reader（连接期即有 stderr 输出，CC client.ts:963-983 在
            // connect 前挂 stderr 监听；洪泛不阻塞子进程管道）
            startStderrReader();
            // 启动 stdout reader 线程 (L1 不变量: 异步读响应才能避免死锁)
            startStdoutReader();
        } catch (Exception e) {
            this.state.set(McpTransport.State.NOT_CONNECTED);
            throw new IllegalStateException("Failed to start MCP stdio transport: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
        if (state.get() != McpTransport.State.CONNECTED) {
            CompletableFuture<JsonNode> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("transport not connected"));
            return failed;
        }
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            Map<String, Object> rpc = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", params == null ? Map.of() : params
            );
            writeFrame(rpc);
            log.debug("[StdioMcpTransport] sent request id={} method={}", id, method);
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
        Map<String, Object> rpc = Map.of(
            "jsonrpc", "2.0",
            "method", method,
            "params", params == null ? Map.of() : params
        );
        writeFrame(rpc);
        log.debug("[StdioMcpTransport] sent notification method={}", method);
    }

    @Override
    public void setNotificationHandler(String method, McpNotificationHandler handler) {
        notificationHandlers.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(handler);
        if (log.isDebugEnabled()) {
            log.debug("[StdioMcpTransport] 注册通知处理器 method={}", method);
        }
    }

    @Override
    public void close() {
        if (state.get() == McpTransport.State.CLOSED) {
            return;
        }
        // [IMP-SS-01] 连接关闭 → abort 该 server 全部挂起 form elicitation → cancel
        // （对齐 CC onAbort resolve({action:'cancel'})，client.ts:1869/:2958-2962 连接关闭语义）
        if (elicitationHandler != null) {
            elicitationHandler.abortAllPendingForServer(serverName);
        }
        state.set(McpTransport.State.CLOSED);
        if (process != null && process.isAlive()) {
            // [S02 X-8] cleanup 升级序列 · 对齐 CC client.ts:1426-1562（SIGINT→SIGTERM→SIGKILL）：
            // Java 无 POSIX SIGINT（Windows 部署）→ destroy()≈SIGTERM → 50ms 轮询 isAlive 至
            // 100ms → 仍活则 destroyForcibly()≈SIGKILL → 400ms → 总 failsafe ≈600ms（受控偏差
            // 登记：CC 三级 → Java 两级，见 concerns）。
            process.destroy();
            waitForExit(100);
            if (process.isAlive()) {
                log.info("[StdioMcpTransport] destroy（≈SIGTERM）后进程仍存活，升级 destroyForcibly（≈SIGKILL）");
                process.destroyForcibly();
                waitForExit(400);
            }
            log.info("[StdioMcpTransport] process terminated alive={}（升级序列完成）", process.isAlive());
        }
        // fail all pending
        for (var entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IllegalStateException("transport closed"));
        }
        pending.clear();
    }

    /**
     * [S02 X-8] 等待子进程退出（50ms 轮询，上限 totalMs）· 对齐 CC client.ts:1445-1557
     * checkInterval 50ms + failsafe 结构。stderr reader 随流关闭自然退出。
     */
    private void waitForExit(long totalMs) {
        long deadline = System.currentTimeMillis() + totalMs;
        try {
            while (process.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * [S02 X-5] stderr reader daemon 线程 · 行读 getErrorStream()，StringBuilder 累积
     * 64MB cap（CC client.ts:973），超 cap 丢弃（防无界内存）。消费管道本身保证 stderr
     * 洪泛不阻塞子进程（X-5 脏代码修复）。随进程退出（EOF）自然终止。
     */
    private void startStderrReader() {
        Thread reader = new Thread(() -> {
            try (var br = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendStderr(line);
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[StdioMcpTransport] stderr reader terminated: {}", e.getMessage());
                }
            }
        }, "mcp-stdio-stderr-reader");
        reader.setDaemon(true);
        this.stderrReaderThread = reader;
        reader.start();
    }

    /** [S02 X-5] stderr 行累积（64MB cap，超 cap 丢弃）。 */
    private synchronized void appendStderr(String line) {
        if (stderrOutput.length() < STDERR_CAP_BYTES) {
            stderrOutput.append(line).append('\n');
        }
    }

    /**
     * [S02 X-5] 取 + 清空 stderr 累积日志 · 对齐 CC client.ts:1081-1083（连接成功后
     * logMCPError(stderr) + 清空释放内存）。供 {@link McpToolPool} 连接成功后日志。
     *
     * @return 累积 stderr 文本（可为空串）
     */
    String drainStderrLog() {
        synchronized (this) {
            String result = stderrOutput.toString();
            stderrOutput.setLength(0);
            return result;
        }
    }

    @Override
    public McpTransport.State getState() {
        return state.get();
    }

    // ────────────────────── 内部辅助 ──────────────────────

    private void writeFrame(Object obj) {
        try {
            byte[] bytes = (mapper.writeValueAsString(obj) + "\n").getBytes();
            // 同步写入：form 模式 elicitation 响应由 WS/超时线程完成 future 后调用本方法，
            // 与 reader/sendRequest 线程并发写 frame 必须互斥防止帧交错。
            synchronized (this) {
                process.getOutputStream().write(bytes);
                process.getOutputStream().flush();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write JSON-RPC frame: " + e.getMessage(), e);
        }
    }

    /**
     * [IMP-SS-01] 把 elicitation 决策写为 JSON-RPC result · 对齐 CC elicitationHandler.ts:106/166
     * {@code return hookResponse / result}（决策作为请求 result 回传）。from 挂起 future
     * 完成回调（WS 线程）或同步 hook 决策路径；无决策降级 cancel（CC :167-170 catch → cancel）。
     */
    private void writeElicitationResponse(JsonNode idNode, ElicitationResponse decision) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (decision != null) {
            result.put("action", decision.action());
            if (decision.content() != null && !decision.content().isEmpty()) {
                result.put("content", decision.content());
            }
        } else {
            result.put("action", "cancel");
        }
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", idNode);
        response.put("result", result);
        writeFrame(response);
        log.info("[StdioMcpTransport] {} elicitation/create 决策回传: action={}", serverName,
            result.get("action"));
    }

    /**
     * P2-15: 按 method 分发 server→client 通知到已注册处理器 · 对齐 CC
     * {@code client.setNotificationHandler}（useManageMCPConnections.ts:619-751）。
     *
     * <p>未注册该 method 时静默忽略（对齐 CC 无 handler 时 SDK 丢弃）。分发在独立线程
     * 执行——本方法从 stdout reader 线程调用，而处理器（McpToolPool list_changed 刷新）
     * 内含阻塞 sendRequest 往返，若同步执行会阻塞 reader 线程导致响应无人读取死锁。
     *
     * @param method JSON-RPC 通知 method
     * @param params 通知 params（list_changed 恒为 {}）
     */
    private void dispatchNotification(String method, JsonNode params) {
        List<McpNotificationHandler> handlers = notificationHandlers.get(method);
        if (handlers == null || handlers.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[StdioMcpTransport] 收到通知但无处理器 method={}", method);
            }
            return;
        }
        for (McpNotificationHandler handler : handlers) {
            CompletableFuture.runAsync(() -> {
                try {
                    handler.handle(params);
                } catch (Exception e) {
                    log.warn("[StdioMcpTransport] 通知处理器执行失败 method={}: {}", method, e.getMessage());
                }
            });
        }
    }

    private void startStdoutReader() {
        Thread reader = new Thread(() -> {
            try (var br = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    handleLine(line);
                }
            } catch (Exception e) {
                log.warn("[StdioMcpTransport] stdout reader terminated: {}", e.getMessage());
            }
        }, "mcp-stdio-reader");
        reader.setDaemon(true);
        reader.start();
    }

    void handleLine(String line) {
        try {
            JsonNode node = mapper.readTree(line);
            if (node.has("method")) {
                // server→client 消息：带 id = request（需响应，对齐 CC setRequestHandler）；
                // 无 id = notification（对齐 CC setNotificationHandler）。
                String method = node.get("method").asText();
                JsonNode params = node.get("params");
                boolean isRequest = node.has("id") && !node.get("id").isNull();
                if (isRequest) {
                    handleServerRequest(node.get("id"), method, params);
                } else {
                    handleServerNotification(method, params);
                }
            } else if (node.has("id") && !node.get("id").isNull()) {
                // 对 client→server 请求的响应（无 method）→ 完成 pending future
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
            } else {
                log.debug("[StdioMcpTransport] received notification: {}", line);
            }
        } catch (Exception e) {
            log.warn("[StdioMcpTransport] failed to parse line: {}", e.getMessage());
        }
    }

    /**
     * [WF-B] server→client JSON-RPC 请求处理 · 对齐 CC {@code client.setRequestHandler}
     * （elicitationHandler.ts:77-171）。当前仅支持 elicitation/create：调用
     * {@link ElicitationHandler#handleRequest} 消费 hook 决策，并把决策作为 JSON-RPC
     * result 回传（△-6/△-7：此前当作 notification 丢弃返回值）。
     *
     * <p>无决策（无 hook 配置 / hookRegistry 未接线）→ fail-closed 回传 {@code {action:'decline'}}
     * （Java 无 form 队列/UI，不能悬挂 server 请求；对齐 McpElicitationStateMachine 的
     * fail-closed auto-decline 约定）。未知 server→client 请求 → 按 JSON-RPC 协议回
     * {@code -32601 Method not found}（同样不悬挂）。
     *
     * @param idNode 请求 id（回传 result 用）
     * @param method JSON-RPC method（elicitation/create）
     * @param params 请求 params
     */
    private void handleServerRequest(JsonNode idNode, String method, JsonNode params) {
        if ("elicitation/create".equals(method)) {
            String name = resolveServerName(params);
            String message = params != null && params.has("message")
                ? params.get("message").asText() : "";
            String mode = params != null && params.has("mode")
                ? params.get("mode").asText() : null;
            String url = params != null && params.has("url")
                ? params.get("url").asText() : null;
            String elicitationId = params != null && params.has("elicitationId")
                ? params.get("elicitationId").asText() : null;
            Map<String, Object> requestedSchema = params != null && params.has("requestedSchema")
                && params.get("requestedSchema").isObject()
                ? mapper.convertValue(params.get("requestedSchema"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                : null;
            // JSON-RPC id 作为 form 挂起 requestId（跨 server 由 serverName 限定）
            String requestId = idNode.asText();
            if (elicitationHandler != null) {
                // [IMP-SS-01] form 模式用户响应链（对齐 CC elicitationHandler.ts:77-171）：
                //   hook 决策 → future 已完成 → whenComplete 同步写帧；无 hook 决策 → 挂起等待
                //   用户弹窗响应 / abort / 超时。读 stdout 线程不阻塞（异步完成时写帧）。
                elicitationHandler.beginFormElicitation(
                        name, message, mode, url, elicitationId, requestedSchema, requestId)
                    .whenComplete((decision, err) -> {
                        ElicitationResponse finalDecision = decision != null
                            ? decision : new ElicitationResponse("cancel", null);
                        try {
                            writeElicitationResponse(idNode, finalDecision);
                        } catch (Exception e) {
                            // [IMP-SS-01 返工] 子进程已死（reader EOF 触发 close → abort→cancel 同步完成
                            // future → whenComplete 同步写帧）时 writeFrame 抛 IllegalStateException，
                            // 不能让它逃逸出 close() 跳过后续 pending 清理 —— 仅记日志。
                            log.warn("[StdioMcpTransport] {} elicitation/create 决策回传失败（可能子进程已退出）: {}",
                                serverName, e.getMessage());
                        }
                    });
                return;
            }
            // handler 未接线 → fail-closed decline（不悬挂 server）
            writeElicitationResponse(idNode, new ElicitationResponse("decline", null));
            return;
        }
        if ("roots/list".equals(method)) {
            // [S02 X-9] server→client roots/list 请求 → {roots:[{uri:"file://"+cwd}]}
            // （对齐 CC client.ts:1009-1018 ListRootsRequestSchema handler → uri=file://${getOriginalCwd()}；
            // CC 真源自验 client.ts:1014：roots 用 STATE.originalCwd=会话项目根，非 pwd/getCwd 动态 cwd。
            // Java 兜底走统一入口 CwdResolution.getOriginalCwdLayer()（绑定项目层 ?? user.dir，
            // 对齐 CC getOriginalCwd）；config.cwd() 优先保留（server 可配沙箱 cwd，非破坏）。
            // DEL-07：移除 System.getProperty("user.dir") 直读，经统一入口兜底。）
            String cwd = config != null && config.cwd() != null && !config.cwd().isBlank()
                ? config.cwd() : CwdResolution.getOriginalCwdLayer();
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", idNode);
            response.put("result", Map.of("roots", List.of(Map.of("uri", "file://" + cwd))));
            writeFrame(response);
            log.info("[StdioMcpTransport] {} roots/list 响应: uri=file://{}", serverName, cwd);
            return;
        }
        log.warn("[StdioMcpTransport] 未处理 server→client 请求 method={}", method);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", idNode);
        Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("code", -32601);
        error.put("message", "Method not found");
        response.put("error", error);
        writeFrame(response);
    }

    /**
     * [WF-B] server→client 通知分发 · 对齐 CC {@code client.setNotificationHandler}
     * （useManageMCPConnections.ts:619-751）。elicitation/create 按 MCP 协议本应是
     * request（带 id）；若某 server 以 notification 形式发送（无 id），无法回传决策 ——
     * 仅 fire-and-forget 触发 Elicitation hook（保留旧 FIX-HK4 行为）并 warn 提示协议偏移。
     */
    private void handleServerNotification(String method, JsonNode params) {
        if ("elicitation/create".equals(method)) {
            String name = resolveServerName(params);
            String message = params != null && params.has("message")
                ? params.get("message").asText() : "";
            log.warn("[StdioMcpTransport] {} elicitation/create 以 notification 形式到达（无 id，"
                + "协议偏移），仅触发 hook 无法回传决策", name);
            if (elicitationHandler != null) {
                elicitationHandler.handleRequest(name, message);
            }
            return;
        }
        dispatchNotification(method, params);
    }

    /**
     * [WF-B] elicitation serverName 解析 · 优先 transport 配置的 serverName
     * （{@link TransportConfig#serverName()}，对齐 CC registerElicitationHandler 闭包），
     * 其次 params.serverName（旧 FIX-HK4 兼容），最后 "unknown"。
     */
    private String resolveServerName(JsonNode params) {
        if (!"unknown".equals(serverName) && serverName != null) {
            return serverName;
        }
        return params != null && params.has("serverName") && params.get("serverName").isTextual()
            ? params.get("serverName").asText() : "unknown";
    }

    private static String[] concatCommand(String cmd, java.util.List<String> args) {
        String[] result = new String[1 + args.size()];
        result[0] = cmd;
        for (int i = 0; i < args.size(); i++) {
            result[i + 1] = args.get(i);
        }
        return result;
    }
}