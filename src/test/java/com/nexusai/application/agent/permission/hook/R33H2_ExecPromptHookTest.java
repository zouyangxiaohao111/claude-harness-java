package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H2] ExecPromptHook 从字符串拼接器重写为 LLM 评估器 · 对齐
 * CC Open-ClaudeCode/src/utils/hooks/execPromptHook.ts (211 行) +
 * :64-70 systemPrompt + :87-98 json_schema + :55 30s timeout +
 * :118/138/154/170/186/199 outcome 4 态.
 *
 * <p>WHY: CC PromptHook 通过 LLM 单轮评估条件是否满足, 返回
 * {@code {ok: boolean, reason?: string}}; Java 当前实现仅字符串拼接
 * ({@code ExecResult}), 不调 LLM, 不返回 {@link HookResult}, 与 CC 行为错位.
 * 本测试覆盖 6 条路径: ok=true / ok=false / JSON 解析失败 / schema 校验失败 /
 * timeout / 其他异常.
 *
 * <h2>测试用例 (6 项, 覆盖 H2 步骤 2)</h2>
 * <ol>
 *   <li>{@link #okTrue_returnsSuccess()} — CC :172 outcome='success'</li>
 *   <li>{@link #okFalse_returnsBlockingWithReason()} — CC :154-167 outcome='blocking' + preventContinuation</li>
 *   <li>{@link #jsonParseFailure_returnsNonBlockingError()} — CC :118 outcome='non_blocking_error'</li>
 *   <li>{@link #schemaValidationFailure_returnsNonBlockingError()} — CC :138 outcome='non_blocking_error'</li>
 *   <li>{@link #timeout_returnsCancelled()} — CC :186 outcome='cancelled'</li>
 *   <li>{@link #otherException_returnsNonBlockingError()} — CC :197-209 outcome='non_blocking_error'</li>
 * </ol>
 *
 * @since Session H2 (P0)
 */
@DisplayName("[H2] ExecPromptHook LLM 评估器对齐 CC execPromptHook.ts")
class R33H2_ExecPromptHookTest {

    private static final String DEFAULT_FAST_MODEL = "haiku-test";

    private ObjectMapper objectMapper;
    private HookEvent hookEvent;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        hookEvent = HookEvent.userPromptSubmit("sess-1", "agent-1", "do something");
    }

    /**
     * 构造一个 ExecPromptHook, 内部 LlmProvider 由 {@code chatResponse} 决定返回内容.
     * chatResponse 为 null 时, provider.chat 抛 RuntimeException (用于异常测试).
     */
    private ExecPromptHook buildHook(String chatResponse, long chatDelayMs) {
        LlmProvider mockProvider = new LlmProvider() {
            @Override public String type() { return "test"; }

            @Override
            public void stream(ProviderConfig config, String modelName,
                               java.util.List<com.nexusai.application.agent.prompt.SystemPromptBlock> systemPromptBlocks,
                               java.util.List<ChatMessageDto> history,
                               com.fasterxml.jackson.databind.node.ArrayNode tools,
                               Integer maxOutputTokensOverride,
                               com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                               String effortValue, String querySource,
                               java.util.function.Consumer<String> onChunk,
                               java.util.function.Consumer<AssistantMessage> onAssistantMessage,
                               java.util.function.Consumer<ToolUseBlock> onToolCallComplete,
                               java.util.function.Consumer<String> onReasoningChunk,
                               Runnable onStreamingFallback,
                               com.nexusai.application.agent.tool.AbortController abortController,
                               java.util.function.Consumer<Throwable> onError,
                               Runnable onComplete) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String chat(ProviderConfig config, String modelName, String systemPrompt, String userMessage) {
                if (chatDelayMs > 0) {
                    try { Thread.sleep(chatDelayMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new RuntimeException(e);
                    }
                }
                if (chatResponse == null) {
                    throw new RuntimeException("provider exploded");
                }
                return chatResponse;
            }
        };
        return new ExecPromptHook(objectMapper);
    }

    private ExecPromptHook.PromptLlmContext ctx(LlmProvider provider) {
        // 4 参构造（DEL-EX-04 收敛: 3 参兼容构造器已删除, 无工具 → 显式传 null）
        return new ExecPromptHook.PromptLlmContext(provider, ProviderConfig.empty(), DEFAULT_FAST_MODEL, null);
    }

    private LlmProvider echoProvider(AtomicReference<String> capturedUser, String response) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                java.util.List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                java.util.List<ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                if (capturedUser != null) capturedUser.set(u);
                return response;
            }
        };
    }

    @Test
    @DisplayName("ok=true → outcome=success (CC execPromptHook.ts:172)")
    void okTrue_returnsSuccess() {
        LlmProvider p = echoProvider(null, "{\"ok\": true}");
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check $ARGUMENTS", null, null, null, null, null);

        HookResult r = hook.exec(cfg, HOOK_NAME(), hookEvent, "{\"tool\":\"bash\"}", ctx(p), null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.SUCCESS);
        assertThat(r.preventContinuation()).isFalse();
    }

    @Test
    @DisplayName("ok=false → outcome=blocking + preventContinuation + stopReason=reason (CC :154-167)")
    void okFalse_returnsBlockingWithReason() {
        LlmProvider p = echoProvider(null, "{\"ok\": false, \"reason\": \"dangerous\"}");
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check $ARGUMENTS", null, null, null, null, null);

        HookResult r = hook.exec(cfg, HOOK_NAME(), hookEvent, "{\"tool\":\"rm\"}", ctx(p), null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.BLOCKING);
        assertThat(r.preventContinuation()).isTrue();
        assertThat(r.stopReason()).contains("dangerous");
    }

    @Test
    @DisplayName("JSON 解析失败 → outcome=non_blocking_error (CC :118)")
    void jsonParseFailure_returnsNonBlockingError() {
        LlmProvider p = echoProvider(null, "not a json at all");
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check $ARGUMENTS", null, null, null, null, null);

        HookResult r = hook.exec(cfg, HOOK_NAME(), hookEvent, "{\"tool\":\"bash\"}", ctx(p), null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
    }

    @Test
    @DisplayName("schema 校验失败 (ok 非 boolean) → outcome=non_blocking_error (CC :138)")
    void schemaValidationFailure_returnsNonBlockingError() {
        LlmProvider p = echoProvider(null, "{\"ok\": \"yes\"}");
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check $ARGUMENTS", null, null, null, null, null);

        HookResult r = hook.exec(cfg, HOOK_NAME(), hookEvent, "{\"tool\":\"bash\"}", ctx(p), null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
    }

    @Test
    @DisplayName("timeout → outcome=cancelled (CC :186)")
    void timeout_returnsCancelled() {
        // provider sleep 2s, hook timeout = 1s → 超时
        LlmProvider p = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                java.util.List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                java.util.List<ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                try { Thread.sleep(2000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new RuntimeException(e);
                }
                return "{\"ok\": true}";
            }
        };
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check $ARGUMENTS", null, 1, null, null, null);

        long start = System.currentTimeMillis();
        HookResult r = hook.exec(cfg, HOOK_NAME(), hookEvent, "{\"tool\":\"bash\"}", ctx(p), null);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(r.outcome()).isEqualTo(HookOutcome.CANCELLED);
        // 应在 ~1s 后超时, 而非等 2s
        assertThat(elapsed).isLessThan(1900L);
    }

    @Test
    @DisplayName("其他异常 (provider 抛错) → outcome=non_blocking_error (CC :197-209)")
    void otherException_returnsNonBlockingError() {
        // chatResponse=null → provider.chat 抛 RuntimeException
        LlmProvider p = new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m,
                java.util.List<com.nexusai.application.agent.prompt.SystemPromptBlock> blocks,
                java.util.List<ChatMessageDto> h,
                com.fasterxml.jackson.databind.node.ArrayNode t,
                Integer maxOutputTokensOverride,
                com.nexusai.infra.llm.TaskBudgetParam taskBudget,
                String effortValue, String querySource,
                java.util.function.Consumer<String> oc,
                java.util.function.Consumer<AssistantMessage> oam,
                java.util.function.Consumer<ToolUseBlock> otc,
                java.util.function.Consumer<String> orc, Runnable osf,
                com.nexusai.application.agent.tool.AbortController ac,
                java.util.function.Consumer<Throwable> oe, Runnable onC) { throw new UnsupportedOperationException(); }
            @Override public String chat(ProviderConfig c, String m, String s, String u) {
                throw new RuntimeException("provider exploded");
            }
        };
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("check $ARGUMENTS", null, null, null, null, null);

        HookResult r = hook.exec(cfg, HOOK_NAME(), hookEvent, "{\"tool\":\"bash\"}", ctx(p), null);

        assertThat(r.outcome()).isEqualTo(HookOutcome.NON_BLOCKING_ERROR);
    }

    @Test
    @DisplayName("$ARGUMENTS 占位符替换 (CC argumentSubstitution.ts:136)")
    void argumentsPlaceholder_substituted() {
        AtomicReference<String> capturedUser = new AtomicReference<>();
        LlmProvider p = echoProvider(capturedUser, "{\"ok\": true}");
        ExecPromptHook hook = new ExecPromptHook(objectMapper);
        PromptHook cfg = new PromptHook("eval input: $ARGUMENTS", null, null, null, null, null);

        hook.exec(cfg, HOOK_NAME(), hookEvent, "{\"tool\":\"bash\"}", ctx(p), null);

        assertThat(capturedUser.get()).contains("{\"tool\":\"bash\"}");
        assertThat(capturedUser.get()).doesNotContain("$ARGUMENTS");
    }

    private static String HOOK_NAME() { return "test-prompt-hook"; }
}