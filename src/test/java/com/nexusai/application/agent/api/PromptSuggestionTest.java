package com.nexusai.application.agent.api;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptSuggestion 主链单测 · 对齐 CC {@code services/PromptSuggestion/promptSuggestion.ts} +
 * {@code speculation.ts}。
 *
 * <p><b>WHY（意图验证，规则九）</b>: promptSuggestion 是"预测性 UX"关键路径 —— 主链
 * tryGenerateSuggestion（promptSuggestion.ts:125-182）必须在早期对话/父缓存冷启动/权限挂起/
 * plan 模式/过滤 等场景<b>静默不打扰用户</b>（返回 null），只在成熟对话 + 无抑制 + 无过滤时
 * 给出建议；且建议成功后可触发 speculation（:214-222），SpeculationEngine 不再是死代码
 * （OPD-WF7-JS-03 ✅ 实施）。若过滤链缺失（如把 "looks good" 建议发给用户）或抑制门放行
 * （如 plan 模式仍出建议），预测 UX 会骚扰用户 —— 本测试固化 CC 主链语义。
 *
 * <p><b>RED→GREEN</b>: 本类对应 Java 端此前仅简化 stub（startSuggestion 直接简路，无
 * 过滤/抑制/缓存门）；实施后 26 用例全绿直证 CC 主链。
 */
class PromptSuggestionTest {

    // ── fakes ────────────────────────────────────────────────────────────────

    private static final class FakeTelemetry implements PromptSuggestion.SuggestionTelemetry {
        final List<String> names = new ArrayList<>();
        final List<Map<String, Object>> fields = new ArrayList<>();
        public void log(String event, Map<String, Object> f) {
            names.add(event);
            fields.add(f);
        }
        Map<String, Object> lastField() {
            return fields.isEmpty() ? Map.of() : fields.get(fields.size() - 1);
        }
        String lastReason() {
            Object r = lastField().get("reason");
            return r == null ? null : r.toString();
        }
        boolean lastSuppressed() {
            return "suppressed".equals(lastField().get("outcome"));
        }
    }

    private static final class FakeFork implements PromptSuggestion.ForkedAgent {
        String output;
        String generationRequestId;
        Runnable onRun; // 生成期间回调（可注入 cancel 以验证 abort-after-generation）
        int calls;
        public PromptSuggestion.ForkResult run(String prompt, Map<String, Object> params, Object signal) {
            calls++;
            if (onRun != null) onRun.run();
            return new PromptSuggestion.ForkResult(output, false, generationRequestId);
        }
    }

    private static final class FakeSpecFork implements SpeculationEngine.ForkedAgent {
        public SpeculationEngine.SpeculationResult run(String prompt,
                SpeculationEngine.CacheSafeParams params, Object signal) {
            return new SpeculationEngine.SpeculationResult("run the tests", 0.0);
        }
    }

    private static PromptSuggestion.SuggestionContext ctx(
            String last, int turns, boolean apiError, long uncached, PromptSuggestion.AppStateSnapshot app) {
        return new PromptSuggestion.SuggestionContext(last, turns, apiError, uncached, app);
    }

    private static PromptSuggestion.SuggestionContext okCtx() {
        return ctx("last assistant turn", 2, false, 0L, PromptSuggestion.AppStateSnapshot.enabled());
    }

    // ── shouldFilterSuggestion（promptSuggestion.ts:354-456 · 12 过滤）──────────

    @Test
    @DisplayName("过滤链：done / meta_text / meta_wrapped / error_message / prefixed_label 全部拦截")
    void filter_catchesMetaAndErrorClasses() {
        assertThat(PromptSuggestion.filterReason("done")).isEqualTo("done");
        assertThat(PromptSuggestion.filterReason("nothing found")).isEqualTo("meta_text");
        assertThat(PromptSuggestion.filterReason("nothing to suggest here")).isEqualTo("meta_text");
        // CC meta_text 的 ^\W*silence\W*$ 先于 meta_wrapped 命中 → "(silence)" 归 meta_text
        assertThat(PromptSuggestion.filterReason("(silence)")).isEqualTo("meta_text");
        assertThat(PromptSuggestion.filterReason("silence")).isEqualTo("meta_text");
        // meta_wrapped ^\(.*\)$|^\[.*\]$：meta_text 未命中（无 nothing/silence 字样）时命中
        assertThat(PromptSuggestion.filterReason("(no suggestion)")).isEqualTo("meta_wrapped");
        assertThat(PromptSuggestion.filterReason("[no suggestion]")).isEqualTo("meta_wrapped");
        assertThat(PromptSuggestion.filterReason("api error: something")).isEqualTo("error_message");
        assertThat(PromptSuggestion.filterReason("prompt is too long")).isEqualTo("error_message");
        assertThat(PromptSuggestion.filterReason("Note: review this")).isEqualTo("prefixed_label");
    }

