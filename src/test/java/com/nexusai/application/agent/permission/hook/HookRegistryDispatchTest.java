package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H4] HookRegistry 配置驱动 hook 分发 + 并行执行 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks.ts:2142-2744} executeHooks
 * (每类型独立 executor 分支 + {@code all(hookPromises)} 并行).
 *
 * <p>WHY (规则九 · 测试验证意图): H4 之前 HookRegistry 只接线 CommandHook, PromptHook/
 * AgentHook/HttpHook "留后续 session" (即配置了但永不执行 = dispatch 断裂的根因). 本测试
 * 验证 4 类型 dispatch + 并行语义:
 * <ul>
 *   <li>4 类型各自被分发到对应 Exec*Hook (配置了就必须执行, 否则 = 断裂)</li>
 *   <li>多个 hook 并行 (allOf, 对齐 CC hooks.ts:2744) — 串行会无重叠时间窗</li>
 *   <li>单 hook 失败不阻塞其他 (CC :2698 catch → non_blocking_error)</li>
 * </ul>
 *
 * <p><b>测试基建</b>:
 * <ul>
 *   <li>{@link StubMatcherEngine}: 覆写 {@code getMatchingHooks} 返回可配置的 MatchedHook 列表,
 *       不依赖 HooksConfigSnapshot 快照 (dispatch 测试聚焦分发, 匹配引擎由 HookMatcherEngineTest 覆盖)</li>
 *   <li>CommandHook 用 {@link FakeLauncher}/{@link FakeHookProcess} 内存 fake 进程 (镜像
 *       CommandHookExecutorTest), 不依赖真实 Git Bash</li>
 *   <li>PromptHook 用匿名 {@link LlmProvider} (返回 fixed JSON), 镜像 R33H2_ExecPromptHookTest</li>
 *   <li>AgentHook 用 {@link RecordingExecAgentHook} 覆写 exec 记录调用 (H7 全量行为由
 *       R33H7_ExecAgentHookTest 覆盖, 本测试只验证 dispatch 接线)</li>
 *   <li>HttpHook 用 JDK {@link HttpServer} 本地 127.0.0.1 (SsrfGuard 放行 loopback)</li>
 *   <li>并行测试用 recording executors 记录 start/end 时间戳, 断言区间重叠</li>
 * </ul>
 *
 * @since Session H4 (P2)
 */
@DisplayName("[H4] HookRegistry 配置驱动 hook 4 类型分发 + 并行执行对齐 CC executeHooks")
class HookRegistryDispatchTest {

    private static final String DEFAULT_FAST_MODEL = "haiku-test";
    private static final String SESSION_ID = "sess-1";
    private static final String AGENT_ID = "agent-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ════════════════════════════════════════════════════════════════════════
    // 测试基建
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 可配置的 matcher 引擎 · WHY: HookRegistry.getMatchingHooks 委托 HookMatcherEngine,
     * 本 stub 直接返回预设 MatchedHook 列表, 隔离"匹配"与"分发"两个关注点.
     */
    static class StubMatcherEngine extends HookMatcherEngine {
        volatile List<MatchedHook> hooks = List.of();

        StubMatcherEngine() {
            super(null, null); // 覆写 getMatchingHooks, 构造参数不被使用
        }

        void setHooks(List<MatchedHook> hooks) {
            this.hooks = hooks;
        }

        @Override
        public List<MatchedHook> getMatchingHooks(HookEvent event) {
            return hooks;
        }
    }

    /** 内存 fake 进程 · WHY: 不依赖真实 Git Bash/pwsh (Windows CI 可能未装). */
    static class FakeHookProcess implements CommandHookExecutor.HookProcess {
        final ByteArrayOutputStream stdinCapture = new ByteArrayOutputStream();
        final InputStream stdoutIn;
        final InputStream stderrIn;
        final int exitCode;

        FakeHookProcess(String stdout, String stderr, int exitCode) {
            this.stdoutIn = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderrIn = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
        }

        @Override public OutputStream stdin() { return stdinCapture; }
        @Override public InputStream stdout() { return stdoutIn; }
        @Override public InputStream stderr() { return stderrIn; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public void destroyForcibly() { }
        @Override public int exitValue() { return exitCode; }
    }

    /** fake launcher · 记录 lastSpec 证明 CommandHookExecutor.execute 被调.
     *  [S4 协调] 构造参数放宽为 HookProcess (HookRegistrySessionEndTest 复用, S5 测试). */
    static class FakeLauncher implements CommandHookExecutor.ProcessLauncher {
        final CommandHookExecutor.HookProcess process;
        CommandHookExecutor.ProcessSpec lastSpec;

        FakeLauncher(CommandHookExecutor.HookProcess process) { this.process = process; }

