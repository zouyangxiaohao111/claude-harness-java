package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [goal V253] 评估器「模型拒绝 thinking:disabled ⇒ 降级 adaptive」测试。
 *
 * <p><b>WHY</b>：强制开启思考的模型（实测 MiniMax-M3.1-Flash）会 400 拒
 * {@code thinking:{type:"disabled"}} ⇒ 不降级则目标（/goal）评估器在该模型上永远跑不起来。
 *
 * <p><b>三条不变量</b>：
 * <ol>
 *   <li>thinking 被拒 ⇒ 自动用 adaptive 重试<b>一次</b>，最终拿到评估结果</li>
 *   <li>thinking 被拒 ⇒ <b>记住该模型</b> ⇒ 后续调用直接发 adaptive（不再白烧 400 往返）</li>
 *   <li><b>其它 400 不得被糊过去</b>（判据必须认具体错误文本，不是「看到 400 就换」）</li>
 * </ol>
 */
@DisplayName("[goal V253] ExecPromptHook adaptive 降级")
class ExecPromptHookAdaptiveFallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HookEvent hookEvent = HookEvent.userPromptSubmit("sess-1", "agent-1", "do something");

    /** 记录每次调用收到的 thinking 类型（从 ChatRequestOptions 读）。 */
    private record Attempt(String thinkingType, String response) {}

    /** 装一个 hook：provider 按脚本逐次抛错/返回。 */
    private ExecPromptHook newHook(List<Attempt> script, AtomicInteger callCount,
                                  AtomicReference<String> lastThinkingType) {
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        LlmSideCallClient client = org.mockito.Mockito.mock(LlmSideCallClient.class);
        org.mockito.Mockito.when(client.chat(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> {
                int idx = callCount.getAndIncrement();
                Attempt a = idx < script.size() ? script.get(idx) : script.get(script.size() - 1);
                lastThinkingType.set(a.thinkingType());
                if (a.response() != null && a.response().startsWith("THROW:")) {
                    throw new IllegalStateException(a.response().substring("THROW:".length()));
                }
                return a.response();
            });
        org.springframework.test.util.ReflectionTestUtils.setField(hook, "sideCallClient", client);
        return hook;
    }

    private HookResult exec(ExecPromptHook hook, String prompt) {
        return exec(hook, prompt, "test-model");
    }

    /**
     * 执行一次评估。
     *
     * <p>⚠️ <b>模型名必须逐用例唯一</b>：{@code REQUIRES_NO_THINKING_FIELD} 是 <b>static 进程内缓存</b>
     * （被拒一次就记住该模型）⇒ 用同一个模型名会让后续用例「直接不传 thinking 字段」，
     * 于是「第一次被拒 → 降级」这条路径根本不会发生（测试假红）。
     */
    private HookResult exec(ExecPromptHook hook, String prompt, String modelName) {
        PromptHook cfg = new PromptHook(prompt, null, null, null, null, null);
        LlmProvider provider = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                List<com.nexusai.model.session.dto.ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<com.nexusai.application.agent.tool.ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC, Boolean skipCacheWrite,
                    com.nexusai.infra.llm.LlmCallScope scope,
                    com.nexusai.infra.llm.StreamIdleControl streamIdleControl) {
                throw new UnsupportedOperationException();
            }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new UnsupportedOperationException("只走 chatWithOptions");
            }
        };
        ExecPromptHook.PromptLlmContext ctx = new ExecPromptHook.PromptLlmContext(
            provider, ProviderConfig.empty(), modelName, List.of(), null);
        return hook.exec(cfg, "test-hook", hookEvent, "{}", ctx, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 判据
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isThinkingRejected：只认具体错误文本，不认泛化的 400")
    void isThinkingRejected_onlySpecificTexts() {
        // 实测形态（MiniMax-M3.1-Flash）
        assertThat(ExecPromptHook.isThinkingRejected(new IllegalStateException(
            "invalid params, model \"MiniMax-M3.1-Flash\" requires adaptive thinking; "
                + "thinking.type=\"disabled\" (including reasoning.effort=none) is not allowed (2013)")))
            .isTrue();
        assertThat(ExecPromptHook.isThinkingRejected(new IllegalStateException(
            "invalid params, model \"X\" requires adaptive thinking; reasoning effort none is not allowed")))
            .isTrue();
        // ⛔ 其它 400 不得被误判为 thinking 被拒
        assertThat(ExecPromptHook.isThinkingRejected(new IllegalStateException(
            "HTTP 400: invalid params, response_format json_schema is not supported"))).isFalse();
        assertThat(ExecPromptHook.isThinkingRejected(new IllegalStateException(
            "HTTP 401: unauthorized"))).isFalse();
        assertThat(ExecPromptHook.isThinkingRejected(new IllegalStateException(
            "HTTP 500: internal error"))).isFalse();
        assertThat(ExecPromptHook.isThinkingRejected(null)).isFalse();
    }

    @Test
    @DisplayName("能力缓存：标记后 requiresNoThinkingField 为真")
    void capabilityCache_roundTrip() {
        String model = "probe-model-" + System.nanoTime();
        assertThat(ExecPromptHook.requiresNoThinkingField(model)).isFalse();
        ExecPromptHook.markRequiresNoThinkingField(model);
        assertThat(ExecPromptHook.requiresNoThinkingField(model)).isTrue();
        assertThat(ExecPromptHook.requiresNoThinkingField("other-model")).isFalse();
        assertThat(ExecPromptHook.requiresNoThinkingField(null)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 降级行为
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("thinking 被拒 → 降级「不传 thinking 字段」重试一次（不是 adaptive），最终拿到评估结果")
    void thinkingRejected_retriesWithAdaptive() {
        String model = "reject-model-" + System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> lastThinking = new AtomicReference<>();
        ExecPromptHook hook = newHook(List.of(
            new Attempt("disabled", "THROW:invalid params, model \"" + model
                + "\" requires adaptive thinking; thinking.type=\"disabled\" is not allowed (2013)"),
            new Attempt("omit", "{\"ok\": true, \"reason\": \"e2e 18/18\"}")
        ), calls, lastThinking);

        HookResult r = exec(hook, "e2e 全绿", model);

        assertThat(calls.get()).as("被拒一次 + 降级重试一次 = 共 2 次").isEqualTo(2);
        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(r.impossible()).isNull();
    }

    @Test
    @DisplayName("thinking 被拒 → 记住该模型 ⇒ 下次直接不传 thinking 字段（不再试错）")
    void thinkingRejected_remembersModel() {
        String model = "remember-model-" + System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> lastThinking = new AtomicReference<>();
        // 第一次：被拒 → 降级成功（并记住模型）
        ExecPromptHook hook = newHook(List.of(
            new Attempt("disabled", "THROW:invalid params, model \"" + model
                + "\" requires adaptive thinking; thinking.type=\"disabled\" is not allowed"),
            new Attempt("omit", "{\"ok\": true}")
        ), calls, lastThinking);
        exec(hook, "cond", model);
        assertThat(ExecPromptHook.requiresNoThinkingField(model))
            .as("被拒后应记住该模型「不传 thinking 字段」").isTrue();

        // 第二次（新 hook，但能力缓存是 static 共享）：直接不传 thinking 字段，不再发 disabled
        AtomicInteger calls2 = new AtomicInteger();
        AtomicReference<String> lastThinking2 = new AtomicReference<>();
        ExecPromptHook hook2 = newHook(List.of(
            new Attempt("omit", "{\"ok\": true}")
        ), calls2, lastThinking2);
        HookResult r2 = exec(hook2, "cond", model);

        assertThat(calls2.get()).as("已记住 ⇒ 只调 1 次（不试 disabled）").isEqualTo(1);
        assertThat(r2.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
    }

    @Test
    @DisplayName("非 thinking 类错误 → 不降级，直接走 non_blocking_error")
    void otherErrors_noFallback() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> lastThinking = new AtomicReference<>();
        ExecPromptHook hook = newHook(List.of(
            new Attempt("disabled", "THROW:HTTP 400: invalid params, response_format json_schema is not supported")
        ), calls, lastThinking);

        HookResult r = exec(hook, "cond", "other-err-model-" + System.nanoTime());

        assertThat(calls.get()).as("不该重试").isEqualTo(1);
        assertThat(r.outcome()).isEqualTo(GenericHook.HookOutcome.NON_BLOCKING_ERROR);
    }
}
