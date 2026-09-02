package com.nexusai.application.agent.api;

import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Prompt suggestion (predictive UX) · 对齐 CC {@code services/PromptSuggestion/promptSuggestion.ts}.
 *
 * <p><b>L2 契约（CC 实际源码逐行核实，6618ab1）</b>:
 * <ul>
 *   <li><b>tryGenerateSuggestion</b>（promptSuggestion.ts:125-182）—— 主链：
 *       abort → early_conversation（assistantTurnCount&lt;2 :141）→ last_response_error（:148）→
 *       cache_cold（:152-156）→ suppress（:158-163）→ generateSuggestion（:165-170）→ abort（:171）→
 *       empty（:175）→ shouldFilterSuggestion（:179）→ 返回 {@code {suggestion, promptId, generationRequestId}}。</li>
 *   <li><b>executePromptSuggestion</b>（:184-237）—— tryGenerateSuggestion 成功后
 *       {@code setAppState(promptSuggestion)} + {@code isSpeculationEnabled() && suggestion}
 *       → {@code startSpeculation(...)}（:214-222）。</li>
 *   <li><b>getSuggestionSuppressReason</b>（:107-119）—— 5 抑制门：disabled / pending_permission /
 *       elicitation_active / plan_mode / rate_limit。</li>
 *   <li><b>getParentCacheSuppressReason</b>（:241-256）—— {@code input + cache_creation + output
 *       > MAX_PARENT_UNCACHED_TOKENS(10_000)} → 'cache_cold'。</li>
 *   <li><b>shouldFilterSuggestion</b>（:354-456）—— 12 过滤：done / meta_text / meta_wrapped /
 *       error_message / prefixed_label / too_few_words / too_many_words / too_long /
 *       multiple_sentences / has_formatting / evaluative / claude_voice。</li>
 *   <li><b>abortPromptSuggestion</b>（:96-101）—— abort 当前 controller。</li>
 * </ul>
 *
 * <p><b>enablement（Java 等价）</b>: CC 停链门控（stopHooks.ts:136-140）=
 * {@code !isBareMode() && !isEnvDefinedFalsy(CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION)}（外层，
 * StopHookPipeline 已实现）+ executePromptSuggestion 内 {@code querySource==='repl_main_thread'}。
 * shouldEnablePromptSuggestion 的 5 重门控（:37-94）不在停链路径上（启动期 TUI 用）；Java
 * {@link #isEnabledViaEnv()} 建模其 env-override 分量 + setting 门控放
 * {@link AppStateSnapshot#promptSuggestionEnabled()}（= CC getSuggestionSuppressReason 'disabled'）。
 *
 * <p><b>speculation 接线</b>: {@link SpeculationEngine} 为可空协作方（CC :214-222）——
 * 建议生成成功且 speculation 启用时触发 {@code startSpeculation}（SpeculationEngine 非死代码）。
 */
public final class PromptSuggestion {

    private static final Logger log = LoggerFactory.getLogger(PromptSuggestion.class);

    /** CC promptSuggestion.ts:258-287 SUGGESTION_PROMPT（逐字对齐）。 */
    public static final String SUGGESTION_PROMPT =
        "[SUGGESTION MODE: Suggest what the user might naturally type next into NexusAI.]\n"
      + "\n"
      + "FIRST: Look at the user's recent messages and original request.\n"
      + "\n"
      + "Your job is to predict what THEY would type - not what you think they should do.\n"
      + "\n"
      + "THE TEST: Would they think \"I was just about to type that\"?\n"
      + "\n"
      + "EXAMPLES:\n"
      + "User asked \"fix the bug and run tests\", bug is fixed → \"run the tests\"\n"
      + "After code written → \"try it out\"\n"
      + "Claude offers options → suggest the one the user would likely pick, based on conversation\n"
      + "Claude asks to continue → \"yes\" or \"go ahead\"\n"
      + "Task complete, obvious follow-up → \"commit this\" or \"push it\"\n"
      + "After error or misunderstanding → silence (let them assess/correct)\n"
      + "\n"
      + "Be specific: \"run the tests\" beats \"continue\".\n"
      + "\n"
      + "NEVER SUGGEST:\n"
      + "- Evaluative (\"looks good\", \"thanks\")\n"
      + "- Questions (\"what about...?\")\n"
      + "- Claude-voice (\"Let me...\", \"I'll...\", \"Here's...\")\n"
      + "- New ideas they didn't ask about\n"
      + "- Multiple sentences\n"
      + "\n"
      + "Stay silent if the next step isn't obvious from what the user said.\n"
      + "\n"
      + "Format: 2-12 words, match the user's style. Or nothing.\n"
      + "\n"
      + "Reply with ONLY the suggestion, no quotes or explanation.";

    /** CC promptSuggestion.ts:466 遥测事件名。 */
    public static final String ANALYTICS_EVENT = "tengu_prompt_suggestion";

    /** CC promptSuggestion.ts:239 MAX_PARENT_UNCACHED_TOKENS = 10_000。 */
    public static final long MAX_PARENT_UNCACHED_TOKENS = 10_000L;

    /** CC promptSuggestion.ts:33-35 getPromptVariant() 恒返回 'user_intent'。 */
    public static final String PROMPT_VARIANT = "user_intent";

    private static final Pattern P_SILENCE_IS = Pattern.compile("\\bsilence is\\b|\\bstay(s|ing)? silent\\b");
    private static final Pattern P_BARE_SILENCE = Pattern.compile("^\\W*silence\\W*$");
    private static final Pattern P_META_WRAPPED = Pattern.compile("^\\(.*\\)$|^\\[.*\\]$");
    private static final Pattern P_PREFIXED_LABEL = Pattern.compile("^\\w+:\\s");
    private static final Pattern P_MULTI_SENTENCE = Pattern.compile("[.!?]\\s+[A-Z]");
    private static final Pattern P_HAS_FORMATTING = Pattern.compile("[\\n*]|\\*\\*");
    private static final Pattern P_EVALUATIVE = Pattern.compile(
        "thanks|thank you|looks good|sounds good|that works|that worked|that's all|nice|great|perfect|makes sense|awesome|excellent");
    private static final Pattern P_CLAUDE_VOICE = Pattern.compile(
        "^(let me|i'll|i've|i'm|i can|i would|i think|i notice|here's|here is|here are|that's|this is|this will|you can|you should|you could|sure,|of course|certainly)",
        Pattern.CASE_INSENSITIVE);

    private static final Set<String> ALLOWED_SINGLE_WORDS = Set.of(
        // Affirmatives
        "yes", "yeah", "yep", "yea", "yup", "sure", "ok", "okay",
        // Actions
        "push", "commit", "deploy", "stop", "continue", "check", "exit", "quit",
        // Negation
        "no");

    /** CC tryGenerateSuggestion 返回 {@code {suggestion, promptId, generationRequestId}}（:181）。 */
    public record SuggestionResult(
            String suggestion, String promptId, long timestamp, String generationRequestId) {
        /** 空结果（禁用 / 抑制时的返回载体）。 */
        public static SuggestionResult empty() {
            return new SuggestionResult("", PROMPT_VARIANT, System.currentTimeMillis(), null);
        }
    }

    /** fork agent 运行结果 · 对齐 CC generateSuggestion（:334-351）提取首个 assistant text + requestId。 */
    public record ForkResult(String output, boolean cancelled, String generationRequestId) {}

    public interface ForkedAgent {
        ForkResult run(String prompt, Map<String, Object> params, Object signal);
    }

    public interface SuggestionTelemetry {
        void log(String event, Map<String, Object> fields);
    }

    /** CC getSuggestionSuppressReason 输入（appState 相关字段）· promptSuggestion.ts:107-119。 */
    public record AppStateSnapshot(
            boolean promptSuggestionEnabled,   // CC: appState.promptSuggestionEnabled → 'disabled'
            boolean pendingWorkerRequest,      // CC: appState.pendingWorkerRequest → 'pending_permission'
            boolean pendingSandboxRequest,     // CC: appState.pendingSandboxRequest → 'pending_permission'
            boolean elicitationQueueNonEmpty,  // CC: appState.elicitation.queue.length > 0 → 'elicitation_active'
            boolean planMode,                  // CC: appState.toolPermissionContext.mode === 'plan' → 'plan_mode'
            boolean rateLimited) {             // CC: USER_TYPE==='external' && limits.status!=='allowed' → 'rate_limit'
        public static AppStateSnapshot enabled() {
            return new AppStateSnapshot(true, false, false, false, false, false);
        }
    }

    /** tryGenerateSuggestion 所需对话上下文 · 对齐 CC messages + getLastAssistantMessage（:141/:147/:241）。 */
    public record SuggestionContext(
            String lastAssistantMessage,    // CC: getLastAssistantMessage content
            int assistantTurnCount,         // CC: count(messages, m.type==='assistant')
            boolean lastAssistantIsApiError,// CC: lastAssistantMessage?.isApiErrorMessage
            long parentUncachedTokens,      // CC: usage.input_tokens + cache_creation_input_tokens + output_tokens
            AppStateSnapshot appState) {    // CC: getSuggestionSuppressReason(appState)
        public static SuggestionContext fromMessages(
                List<ChatMessageDto> messages, AppStateSnapshot appState) {
            AppStateSnapshot snap = appState != null ? appState : AppStateSnapshot.enabled();
            if (messages == null) {
                return new SuggestionContext(null, 0, false, 0L, snap);
            }
            int assistantCount = 0;
            ChatMessageDto lastAssistant = null;
            for (ChatMessageDto m : messages) {
                if (m != null && m.role() == Role.assistant) {
                    assistantCount++;
                    lastAssistant = m;
                }
            }
            long uncached = 0L;
            if (lastAssistant != null && lastAssistant.usage() != null) {
                AgentUsage usage = lastAssistant.usage();
                uncached = usage.inputTokens()
                    + usage.outputTokens()
                    + (usage.cacheCreationInputTokens() == null ? 0 : usage.cacheCreationInputTokens());
            }
            return new SuggestionContext(
                lastAssistant == null ? null : lastAssistant.content(),
                assistantCount,
                lastAssistant != null && lastAssistant.isApiErrorMessage(),
                uncached,
                snap);
        }
    }

    private final BooleanSupplier enabledSupplier;
    private final ForkedAgent forkedAgent;
    private final SuggestionTelemetry telemetry;
    private final SpeculationEngine speculationEngine;
    private final AtomicReference<Object> currentAbort = new AtomicReference<>();

    public PromptSuggestion(BooleanSupplier enabledSupplier,
            ForkedAgent forkedAgent,
            SuggestionTelemetry telemetry,
            SpeculationEngine speculationEngine) {
        this.enabledSupplier = enabledSupplier == null ? () -> false : enabledSupplier;
        this.forkedAgent = forkedAgent == null
            ? (p, params, signal) -> new ForkResult("", false, null)
            : forkedAgent;
        this.telemetry = telemetry == null ? (e, f) -> {} : telemetry;
        this.speculationEngine = speculationEngine;
    }

    public PromptSuggestion() {
        this(null, null, null, null);
    }

    /**
     * CC shouldEnablePromptSuggestion（:37-94）env 分量 · {@code isEnvTruthy(CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION)}。
     * Java 无 GrowthBook/交互会话检测 → 仅建模 env override；缺省 false（对齐 CC 生产
     * GrowthBook 默认关闭语义）。{@code promptSuggestionEnabled} setting 门控走
     * {@link AppStateSnapshot#promptSuggestionEnabled()}（getSuggestionSuppressReason 'disabled'）。
     */
    public static boolean isEnabledViaEnv() {
        String v = System.getProperty("CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION",
            System.getenv("CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION"));
        if (v == null || v.isBlank()) {
            return false;
        }
        String t = v.trim();
        return "true".equalsIgnoreCase(t) || "1".equals(t)
            || "on".equalsIgnoreCase(t) || "yes".equalsIgnoreCase(t);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabledSupplier.getAsBoolean());
    }

    /** CC getParentCacheSuppressReason（promptSuggestion.ts:241-256）。 */
    public static String getParentCacheSuppressReason(long uncachedTokens) {
        return uncachedTokens > MAX_PARENT_UNCACHED_TOKENS ? "cache_cold" : null;
    }

    /** CC getSuggestionSuppressReason（promptSuggestion.ts:107-119）。 */
    public static String getSuggestionSuppressReason(AppStateSnapshot appState) {
        if (appState == null) return null;
        if (!appState.promptSuggestionEnabled()) return "disabled";
        if (appState.pendingWorkerRequest() || appState.pendingSandboxRequest()) return "pending_permission";
        if (appState.elicitationQueueNonEmpty()) return "elicitation_active";
        if (appState.planMode()) return "plan_mode";
        if (appState.rateLimited()) return "rate_limit";
        return null;
    }

    /**
     * CC shouldFilterSuggestion（promptSuggestion.ts:354-456）—— 返回过滤原因；null = 通过。
     * 过滤顺序与 CC 一致（:367-445 数组顺序）。
     */
    public static String filterReason(String suggestion) {
        if (suggestion == null) return "empty";
        String lower = suggestion.toLowerCase();
        String trimmed = suggestion.trim();
        int wordCount = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;

        // 'done'（CC :368）
        if (lower.equals("done")) return "done";
        // meta_text（CC :371-380）
        if (lower.equals("nothing found") || lower.equals("nothing found.")
                || lower.startsWith("nothing to suggest") || lower.startsWith("no suggestion")
                || P_SILENCE_IS.matcher(lower).find()
                || P_BARE_SILENCE.matcher(lower).find()) {
            return "meta_text";
        }
        // meta_wrapped（CC :384）
        if (P_META_WRAPPED.matcher(suggestion).find()) return "meta_wrapped";
        // error_message（CC :389-393）
        if (lower.startsWith("api error:") || lower.startsWith("prompt is too long")
                || lower.startsWith("request timed out") || lower.startsWith("invalid api key")
                || lower.startsWith("image was too large")) {
            return "error_message";
        }
        // prefixed_label（CC :395）
        if (P_PREFIXED_LABEL.matcher(suggestion).find()) return "prefixed_label";
        // too_few_words（CC :398-426）—— 允许 slash 命令 + 常见单词
        if (wordCount < 2) {
            if (suggestion.startsWith("/")) return null;
            if (!ALLOWED_SINGLE_WORDS.contains(lower)) return "too_few_words";
        }
        // too_many_words（CC :428）
        if (wordCount > 12) return "too_many_words";
        // too_long（CC :429）
        if (suggestion.length() >= 100) return "too_long";
        // multiple_sentences（CC :430）
        if (P_MULTI_SENTENCE.matcher(suggestion).find()) return "multiple_sentences";
        // has_formatting（CC :431）
        if (P_HAS_FORMATTING.matcher(suggestion).find()) return "has_formatting";
        // evaluative（CC :433-437）
        if (P_EVALUATIVE.matcher(lower).find()) return "evaluative";
        // claude_voice（CC :439-444）
        if (P_CLAUDE_VOICE.matcher(suggestion).find()) return "claude_voice";
        return null;
    }

    public static boolean shouldFilterSuggestion(String suggestion) {
        return filterReason(suggestion) != null;
    }

    /**
     * CC tryGenerateSuggestion（promptSuggestion.ts:125-182）主链。
     *
     * <p><b>abort 语义</b>（对齐 CC）: CC executePromptSuggestion 创建 {@code currentAbortController}
     * 并作为 {@code abortController} 传入 tryGenerateSuggestion；abort 检查读传入的本地
     * controller（:136/:171 {@code abortController.signal.aborted}），模块级
     * {@code currentAbortController} 仅做外部 abort 入口（:96-101）。Java 等价：
     * {@code signal} 参数 = 传入的本地 controller；{@link #cancelSuggestion()} 取消的是同一个实例
     * （若该实例仍在 {@code currentAbort} 模块槽）。
     *
     * @param ctx    对话上下文（messages 派生）；null → 视作无上下文（early_conversation 抑制）
     * @param signal abort signal（CC abortController；null = 无 abort）
     * @return {@link SuggestionResult}；被抑制 / 过滤 / 无建议 → null（CC 语义）
     */
    public SuggestionResult tryGenerateSuggestion(SuggestionContext ctx, Object signal) {
        if (isAborted(signal)) {
            logSuppressed("aborted", null);
            return null;
        }
        SuggestionContext c = ctx != null ? ctx
            : new SuggestionContext(null, 0, false, 0L, AppStateSnapshot.enabled());
        // CC :141-145 assistantTurnCount < 2 → early_conversation
        if (c.assistantTurnCount() < 2) {
            logSuppressed("early_conversation", null);
            return null;
        }
        // CC :147 lastAssistantMessage 缺省（Java 无消息 → 静默跳过）
        if (c.lastAssistantMessage() == null) {
            log.debug("PromptSuggestion: no last assistant message, skipping suggestion");
            return null;
        }
        // CC :148-151 lastAssistantMessage?.isApiErrorMessage
        if (c.lastAssistantIsApiError()) {
            logSuppressed("last_response_error", null);
            return null;
        }
        // CC :152-156 getParentCacheSuppressReason
        String cacheReason = getParentCacheSuppressReason(c.parentUncachedTokens());
        if (cacheReason != null) {
            logSuppressed(cacheReason, null);
            return null;
        }
        // CC :158-163 getSuggestionSuppressReason(appState)
        String suppressReason = getSuggestionSuppressReason(c.appState());
        if (suppressReason != null) {
            logSuppressed(suppressReason, null);
            return null;
        }
        // CC :165-170 generateSuggestion（fork agent，tool 全 deny）
        Map<String, Object> params = Map.of(
            "system_prompt", SUGGESTION_PROMPT,
            "max_tokens", 100,
            "model", "haiku");
        ForkResult result;
        try {
            result = forkedAgent.run(c.lastAssistantMessage(), params, signal);
        } catch (Exception ex) {
            log.warn("PromptSuggestion: fork agent 失败(静默): {}", ex.getMessage());
            return null;
        }
        // CC :171-174 aborted after generation
        if (isAborted(signal)) {
            logSuppressed("aborted", null);
            return null;
        }
        // CC :175-178 !suggestion → empty
        String suggestion = result.output() == null ? null : result.output().trim();
        if (suggestion == null || suggestion.isEmpty()) {
            logSuppressed("empty", null);
            return null;
        }
        // CC :179 shouldFilterSuggestion
        String filterReason = filterReason(suggestion);
        if (filterReason != null) {
            logSuppressed(filterReason, suggestion);
            return null;
        }
        if (log.isInfoEnabled()) {
            log.info("PromptSuggestion: 建议生成成功 suggestion=\"{}\" promptId={} generationRequestId={}",
                abbreviate(suggestion, 50), PROMPT_VARIANT, result.generationRequestId());
        }
        return new SuggestionResult(suggestion, PROMPT_VARIANT,
            System.currentTimeMillis(), result.generationRequestId());
    }

    /**
     * CC executePromptSuggestion（promptSuggestion.ts:184-237）主链 —— tryGenerateSuggestion
     * 成功后触发 speculation（:214-222 isSpeculationEnabled && suggestion → startSpeculation）。
     *
     * @param ctx 对话上下文（null 容错）
     * @return 建议结果；被抑制 / 禁用 → null
     */
    public SuggestionResult executeSuggestion(SuggestionContext ctx) {
        if (!isEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("PromptSuggestion: disabled, skipping (CC shouldEnablePromptSuggestion env gate)");
            }
            return null;
        }
        SpeculationEngine.AbortController abortController = new SpeculationEngine.AbortController();
        currentAbort.set(abortController);
        try {
            SuggestionResult result = tryGenerateSuggestion(ctx, abortController);
            if (result != null && !result.suggestion().isBlank()
                    && speculationEngine != null && speculationEngine.isSpeculationEnabled()) {
                speculationEngine.startSpeculation(result.suggestion(),
                    new SpeculationEngine.CacheSafeParams(Map.of()));
            }
            return result;
        } finally {
            if (currentAbort.get() == abortController) {
                currentAbort.set(null);
            }
        }
    }

    /** CC abortPromptSuggestion（promptSuggestion.ts:96-101）· 取消模块级当前 controller。 */
    public void cancelSuggestion() {
        Object current = currentAbort.getAndSet(null);
        if (current instanceof SpeculationEngine.AbortController ac) {
            ac.cancel();
        } else if (current != null) {
            try {
                current.getClass().getMethod("cancel").invoke(current);
            } catch (Exception ex) {
                log.debug("PromptSuggestion: cancel failed: {}", ex.getMessage());
            }
        }
    }

    private static boolean isAborted(Object signal) {
        return signal instanceof SpeculationEngine.AbortController ac && ac.isCancelled();
    }

    /** CC logSuggestionSuppressed（promptSuggestion.ts:499-523）· outcome='suppressed' 事件。 */
    private void logSuppressed(String reason, String suggestion) {
        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("outcome", "suppressed");
        fields.put("reason", reason);
        fields.put("prompt_id", PROMPT_VARIANT);
        if (suggestion != null) {
            fields.put("suggestion", suggestion);
        }
        telemetry.log(ANALYTICS_EVENT, fields);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…(" + s.length() + ")";
    }
}