        @Override
        public CommandHookExecutor.HookProcess launch(CommandHookExecutor.ProcessSpec spec) throws IOException {
            this.lastSpec = spec;
            return process;
        }
    }

    /** 共享时间窗 recorder · WHY: 并行测试断言各 hook 区间有重叠, 串行则无重叠. */
    static class Recorder {
        final List<long[]> spans = Collections.synchronizedList(new ArrayList<>());

        void sleepRecord(long sleepMs) {
            long s = System.currentTimeMillis();
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long e = System.currentTimeMillis();
            spans.add(new long[]{s, e});
        }
    }

    /**
     * [RV-FOLLOWUP DEDUP-01] stub ModelConfigResolver · 让配置 prompt hook 走真实 provider 解析
     * （resolvePromptProvider 已薄委托 ModelConfigResolver，镜像生产单一解析来源）。
     * 解析任意非空模型名 → 可用 (config, openai_compatible)（对齐旧 StubProviderService 语义）。
     */
    static class StubModelConfigResolver extends ModelConfigResolver {
        @Override
        public com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolve(String modelName) {
            if (modelName == null || modelName.isBlank()) {
                return null;
            }
            return new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(
                new ProviderConfig("https://llm.example.com", "sk-test-123"), "openai_compatible");
        }
    }

    /** recording CommandHookExecutor · 覆写 execute 记录时间窗并返回 proceed. */
    static class RecordingCommandHookExecutor extends CommandHookExecutor {
        final Recorder recorder;

