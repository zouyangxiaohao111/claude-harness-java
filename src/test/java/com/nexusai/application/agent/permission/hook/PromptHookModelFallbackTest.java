package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [EX-HOOK R4] 配置驱动 PromptHook 空模型守卫改回落 · 对齐 CC execPromptHook.ts:79
 * {@code model: hook.model ?? getSmallFastModel()} + model.ts:36-38
 * （ANTHROPIC_SMALL_FAST_MODEL → ANTHROPIC_DEFAULT_HAIKU_MODEL → 默认 haiku45）。
 *
 * <p>WHY (规则九 · 验证意图): 旧守卫在 hook.model 与 nexusai.hook.fastModel 均为空时
 * warn + 跳过 proceed — 空模型配置的 prompt hook 确定性不执行（静默功能缺失）。CC 语义
 * 是回落默认小模型（getSmallFastModel env 链），不跳过。本测试锁定：模型空 → 回落非空
 * 默认模型 → provider 解析 → exec 被调（llmContext.defaultFastModel() = 回落值）。
 *
 * <p>不依赖 Spring 容器：StubMatcherEngine 直接注入预设 prompt hook + StubProviderService
 * （model 名 = 回落值，env 无关）+ routingFactory（可用 config → 捕获型真实 provider）。
 */
@DisplayName("[EX-HOOK R4] 配置 PromptHook 空模型 → 回落 getSmallFastModel（不跳过）")
class PromptHookModelFallbackTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── Stub matcher engine: 直接返回预设 MatchedHook（镜像 PromptHookRealProviderTest）──

    static class StubMatcherEngine extends HookMatcherEngine {
        volatile List<MatchedHook> hooks = List.of();

        StubMatcherEngine() {
            super(null, null);
        }

        void setHooks(List<MatchedHook> hooks) {
            this.hooks = hooks;
        }

        @Override
        public List<MatchedHook> getMatchingHooks(HookEvent event) {
            return hooks;
        }
    }

    // ── Stub ModelConfigResolver: model 名 = 回落值（SkillImprovementHook.getSmallFastModel，env 无关）──

    static class StubModelConfigResolver extends ModelConfigResolver {
        @Override
        public ResolvedModel resolve(String modelName) {
            return new ResolvedModel(
                new ProviderConfig("https://llm.example.com", "sk-test-123"),
                "openai_compatible");
        }
    }

    // ── 捕获型真实 provider: 记录 config/options，返回 {"ok": true} ──

    static class CapturingProvider implements LlmProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override public String type() { return "openai_compatible"; }

        @Override public void stream(ProviderConfig c, String m,
            List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
            List<com.nexusai.model.session.dto.ChatMessageDto> h,
            com.fasterxml.jackson.databind.node.ArrayNode t,
            Integer maxOutputTokensOverride,
            com.nexusai.infra.llm.TaskBudgetParam taskBudget,
            String effortValue, String querySource,
            java.util.function.Consumer<String> oc,
            java.util.function.Consumer<com.nexusai.infra.llm.AssistantMessage> oam,
            java.util.function.Consumer<ToolUseBlock> otc,
            java.util.function.Consumer<String> orc, Runnable osf,
            AbortController ac,
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
            return "{\"ok\": true}";
        }
    }

    // ── recording ExecPromptHook: 捕获 llmContext 的模型名（回落观察点）──

    static class RecordingExecPromptHook extends ExecPromptHook {
        final AtomicReference<String> modelSeen = new AtomicReference<>();
        final AtomicInteger execCalls = new AtomicInteger();

        RecordingExecPromptHook() {
            super(new ObjectMapper());
        }

        @Override
        public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                               String jsonInput, PromptLlmContext llmContext, AbortController parentAbort) {
            // [H2/CCJ-EXEC-01] 6 参委托 7 参（分发层现调 7 参版本）
            return exec(hook, hookName, hookEvent, jsonInput, llmContext, parentAbort, null);
        }

        @Override
        public HookResult exec(PromptHook hook, String hookName, HookEvent hookEvent,
                               String jsonInput, PromptLlmContext llmContext, AbortController parentAbort,
                               java.util.List<com.nexusai.model.session.dto.ChatMessageDto> messages) {
            execCalls.incrementAndGet();
            modelSeen.set(llmContext.defaultFastModel());
            return HookResult.proceed();
        }
    }

    private HookRegistry registryWithEmptyModelPromptHook(RecordingExecPromptHook exec) {
        HookRegistry registry = new HookRegistry();
        StubMatcherEngine engine = new StubMatcherEngine();
        // model=null（CC execPromptHook.ts:79 hook.model ?? getSmallFastModel() 的 hook.model 空分支）
        engine.setHooks(List.of(new MatchedHook(
            new PromptHook("check $ARGUMENTS", null, null, null, null, null),
            null, null, null, "settings")));
        registry.setHookMatcherEngine(engine);
        registry.setExecPromptHook(exec);
        registry.setModelConfigResolver(new StubModelConfigResolver());
        registry.setLlmProviderFactory(new LlmProviderFactory() {
            @Override public LlmProvider getProvider(ProviderConfig config, String providerType) {
                return new CapturingProvider();
            }
        });
        return registry;
    }

    /**
     * WHY: 旧守卫（hook.model 与 nexusai.hook.fastModel 均为空 → warn + 跳过 proceed）
     * 让空模型 prompt hook 确定性不执行。CC 语义是回落 getSmallFastModel（model.ts:36-38
     * env 链）→ 非空模型 → 正常执行。断言: exec 被调 + llmContext 模型名 = 回落值。
     */
    @Test
    @DisplayName("hook.model 与 fastModel 均空 → 回落 getSmallFastModel 并执行（不跳过）")
    void emptyModel_fallsBackToSmallFastModelAndExecutes() {
        RecordingExecPromptHook exec = new RecordingExecPromptHook();
        HookRegistry registry = registryWithEmptyModelPromptHook(exec);

        registry.executeEvent(
            HookEvent.userPromptSubmit(UUID.randomUUID().toString(), null, "do something"));

        assertThat(exec.execCalls.get())
            .as("空模型必须回落默认小模型并执行（不跳过 proceed，EX-HOOK R4）")
            .isEqualTo(1);
        assertThat(exec.modelSeen.get())
            .as("llmContext 模型名 = getSmallFastModel 回落值（CC execPromptHook.ts:79 hook.model ?? getSmallFastModel()）")
            .isEqualTo(SkillImprovementHook.getSmallFastModel())
            .isNotBlank();
    }
}