    @Test
    @DisplayName("过滤链：词数（too_few / too_many）—— slash 命令与常见单字放行，生僻单字拦截")
    void filter_wordCountGates() {
        assertThat(PromptSuggestion.filterReason("push")).isNull();          // allowed single word
        assertThat(PromptSuggestion.filterReason("yes")).isNull();           // allowed single word
        assertThat(PromptSuggestion.filterReason("/help")).isNull();         // slash command
        assertThat(PromptSuggestion.filterReason("xyzzy")).isEqualTo("too_few_words");
        assertThat(PromptSuggestion.filterReason("one two three four five six seven eight nine ten eleven twelve extra"))
            .isEqualTo("too_many_words"); // 13 words
    }

    @Test
    @DisplayName("过滤链：too_long / multiple_sentences / has_formatting / evaluative / claude_voice")
    void filter_styleClasses() {
        // too_long 需 ≥2 词且 ≤12 词但长度≥100（单词 100 字符会先命中 too_few_words，CC 数组序）
        String longMultiWord = "abcdefghijklmno abcdefghijklmno abcdefghijklmno abcdefghijklmno abcdefghijklmno abcdefghijklmno abcdefghijklmno abcdefghijklmno";
        assertThat(longMultiWord.length()).isGreaterThanOrEqualTo(100);
        assertThat(PromptSuggestion.filterReason(longMultiWord)).isEqualTo("too_long");
        assertThat(PromptSuggestion.filterReason("This is done. Next step")).isEqualTo("multiple_sentences");
        assertThat(PromptSuggestion.filterReason("run **the** tests")).isEqualTo("has_formatting");
        assertThat(PromptSuggestion.filterReason("looks good")).isEqualTo("evaluative");
        // "thanks" 单字且不在 ALLOWED_SINGLE_WORDS → CC too_few_words 先于 evaluative 命中（数组序）
        assertThat(PromptSuggestion.filterReason("thanks")).isEqualTo("too_few_words");
        assertThat(PromptSuggestion.filterReason("Let me fix that")).isEqualTo("claude_voice");
        assertThat(PromptSuggestion.filterReason("I'll take a look")).isEqualTo("claude_voice");
        assertThat(PromptSuggestion.filterReason("run the tests")).isNull(); // legit suggestion passes
        assertThat(PromptSuggestion.filterReason("finish the refactor and then run tests")).isNull();
    }

    // ── getParentCacheSuppressReason（promptSuggestion.ts:241-256）──────────────

    @Test
    @DisplayName("cache 门：uncached > 10000 → cache_cold；== 10000 放行")
    void parentCacheSuppress_boundary() {
        assertThat(PromptSuggestion.getParentCacheSuppressReason(10_000L)).isNull();
        assertThat(PromptSuggestion.getParentCacheSuppressReason(10_001L)).isEqualTo("cache_cold");
    }

    // ── getSuggestionSuppressReason（promptSuggestion.ts:107-119）──────────────

