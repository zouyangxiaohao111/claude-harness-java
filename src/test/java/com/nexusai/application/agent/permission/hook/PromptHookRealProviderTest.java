package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-05] 配置 prompt hook 生产接线（D10 + OD-EX-05 + OD-EX-01）E3 测试.
 *
 * <p>WHY（EV-EX-039 / EV-EX-023）: 旧实现 {@code HookRegistry.executeConfiguredPrompt}
 * 恒用 {@code llmProviderFactory.getProvider(ProviderConfig.empty())} → MockLlmProvider
 * （反射文本非 JSON）→ ExecPromptHook.safeParseJSON 失败 → 所有生产 prompt hook 确定性
 * outcome=non_blocking_error（'JSON validation failed'）。OD-EX-05 ADJUDICATED: 复用
 * ChatService.resolveProvider 模式注入真实 provider；OD-EX-01 ADJUDICATED: querySource
 * 'hook_prompt' 透传（对齐 CC execPromptHook.ts:84）。
 *
 * <p>RED 证明（改动前）: {@code executeConfiguredPrompt} 以 empty config 调工厂 → 工厂
 * 按生产路由返回 MockLlmProvider → outcome=non_blocking_error，本测试断言 SUCCESS 失败；
 * querySource 断言（null）失败。
 *
 * <p>不依赖 Spring 容器：手动构造 HookRegistry + ModelConfigResolver stub（RV-FOLLOWUP DEDUP-01
 * 后 resolvePromptProvider 薄委托的单一解析来源）+
 * LlmProviderFactory stub（镜像生产路由：empty config → mock；可用 config → 真实 provider）。
 */