        RecordingCommandHookExecutor(Recorder recorder) {
            super(null, null, null, null, null);
            this.recorder = recorder;
        }

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                         String pluginRoot, String pluginId, String skillRoot,
                                         Integer hookIndex, boolean forceSyncExecution,
                                         com.nexusai.application.agent.tool.AbortController parentAbort) {
            recorder.sleepRecord(300);
            return new CommandHookResult("", "", "", 0, false, false);
        }

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                         String pluginRoot, String pluginId, String skillRoot,
                                         Integer hookIndex, boolean forceSyncExecution,
                                         com.nexusai.application.agent.tool.AbortController parentAbort,
                                         long defaultTimeoutMs, String hookCwd) {
            // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
            return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                hookIndex, forceSyncExecution, parentAbort);
        }
    }

    /** recording ExecPromptHook · 覆写 exec 记录时间窗并返回 proceed. */
    static class RecordingExecPromptHook extends ExecPromptHook {
        final Recorder recorder;

        RecordingExecPromptHook(Recorder recorder) {
            super(new ObjectMapper());
            this.recorder = recorder;
        }

        @Override
        public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                               String jsonInput, PromptLlmContext llmContext, AbortController parentAbort) {
            // [H2/CCJ-EXEC-01] 6 参重载委托 7 参（messages=null）——分发层现调 7 参版本，
            //   仅覆写 6 参会绕过记录器（父类委托直通真实实现）
            return exec(hook, hookName, hookEvent, jsonInput, llmContext, parentAbort, null);
        }

        @Override
        public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                               String jsonInput, PromptLlmContext llmContext, AbortController parentAbort,
                               java.util.List<com.nexusai.model.session.dto.ChatMessageDto> messages) {
            recorder.sleepRecord(300);
            return HookResult.proceed();
        }
    }

    /** recording ExecHttpHook · 覆写 exec 记录时间窗并返回 proceed. */
    static class RecordingExecHttpHook extends ExecHttpHook {
        final Recorder recorder;

        RecordingExecHttpHook(Recorder recorder) {
            super(new HooksSettings(key -> null), new SsrfGuard());
            this.recorder = recorder;
        }

        @Override
        public HttpHookResult exec(HttpHook hook, String hookName, HookEvent hookEvent, String jsonInput,
                                   AbortController parentAbort) {
            recorder.sleepRecord(300);
            // [H7] HttpHookResult 增加 hookId 字段 (对齐 CC hooks.ts:2199 randomUUID)
            return new HttpHookResult(true, 200, "", null, false);
        }
    }

    /** recording ExecAgentHook · 覆写 exec 记录调用 (H7 全量行为由 R33H7 覆盖). */
    static class RecordingExecAgentHook extends ExecAgentHook {
        final AtomicInteger calls = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<String> lastAgentName =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<ToolPermissionContext> lastParentPermCtx =
            new java.util.concurrent.atomic.AtomicReference<>();

        RecordingExecAgentHook() {
            // [H13] 构造签名新增 telemetry 参数
            // [EX-C ?-EX-06] 构造签名新增 providerService/llmProviderFactory（null = 未接线）
            super(new ObjectMapper(), null, new ToolRegistry(), null, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null, null, null);
        }

        @Override
        public HookResult exec(AgentHook hook, String hookName, HookEvent hookEvent, String jsonInput,
                               String transcriptPath, AbortController parentAbort, String sessionId,
                               String agentName, ToolPermissionContext parentPermCtx) {
            calls.incrementAndGet();
            lastAgentName.set(agentName);
            lastParentPermCtx.set(parentPermCtx);
            return HookResult.proceed();
        }
    }

    /** 匿名 LlmProvider · chat 返回 fixed JSON, 记录调用次数 (镜像 R33H2). */
    private LlmProvider chatProvider(String response, AtomicInteger calls) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig config, String modelName,
                                         List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                                         List<ChatMessageDto> history, ArrayNode tools,
                                         Integer maxOutputTokensOverride,
                                         com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                                         String effortValue, String querySource,
                                         java.util.function.Consumer<String> onChunk,
                                         java.util.function.Consumer<AssistantMessage> onAssistantMessage,
                                         java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> onToolCallComplete,
                                         java.util.function.Consumer<String> onReasoningChunk,
                                         Runnable onStreamingFallback,
                                         AbortController abortController,
                                         java.util.function.Consumer<Throwable> onError,
                                         Runnable onComplete) {
                throw new UnsupportedOperationException();
            }
            @Override public String chat(ProviderConfig config, String modelName, String systemPrompt, String userMessage) {
                if (calls != null) {
                    calls.incrementAndGet();
                }
                return response;
            }
        };
    }

    private static MatchedHook matched(HookCommand hook) {
        return new MatchedHook(hook, null, null, null, "settings");
    }

    private static CommandHook commandHook(String command) {
        return new CommandHook(command, null, null, null, null, null, null, null);
    }

    private static PromptHook promptHook(String prompt) {
        // [IMPL-05] model 显式指定（CC execPromptHook.ts:79 hook.model ?? getSmallFastModel()）
        return new PromptHook(prompt, null, null, DEFAULT_FAST_MODEL, null, null);
    }

    private static AgentHook agentHook(String prompt) {
        return new AgentHook(prompt, null, null, null, null, null);
    }

    private static HttpHook httpHook(String url) {
        return new HttpHook(url, null, null, null, null, null, null);
    }

    private static HookEvent userPrompt() {
        return HookEvent.userPromptSubmit(SESSION_ID, AGENT_ID);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1-4. 正向: 4 类型 dispatch
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: H2 已接线 CommandHook, 本测试证明 dispatch 改造后 command 类型仍被路由到
     * CommandHookExecutor (回归保护). FakeLauncher.lastSpec != null = execute 被调.
     */
    @Test
    @DisplayName("command 类型 → CommandHookExecutor 被调 (H2 接线回归)")
    void commandType_routesToCommandHookExecutor() {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(matched(commandHook("echo hi"))));
        FakeLauncher launcher = new FakeLauncher(new FakeHookProcess("", "", 0));
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(new CommandHookExecutor(launcher, null, null, null, null));

        HookResult result = registry.executeEvent(userPrompt());

        assertThat(launcher.lastSpec).isNotNull(); // execute 被调
        assertThat(result.preventContinuation()).isFalse(); // exit 0 空 stdout → proceed
    }

    /**
     * WHY: prompt 类型配置了但 H4 前永不执行 = dispatch 断裂. 本测试证明配置的 PromptHook
     * 被分发到 ExecPromptHook, 且 LLM provider 被真实调用 (mock LlmProvider 返回 fixed JSON).
     * providerCalls==1 = exec 内 chat 被调; outcome=SUCCESS = {"ok":true} 解析生效.
     */
    @Test
    @DisplayName("prompt 类型 → ExecPromptHook 被调 (mock LlmProvider 返回 fixed JSON)")
    void promptType_routesToExecPromptHook_withMockLlmProvider() {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(matched(promptHook("check $ARGUMENTS"))));
        AtomicInteger providerCalls = new AtomicInteger();
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                return chatProvider("{\"ok\": true}", providerCalls);
            }
        };
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setExecPromptHook(new ExecPromptHook(objectMapper));
        registry.setModelConfigResolver(new StubModelConfigResolver());
        registry.setLlmProviderFactory(factory);

        HookResult result = registry.executeEvent(userPrompt());

        assertThat(providerCalls.get()).isEqualTo(1); // LLM 被调 → ExecPromptHook.exec 被调
        assertThat(result.outcome()).isEqualTo(HookOutcome.SUCCESS);
    }

    /**
     * WHY: agent 类型配置了但 H4 前永不执行 = dispatch 断裂. 本测试证明配置的 AgentHook
     * 被分发到 ExecAgentHook (recording subclass 记录 exec 调用). H7 全量 exec 行为由
     * R33H7_ExecAgentHookTest 覆盖, 此处只验证"被调".
     */
    @Test
    @DisplayName("agent 类型 → ExecAgentHook 被调")
    void agentType_routesToExecAgentHook() {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(matched(agentHook("verify tests"))));
        RecordingExecAgentHook execAgentHook = new RecordingExecAgentHook();
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setExecAgentHook(execAgentHook);

        HookResult result = registry.executeEvent(userPrompt());

        assertThat(execAgentHook.calls.get()).isEqualTo(1); // ExecAgentHook.exec 被调
        assertThat(result.preventContinuation()).isFalse();
    }

    /**
     * WHY (对抗核验 H13-GAP): CC hooks.ts:2283-2286 把 hookInput.agent_type 传给 execAgentHook
     * 的 agentName（写入 tengu_agent_stop_hook_* analytics 载荷）。旧 Java executeConfiguredAgent
     * 恒传 null —— agentName analytics 在生产无实际载荷。HookEvent.data 的 agent_type 即
     * buildJsonInput 注入的 hookInput.agent_type（CommandHookExecutor.buildJsonInput 逐项透传 data）。
     * 本测试证明分发层从 event.data.agent_type 读到 agentName 并传入 exec。
     */
    @Test
    @DisplayName("agent 类型 → agentName 从 event.data.agent_type 透传（对抗核验 H13-GAP）")
    void agentType_agentName_readsFromEventData() {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(matched(agentHook("verify tests"))));
        RecordingExecAgentHook execAgentHook = new RecordingExecAgentHook();
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setExecAgentHook(execAgentHook);

        // CC hookInput.agent_type = 'subagent-1'（STOP 事件 data 注入, 对齐 buildJsonInput 透传）
        // [IMP-CF-01] data 改类型化 record：STOP 载荷 {stop_hook_active, agent_type}
        HookEvent event = new HookEvent(HookEventType.STOP, SESSION_ID, null, null, null, AGENT_ID,
            null, null, null, null, null, null, new HookEventData.Stop(false, null, "subagent-1"), 0);

        registry.executeEvent(event);

        assertThat(execAgentHook.calls.get()).isEqualTo(1);
        assertThat(execAgentHook.lastAgentName.get()).isEqualTo("subagent-1");
    }

    /**
     * WHY (对抗核验 H13-GAP): event.data 无 agent_type 时须安全降级为 null（不抛错）, 对齐 CC
     * hooks.ts:2285 {@code 'agent_type' in hookInput ? hookInput.agent_type : undefined}。
     */
    @Test
    @DisplayName("agent 类型 → agentName 缺省降级 null（CC 'agent_type' in hookInput 判别）")
    void agentType_agentName_absentFallsBackToNull() {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(matched(agentHook("verify tests"))));
        RecordingExecAgentHook execAgentHook = new RecordingExecAgentHook();
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setExecAgentHook(execAgentHook);

        registry.executeEvent(userPrompt()); // data 无 agent_type

        assertThat(execAgentHook.calls.get()).isEqualTo(1);
        assertThat(execAgentHook.lastAgentName.get()).isNull();
    }

    /**
     * WHY (对抗核验 H13-GAP-1 v3): CC execAgentHook.ts:141-153 getAppState() override 继承父
     * alwaysAllowRules。Java executeConfiguredAgent 旧实现无父 permission context 透传 ——
     * hook agent 空规则集 + DONT_ASK 会拒绝全部工具。本测试证明 executeEvent(event, messages, parentTuc)
     * 沿分发链把父 permCtx 透传到 ExecAgentHook.exec。
     */
    @Test
    @DisplayName("agent 类型 → executeEvent(parentTuc) 把父 permCtx 透传到 exec（对抗核验 H13-GAP-1）")
    void agentType_parentPermCtx_threadsToExec() {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(matched(agentHook("verify tests"))));
        RecordingExecAgentHook execAgentHook = new RecordingExecAgentHook();
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setExecAgentHook(execAgentHook);

        // 父 per-turn TUC 携带最新 permCtx（父规则）
        ToolPermissionContext parentPermCtx = ToolPermissionContext.strict(
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT);
        com.nexusai.application.agent.tool.ToolUseContext parentTuc =
            com.nexusai.application.agent.tool.ToolUseContext.of(
                java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
                java.util.List.of(), "", AbortController.NOOP, java.util.List.of(),
                parentPermCtx, com.nexusai.application.agent.permission.PermissionMode.DEFAULT);

        registry.executeEvent(userPrompt(), null, parentTuc);

        assertThat(execAgentHook.calls.get()).isEqualTo(1);
        assertThat(execAgentHook.lastParentPermCtx.get()).isSameAs(parentPermCtx);
    }

    /**
     * WHY: http 类型配置了但 H4 前永不执行 = dispatch 断裂. 本测试证明配置的 HttpHook
     * 被分发到 ExecHttpHook (真实 HTTP 请求发出, 本地 HttpServer 记录命中).
     * serverHits==1 = exec 被调并 POST.
     */
    @Test
    @DisplayName("http 类型 → ExecHttpHook 被调 (本地 HttpServer 收到请求)")
    void httpType_routesToExecHttpHook() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger serverHits = new AtomicInteger();
        server.createContext("/", exchange -> {
            serverHits.incrementAndGet();
            byte[] resp = "{\"continue\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            StubMatcherEngine engine = new StubMatcherEngine();
            engine.setHooks(List.of(matched(httpHook(url))));
            HookRegistry registry = new HookRegistry();
            registry.setHookMatcherEngine(engine);
            registry.setExecHttpHook(new ExecHttpHook(new HooksSettings(key -> null), new SsrfGuard()));

            HookResult result = registry.executeEvent(userPrompt());

            assertThat(serverHits.get()).isEqualTo(1); // HTTP 请求发出 → ExecHttpHook.exec 被调
            assertThat(result.preventContinuation()).isFalse(); // 2xx → ok=true → proceed
        } finally {
            server.stop(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. 正向: 并行执行
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九): CC all(hookPromises) (hooks.ts:2744) 让全部 hook 并行.
     * 若 Java 端串行 for 循环, 三个 300ms hook 的区间必然无重叠 (首尾相接);
     * 并行则三个区间共享重叠窗口. 断言 {@code max(start) < min(end)} = 存在共同时间点,
     * 直接证明 allOf 并行语义, 而非靠总耗时猜测.
     */
    @Test
    @DisplayName("多个 hook 并行执行 (时间窗重叠, 对齐 CC all(hookPromises) hooks.ts:2744)")
    void multipleHooks_runInParallel_withOverlappingTimeWindows() {
        Recorder recorder = new Recorder();
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(
            matched(commandHook("sleep-cmd")),
            matched(promptHook("sleep-prompt")),
            matched(httpHook("http://sleep-http.example.com"))
        ));
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                return chatProvider("{\"ok\": true}", null);
            }
        };
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(new RecordingCommandHookExecutor(recorder));
        registry.setExecPromptHook(new RecordingExecPromptHook(recorder));
        registry.setModelConfigResolver(new StubModelConfigResolver());
        registry.setLlmProviderFactory(factory);
        registry.setExecHttpHook(new RecordingExecHttpHook(recorder));

        registry.executeEvent(userPrompt());

        assertThat(recorder.spans).hasSize(3);
        long maxStart = recorder.spans.stream().mapToLong(p -> p[0]).max().orElseThrow();
        long minEnd = recorder.spans.stream().mapToLong(p -> p[1]).min().orElseThrow();
        assertThat(maxStart).isLessThan(minEnd); // 三区间有重叠 → 并行
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. 反向: 跳过不抛
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九): 反向验证 "配置了 hook 但对应 executor 未接线" 必须静默跳过而非抛异常
     * (不破坏现有路径). 说明: {@link HookCommand} 是 sealed interface 只 permits 4 类型,
     * 真实"未知类型"无法构造, 本测试用"command 类型但 commandHookExecutor 未注入"验证
     * dispatch 的防御性 skip 路径 — 等价覆盖"dispatch 遇到无法执行的 hook 时跳过不抛"的意图.
     */
    @Test
    @DisplayName("无法执行的 hook (executor 未接线) → 跳过不抛")
    void unavailableExecutor_isSkippedWithoutThrowing() {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(matched(commandHook("echo hi"))));
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        // 注意: 不注入 commandHookExecutor → executeConfiguredCommand 返回 proceed

        HookResult result = registry.executeEvent(userPrompt());

        assertThat(result).isNotNull();
        assertThat(result.preventContinuation()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 7. 边界: 单 hook 失败不阻塞其他
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九): CC executeHooks 每个 hook 独立 catch (hooks.ts:2698-2729) →
     * non_blocking_error, 一个 hook 抛错不阻断同批其他 hook. Java 端 allOf 语义:
     * command executor 抛异常被 {@code executeOneConfiguredHook} 隔离为 proceed,
     * prompt hook 的 blocking 结果必须仍生效 — 否则并行化会引入"单点失败拖垮全部"的回归.
     */
    @Test
    @DisplayName("单 hook 失败不阻塞其他 (allOf 语义, 对齐 CC :2698 catch)")
    void singleHookFailure_doesNotBlockOtherHooks() {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(
            matched(commandHook("explode-cmd")),
            matched(promptHook("gate-check"))
        ));
        // command executor 抛异常
        CommandHookExecutor explodingCommand = new CommandHookExecutor(null, null, null, null, null) {
            @Override
            public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                             String pluginRoot, String pluginId, String skillRoot,
                                             Integer hookIndex, boolean forceSyncExecution,
                                             com.nexusai.application.agent.tool.AbortController parentAbort) {
                throw new RuntimeException("command exploded");
            }
            @Override
            public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                             String pluginRoot, String pluginId, String skillRoot,
                                             Integer hookIndex, boolean forceSyncExecution,
                                             com.nexusai.application.agent.tool.AbortController parentAbort,
                                             long defaultTimeoutMs, String hookCwd) {
                // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
                return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                    hookIndex, forceSyncExecution, parentAbort);
            }
        };
        // prompt: 真实 ExecPromptHook + fake provider 返回 ok=false → BLOCKING
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                return chatProvider("{\"ok\": false, \"reason\": \"gate blocked\"}", null);
            }
        };
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setModelConfigResolver(new StubModelConfigResolver());
        registry.setExecPromptHook(new ExecPromptHook(objectMapper));
        registry.setLlmProviderFactory(factory);

        HookResult result = registry.executeEvent(userPrompt());

        // command 抛异常被隔离 → prompt hook 的 blocking 结果仍生效
        assertThat(result.preventContinuation()).isTrue();
        assertThat(result.stopReason()).contains("gate blocked");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 8. programmatic hook 并行化 (H4 补充任务 · 对齐 CC all(hookPromises))
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九): H4 配置驱动 hook 已并行 (allOf), 但 programmatic PreToolUse hook 仍是串行
     * supplyAsync(...).get(...). CC 真源 executePreToolHooks (hooks.ts:3394-3444) = yield*
     * executeHooks = all(hookPromises) (hooks.ts:2744) 并行, 无串行路径. 本测试验证 programmatic
     * PreToolUse hook 并行化: 三个 300ms hook 若串行则首尾相接无重叠 (max(start) >= min(end));
     * 并行则共享重叠窗口 (max(start) < min(end)), 直接证明 allOf 语义.
     */
    @Test
    @DisplayName("programmatic PreToolUse 多个 hook 并行执行 (时间窗重叠, 对齐 CC all(hookPromises) hooks.ts:2744)")
    void programmaticPreToolUse_hooksRunInParallel_withOverlappingTimeWindows() {
        Recorder recorder = new Recorder();
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("pre-a", (toolName, in, ctx) -> {
            recorder.sleepRecord(300);
            return AggregatedHookResult.proceed();
        });
        registry.registerPreToolUse("pre-b", (toolName, in, ctx) -> {
            recorder.sleepRecord(300);
            return AggregatedHookResult.proceed();
        });
        registry.registerPreToolUse("pre-c", (toolName, in, ctx) -> {
            recorder.sleepRecord(300);
            return AggregatedHookResult.proceed();
        });

        registry.executePreToolUse("Bash", objectMapper.createObjectNode(), null,
            "tu-par-1");

        assertThat(recorder.spans).hasSize(3);
        long maxStart = recorder.spans.stream().mapToLong(p -> p[0]).max().orElseThrow();
        long minEnd = recorder.spans.stream().mapToLong(p -> p[1]).min().orElseThrow();
        assertThat(maxStart)
            .as("三个 PreToolUse hook 区间共享重叠窗口 → 并行 (串行则 max(start) >= min(end))")
            .isLessThan(minEnd);
    }

    /**
     * WHY (规则九): CC executeHooks 每 hook 独立 catch (hooks.ts:2698-2729) → 单 hook 抛错不阻断
     * 同批其他 hook. programmatic generic hook 并行化后, 单 hook 失败必须在 future 内隔离
     * (返回 null), 另一个 hook 的 stop 结果必须仍生效 — 否则并行化引入"单点失败拖垮全部"回归.
     */
    @Test
    @DisplayName("programmatic generic hook 单 hook 失败不阻塞其他 (allOf 语义, 对齐 CC :2698 catch)")
    void programmaticGenericHook_singleFailure_doesNotBlockOthers() {
        HookRegistry registry = new HookRegistry();
        registry.register("explode", event -> {
            throw new RuntimeException("generic exploded");
        }, HookEventType.USER_PROMPT_SUBMIT);
        registry.register("gate", event -> GenericHook.HookResult.stop("gate-blocked"),
            HookEventType.USER_PROMPT_SUBMIT);

        GenericHook.HookResult result = registry.executeEvent(userPrompt());

        assertThat(result.preventContinuation())
            .as("explode hook 失败被隔离 → gate hook 的 stop 结果仍生效")
            .isTrue();
        assertThat(result.stopReason()).isEqualTo("gate-blocked");
    }

    /**
     * WHY (规则九): PreToolUse 并行化同样必须隔离单 hook 异常 — 一个 hook 抛错不能吞掉另一个
     * hook 的 preventContinuation 决策 (16 字段聚合逐字段不变, 对齐 CC toolHooks.ts:435-461).
     */
    @Test
    @DisplayName("programmatic PreToolUse 单 hook 失败不阻塞其他 (异常隔离, 对齐 CC :2698 catch)")
    void programmaticPreToolUse_singleHookFailure_doesNotBlockOthers() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("explode", (toolName, in, ctx) -> {
            throw new RuntimeException("pre exploded");
        });
        registry.registerPreToolUse("gate", (toolName, in, ctx) ->
            new AggregatedHookResult(null, null, true, "pre-blocked",
                null, null, null, null, null, null, null, null, null, null, null, null));

        AggregatedHookResult result = registry.executePreToolUse(
            "Bash", objectMapper.createObjectNode(), null, "tu-par-2");

        assertThat(result.preventContinuation())
            .as("explode hook 失败被隔离 → gate hook 的 preventContinuation 决策仍生效")
            .isTrue();
        assertThat(result.stopReason()).isEqualTo("pre-blocked");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 9. [S4 G07] outside-REPL 事件 prompt/agent hook 拒绝 (CC :3152-3170)
    // ════════════════════════════════════════════════════════════════════════

    /** 计数 ExecPromptHook · 覆写 7 参 exec (分发层现调 7 参版本). */
    static class CountingExecPromptHook extends ExecPromptHook {
        final AtomicInteger calls = new AtomicInteger();

        CountingExecPromptHook() {
            super(new ObjectMapper());
        }

        @Override
        public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                               String jsonInput, PromptLlmContext llmContext, AbortController parentAbort) {
            return exec(hook, hookName, hookEvent, jsonInput, llmContext, parentAbort, null);
        }

        @Override
        public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                               String jsonInput, PromptLlmContext llmContext, AbortController parentAbort,
                               java.util.List<com.nexusai.model.session.dto.ChatMessageDto> messages) {
            calls.incrementAndGet();
            return HookResult.proceed();
        }
    }

    /** 计数 ExecAgentHook · 覆写 exec 记录调用. */
    static class CountingExecAgentHook extends ExecAgentHook {
        final AtomicInteger calls = new AtomicInteger();

        CountingExecAgentHook() {
            super(new ObjectMapper(), null, new ToolRegistry(), null, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null, null, null);
        }

        @Override
        public HookResult exec(AgentHook hook, String hookName, HookEvent hookEvent, String jsonInput,
                               String transcriptPath, AbortController parentAbort, String sessionId,
                               String agentName, ToolPermissionContext parentPermCtx) {
            calls.incrementAndGet();
            return HookResult.proceed();
        }
    }

    /** 计数 CommandHookExecutor · 覆写 execute 记录调用 (G07 应不影响 command hook). */
    static class CountingCommandExecutor extends CommandHookExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                         String jsonInput, String pluginRoot, String pluginId,
                                         String skillRoot, Integer hookIndex,
                                         boolean forceSyncExecution,
                                         com.nexusai.application.agent.tool.AbortController parentAbort) {
            calls.incrementAndGet();
            return new CommandHookResult("", "", "", 0, false, false);
        }

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                         String jsonInput, String pluginRoot, String pluginId,
                                         String skillRoot, Integer hookIndex,
                                         boolean forceSyncExecution,
                                         com.nexusai.application.agent.tool.AbortController parentAbort,
                                         long defaultTimeoutMs, String hookCwd) {
            // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
            return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                hookIndex, forceSyncExecution, parentAbort);
        }
    }

    private HookRegistry registryWith(MatchedHook... hooks) {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(hooks));
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        return registry;
    }

    private static MatchedHook promptHookMatched(String prompt) {
        // [FINAL-SETTLE-T1] pluginRoot 置 null —— Java 插件 hook 仅能声明 CommandHook
        //   （PluginLoader.parsePluginHookCommand 恒返回 CommandHook），prompt hook 只可能是
        //   配置驱动（settings/userSettings 源）；原 "plugin-x" 会触发 IMP-HR-02 统一单链的
        //   插件源跳过（executeConfiguredHooks:4274-4284 → 走 genericHooks 链）导致本用例
        //   G07 拒绝结果丢失（results 空）。置 null 后走 executeConfiguredHooks → G07 门禁。
        return new MatchedHook(new PromptHook(prompt, null, null, "model-x", null, null),
            null, null, null, "userSettings");
    }

    private static MatchedHook agentHookMatched(String prompt) {
        // [FINAL-SETTLE-T1] pluginRoot 置 null —— 同 promptHookMatched：agent hook 只可能是
        //   配置驱动源（Java 插件无法声明 agent hook），原 "plugin-y" 触发插件源跳过。
        return new MatchedHook(new AgentHook(prompt, null, null, "model-y", null, null),
            null, null, null, "userSettings");
    }

    private static MatchedHook commandHookMatched(String command) {
        return new MatchedHook(new CommandHook(command, null, null, null, null, null, null, null),
            null, null, null, "userSettings");
    }

    private static HookEvent worktreeCreateEvent(String sessionId, String name) {
        return new HookEvent(HookEventType.WORKTREE_CREATE, sessionId, null, null, null, null,
            null, null, null, null, null, null, new HookEventData.WorktreeCreate(name), 0);
    }

    /**
     * WHY (规则九): CC executeHooksOutsideREPL :3152-3170 — prompt/agent stop hooks 在
     * outside-REPL 事件 (Notification/SessionEnd/WorktreeCreate/PreCompact 等 13 事件) 下
     * 直接返回 succeeded:false ('...not yet supported outside REPL'), 不执行模型调用.
     * Java 旧实现 executeEventAll 对 prompt/agent 一视同仁执行 → 模型调用被浪费 + 语义偏离.
     */
    @Test
    @DisplayName("G07 Notification 事件 PromptHook → 0 执行 + NON_BLOCKING_ERROR 失败结果 (CC :3152-3170)")
    void outsideRepl_notification_promptHookRejected() {
        CountingExecPromptHook prompt = new CountingExecPromptHook();
        HookRegistry registry = registryWith(promptHookMatched("You are a prompt hook"));
        registry.setExecPromptHook(prompt);

        List<GenericHook.HookResult> results = registry.executeEventAll(
            HookEvent.notification("s-1", null, "msg", "t", "info"));

        assertThat(prompt.calls.get()).as("outside-REPL prompt hook 不得执行 (CC succeeded:false)").isZero();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(results.get(0).message()).as("CC succeeded:false 无 attachment").isNull();
    }

    @Test
    @DisplayName("G07 SessionEnd 事件 AgentHook → 0 执行 + 失败结果 (CC :3162-3170)")
    void outsideRepl_sessionEnd_agentHookRejected() {
        CountingExecAgentHook agent = new CountingExecAgentHook();
        HookRegistry registry = registryWith(agentHookMatched("You are an agent hook"));
        registry.setExecAgentHook(agent);

        List<GenericHook.HookResult> results = registry.executeEventAll(HookEvent.sessionEnd("s-1", null, null));

        assertThat(agent.calls.get()).as("outside-REPL agent hook 不得执行 (CC succeeded:false)").isZero();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
        assertThat(results.get(0).message()).isNull();
    }
    @Test
    @DisplayName("G07 WorktreeCreate 事件 PromptHook → 拒绝; command hook 不受影响 (仍执行)")
    void outsideRepl_worktreeCreate_promptRejected_commandRuns() {
        CountingExecPromptHook prompt = new CountingExecPromptHook();
        CountingCommandExecutor command = new CountingCommandExecutor();
        HookRegistry registry = registryWith(
            promptHookMatched("You are a prompt hook"),
            commandHookMatched("echo wt"));
        registry.setExecPromptHook(prompt);
        registry.setCommandHookExecutor(command);

        List<GenericHook.HookResult> results = registry.executeEventAll(worktreeCreateEvent("s-1", "wt-1"));

        assertThat(prompt.calls.get()).as("outside-REPL prompt hook 不得执行").isZero();
        assertThat(command.calls.get()).as("G07 仅拒绝 prompt/agent, command hook 正常执行").isEqualTo(1);
        assertThat(results).hasSize(2);
        assertThat(results).anySatisfy(r ->
            assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR));
    }

    /**
     * WHY (回归守卫): G07 拒绝集 = outside-REPL 13 事件, Stop 属 REPL (CC executeHooks,
     * hooks.ts:3688) — prompt stop hook 必须仍执行, 禁止按入口方法 (executeEventAll) 一刀切.
     */
    @Test
    @DisplayName("G07 回归守卫: Stop 事件 prompt hook 仍执行 (REPL 事件, CC executeHooks)")
    void replEvent_stop_promptHookStillRuns() {
        CountingExecPromptHook prompt = new CountingExecPromptHook();
        HookRegistry registry = registryWith(promptHookMatched("You are a prompt hook"));
        registry.setExecPromptHook(prompt);
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                // resolvePromptProvider 会先调 getProvider 再进 exec — 返回 provider 即可,
                // 计数由 CountingExecPromptHook.exec 承担 (provider 的 chat 不会被调).
                return chatProvider("{\"ok\": true}", new AtomicInteger());
            }
        };
        registry.setModelConfigResolver(new StubModelConfigResolver());
        registry.setLlmProviderFactory(factory);

        registry.executeEventAll(HookEvent.stop("s-1", null, true, "bye"), null, null);

        assertThat(prompt.calls.get()).as("Stop 事件 prompt hook 必须执行 (REPL 事件)").isEqualTo(1);
    }
}