    @Test
    @DisplayName("suppress 门：disabled / pending_permission / elicitation_active / plan_mode / rate_limit")
    void suppressReason_gates() {
        assertThat(PromptSuggestion.getSuggestionSuppressReason(
            new PromptSuggestion.AppStateSnapshot(false, false, false, false, false, false)))
            .isEqualTo("disabled");
        assertThat(PromptSuggestion.getSuggestionSuppressReason(
            new PromptSuggestion.AppStateSnapshot(true, true, false, false, false, false)))
            .isEqualTo("pending_permission");
        assertThat(PromptSuggestion.getSuggestionSuppressReason(
            new PromptSuggestion.AppStateSnapshot(true, false, true, false, false, false)))
            .isEqualTo("pending_permission");
        assertThat(PromptSuggestion.getSuggestionSuppressReason(
            new PromptSuggestion.AppStateSnapshot(true, false, false, true, false, false)))
            .isEqualTo("elicitation_active");
        assertThat(PromptSuggestion.getSuggestionSuppressReason(
            new PromptSuggestion.AppStateSnapshot(true, false, false, false, true, false)))
            .isEqualTo("plan_mode");
        assertThat(PromptSuggestion.getSuggestionSuppressReason(
            new PromptSuggestion.AppStateSnapshot(true, false, false, false, false, true)))
            .isEqualTo("rate_limit");
        assertThat(PromptSuggestion.getSuggestionSuppressReason(
            PromptSuggestion.AppStateSnapshot.enabled())).isNull();
    }

    // ── tryGenerateSuggestion 主链（promptSuggestion.ts:125-182）────────────────

