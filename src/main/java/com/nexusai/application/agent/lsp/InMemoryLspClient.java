package com.nexusai.application.agent.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LspClient 内存实现 (用于测试/单测, 不启动真实子进程).
 *
 * <p>L1 语义: 满足 {@link LspClient} 契约的状态机. 真实 stdio 启动留后续 (P1-2),
 * 当前 commit 提供可单测的 in-memory 替身, 让 LSPTool/Manager 可在无 LSP server 环境下跑通.
 *
 * <p>L2 契约:
 * <ul>
 *   <li>{@link #start} → state=STARTING → READY (成功) 或 FAILED (失败)</li>
 *   <li>{@link #initialize} 前调 sendRequest → IllegalStateException</li>
 *   <li>{@link #stop} 后 start() → 重新 STARTING (允许重启)</li>
 * </ul>
 */
@Component
public class InMemoryLspClient implements LspClient {

    private static final Logger log = LoggerFactory.getLogger(InMemoryLspClient.class);

    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);
    private Map<String, Object> capabilitiesValue = null;
    private LspInitializeResult initResult = null;

    enum State { NOT_STARTED, STARTING, READY, FAILED, STOPPED }

    @Override
    public Map<String, Object> capabilities() {
        return capabilitiesValue;
    }

    @Override
    public boolean isInitialized() {
        return initResult != null;
    }

    @Override
    public void start(String command, String[] args, Map<String, String> env, String cwd) {
        Objects.requireNonNull(command, "command must not be null");
        state.set(State.STARTING);
        log.info("[InMemoryLspClient] start cmd={} args={} cwd={}", command, java.util.Arrays.toString(args), cwd);
        // 模拟启动: 空 command 视为启动失败 (L2 契约)
        if (command.isEmpty()) {
            state.set(State.FAILED);
            throw new IllegalStateException("LSP server failed to start: empty command");
        }
        state.set(State.READY);
    }

    @Override
    public LspInitializeResult initialize(String rootUri) {
        if (state.get() != State.READY) {
            throw new IllegalStateException("LSP server not READY (state=" + state.get() + ")");
        }
        Map<String, Object> caps = new HashMap<>();
        caps.put("definitionProvider", true);
        caps.put("hoverProvider", true);
        caps.put("referencesProvider", true);
        this.capabilitiesValue = Map.copyOf(caps);
        this.initResult = new LspInitializeResult(
            "2024-11-05",
            this.capabilitiesValue,
            new LspServerInfo("in-memory-lsp", "1.0.0")
        );
        log.info("[InMemoryLspClient] initialized rootUri={} caps={}", rootUri, caps.keySet());
        return this.initResult;
    }

    @Override
    public <T> T sendRequest(String method, Object params, Class<T> resultType) {
        if (!isInitialized()) {
            throw new IllegalStateException("LSP server not initialized; call initialize() first");
        }
        log.debug("[InMemoryLspClient] sendRequest method={} (in-memory stub)", method);
        return null;
    }

    @Override
    public void sendNotification(String method, Object params) {
        if (!isInitialized()) {
            throw new IllegalStateException("LSP server not initialized; call initialize() first");
        }
        log.debug("[InMemoryLspClient] sendNotification method={}", method);
    }

    @Override
    public void stop() {
        state.set(State.STOPPED);
        log.info("[InMemoryLspClient] stopped");
    }

    /** 测试用: 当前内部状态. */
    public State getState() { return state.get(); }
}