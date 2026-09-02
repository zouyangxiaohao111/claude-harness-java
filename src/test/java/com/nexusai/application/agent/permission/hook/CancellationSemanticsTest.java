package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.MockLlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-06] 取消语义测试 · 对齐 CC 真源:
 * combinedAbortSignal 三处消费 execPromptHook.ts:73 / execAgentHook.ts:81-85 / hooks.ts:2306
 * + OD-EX-03 (timeoutMs truthy 语义 execPromptHook.ts:55) + OD-EX-04 (attachment command/durationMs
 * 注入 hooks.ts:2241-2250/2281-2290)。
 *
 * <p>RED 证据（改动前）:
 * <ul>
 *   <li>{@link #agentHook_parentAbortPreCancelled_immediateCancel_noProviderTurn}: HookRegistry 分发
 *       agent hook 时传 parentAbort=null（EV-EX-027/028 佐证）→ 父已取消仍跑完整 loop（provider 被调
 *       ≥1 次）→ 断言 0 次失败。</li>
 *   <li>{@link #promptHook_attachment_carriesCommandAndDurationMs} / {@link #agentHook_attachment_carriesCommandAndDurationMs}:
 *       ExecPromptHook/ExecAgentHook 返回的 hook_success attachment 无 command/durationMs（EV-EX-029/030）
 *       → 断言 command=hook.prompt 失败。</li>
 *   <li>{@link #promptHookTimeoutZero_usesDefaultTimeout}: {@code PromptHook.timeoutMs()} 现为非 null 判断
 *       （timeout=0 → 0ms 立即超时）→ 断言 truthy 语义（0 → 默认 30s）失败。</li>
 * </ul>
 */
@DisplayName("[IMPL-06] 取消语义对齐 CC（D5 + OD-EX-02/03/04）")
class CancellationSemanticsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_FAST_MODEL = "haiku-test";

    private static final String HOOK_NAME = "test-prompt-hook";
    private final HookEvent hookEvent = HookEvent.userPromptSubmit(SESSION_ID, AGENT_ID, "do something");
    private static final String SESSION_ID = "sess-1";
    private static final String AGENT_ID = "agent-1";
    private static final String PROMPT = "check $ARGUMENTS";

    // ════════════════════════════════════════════════════════════════════════
    // 测试夹具（镜像 ExecAgentHookSemanticsTest / PromptHookRealProviderTest 基建）
    // ════════════════════════════════════════════════════════════════════════

    /** 按脚本返回 AssistantMessage 的 provider · 超出脚本长度时重复最后一条（复用 ExecAgentHookSemanticsTest 模式）. */
    static class ScriptableProvider implements LlmProvider {
        final List<AssistantMessage> responses;
        final AtomicInteger callCount = new AtomicInteger(0);

        ScriptableProvider(List<AssistantMessage> responses) { this.responses = responses; }

        @Override public String type() { return "test"; }
        @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }

        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                           List<ChatMessageDto> history, ArrayNode tools,
                           Integer maxOutputTokensOverride,
                           com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                           String effortValue, String querySource,
                           java.util.function.Consumer<String> onChunk,
                           java.util.function.Consumer<AssistantMessage> onAssistantMessage,
                           java.util.function.Consumer<ToolUseBlock> onToolCallComplete,
                           java.util.function.Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           AbortController abortController,
                           java.util.function.Consumer<Throwable> onError,
                           Runnable onComplete) {
            int idx = callCount.getAndIncrement();
            AssistantMessage am = responses.get(Math.min(idx, responses.size() - 1));
            onAssistantMessage.accept(am);
            onComplete.run();
        }
    }

    /** 合法 StructuredOutput tool_call · {ok, reason?} · 对齐 CC hookResponseSchema. */
    private ToolUseBlock structuredCall(boolean ok) {
        ObjectNode input = JSON.createObjectNode();
        input.put("ok", ok);
        return new ToolUseBlock("toolu-struct-" + System.nanoTime(),
            ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME, input);
    }

    /** 纯文本 stop 响应 · 让 queryLoop needsFollowUp=false 退出循环. */
    private static AssistantMessage stopText(String text) {
        return new AssistantMessage(text, "stop", List.of());
    }

    /** 构建 ExecAgentHook（真实 loop 基建，provider 经 contextFactory 注入）.
     *  [MAINCHAIN-01] LlmAgentLoop 主链现调 2 参 getProvider(config, providerType)，工厂须覆写 2 参版本。 */
    private ExecAgentHook agentHookWith(LlmProvider provider) {
        LlmProviderFactory factory = new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                return provider;
            }
        };
        AgentLoopContextFactory contextFactory = new AgentLoopContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        return new ExecAgentHook(JSON, contextFactory, new ToolRegistry(), null,
            ProviderConfig.empty(), DEFAULT_FAST_MODEL, null, null, null);
    }

    /** Stub matcher engine: 直接返回预设 MatchedHook（镜像 PromptHookRealProviderTest）. */
    static class StubMatcherEngine extends HookMatcherEngine {
        volatile List<MatchedHook> hooks = List.of();

        StubMatcherEngine() { super(null, null); }

        void setHooks(List<MatchedHook> hooks) { this.hooks = hooks; }

        @Override
        public List<MatchedHook> getMatchingHooks(HookEvent event) { return hooks; }
    }

    /** Stub ModelConfigResolver（RV-FOLLOWUP DEDUP-01: resolvePromptProvider 已薄委托单一解析来源）.
     *  解析任意非空模型名 → 可用 (config, openai_compatible). */
    static class StubModelConfigResolver extends ModelConfigResolver {
        @Override
        public ResolvedModel resolve(String modelName) {
            if (modelName == null || modelName.isBlank()) {
                return null;
            }
            return new ResolvedModel(new ProviderConfig("https://llm.example.com", "sk-test-123"),
                "openai_compatible");
        }
    }
    /** 捕获型真实 provider（镜像 PromptHookRealProviderTest）· 返回 {"ok": true}. */
    static class CapturingProvider implements LlmProvider {
        final AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override public String type() { return "openai_compatible"; }

        @Override public void stream(ProviderConfig c, String m,
            List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
            List<ChatMessageDto> h, ArrayNode t,
            Integer maxOutputTokensOverride,
            com.nexusai.infra.llm.TaskBudgetParam taskBudget,
            String effortValue, String querySource,
            java.util.function.Consumer<String> oc,
            java.util.function.Consumer<AssistantMessage> oam,
            java.util.function.Consumer<ToolUseBlock> otc,
            java.util.function.Consumer<String> orc, Runnable osf,
            AbortController ac,
            java.util.function.Consumer<Throwable> oe, Runnable onC) {
            throw new UnsupportedOperationException();
        }

        @Override public String chat(ProviderConfig c, String m, String s, String u) {
            throw new UnsupportedOperationException("chatWithOptions 必须被调用");
        }

        @Override
        public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                      LlmProvider.ChatRequestOptions options) {
            calls.incrementAndGet();
            capturedOptions.set(options);
            return "{\"ok\": true}";
        }
    }

    private LlmProviderFactory routingFactory(CapturingProvider real) {
        return new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                if (config == null || !config.isUsable()) {
                    return new MockLlmProvider();
                }
                return real;
            }
        };
    }

    /** 带父 abort 的 parentTuc · 对齐 CC toolUseContext.abortController 语义. */
    private static ToolUseContext parentTucWithAbort(AbortController abort) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", abort, List.of(), null, PermissionMode.DEFAULT);
    }

    private static HookEvent userPrompt() {
        return HookEvent.userPromptSubmit(SESSION_ID, AGENT_ID, "do something");
    }

    /**
     * 带有效 String sessionId 的事件 · HookRegistry.executeConfiguredAgent 经
     * {@code parseSessionUuid(event.sessionId())} 解析 sessionId，非 UUID 串（如 "sess-1"）
     * 解析失败 → exec sessionId=null → hook agent loop 异常退出（harness 噪音，非被测行为）。
     */
    private static HookEvent userPromptWithUuidSession() {
        return new HookEvent(HookEventType.USER_PROMPT_SUBMIT, UUID.randomUUID().toString(),
            null, null, null, AGENT_ID, null, null, null, null, null, null,
            new HookEventData.UserPromptSubmit(null), 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // D5-2: 父 abort → ExecAgentHook 硬取消（turn 不跑完）
    // CC execAgentHook.ts:81-85 createCombinedAbortSignal(signal, {timeoutMs}) → hookAbortController
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("父 abort（已取消）→ 入口级早退整批跳过，provider 0 次调用（对齐 CC executeHooks 入口 signal.aborted 早返）")
    void agentHook_parentAbortPreCancelled_immediateCancel_noProviderTurn() {
        // WHY (fix-ts04 IMPL-02 / OD-TS04-07): CC 父 signal 预取消 → executeHooks 入口
        //   早返（hooks.ts:2015-2017 `if (signal?.aborted) return`）→ 匹配后、执行前
        //   整批静默跳过（零结果产出，Java 表达 = proceed 无干预）。旧 Java 语义为
        //   outcome=CANCELLED（executor 运行期链）——与 CC 静默跳过偏离，按定案对齐。
        //   断言 provider 0 次调用 = 执行器从未被触达（比运行期短路更早）。
        ScriptableProvider p = new ScriptableProvider(List.of(stopText("done")));
        HookRegistry registry = new HookRegistry();
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(new MatchedHook(
            new AgentHook(PROMPT, null, null, null, null, null),
            null, null, null, "settings")));
        registry.setHookMatcherEngine(engine);
        registry.setExecAgentHook(agentHookWith(p));

        AbortController parentAbort = new AbortController();
        parentAbort.abort("user_cancelled");
        HookResult result = registry.executeEvent(userPromptWithUuidSession(), null, parentTucWithAbort(parentAbort));

        assertThat(result.outcome())
            .as("父已取消 → 入口级早退无干预结果（对齐 CC 静默跳过 hooks.ts:2015-2017，非 CANCELLED 干预结果）")
            .isNotEqualTo(HookOutcome.CANCELLED);
        assertThat(result.preventContinuation())
            .as("入口级早退 → 无干预（preventContinuation=false）")
            .isFalse();
        assertThat(p.callCount.get())
            .as("入口级早退 → hook agent executor 不得被触达（provider 0 次调用）")
            .isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // OD-EX-04: prompt/agent attachment 补 command/durationMs
    // CC hooks.ts:2241-2250 (prompt) / :2281-2290 (agent)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("prompt hook 结果 attachment 携带 command=hook.prompt + durationMs（CC hooks.ts:2241-2250）")
    void promptHook_attachment_carriesCommandAndDurationMs() {
        // WHY (OD-EX-04 / EV-EX-029/030): CC 在 prompt 分支执行后注入
        // att.command = hookCommand (getHookDisplayText = statusMessage ?? prompt)
        // + att.durationMs = Date.now() - hookStartMs。Java 3 参工厂无 command/durationMs 通道，
        // 且分发层无注入 → 审计/UI 看不到 hook 命令与耗时。
        CapturingProvider real = new CapturingProvider();
        HookRegistry registry = new HookRegistry();
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(new MatchedHook(
            new PromptHook(PROMPT, null, null, DEFAULT_FAST_MODEL, null, null),
            null, null, null, "settings")));
        registry.setHookMatcherEngine(engine);
        registry.setExecPromptHook(new ExecPromptHook(JSON));
        registry.setLlmProviderFactory(routingFactory(real));
        registry.setModelConfigResolver(new StubModelConfigResolver());

        HookResult result = registry.executeEvent(userPrompt());

        assertThat(result.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) result.message();
        assertThat(att.type()).isEqualTo("hook_success");
        // CC hooks.ts:2247 att.command = hookCommand（statusMessage 未设 → hook.prompt）
        assertThat(att.command())
            .as("hook_success attachment 必须携带 command（CC hooks.ts:2247）")
            .isEqualTo(PROMPT);
        // CC hooks.ts:2248 att.durationMs = Date.now() - hookStartMs
        assertThat(att.durationMs())
            .as("hook_success attachment 必须携带 durationMs（CC hooks.ts:2248）")
            .isNotNull();
        assertThat(att.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("agent hook 结果 attachment 携带 command=hook.prompt + durationMs（CC hooks.ts:2281-2290）")
    void agentHook_attachment_carriesCommandAndDurationMs() {
        // WHY (OD-EX-04 / EV-EX-029): CC agent 分支执行后同样注入 att.command + att.durationMs
        // （hooks.ts:2287-2288）。Java ExecAgentHook success attachment 无这两个字段。
        ScriptableProvider p = new ScriptableProvider(List.of(
            new AssistantMessage("", "tool_calls", List.of(structuredCall(true))),
            stopText("done")
        ));
        HookRegistry registry = new HookRegistry();
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(new MatchedHook(
            new AgentHook(PROMPT, null, null, null, null, null),
            null, null, null, "settings")));
        registry.setHookMatcherEngine(engine);
        registry.setExecAgentHook(agentHookWith(p));

        HookResult result = registry.executeEvent(userPromptWithUuidSession());

        assertThat(result.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) result.message();
        assertThat(att.type()).isEqualTo("hook_success");
        assertThat(att.command())
            .as("hook_success attachment 必须携带 command（CC hooks.ts:2287）")
            .isEqualTo(PROMPT);
        assertThat(att.durationMs())
            .as("hook_success attachment 必须携带 durationMs（CC hooks.ts:2288）")
            .isNotNull();
        assertThat(att.durationMs()).isGreaterThanOrEqualTo(0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // OD-EX-03: timeoutMs truthy 语义（0 → 默认 30s，非 0ms 立即超时）
    // CC execPromptHook.ts:55 hook.timeout ? hook.timeout*1000 : 30000
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("timeout=0 → 默认 30s（truthy 语义，非 0ms 立即超时）· CC execPromptHook.ts:55")
    void promptHookTimeoutZero_usesDefaultTimeout() {
        // WHY (OD-EX-03 / EV-EX-019): CC truthy 判断 hook.timeout ? hook.timeout*1000 : 30000 —
        // timeout=0（falsy）→ 默认 30s。Java 现为非 null 判断（0 → 0ms → .get(0) 立即超时
        // → 所有显式配 0 的 prompt hook 确定性 cancelled）。
        PromptHook cfg = new PromptHook(PROMPT, null, 0, null, null, null);
        assertThat(cfg.timeoutMs())
            .as("timeout=0 必须走默认 30s（truthy 语义对齐 CC execPromptHook.ts:55）")
            .isEqualTo(30_000L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // D5-1: 父 abort → ExecPromptHook 立即中止（cancelled 结果，非跑完）
    // CC execPromptHook.ts:73 signal: combinedSignal（父分量，combinedAbortSignal.ts:22-25）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("父 abort（已取消）→ PromptHook 立即 CANCELLED，provider 0 次调用")
    void promptHook_parentAbortPreCancelled_immediateCancelled_noProviderCall() {
        // WHY (D5-1 / EV-EX-024): CC createCombinedAbortSignal 在 signal.aborted 时立即 abort
        // combined → queryModelWithoutStreaming 立即拒绝 → outcome=cancelled（CC :186-190）。
        // Java 旧实现 exec 无 signal 参（父取消完全无法表达）；新实现预检 combinedAbort →
        // 跳过 LLM 调用（provider 0 次 = 未发起任何请求）。
        CapturingProvider real = new CapturingProvider();
        ExecPromptHook hook = new ExecPromptHook(JSON);
        PromptHook cfg = new PromptHook(PROMPT, null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            real, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);
        AbortController parentAbort = new AbortController();
        parentAbort.abort("user_cancelled");

        HookResult r = hook.exec(cfg, HOOK_NAME, hookEvent, "{}", ctx, parentAbort);

        assertThat(r.outcome())
            .as("父已取消 → prompt hook 必须立即 cancelled（CC :186-190）")
            .isEqualTo(HookOutcome.CANCELLED);
        assertThat(real.calls.get())
            .as("父已取消 → 不得发起任何 LLM 调用（combined 预检短路）")
            .isZero();
    }

    @Test
    @DisplayName("父 abort（飞行中）→ PromptHook 立即返回 CANCELLED（不等 LLM 跑完）")
    void promptHook_parentAbortMidFlight_returnsCancelledImmediately() {
        // WHY (D5-1 / EV-EX-024 / INV-6): 用户取消后 hook 副作用立即停止 —— LLM 调用进行中
        // 父 abort → combinedAbort.abort → future.cancel(true) 立即唤醒 get（不等 2s sleep 完成）。
        LlmProvider slow = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<ChatMessageDto> h, ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) {
                throw new UnsupportedOperationException();
            }
            @Override public String chat(ProviderConfig c, String m, String s, String u) { return ""; }
            @Override
            public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                          LlmProvider.ChatRequestOptions options) {
                try {
                    Thread.sleep(2000); // 模拟慢 LLM 调用
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "{\"ok\": true}";
            }
        };
        ExecPromptHook hook = new ExecPromptHook(JSON);
        PromptHook cfg = new PromptHook(PROMPT, null, null, null, null, null);
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            slow, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);
        AbortController parentAbort = new AbortController();

        long start = System.currentTimeMillis();
        Thread aborter = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            parentAbort.abort("user_cancelled");
        });
        aborter.setDaemon(true);
        aborter.start();
        HookResult r = hook.exec(cfg, HOOK_NAME, hookEvent, "{}", ctx, parentAbort);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(r.outcome())
            .as("飞行中父 abort → outcome=cancelled（CC :183-191 combinedSignal.aborted）")
            .isEqualTo(HookOutcome.CANCELLED);
        assertThat(elapsed)
            .as("必须立即返回（LLM 2s 未跑完；预期 <1500ms）")
            .isLessThan(1500L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // D5-3: 父 abort → ExecHttpHook 立即中止（aborted 结果）
    // CC hooks.ts:2306 signal → execHttpHook.ts:151-154 createCombinedAbortSignal
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("父 abort（已取消）→ HttpHook 立即 aborted，服务器 0 次请求")
    void httpHook_parentAbortPreCancelled_aborted_noRequest() throws Exception {
        // WHY (D5-3 / EV-EX-027): CC hooks.ts:2306 http 分支传父 signal →
        // createCombinedAbortSignal 预检（signal.aborted → combined.abort()）→ axios 立即拒绝
        // → aborted:true（execHttpHook.ts:234-236）。Java 旧实现 exec 无 signal 参（父取消无法表达）；
        // 新实现预检短路 → 不发起任何 HTTP 请求（服务器命中 0 = 无 I/O）。
        AtomicInteger hits = new AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            hits.incrementAndGet();
            byte[] resp = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            ExecHttpHook hook = new ExecHttpHook(new HooksSettings(key -> null), new SsrfGuard());
            HttpHook httpHook = new HttpHook(url, null, 10, null, null, null, null);
            AbortController parentAbort = new AbortController();
            parentAbort.abort("user_cancelled");

            ExecHttpHook.HttpHookResult r = hook.exec(httpHook, "t", hookEvent, "{}", parentAbort);

            assertThat(r.aborted())
                .as("父已取消 → http hook 必须立即 aborted（CC execHttpHook.ts:234-236）")
                .isTrue();
            assertThat(hits.get())
                .as("父已取消 → 不得发起任何 HTTP 请求（预检短路）")
                .isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("父 abort（已取消）+ SSRF 拦截 host → aborted 优先于 SSRF error（CC combined 信号先于 axios lookup）")
    void httpHook_parentPreCancelled_ssrfBlockedHost_returnsAbortedNotSsrfError() {
        // WHY (reflection P2-3 微差): CC createCombinedAbortSignal（execHttpHook.ts:151-154，
        //   combinedAbortSignal.ts:22-25 预检 signal.aborted → combined.abort()）创建于
        //   axios.post 之前，SSRF 校验发生在 axios 内 lookup（execHttpHook.ts:216）→
        //   预取消 + SSRF 拦截 host 的角落用例 CC 返回 aborted。Java 旧序为
        //   allowlist → SSRF → 预检，该角落返回 SSRF error（non_blocking_error 通道）——
        //   与 CC 顺序相反。预检上移后 aborted 优先（D5-3/INV-6 一致：父取消立即中止）。
        ExecHttpHook hook = new ExecHttpHook(new HooksSettings(key -> null), new SsrfGuard());
        // 10.0.0.1 = RFC1918 私有段，ssrfGuardedLookup 必抛 SecurityException（不发起网络 I/O）
        HttpHook httpHook = new HttpHook("http://10.0.0.1/hook", null, 10, null, null, null, null);
        AbortController parentAbort = new AbortController();
        parentAbort.abort("user_cancelled");

        ExecHttpHook.HttpHookResult r = hook.exec(httpHook, "t", hookEvent, "{}", parentAbort);

        assertThat(r.aborted())
            .as("预取消 + SSRF 拦截 host → 必须 aborted（CC combined 预检先于 axios SSRF lookup）")
            .isTrue();
        assertThat(r.error())
            .as("aborted 优先 → 不得落入 SSRF error 通道")
            .isNull();
    }

    @Test
    @DisplayName("父 abort（飞行中）→ HttpHook 立即返回 aborted（不等慢响应）")
    void httpHook_parentAbortMidFlight_returnsAbortedImmediately() throws Exception {
        // WHY (D5-3 / INV-6): 请求进行中父 abort → abort latch 胜出 → 立即返回 aborted
        // （CC :234-236 combinedSignal.aborted）。Java 竞速路径不等 3s 慢响应。
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(3000); // 模拟慢 hook 服务端
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] resp = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            ExecHttpHook hook = new ExecHttpHook(new HooksSettings(key -> null), new SsrfGuard());
            HttpHook httpHook = new HttpHook(url, null, 10, null, null, null, null);
            AbortController parentAbort = new AbortController();

            long start = System.currentTimeMillis();
            Thread aborter = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                parentAbort.abort("user_cancelled");
            });
            aborter.setDaemon(true);
            aborter.start();
            ExecHttpHook.HttpHookResult r = hook.exec(httpHook, "t", hookEvent, "{}", parentAbort);
            long elapsed = System.currentTimeMillis() - start;

            assertThat(r.aborted())
                .as("飞行中父 abort → aborted=true（CC :234-236）")
                .isTrue();
            assertThat(elapsed)
                .as("必须立即返回（服务端 3s 未响应；预期 <2000ms）")
                .isLessThan(2000L);
        } finally {
            server.stop(0);
        }
    }
}