    @Test
    @DisplayName("早期对话（assistant<2）→ early_conversation 抑制，不调 fork")
    void tryGenerate_earlyConversation_suppressed() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        fork.output = "run the tests";
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, null);
        PromptSuggestion.SuggestionResult result =
            ps.tryGenerateSuggestion(ctx("last", 1, false, 0L, PromptSuggestion.AppStateSnapshot.enabled()),
                new SpeculationEngine.AbortController());
        assertThat(result).as("early_conversation 必须返回 null，避免骚扰新会话用户").isNull();
        assertThat(fork.calls).as("early_conversation 不得触发 fork agent").isZero();
        assertThat(telemetry.lastReason()).isEqualTo("early_conversation");
        assertThat(telemetry.lastSuppressed()).isTrue();
    }

    @Test
    @DisplayName("最后一条 assistant 是 API 错误 → last_response_error 抑制")
    void tryGenerate_lastApiError_suppressed() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, null);
        PromptSuggestion.SuggestionResult result = ps.tryGenerateSuggestion(
            ctx("err", 2, true, 0L, PromptSuggestion.AppStateSnapshot.enabled()),
            new SpeculationEngine.AbortController());
        assertThat(result).isNull();
        assertThat(fork.calls).isZero();
        assertThat(telemetry.lastReason()).isEqualTo("last_response_error");
    }

    @Test
    @DisplayName("父对话缓存冷启动（uncached>10000）→ cache_cold 抑制")
    void tryGenerate_cacheCold_suppressed() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, null);
        PromptSuggestion.SuggestionResult result = ps.tryGenerateSuggestion(
            ctx("last", 2, false, 20_000L, PromptSuggestion.AppStateSnapshot.enabled()),
            new SpeculationEngine.AbortController());
        assertThat(result).isNull();
        assertThat(fork.calls).isZero();
        assertThat(telemetry.lastReason()).isEqualTo("cache_cold");
    }

    @Test
    @DisplayName("plan 模式 → plan_mode 抑制（web 后端 LlmAgentLoop 注入 planMode）")
    void tryGenerate_planMode_suppressed() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, null);
        PromptSuggestion.SuggestionResult result = ps.tryGenerateSuggestion(
            ctx("last", 2, false, 0L,
                new PromptSuggestion.AppStateSnapshot(true, false, false, false, true, false)),
            new SpeculationEngine.AbortController());
        assertThat(result).isNull();
        assertThat(fork.calls).isZero();
        assertThat(telemetry.lastReason()).isEqualTo("plan_mode");
    }

    @Test
    @DisplayName("无最后 assistant 消息 → 静默跳过（CC :147 无日志抑制事件）")
    void tryGenerate_noLastAssistant_silent() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, null);
        PromptSuggestion.SuggestionResult result = ps.tryGenerateSuggestion(
            ctx(null, 2, false, 0L, PromptSuggestion.AppStateSnapshot.enabled()),
            new SpeculationEngine.AbortController());
        assertThat(result).isNull();
        assertThat(fork.calls).isZero();
        assertThat(telemetry.names).as("无 last assistant 不产生抑制遥测").isEmpty();
    }

    @Test
    @DisplayName("fork 输出被过滤（'done'）→ empty 语义（过滤 reason 遥测）")
    void tryGenerate_filteredOutput_suppressed() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        fork.output = "done";
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, null);
        PromptSuggestion.SuggestionResult result = ps.tryGenerateSuggestion(
            okCtx(), new SpeculationEngine.AbortController());
        assertThat(result).isNull();
        assertThat(fork.calls).as("fork 已调用（生成后过滤）").isEqualTo(1);
        assertThat(telemetry.lastReason()).isEqualTo("done");
        assertThat(telemetry.lastSuppressed()).isTrue();
    }

    @Test
    @DisplayName("成功：成熟对话 + 无抑制 + 无过滤 → 返回 {suggestion, promptId, generationRequestId}")
    void tryGenerate_success() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        fork.output = "  run the tests  ";
        fork.generationRequestId = "req-123";
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, null);
        PromptSuggestion.SuggestionResult result = ps.tryGenerateSuggestion(
            okCtx(), new SpeculationEngine.AbortController());
        assertThat(result).isNotNull();
        assertThat(result.suggestion()).as("输出必须 trim").isEqualTo("run the tests");
        assertThat(result.promptId()).as("getPromptVariant 恒 user_intent").isEqualTo("user_intent");
        assertThat(result.generationRequestId()).isEqualTo("req-123");
        assertThat(fork.calls).isEqualTo(1);
        assertThat(telemetry.names).doesNotContain("tengu_prompt_suggestion");
    }

    // ── executeSuggestion（promptSuggestion.ts:184-237 · 含 speculation 触发）─────

    @Test
    @DisplayName("禁用（shouldEnable env 门）→ 不生成、不调 fork")
    void execute_disabled_noop() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        fork.output = "run the tests";
        PromptSuggestion ps = new PromptSuggestion(() -> false, fork, telemetry, null);
        PromptSuggestion.SuggestionResult result = ps.executeSuggestion(okCtx());
        assertThat(result).isNull();
        assertThat(fork.calls).isZero();
    }

    @Test
    @DisplayName("建议成功 + speculation 启用 → startSpeculation 触发（SpeculationEngine 非死代码）")
    void execute_wiresSpeculation_whenEnabled() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        fork.output = "run the tests";
        SpeculationEngine speculation = new SpeculationEngine(
            () -> true, new FakeSpecFork(),
            new SpeculationEngine.StateStore() {
                private SpeculationEngine.SpeculationState s = SpeculationEngine.SpeculationState.idle();
                public SpeculationEngine.SpeculationState get() { return s; }
                public void set(SpeculationEngine.SpeculationState v) { s = v; }
            },
            (e, f) -> {});
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, speculation);
        PromptSuggestion.SuggestionResult result = ps.executeSuggestion(okCtx());
        assertThat(result).isNotNull();
        assertThat(result.suggestion()).isEqualTo("run the tests");
        assertThat(speculation.getCurrentState().uuid())
            .as("建议成功 + speculation 启用 → startSpeculation 必须触发（CC promptSuggestion.ts:214-222）")
            .isNotEqualTo(SpeculationEngine.IDLE_SPECULATION_STATE);
    }

    @Test
    @DisplayName("建议成功 + speculation 禁用 → 不触发 speculation")
    void execute_noSpeculation_whenDisabled() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        fork.output = "run the tests";
        SpeculationEngine speculation = new SpeculationEngine(() -> false, new FakeSpecFork(), null, (e, f) -> {});
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, speculation);
        PromptSuggestion.SuggestionResult result = ps.executeSuggestion(okCtx());
        assertThat(result).isNotNull();
        assertThat(speculation.getCurrentState().uuid())
            .as("speculation 禁用 → 保持 IDLE")
            .isEqualTo(SpeculationEngine.IDLE_SPECULATION_STATE);
    }

    @Test
    @DisplayName("生成期间 cancel → abort-after-generation 抑制（CC :171-174）")
    void execute_abortDuringGeneration_suppressed() {
        FakeTelemetry telemetry = new FakeTelemetry();
        FakeFork fork = new FakeFork();
        fork.output = "run the tests";
        PromptSuggestion ps = new PromptSuggestion(() -> true, fork, telemetry, null);
        fork.onRun = ps::cancelSuggestion; // 用户生成中途 abort（promptSuggestion.ts:96-101）
        PromptSuggestion.SuggestionResult result = ps.executeSuggestion(okCtx());
        assertThat(result).as("生成后 abort 必须丢弃建议").isNull();
        assertThat(telemetry.lastReason()).isEqualTo("aborted");
        assertThat(telemetry.lastSuppressed()).isTrue();
    }

    // ── fromMessages（SuggestionContext 派生 · CC messages + getLastAssistantMessage）─

    @Test
    @DisplayName("fromMessages：assistant 计数 / 最后内容 / isApiError / uncached 求和")
    void fromMessages_buildsContext() {
        ChatMessageDto u1 = new ChatMessageDto("u1", null, Role.user, "user", "first", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of());
        ChatMessageDto a1 = new ChatMessageDto("a1", null, Role.assistant, "assistant", "reply one", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of())
            .withUsage(new AgentUsage(100L, 50L, 200L, 0L, null, null, null));
        ChatMessageDto a2 = new ChatMessageDto("a2", null, Role.assistant, "assistant", "reply two", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of())
            .withUsage(new AgentUsage(300L, 100L, 0L, 0L, null, null, null));
        PromptSuggestion.SuggestionContext c = PromptSuggestion.SuggestionContext.fromMessages(
            List.of(u1, a1, a2), PromptSuggestion.AppStateSnapshot.enabled());
        assertThat(c.assistantTurnCount()).as("assistant 消息计数 = 2").isEqualTo(2);
        assertThat(c.lastAssistantMessage()).as("最后 assistant 内容").isEqualTo("reply two");
        assertThat(c.lastAssistantIsApiError()).isFalse();
        assertThat(c.parentUncachedTokens())
            .as("last assistant uncached = input(300) + output(100) + cache_creation(0) = 400")
            .isEqualTo(400L);
    }

    @Test
    @DisplayName("fromMessages：最后 assistant 为 API 错误 → isApiError=true")
    void fromMessages_detectsApiError() {
        // DEC-04 27 参兼容构造器（…errorDetails + usage），isApiErrorMessage=true 建模 CC AssistantMessage
        ChatMessageDto err = new ChatMessageDto(
            "a1", null, Role.assistant, "assistant", "boom", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of(),
            null, false, false, null, null,
            true, "max_output_tokens", "boom", null, null);
        PromptSuggestion.SuggestionContext c = PromptSuggestion.SuggestionContext.fromMessages(
            List.of(err), PromptSuggestion.AppStateSnapshot.enabled());
        assertThat(c.lastAssistantIsApiError()).as("last assistant isApiErrorMessage → 抑制").isTrue();
    }

    // ── SpeculationEngine 状态机（speculation.ts:402-833）────────────────────────

    @Test
    @DisplayName("SpeculationEngine：start → done（非 IDLE）/ accept → IDLE / abort → IDLE")
    void speculation_stateMachine() {
        AtomicInteger forkCalls = new AtomicInteger();
        SpeculationEngine eng = new SpeculationEngine(
            () -> true,
            (p, params, signal) -> { forkCalls.incrementAndGet(); return new SpeculationEngine.SpeculationResult("next", 0.0); },
            null,
            (e, f) -> {});
        SpeculationEngine.SpeculationState active = eng.startSpeculation("run the tests", null);
        assertThat(active.uuid()).isNotEqualTo(SpeculationEngine.IDLE_SPECULATION_STATE);
        assertThat(active.result()).isEqualTo("next");
        assertThat(forkCalls.get()).isEqualTo(1);

        assertThat(eng.acceptSpeculation()).as("accept 活跃 speculation → true").isTrue();
        assertThat(eng.getCurrentState().uuid()).isEqualTo(SpeculationEngine.IDLE_SPECULATION_STATE);

        SpeculationEngine.SpeculationState active2 = eng.startSpeculation("again", null);
        assertThat(active2.uuid()).isNotEqualTo(SpeculationEngine.IDLE_SPECULATION_STATE);
        eng.abortSpeculation();
        assertThat(eng.getCurrentState().uuid()).isEqualTo(SpeculationEngine.IDLE_SPECULATION_STATE);
    }
}