@DisplayName("[IMPL-05] 配置 prompt hook 生产接线（真实 provider + querySource 透传）")
class PromptHookRealProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_ID = "sess-1";
    private static final String AGENT_ID = "agent-1";
    private static final String MODEL = "haiku-test";
    private static final String BASE_URL = "https://llm.example.com";
    private static final String API_KEY = "sk-test-123";

    // ── Stub matcher engine: 直接返回预设 MatchedHook（隔离匹配与分发，镜像 HookRegistryDispatchTest）──

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

    // ── Stub ModelConfigResolver: 镜像生产 resolvePromptProvider 薄委托后的单一解析来源
    //（RV-FOLLOWUP DEDUP-01：HookRegistry.resolvePromptProvider 委托 ModelConfigResolver）──

    static class StubModelConfigResolver extends ModelConfigResolver {
        private final boolean failResolve;

        StubModelConfigResolver() {
            this(false);
        }

        StubModelConfigResolver(boolean failResolve) {
            this.failResolve = failResolve;
        }

        @Override
        public ResolvedModel resolve(String modelName) {
            if (failResolve || modelName == null || modelName.isBlank()) {
                return null; // 解析失败 → warn+null（不落 mock）
            }
            return new ResolvedModel(new ProviderConfig(BASE_URL, API_KEY), "openai_compatible");
        }
    }

    // ── 捕获型真实 provider: 记录 config/options，返回 {"ok": true} ──

    static class CapturingProvider implements LlmProvider {
        final AtomicReference<ProviderConfig> capturedConfig = new AtomicReference<>();
        final AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override public String type() { return "openai_compatible"; }

        @Override public void stream(ProviderConfig c, String m,
            List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
            List<ChatMessageDto> h,
            com.fasterxml.jackson.databind.node.ArrayNode t,
            Integer maxOutputTokensOverride,
            com.nexusai.infra.llm.TaskBudgetParam taskBudget,
            String effortValue, String querySource,
            java.util.function.Consumer<String> oc,
            java.util.function.Consumer<AssistantMessage> oam,
            java.util.function.Consumer<ToolUseBlock> otc,
            java.util.function.Consumer<String> orc, Runnable osf,
            com.nexusai.application.agent.tool.AbortController ac,
            java.util.function.Consumer<Throwable> oe,
            Runnable onC) {
            throw new UnsupportedOperationException();
        }

        @Override public String chat(ProviderConfig c, String m, String s, String u) {
            throw new UnsupportedOperationException("chatWithOptions 必须被调用");
        }

        @Override
        public String chatWithOptions(ProviderConfig c, String m, String s, String u,
                                      LlmProvider.ChatRequestOptions options) {
            calls.incrementAndGet();
            capturedConfig.set(c);
            capturedOptions.set(options);
            return "{\"ok\": true}";
        }
    }

    // ── 镜像生产路由的工厂 stub: empty config → MockLlmProvider；可用 config → 真实 provider ──

    private LlmProviderFactory routingFactory(CapturingProvider real, AtomicInteger mockCalls) {
        return new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                if (config == null || !config.isUsable()) {
                    mockCalls.incrementAndGet();
                    return new MockLlmProvider();
                }
                return real;
            }
        };
    }

    private HookRegistry registryWithPromptHook(LlmProviderFactory factory, ModelConfigResolver resolver) {
        HookRegistry registry = new HookRegistry();
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(matchedPromptHook()));
        registry.setHookMatcherEngine(engine);
        registry.setExecPromptHook(new ExecPromptHook(JSON));
        registry.setLlmProviderFactory(factory);
        registry.setModelConfigResolver(resolver);
        return registry;
    }

    private static MatchedHook matchedPromptHook() {
        // model 显式指定（CC execPromptHook.ts:79 hook.model ?? getSmallFastModel()）
        return new MatchedHook(
            new PromptHook("check $ARGUMENTS", null, null, MODEL, null, null),
            null, null, null, "settings");
    }

    private static HookEvent userPrompt() {
        return HookEvent.userPromptSubmit(SESSION_ID, AGENT_ID, "do something");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. OD-EX-05: 生产 prompt hook 走真实 provider（非 MockLlmProvider）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. 配置 prompt hook → 真实 provider 被调（mock 0 次）+ outcome=success（OD-EX-05）")
    void configuredPromptHook_usesRealProvider_success() {
        // WHY: 旧实现恒 ProviderConfig.empty() → MockLlmProvider 反射文本 → JSON 解析失败 →
        // 所有生产 prompt hook 确定性 non_blocking_error（EV-EX-039）。真实 provider 注入后
        // 解析成功 → outcome=success（INV-15：prompt hook 用真实 provider）。
        CapturingProvider real = new CapturingProvider();
        AtomicInteger mockCalls = new AtomicInteger();
        HookRegistry registry = registryWithPromptHook(
            routingFactory(real, mockCalls), new StubModelConfigResolver());

        HookResult result = registry.executeEvent(userPrompt());

        assertThat(mockCalls.get())
            .as("MockLlmProvider 不得被调（生产 prompt hook 走真实 provider, INV-15）")
            .isZero();
        assertThat(real.calls.get())
            .as("真实 provider 必须被调（非 MockLlmProvider）")
            .isEqualTo(1);
        assertThat(real.capturedConfig.get())
            .as("provider 收到解析出的真实配置（baseUrl+apiKey, 非 empty）")
            .isNotNull();
        assertThat(real.capturedConfig.get().isUsable()).isTrue();
        assertThat(real.capturedConfig.get().baseUrl()).isEqualTo(BASE_URL);
        assertThat(real.capturedConfig.get().apiKey()).isEqualTo(API_KEY);
        // 当前恒 non_blocking_error 必须消失（JSON 解析成功 → success）
        assertThat(result.outcome())
            .as("真实 provider 返回合法 {ok:true} → outcome=success（非 non_blocking_error）")
            .isEqualTo(HookOutcome.SUCCESS);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. OD-EX-01: querySource 'hook_prompt' 透传（对齐 CC execPromptHook.ts:84）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("2. chatWithOptions 携带 querySource='hook_prompt'（OD-EX-01, CC execPromptHook.ts:84）")
    void configuredPromptHook_passesQuerySourceHookPrompt() {
        // WHY: CC execPromptHook.ts:84 querySource: 'hook_prompt'（遥测/日志按来源分流）；
        // Java 旧实现 ChatRequestOptions.querySource 恒 null（EV-EX-023 注释事实错误）。
        CapturingProvider real = new CapturingProvider();
        HookRegistry registry = registryWithPromptHook(
            routingFactory(real, new AtomicInteger()), new StubModelConfigResolver());

        registry.executeEvent(userPrompt());

        assertThat(real.capturedOptions.get()).isNotNull();
        assertThat(real.capturedOptions.get().querySource())
            .as("querySource 必须透传 'hook_prompt'（CC execPromptHook.ts:84）")
            .isEqualTo("hook_prompt");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. 反向: provider 解析不可用（无模型/无 key）→ 显式跳过不抛（fail-loud 日志）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3. 无可用 provider 配置 → hook 跳过不抛（proceed），不落 mock 路径")
    void configuredPromptHook_noUsableProvider_skipsWithoutThrowing() {
        // WHY: 对齐 CC 无 apiKey → queryModel 抛错 → non_blocking_error 的"不阻断"语义；
        // Java 端解析失败显式 warn + proceed（不构造 ProviderConfig.empty() 兜底 mock —
        // mock 反射文本对生产无意义且造成恒 non_blocking_error 假象）。
        CapturingProvider real = new CapturingProvider();
        AtomicInteger mockCalls = new AtomicInteger();
        HookRegistry registry = registryWithPromptHook(
            routingFactory(real, mockCalls), new StubModelConfigResolver(true)); // failResolve → 解析失败

        HookResult result = registry.executeEvent(userPrompt());

        assertThat(real.calls.get()).isZero();
        assertThat(mockCalls.get()).isZero();
        assertThat(result).isNotNull();
        assertThat(result.preventContinuation()).isFalse();
    }
}
