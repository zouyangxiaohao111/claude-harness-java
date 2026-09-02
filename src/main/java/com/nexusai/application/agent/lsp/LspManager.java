package com.nexusai.application.agent.lsp;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * LSP 服务器管理器 · 对齐 CC LSPServerManager.ts:16-43 + manager.ts:99-110.
 *
 * <p>L1 语义: 按文件扩展名路由到对应 LSP server. 全局单例, 启动时初始化, 关闭时清理.
 * 至少一个 server 非 FAILED 时 {@link #isLspConnected()} 返回 true (供 LSPTool.isEnabled() 使用).
 *
 * <p>L2 契约:
 * <ul>
 *   <li>{@link #initialize} 加载配置 (空 Map → 无 server, 不抛), 幂等可重复调用</li>
 *   <li>{@link #isLspConnected} {@code initialized && 非空 && 任一 state != FAILED} → true
 *       (对齐 CC manager.ts:100-110, NOT_STARTED/stopped 也计为 connected)</li>
 *   <li>{@link #getServerForFile} 找不到扩展名映射 → Optional.empty()</li>
 *   <li>{@link #ensureServerStarted} 首次文件访问惰性启动真实子进程 (CC LSPServerManager.ts:215-236)</li>
 *   <li>{@link #shutdown} 幂等, 真实 stop running client + 状态置 STOPPED</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Spring @Component 单例 + ConcurrentHashMap + AtomicReference 状态机.
 */
@Component
public class LspManager {

    private static final Logger log = LoggerFactory.getLogger(LspManager.class);

    /** server 生命周期状态 · 对齐 CC LSPServerInstance LspServerState (stopped/starting/running/error). */
    public enum State {
        /** CC 'stopped' — 从未启动. */
        NOT_STARTED,
        /** CC 'starting' — 子进程启动中. */
        STARTING,
        /** CC 'running' — 子进程已 start + initialize 握手完成. */
        READY,
        /** CC 'error' — 启动/握手失败. */
        FAILED,
        /** CC 'stopped' (shutdown 后) — 已关闭. */
        STOPPED
    }

    /** 单个 server 状态. 对齐 CC LSPServerInstance. */
    public static final class ServerState {
        private final String name;
        private final List<String> extensions;
        private final LspServerConfig config;
        private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);

        public ServerState(String name, LspServerConfig config) {
            this.name = name;
            this.config = config;
            this.extensions = List.copyOf(config.extensions());
        }

        public String name() { return name; }
        public List<String> extensions() { return extensions; }
        /** 持有配置 (供真实启动 command/args/env + languageId 解析). */
        public LspServerConfig config() { return config; }
        public State state() { return state.get(); }
        /** public: 状态机转换 (manager.READY → server.setState(READY)), 测试也可调. */
        public void setState(State s) { state.set(s); }
    }

    private final Map<String, ServerState> servers = new ConcurrentHashMap<>();
    private final Map<String, String> extensionToServer = new ConcurrentHashMap<>();
    /** server name → LspClient。惰性启动时填入真实 ProcessLspClient；测试经 registerClient 注入 mock。 */
    private final Map<String, LspClient> clients = new ConcurrentHashMap<>();
    /** fileUri → server name。对齐 CC openedFiles (URI -> server name). */
    private final Map<String, String> openedFiles = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    /** 生产配置源 (默认空 Map → 0 server, 对齐 CC config.ts:76-78 返回空 map 的诚实态). */
    private volatile Supplier<Map<String, LspServerConfig>> configSupplier = () -> Map.of();

    /**
     * 注入生产配置源 (Java 无插件 LSP 源时的替代注入缝, 后续 plugin LSP 集成接入真实源).
     * {@code null} → 回退空 Map。
     */
    public void setConfigSupplier(Supplier<Map<String, LspServerConfig>> supplier) {
        this.configSupplier = supplier == null ? () -> Map.of() : supplier;
    }

    /**
     * 生产入口 (无参) · 对齐 CC manager.ts:181 {@code lspManagerInstance.initialize()} 启动时异步初始化.
     * 幂等: {@link #initialize(Map)} 已 initialized 时早返. LlmAgentLoop.run() 会话启动时调用.
     */
    public void initialize() {
        initialize(configSupplier.get());
    }

    /**
     * 加载 LSP server 配置 · 对齐 CC LSPServerManager.initialize().
     *
     * @param config serverName → LspServerConfig. 空 Map 合法 (无 server, 不抛).
     */
    public void initialize(Map<String, LspServerConfig> config) {
        if (initialized) {
            return;
        }
        for (Map.Entry<String, LspServerConfig> e : config.entrySet()) {
            String name = e.getKey();
            LspServerConfig cfg = e.getValue();
            ServerState ss = new ServerState(name, cfg);
            servers.put(name, ss);
            for (String ext : cfg.extensions()) {
                extensionToServer.put(ext.toLowerCase(), name);
            }
        }
        initialized = true;
        log.info("LspManager: initialized with {} server(s)", servers.size());
    }

    /**
     * 是否至少一个 server 非 FAILED · 对齐 CC manager.ts:100-110 isLspConnected().
     * {@code !initialized} → false; {@code servers 空} → false; 任一 state != FAILED → true.
     * NOT_STARTED/STOPPED (从未启动/已关闭) 也计为 connected (CC 'stopped' 计 connected).
     */
    public boolean isLspConnected() {
        if (!initialized) {
            return false;
        }
        if (servers.isEmpty()) {
            return false;
        }
        return servers.values().stream().anyMatch(s -> s.state() != State.FAILED);
    }

    /**
     * 按文件扩展名找对应 server · 对齐 CC LSPServerManager.getServerForFile().
     *
     * @param filePath 文件绝对路径
     * @return server 状态, 找不到扩展名映射时 empty
     */
    public Optional<ServerState> getServerForFile(String filePath) {
        String ext = extensionOf(filePath);
        if (ext.isEmpty()) {
            return Optional.empty();
        }
        String serverName = extensionToServer.get(ext);
        if (serverName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(servers.get(serverName));
    }

    /**
     * 确保对应 server 已启动 (惰性真实子进程) · 对齐 CC LSPServerManager.ts:215-236
     * {@code ensureServerStarted(filePath)}。
     *
     * <p>NOT_STARTED/FAILED → setState(STARTING) → new ProcessLspClient().start(command,args,env,workspaceFolder)
     * + .initialize(rootUri) → setState(READY) 存入 clients；失败 → setState(FAILED) + fail-loud throw.
     * READY/STARTING → 直接返回 (不重复启动)。
     *
     * @param filePath 文件路径
     * @return server 状态；无扩展名映射时返回 null
     */
    public ServerState ensureServerStarted(String filePath) {
        Optional<ServerState> serverOpt = getServerForFile(filePath);
        if (serverOpt.isEmpty()) {
            return null;
        }
        ServerState server = serverOpt.get();
        State s = server.state();
        if (s == State.NOT_STARTED || s == State.FAILED) {
            synchronized (server) {
                s = server.state();
                if (s == State.NOT_STARTED || s == State.FAILED) {
                    server.setState(State.STARTING);
                    LspServerConfig cfg = server.config();
                    try {
                        ProcessLspClient client = new ProcessLspClient();
                        client.start(cfg.command(),
                            cfg.args() == null ? new String[0] : cfg.args().toArray(new String[0]),
                            cfg.env(), cfg.workspaceFolder());
                        client.initialize(rootUriOf(cfg));
                        clients.put(server.name(), client);
                        server.setState(State.READY);
                        log.info("LspManager: LSP server 惰性启动成功 name={} cmd={}", server.name(), cfg.command());
                    } catch (Exception e) {
                        server.setState(State.FAILED);
                        log.warn("LspManager: LSP server 惰性启动失败 name={} cause={}", server.name(), e.toString());
                        throw new IllegalStateException(
                            "Failed to start LSP server " + server.name() + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        return server;
    }

    /**
     * 发送 LSP request · 对齐 CC LSPServerManager.ts:244-263 sendRequest (ensureServerStarted → server.sendRequest).
     *
     * @return 服务端响应；无 server 时返回 null (CC 返回 undefined)
     */
    public <T> T sendRequest(String filePath, String method, Object params, Class<T> resultType) {
        ServerState server = ensureServerStarted(filePath);
        if (server == null) {
            return null;
        }
        LspClient client = clients.get(server.name());
        if (client == null) {
            return null;
        }
        return client.sendRequest(method, params, resultType);
    }

    /**
     * 同步文件打开 (didOpen) · 对齐 CC LSPServerManager.ts:270-310 openFile.
     * didOpen 参数含 uri + languageId (extensionToLanguage[ext] || 'plaintext') + version:1 + text.
     * openedFiles 跟踪, 已 open 跳过.
     */
    public void openFile(String filePath, String content) {
        ServerState server = ensureServerStarted(filePath);
        if (server == null) {
            return;
        }
        String fileUri = fileUriOf(filePath);
        if (server.name().equals(openedFiles.get(fileUri))) {
            if (log.isDebugEnabled()) {
                log.debug("LspManager: 文件已 opened, 跳过 didOpen: {}", filePath);
            }
            return;
        }
        String ext = extensionOf(filePath);
        String languageId = server.config().extensionToLanguage().getOrDefault(ext, "plaintext");
        LspClient client = clients.get(server.name());
        if (client == null) {
            return;
        }
        try {
            client.sendNotification("textDocument/didOpen", Map.of(
                "textDocument", Map.of(
                    "uri", fileUri, "languageId", languageId, "version", 1, "text", content)
            ));
            openedFiles.put(fileUri, server.name());
            if (log.isDebugEnabled()) {
                log.debug("LspManager: 已发送 didOpen: {} (languageId={})", filePath, languageId);
            }
        } catch (Exception e) {
            log.warn("LspManager: didOpen 通知失败: {} cause={}", filePath, e.toString());
        }
    }

    /**
     * 通知 LSP server 文件内容变更（didChange）· 对齐 CC LSPServerManager.ts:312-343
     * {@code changeFile(filePath, content)}。
     *
     * <p>CC 语义：getServerForFile → server 不存在或 state!=='running' → {@code openFile} 兜底；
     * 文件尚未 opened → {@code openFile} 兜底 (LSP 要求 didOpen 先于 didChange)；否则
     * {@code sendNotification('textDocument/didChange', {textDocument:{uri,version:1},
     * contentChanges:[{text:content}]})} (version 恒 1, 对齐 CC :327 不递增).
     *
     * @param filePath 文件路径
     * @param content  写入后的新内容
     */
    public void changeFile(String filePath, String content) {
        Optional<ServerState> serverOpt = getServerForFile(filePath);
        if (serverOpt.isEmpty() || serverOpt.get().state() != State.READY) {
            // CC: server 不存在或非 running → openFile 兜底 (openFile 内部 ensureServerStarted 惰性启动).
            openFile(filePath, content);
            return;
        }
        ServerState server = serverOpt.get();
        String fileUri = fileUriOf(filePath);
        if (!server.name().equals(openedFiles.get(fileUri))) {
            // 文件尚未 opened → openFile 兜底 (LSP 要求 didOpen 先于 didChange).
            openFile(filePath, content);
            return;
        }
        LspClient client = clients.get(server.name());
        if (client == null) {
            return;
        }
        try {
            client.sendNotification("textDocument/didChange", Map.of(
                "textDocument", Map.of("uri", fileUri, "version", 1),
                "contentChanges", List.of(Map.of("text", content))
            ));
            if (log.isDebugEnabled()) {
                log.debug("LspManager: 已发送 didChange: {} (version=1, CC LSPServerManager.ts:327)", filePath);
            }
        } catch (Exception e) {
            log.warn("LspManager: didChange 通知失败: {} cause={}", filePath, e.toString());
        }
    }

    /**
     * 通知 LSP server 文件已保存（didSave）· 对齐 CC LSPServerManager.ts:349-368
     * {@code saveFile(filePath)}。仅 server READY 发送 didSave, 无兜底.
     *
     * @param filePath 文件路径
     */
    public void saveFile(String filePath) {
        Optional<ServerState> serverOpt = getServerForFile(filePath);
        if (serverOpt.isEmpty() || serverOpt.get().state() != State.READY) {
            return;
        }
        ServerState server = serverOpt.get();
        LspClient client = clients.get(server.name());
        if (client == null) {
            return;
        }
        String fileUri = fileUriOf(filePath);
        try {
            client.sendNotification("textDocument/didSave", Map.of(
                "textDocument", Map.of("uri", fileUri)
            ));
            if (log.isDebugEnabled()) {
                log.debug("LspManager: 已发送 didSave: {}", filePath);
            }
        } catch (Exception e) {
            log.warn("LspManager: didSave 通知失败: {} cause={}", filePath, e.toString());
        }
    }

    /**
     * 文件是否已 opened · 对齐 CC LSPServerManager.ts:402-405 isFileOpen (openedFiles.has(fileUri)).
     */
    public boolean isFileOpen(String filePath) {
        return openedFiles.containsKey(fileUriOf(filePath));
    }

    /**
     * 注册 LspClient（测试 mock / 注入真实 client）· 覆盖惰性真实子进程 client。
     * {@code client == null} 时移除注册。
     */
    public void registerClient(String serverName, LspClient client) {
        if (client == null) {
            clients.remove(serverName);
            return;
        }
        clients.put(serverName, client);
    }

    /** 归一化绝对路径（CC pathToFileURL(path.resolve(filePath)).href 的 file:// 前缀）。 */
    private String toAbsolutePath(String filePath) {
        try {
            return Path.of(filePath).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return filePath;
        }
    }

    /** 文件路径 → file:// URI. */
    private String fileUriOf(String filePath) {
        return "file://" + toAbsolutePath(filePath);
    }

    /** 提取小写扩展名 (无扩展名返回 ""). */
    private String extensionOf(String filePath) {
        int dot = filePath.lastIndexOf('.');
        if (dot < 0 || dot == filePath.length() - 1) {
            return "";
        }
        return filePath.substring(dot + 1).toLowerCase();
    }

    /** workspaceFolder → rootUri (CC pathToFileURL(workspaceFolder).href); 空则默认 file:///workspace. */
    private String rootUriOf(LspServerConfig cfg) {
        String ws = cfg.workspaceFolder();
        if (ws == null || ws.isBlank()) {
            return "file:///workspace";
        }
        return "file://" + toAbsolutePath(ws);
    }

    /** 所有 server 状态快照 (只读). 用于诊断. */
    public Map<String, ServerState> getAllServers() {
        return Collections.unmodifiableMap(new HashMap<>(servers));
    }

    /**
     * 关闭所有 server (幂等) · 对齐 CC LSPServerManager.shutdown + init.ts:189 registerCleanup.
     * 真实 stop running ProcessLspClient 子进程, 状态置 STOPPED, 清空 clients/openedFiles.
     */
    @PreDestroy
    public void shutdown() {
        for (ServerState s : servers.values()) {
            if (s.state() == State.READY) {
                LspClient client = clients.get(s.name());
                if (client != null) {
                    try {
                        client.stop();
                    } catch (Exception e) {
                        log.warn("LspManager: LSP server stop 失败 name={} cause={}", s.name(), e.toString());
                    }
                }
            }
            s.setState(State.STOPPED);
        }
        clients.clear();
        openedFiles.clear();
        log.info("LspManager: shutdown 完成 ({} server)", servers.size());
    }

    /** 测试/诊断用: 是否已 initialize. */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * LSP server 配置 record · 对齐 CC ScopedLspServerConfig.
     *
     * @param extensions           该 server 服务的文件扩展名 (小写)
     * @param command              可执行命令 (CC original: command)
     * @param args                 命令参数 (CC original: args)
     * @param env                  合并环境变量 (CC original: env)
     * @param extensionToLanguage  ext → languageId 映射 (CC original: extensionToLanguage, didOpen languageId 来源)
     * @param workspaceFolder      工作目录 / rootUri 来源 (CC original: workspaceFolder), null 允许
     */
    public record LspServerConfig(
        List<String> extensions,
        String command,
        List<String> args,
        Map<String, String> env,
        Map<String, String> extensionToLanguage,
        String workspaceFolder
    ) {
        /**
         * 4 参兼容构造器: extensionToLanguage 派生自 extensions (ext → 'plaintext'), workspaceFolder=null.
         * 保留避免破坏现有测试构造.
         */
        public LspServerConfig(List<String> extensions, String command, List<String> args, Map<String, String> env) {
            this(extensions, command, args, env, deriveExtensionToLanguage(extensions), null);
        }

        private static Map<String, String> deriveExtensionToLanguage(List<String> extensions) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String ext : extensions) {
                m.put(ext.toLowerCase(), "plaintext");
            }
            return m;
        }
    }
}
