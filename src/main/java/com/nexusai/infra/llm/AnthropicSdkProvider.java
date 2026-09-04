package com.nexusai.infra.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ThinkingConfigParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.BashCodeExecutionToolResultBlockParam;
import com.anthropic.models.messages.CodeExecutionToolResultBlockParam;
import com.anthropic.models.messages.ContainerUploadBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.MidConversationSystemBlockParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.SearchResultBlockParam;
import com.anthropic.models.messages.ServerToolUseBlockParam;
import com.anthropic.models.messages.TextEditorCodeExecutionToolResultBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ToolReferenceBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolSearchToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.WebFetchToolResultBlockParam;
import com.anthropic.models.messages.WebSearchToolResultBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.MicroCompactResult;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.PromptCaching;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.prompt.SystemPromptBlocksBuilder;
import com.nexusai.application.agent.recovery.ErrorClassifier;
import com.nexusai.application.agent.recovery.RetryDelayCalculator;
import com.nexusai.application.agent.recovery.RetryOptions;
import com.nexusai.application.agent.recovery.TransientErrorHandler;
import com.nexusai.application.agent.recovery.WithRetryEngine;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.common.RequestContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Anthropic Provider · 官方 Java SDK 实现（anthropic-java）。
 *
 * <p>[DEC-RV-07] 引入 Anthropic 官方 Java SDK（{@code com.anthropic:anthropic-java:2.53.0}），
 * 替代旧 {@code AnthropicProvider} 的手写 HTTP（java.net.http + SSE 自解析；该类已整体删除）。
 * 行为对齐 CC {@code Open-ClaudeCode/src/services/api/claude.ts}：
 * <ul>
 *   <li><b>maxRetries=0</b>（claude.ts:1781）→ SDK client builder {@code .maxRetries(0)}，
 *       自动重试关闭，交由上层（LlmAgentLoop / WithRetryEngine / OAuth401Retry 外层）手工重试。</li>
 *   <li><b>raw stream</b>（claude.ts:1818，不用 SDK partial JSON）→
 *       {@code client.messages().createStreaming(params)} 逐 {@link RawMessageStreamEvent} 映射，
 *       不用 SDK 累加 partial JSON。</li>
 *   <li><b>withResponse 取 request_id</b>（claude.ts:1832）→
 *       {@code MessageService.WithRawResponse.create} 的 {@code HttpResponseFor.requestId()}。</li>
 * </ul>
 *
 * <p><b>OAuth 链路保留</b>（DEC-RV-07 硬约束）：本 provider 纯 API-key（x-api-key），
 * OAuth401Retry/OAuth401Refresher 为独立类零改动；SDK 未来 OAuth 需在 provider 外层 wrap。
 *
 * <p>[RV-MERGE] 旧 {@code AnthropicProvider} 已删除，其静态 max-token 工具
 * （{@link #getMaxOutputTokensForModel} / {@link #isMaxTokensCapEnabled} /
 * {@link #validateBoundedIntEnvVar} / {@link #getModelMaxOutputTokens}）与 DEC-RV-03 流式→非流式
 * 回退链（{@link #shouldUseNonStreamingFallback} / {@link #computeInitialConsecutive529Errors} /
 * {@link #nonStreamingFallback}）全部迁入本类；MaxTokensHandler / AgentLoopContext 已重指向本类。
 *
 * @see LlmProvider
 */
@Component
public class AnthropicSdkProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicSdkProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** [IMP-16 REWORK] task_budget beta header · CC original: TASK_BUDGETS_BETA_HEADER (claude.ts betas.ts:16). */
    static final String TASK_BUDGETS_BETA_HEADER = "task-budgets-2026-03-13";
    /** [C-31] effort beta header · CC original: EFFORT_BETA_HEADER (claude.ts betas.ts:15). */
    static final String EFFORT_BETA_HEADER = "effort-2025-11-24";
    /** [FIX-FR] structured-outputs beta header · CC original: STRUCTURED_OUTPUTS_BETA_HEADER (betas.ts:8). */
    static final String STRUCTURED_OUTPUTS_BETA_HEADER = "structured-outputs-2025-12-15";
    /** [IMP-SP2-07 ✗-13] prompt-caching-scope beta header · CC original:
     *  PROMPT_CACHING_SCOPE_BETA_HEADER（constants/betas.ts:17-18）。firstParty 全局缓存场景
     *  必须随 anthropic-beta 下发（claude.ts:1217-1222），否则 API 400 / 缓存不生效（§10 风险 1）。 */
    static final String PROMPT_CACHING_SCOPE_BETA_HEADER = "prompt-caching-scope-2026-01-05";
    /** [DEC-RV-09] 非流式请求 max_tokens 硬 cap · CC original: MAX_NON_STREAMING_TOKENS
     *  (Open-ClaudeCode/src/services/api/claude.ts:3354) = 64_000。
     *  非流式请求有 10 分钟硬上限（platform docs long-requests）；SDK 默认 21333-token cap 由
     *  10min×128k tokens/hour 推导，CC 用 client 级 timeout 绕过 SDK cap 因此自行 cap 到 64_000。
     *  纯 const 硬编码，无 env / settings / feature-flag 配置入口（不可配置）。 */
    static final int MAX_NON_STREAMING_TOKENS = 64_000;
    /** [IMP-15] 未启用 slot cap 时模型族默认 max_tokens · CC original: MAX_OUTPUT_TOKENS_DEFAULT / MAX_OUTPUT_TOKENS_UPPER_LIMIT
     *  (Open-ClaudeCode/src/utils/context.ts:15-16)。 */
    static final int MAX_OUTPUT_TOKENS_DEFAULT = 32_000;
    static final int MAX_OUTPUT_TOKENS_UPPER_LIMIT = 64_000;
    /** CC original: CAPPED_DEFAULT_MAX_TOKENS (Open-ClaudeCode/src/utils/context.ts:24) = 8_000 · slot-reservation 上限。 */
    static final int CAPPED_DEFAULT_MAX_TOKENS = 8_000;
    /** [G-17] cap.max_tokens 生效下限 · CC original: {@code cap.max_tokens >= 4_096} (Open-ClaudeCode/src/utils/context.ts:204)。 */
    static final int MAX_OUTPUT_TOKENS_CAP_MIN = 4_096;
    /** [F1] settings 表 singleton id=1（V1__init_schema.sql 已默认插入）· settings.maxOutputTokens 读取用。 */
    private static final int SETTINGS_SINGLETON_ID = 1;
    /** [IMP-15] 64k 升级 feature gate 系统属性名 · CC original: {@code tengu_otk_slot_v1}
     *  growthbook flag（Open-ClaudeCode/src/query.ts:1195-1198 + claude.ts:3394-3397，默认 false）。 */
    static final String TENGU_OTK_SLOT_V1_PROPERTY = "nexusai.feature.tengu-otk-slot-v1";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.loop.FeatureFlags featureFlags;

    /** [A-13] Telemetry bean · per-LLM-call API terminal 事件发射（对齐 CC logging.ts:294/:461）；
     *  null（测试/未接线）→ 静默跳过。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private volatile Telemetry telemetry;

    /** [G-17] models.max_tokens DB 能力来源（CC getModelCapability 的 Java 等价位）· 抬 upperLimit 用；
     *  null（测试/未接线）→ 静默回落家族表（等价 CC cap 未命中分支）。
     *  <p>实例注入供 {@link #bridgeDbMappersToStatic()} 桥接至静态持有（G-18 请求体 DB 优先单源）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private volatile ModelMapper modelMapper;

    /** [G-17] ProviderMapper · resolveMaxTokens 全名路径（providerName/modelName）用；null → 按 name 兼容路径。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private volatile ProviderMapper providerMapper;

    /** [F1] settings.maxOutputTokens 有界 override 来源（CC envValidation.ts:9-38 CLAUDE_CODE_MAX_OUTPUT_TOKENS
     *  迁移为 settings 配置）· null（测试/未接线）→ 静默回落模型默认（等价 CC env 未设置分支）。
     *  <p>实例注入供 {@link #bridgeDbMappersToStatic()} 桥接至静态持有（static 方法读 settings 行）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private volatile SettingsMapper settingsMapper;

    private volatile com.nexusai.application.agent.lsp.PromptCacheBreakDetection promptCacheBreak;

    // ════════════════════════════════════════════════════════════════════
    // [G-18] DB 优先 max_tokens 单源（请求体与压缩链同源）
    //   DB mapper 实例字段由 [G-17] 已注入（models.max_tokens 能力来源），本段仅桥接至静态持有
    // ════════════════════════════════════════════════════════════════════

    /** [G-18] Spring 装配后静态持有 DB mapper · 供 static {@code buildMessageParams} 请求体
     *  max_tokens DB 优先解析（static 方法无法读实例字段）；未接线 → null → 回落 CC 家族表。 */
    private static volatile ModelMapper staticModelMapper;
    private static volatile ProviderMapper staticProviderMapper;
    /** [F1] settings.maxOutputTokens 有界 override 来源 · 静态持有（static 方法读 settings 行）；
     *  未接线 → null → 回落模型默认（等价 CC env 未设置分支）。 */
    private static volatile SettingsMapper staticSettingsMapper;

    /**
     * [G-18] Spring 装配后桥接实例注入的 DB mapper 到静态持有（AgentColorCommand @PostConstruct
     * 同款模式）。plain JUnit（无 Spring 容器）不触发 → {@code static*} 恒 null → 请求体回落
     * CC 家族表，与 CompactThresholdSystem 未注入语义一致（fail loud：warn 记录）。
     */
    @PostConstruct
    public void bridgeDbMappersToStatic() {
        staticModelMapper = this.modelMapper;
        staticProviderMapper = this.providerMapper;
        staticSettingsMapper = this.settingsMapper;
        if (staticModelMapper == null || staticProviderMapper == null) {
            log.warn("[AnthropicSdkProvider] modelMapper/providerMapper 未注入（无 Spring 上下文），"
                + "请求体 max_tokens 走 CC 家族表（G-18 DB 优先不可用，回落家族表保持单源）");
        }
    }

    @Override
    public String type() {
        return "anthropic";
    }

    // ════════════════════════════════════════════════════════════════════
    // client 构建 · 对齐 CC client.ts:143-144 + claude.ts:1781 maxRetries:0
    // ════════════════════════════════════════════════════════════════════

    /**
     * 构建 Anthropic SDK client · 对齐 CC {@code maxRetries: 0}（claude.ts:1781，
     * "Disabled auto-retry in favor of manual implementation"）。
     *
     * <p>每次请求按 {@link ProviderConfig} 构建（与 OpenAiSdkProvider 先例同构）；SDK
     * 内部 okhttp，baseUrl 非空时覆盖（默认 https://api.anthropic.com）。
     *
     * @param config 解密后的运行时配置（apiKey + 可选 baseUrl）
     */
    static AnthropicClient buildClient(ProviderConfig config) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
            .apiKey(config.apiKey())
            // [CC claude.ts:1781] Disabled auto-retry in favor of manual implementation
            .maxRetries(0);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(normalizeBaseUrl(config.baseUrl()));
        }
        return builder.build();
    }

    // ════════════════════════════════════════════════════════════════════
    // stream · blocks 唯一重载（⊕C-1 已删除 String systemPrompt 兼容路径 · 发送契约数组态唯一）
    // ════════════════════════════════════════════════════════════════════


    @Override
    public void stream(ProviderConfig config,
                       String modelName,
                       List<SystemPromptBlock> systemPromptBlocks,
                       List<ChatMessageDto> history,
                       ArrayNode tools,
                       Integer maxOutputTokensOverride,
                       TaskBudgetParam taskBudget,
                       String effortValue,
                       String querySource,
                       Consumer<String> onChunk,
                       Consumer<AssistantMessage> onAssistantMessage,
                       Consumer<ToolUseBlock> onToolCallComplete,
                       Consumer<String> onReasoningChunk,
                       Runnable onStreamingFallback,
                       com.nexusai.application.agent.tool.AbortController abortController,
                       Consumer<Throwable> onError,
                       Runnable onComplete) {
        AtomicBoolean aborted = new AtomicBoolean(false);
        if (abortController != null) {
            abortController.onCancel(ac -> {
                if (aborted.compareAndSet(false, true)) {
                    onError.accept(new java.util.concurrent.CancellationException(
                        ac.reason() != null ? ac.reason() : "stream aborted"));
                }
            });
        }
        doStream(config, modelName, systemPromptBlocks, history, tools, maxOutputTokensOverride,
            taskBudget, effortValue, querySource,
            onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
            onStreamingFallback, aborted, onError, onComplete);
    }

    // ════════════════════════════════════════════════════════════════════
    // doStream · SDK createStreaming + RawMessageStreamEvent 逐事件映射
    // ════════════════════════════════════════════════════════════════════


    /**
     * 流式核心 · blocks 变体 · SDK createStreaming + RawMessageStreamEvent 逐事件映射。
     *
     * <p>[DEC-RV-07] 替代手写 SSE 解析（原 AnthropicProvider.doStream + parseAnthropicEvent）。
     * 行为对齐 CC claude.ts:1818 raw stream（不用 SDK partial JSON）：SDK 把 SSE 解析为
     * {@link RawMessageStreamEvent} 密封 union，本方法逐事件映射到 {@link StreamState}：
     * <ul>
     *   <li>message_start → usage.cacheRead/cacheCreation + outputTokens（初始）</li>
     *   <li>content_block_start（tool_use）→ ToolCallAccumulator</li>
     *   <li>content_block_delta（text/input_json/thinking）→ onChunk / tool args / onReasoningChunk</li>
     *   <li>content_block_stop → onToolCallComplete</li>
     *   <li>message_delta → stop_reason（finishReason）+ usage.output_tokens（最终覆盖）</li>
     *   <li>message_stop → 流结束</li>
     *   <li>unknown（ping）→ no-op（SDK 把 ping 落入 {@code _json()}，校验通过不抛）</li>
     * </ul>
     *
     * <p>[H13-GAP-4 v3] 硬中断：abort 后消费循环在事件边界检查 aborted 停止，以
     * {@link java.util.concurrent.CancellationException} 调 onError（abort listener 已发）。
     */
    private void doStream(ProviderConfig config,
                          String modelName,
                          List<SystemPromptBlock> systemPromptBlocks,
                          List<ChatMessageDto> history,
                          ArrayNode tools,
                          Integer maxOutputTokensOverride,
                          TaskBudgetParam taskBudget,
                          String effortValue,
                          String querySource,
                          Consumer<String> onChunk,
                          Consumer<AssistantMessage> onAssistantMessage,
                          Consumer<ToolUseBlock> onToolCallComplete,
                          Consumer<String> onReasoningChunk,
                          Runnable onStreamingFallback,
                          AtomicBoolean aborted,
                          Consumer<Throwable> onError,
                          Runnable onComplete) {
        if (config == null || !config.isUsable()) {
            onError.accept(new IllegalStateException(
                "AnthropicSdkProvider.stream called without usable ProviderConfig"));
            return;
        }

        long streamStartMs = System.currentTimeMillis(); // [A-13] per-LLM-call durationMs 起点 · CC logging.ts start

        try {
            AnthropicClient client = buildClient(config);
            MessageCreateParams params = buildMessageParams(modelName, systemPromptBlocks, history, tools,
                maxOutputTokensOverride, taskBudget, effortValue, null, null, null,
                StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl(config.baseUrl()), config);

            String resolvedEffort = EffortSupport.resolveAppliedEffort(modelName, effortValue);
            if (log.isDebugEnabled()) {
                log.debug("AnthropicSdkProvider stream → model={} messages={} tools={} maxOutputTokens={} taskBudget={} effort={} · CC claude.ts:479-500 + :1458",
                    modelName,
                    history == null ? 0 : history.size(),
                    tools == null ? 0 : tools.size(),
                    maxOutputTokensOverride,
                    taskBudget != null
                        ? "(" + taskBudget.total() + "," + taskBudget.remaining() + ")"
                        : "null",
                    resolvedEffort);
            }

            // [IMP-SP-08] promptCacheBreakDetection recordPromptState（发送前）· CC claude.ts:1471
            if (promptCacheBreakEnabled()) {
                try {
                    promptCacheBreakDetector().recordPromptState(
                        buildPromptStateSnapshot(systemPromptBlocks, tools, querySource, modelName, effortValue));
                } catch (Exception e) {
                    log.warn("[PromptCacheBreak] recordPromptState 失败: {}", e.toString());
                }
            }

            // [D-4] withRawResponse 捕获 request_id 头（CC claude.ts:1832-1834 withResponse →
            //   streamRequestId = result.request_id · req_xxx 格式，非 message id msg_xxx）·
            //   子 agent invokingRequestId 归因值源（AgentTool.tsx:723/:778 assistantMessage?.requestId）。
            HttpResponseFor<StreamResponse<RawMessageStreamEvent>> rawResp =
                client.messages().withRawResponse().createStreaming(params);
            String requestId = rawResp.requestId().orElse(null);

            // 逐事件消费 · [H13-GAP-4 v3] 迭代器 + aborted 事件边界检查
            StreamState state = new StreamState();
            state.requestId = requestId;
            if (log.isDebugEnabled()) {
                log.debug("AnthropicSdkProvider 流式捕获 request_id={} · CC claude.ts:1834 streamRequestId=result.request_id",
                    requestId);
            }
            StreamResponse<RawMessageStreamEvent> resp = rawResp.parse();
            AtomicBoolean finished = new AtomicBoolean(false);
            java.util.Set<String> completedToolIds =
                onToolCallComplete == null ? null : java.util.concurrent.ConcurrentHashMap.newKeySet();

            java.util.Iterator<RawMessageStreamEvent> it = resp.stream().iterator();
            while (it.hasNext()) {
                if (aborted != null && aborted.get()) {
                    break; // 硬中断: 不再消费
                }
                if (finished.get()) break;
                try {
                    RawMessageStreamEvent event = it.next();
                    mapStreamEvent(event, state, onChunk, onToolCallComplete, onReasoningChunk, completedToolIds);
                } catch (Exception e) {
                    log.warn("AnthropicSdkProvider 事件映射失败: {}", e.toString());
                }
            }

            // abort 后不触发 onAssistantMessage / onComplete（onError 已由 abort listener 发出）
            if (aborted != null && aborted.get()) {
                // [A-13] per-LLM-call 错误事件 · CC claude.ts:2738 APIUserAbortError 在 abort
                //   返回前也先 logAPIError（claude.ts:2720 logAPIError 先于 :2738 abort 返回）。
                emitApiTerminalEvent("tengu_api_error", apiErrorAttrs(modelName,
                    history == null ? 0 : history.size(),
                    new java.util.concurrent.CancellationException("stream aborted"),
                    querySource, streamStartMs));
                log.info("AnthropicSdkProvider stream aborted: SDK 流消费已中断, 跳过 onComplete");
                return;
            }
            consumePostCompactionAtApiSuccess(history);
            if (onAssistantMessage != null) {
                onAssistantMessage.accept(buildAssistantMessage(state));
            }

            if (promptCacheBreakEnabled()) {
                try {
                    promptCacheBreakDetector().checkResponseForCacheBreak(
                        querySource, state.cacheReadInputTokens, state.cacheCreationInputTokens,
                        null, null, null);
                } catch (Exception e) {
                    log.warn("[PromptCacheBreak] checkResponseForCacheBreak 失败: {}", e.toString());
                }
            }

            finished.set(true);
            // [A-13] per-LLM-call 流式成功事件 · CC claude.ts:2858 logAPISuccessAndDuration
            emitApiTerminalEvent("tengu_api_success", apiSuccessAttrs(modelName,
                history == null ? 0 : history.size(), state, querySource, streamStartMs));
            onComplete.run();
        } catch (Exception e) {
            RuntimeException translated = translateSdkError(e);
            // [A-13] per-LLM-call 流式错误事件 · CC claude.ts:2720/:2776 logAPIError
            //   （翻译后 error/status 面可用；abort 也先记录再返回）
            emitApiTerminalEvent("tengu_api_error", apiErrorAttrs(modelName,
                history == null ? 0 : history.size(), translated, querySource, streamStartMs));
            if (aborted != null && aborted.get()) {
                return;
            }
            // [P-1] 流式 SDK 异常先翻译 → LlmApiException（保留 headers）· CC claude.ts:2501/2451
            //   throw streamingError 恒为 APIError（headers 是重试链载荷 withRetry.ts:519-528/:814-822）；
            //   Java raw AnthropicServiceException 使 shouldUseNonStreamingFallback / nonStreamingFallback
            //   的 ErrorClassifier 类型闸（is529Error/isRetryable，LlmApiException instanceof 前置）与
            //   LlmAgentLoop Path3 instanceof LlmApiException 全失守 → 429/529 不可重试。
            //   IOException（连接中断）包 RuntimeException 后 isConnectionError 5 层 cause 解包仍可达
            //   （ErrorClassifier.isConnectionError:244-264）。
            // [RV-03-01] 流式中途失败 → 非流式回退 · 对齐 CC claude.ts:2505-2562 catch 分支：
            //   流式失败（连接中断等）时，除非用户中止/超时/门控禁用，否则切非流式重试，
            //   并预置流式 529 计数（claude.ts:2559 is529Error(streamingError) ? 1 : 0）。
            //   DEC-RV-03 回退链迁移自旧 AnthropicProvider.nonStreamingFallback。
            if (onStreamingFallback != null
                && shouldUseNonStreamingFallback(translated, aborted, streamingFallbackDisabled())) {
                if (nonStreamingFallback(config, modelName, systemPromptBlocks, history, tools,
                    maxOutputTokensOverride, taskBudget, effortValue, translated, aborted,
                    onStreamingFallback, onAssistantMessage, onComplete)) {
                    return;
                }
                log.warn("[AnthropicSdkProvider] 非流式回退失败，走原始流式错误 · CC claude.ts:2562");
            }
            log.error("AnthropicSdkProvider.stream failed: {}", translated.toString());
            onError.accept(translated);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // RV-03 · 流式→非流式回退（DEC-RV-03 · CC claude.ts:2404-2562）· 迁移自旧 AnthropicProvider
    // ════════════════════════════════════════════════════════════════════

    /**
     * [RV-03-03] 非流式回退禁用门 · CC original: claude.ts:2469-2474
     * {@code disableFallback = isEnvTruthy(process.env.CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK)
     *   || getFeatureValue_CACHED_MAY_BE_STALE('tengu_disable_streaming_to_non_streaming_fallback', false)}。
     *
     * <p>门开启时流式失败不回退，直接抛流式错误（CC claude.ts:2476-2501 throw streamingError）。
     * 门存在的 WHY：mid-stream fallback 在流式工具执行激活时会双工具执行——流的部分工具已启动，
     * 非流式重试再产出同一 tool_use 又跑一次（inc-4258）。
     */
    private boolean streamingFallbackDisabled() {
        return ErrorClassifier.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK"))
            || (featureFlags != null && featureFlags.tenguDisableStreamingToNonStreamingFallback());
    }

    /**
     * [RV-03-01] 流式失败→非流式回退资格判定 · 对齐 CC claude.ts:2434-2501 catch 分支。
     *
     * <p>CC catch 仅排除三类：用户中止（:2443 APIUserAbortError + signal.aborted → rethrow）、
     * SDK 超时（:2457 → throw APIConnectionTimeoutError）、门控禁用（:2476-2501 → throw streamingError）。
     * 其余流式错误一律回退非流式——<b>无 isRetryable 门</b>（非流式重试自带 withRetry 容错，
     * CC withRetry.ts:170-517；Java 回退循环等价）。Java 映射：aborted 标志 = signal.aborted；
     * HttpTimeoutException/SocketTimeoutException = SDK 超时；disabled = 门控。
     *
     * @param e        流式错误
     * @param aborted  用户中止标志（null=无中止信号）
     * @param disabled 门控禁用标志（{@link #streamingFallbackDisabled()}）
     * @return true=应回退非流式
     */
    static boolean shouldUseNonStreamingFallback(Throwable e, AtomicBoolean aborted, boolean disabled) {
        if (disabled) {
            return false;
        }
        if (aborted != null && aborted.get()) {
            return false;
        }
        if (e instanceof java.util.concurrent.CancellationException) {
            return false;
        }
        if (e instanceof java.net.http.HttpTimeoutException || e instanceof java.net.SocketTimeoutException) {
            return false;
        }
        return true;
    }

    /**
     * [RV-03-02] 流式 529 预置计数 · CC original: claude.ts:2559
     * {@code initialConsecutive529Errors: is529Error(streamingError) ? 1 : 0}。
     *
     * <p>WHY：流式失败本身是 529 时，非流式重试的连续 529 预算从 1 起算，保证无论过载打在
     * 流式还是非流式，模型降级前的总 529 数一致（CC 注释 github issue #1513）。预置值填入
     * {@link RetryOptions#initialConsecutive529Errors()} 由非流式回退循环消费（withRetry.ts:186
     * {@code consecutive529Errors = options.initialConsecutive529Errors ?? 0}）。
     *
     * @param streamingError 流式失败异常
     * @return 0（非 529）/ 1（529）
     */
    static int computeInitialConsecutive529Errors(Throwable streamingError) {
        return ErrorClassifier.is529Error(streamingError) ? 1 : 0;
    }

    /**
     * [RV-03-01] 非流式回退执行 · 对齐 CC executeNonStreamingRequest（claude.ts:818-910）+
     * withRetry（withRetry.ts:170-517）。
     *
     * <p>流式失败后：触发 onStreamingFallback（loop tombstone 部分消息）→ 非流式 messages.create
     * 重试（同 history/tools，max_tokens 按 {@link #MAX_NON_STREAMING_TOKENS} 封顶）→ 产出
     * AssistantMessage 走 onAssistantMessage/onComplete。非流式重试循环自带 withRetry 语义：
     * 连续 529 计数（初始 = 流式 529 预置值，withRetry.ts:186 {@code initialConsecutive529Errors ?? 0}）、
     * 退避重试、耗尽即失败。连续 529 达阈值 → 回退失败 → 上层 onError(原始流式错误) →
     * LlmAgentLoop Path3 以 loop 级 fallbackModel 做模型降级（fallbackModel 在 provider 不可见）。
     *
     * @return true=回退成功并已送达 onAssistantMessage/onComplete；false=回退失败（调用方走 onError 原错误）
     */
    private boolean nonStreamingFallback(ProviderConfig config, String modelName,
                                         List<SystemPromptBlock> systemPromptBlocks,
                                         List<ChatMessageDto> history, ArrayNode tools,
                                         Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                                         String effortValue, Throwable streamingError,
                                         AtomicBoolean aborted, Runnable onStreamingFallback,
                                         Consumer<AssistantMessage> onAssistantMessage,
                                         Runnable onComplete) {
        if (onStreamingFallback != null) {
            try {
                onStreamingFallback.run();
            } catch (Exception ex) {
                log.warn("[AnthropicSdkProvider] onStreamingFallback 回调异常: {}", ex.toString());
            }
        }
        // CC claude.ts:2559：流式失败若为 529，非流式 529 预算从 1 起算（claude.ts:830/:903 透传）
        RetryOptions retryOptions = new RetryOptions(
            null, modelName, null, null, null, () -> aborted != null && aborted.get(),
            null, computeInitialConsecutive529Errors(streamingError));
        // withRetry.ts:186 consecutive529Errors = options.initialConsecutive529Errors ?? 0
        int consecutive529Errors = retryOptions.initialConsecutive529Errors() != null
            ? retryOptions.initialConsecutive529Errors() : 0;
        int maxRetries = WithRetryEngine.getDefaultMaxRetries();
        int attempts = 0;
        while (attempts++ < maxRetries + 1) {
            if (aborted != null && aborted.get()) {
                log.info("[AnthropicSdkProvider] 非流式回退中止，放弃");
                return false;
            }
            long attemptStartMs = System.currentTimeMillis(); // [A-13] 每次非流式尝试 = 一次真实 LLM 调用
            try {
                AssistantMessage msg = nonStreamingSend(config, modelName, systemPromptBlocks,
                    history, tools, maxOutputTokensOverride, taskBudget, effortValue, attemptStartMs);
                if (onAssistantMessage != null) {
                    onAssistantMessage.accept(msg);
                }
                if (onComplete != null) {
                    onComplete.run();
                }
                log.info("[AnthropicSdkProvider] 流式失败→非流式回退成功 (attempt={}, model={}, 529预置={}) · CC claude.ts:2505-2562",
                    attempts, modelName, retryOptions.initialConsecutive529Errors());
                return true;
            } catch (Throwable e2) {
                if (aborted != null && aborted.get()) {
                    return false;
                }
                if (e2 instanceof java.util.concurrent.CancellationException) {
                    return false;
                }
                // [P-1] catch 顶部翻译 SDK 异常 → LlmApiException（保留 headers）· CC withRetry.ts:331-334/:377-382
                //   raw AnthropicServiceException 使下方 is529Error / isRetryable（ErrorClassifier
                //   LlmApiException instanceof 类型闸）与 retry-after 提取（extractRetryAfterSeconds 仅认
                //   LlmApiException headers）全失守 → 回退循环实际不重试 429/529（偏离 CC :331-334/:377-382）。
                e2 = translateSdkError(e2);
                // [A-13] per-LLM-call 非流式尝试错误事件（每次尝试 = 一次真实 LLM 调用）· CC logAPIError
                emitApiTerminalEvent("tengu_api_error", apiErrorAttrs(modelName,
                    history == null ? 0 : history.size(), e2, null, attemptStartMs));
                if (ErrorClassifier.is529Error(e2)) {
                    if (TransientErrorHandler.isEligibleFor529Fallback(modelName)) {
                        consecutive529Errors++;
                        if (consecutive529Errors >= ApiErrors.MAX_CONSECUTIVE_529) {
                            log.warn("[AnthropicSdkProvider] 非流式回退连续 {} 次 529（含流式预置 {}），放弃回退"
                                    + " → 上层 Path3 模型降级 · CC withRetry.ts:334-337",
                                consecutive529Errors, retryOptions.initialConsecutive529Errors());
                            return false;
                        }
                    } else {
                        log.warn("[AnthropicSdkProvider] 非流式回退 529 但主模型 {} 非降级资格，仅退避 · CC withRetry.ts:329-333",
                            modelName);
                    }
                }
                if (!ErrorClassifier.isRetryable(e2)) {
                    log.warn("[AnthropicSdkProvider] 非流式回退不可重试错误，放弃: {}", e2.toString());
                    return false;
                }
                long delayMs = RetryDelayCalculator.calculate(attempts,
                    e2 instanceof LlmApiException lae2 ? ErrorClassifier.extractRetryAfterSeconds(lae2) : null,
                    ApiErrors.MAX_DELAY_MS);
                log.warn("[AnthropicSdkProvider] 非流式回退重试 attempt={}/{} 退避 {}ms · CC withRetry.ts:429-463",
                    attempts, maxRetries, delayMs);
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    /**
     * [RV-03-01] 非流式单次发送 · 对齐 CC executeNonStreamingRequest 的
     * {@code anthropic.messages.create({...adjustedParams}, {signal, timeout})}（claude.ts:870-903）。
     *
     * <p>SDK 路径：buildMessageParams（同 history/tools）→ [DEC-RV-09] adjustParamsForNonStreaming
     * 对 max_tokens 封顶 {@link #MAX_NON_STREAMING_TOKENS}（claude.ts:3364-3389）→
     * {@code client.messages().create()}；解析 content blocks：text → content、tool_use → toolCalls、
     * thinking → reasoning、stop_reason → finishReason、usage.output_tokens → outputTokens。
     *
     * @return 完整 AssistantMessage（含 tool_use/thinking）
     */
    private AssistantMessage nonStreamingSend(ProviderConfig config, String modelName,
                                              List<SystemPromptBlock> systemPromptBlocks,
                                              List<ChatMessageDto> history, ArrayNode tools,
                                              Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                                              String effortValue, long startMs) {
        AnthropicClient client = buildClient(config);
        MessageCreateParams params = buildMessageParams(modelName, systemPromptBlocks, history, tools,
            maxOutputTokensOverride, taskBudget, effortValue, null, null, null,
            StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl(config.baseUrl()), config);
        // [DEC-RV-09] 非流式硬 cap 64000 · CC claude.ts:3364-3389 adjustParamsForNonStreaming
        //   （buildMessageParams 之后、create 之前无条件应用；claude.ts:857-860）
        params = adjustParamsForNonStreaming(params);
        Message message = client.messages().create(params);
        AssistantMessage msg = buildAssistantMessageFromMessage(message);
        // [A-13] per-LLM-call 非流式成功事件 · CC claude.ts:2858 logAPISuccessAndDuration
        Map<String, Object> attrs = nonStreamApiSuccessAttrs(modelName,
            history == null ? 0 : history.size(), msg.usage(), msg.finishReason(), null, startMs);
        attrs.put("didFallBackToNonStreaming", true); // 本路径恒为回退链（CC claude.ts:2593/:2686 fallbackMessage）
        emitApiTerminalEvent("tengu_api_success", attrs);
        return msg;
    }

    /** 非流式 SDK Message → AssistantMessage（text / tool_use / thinking / stop_reason / output_tokens）。 */
    static AssistantMessage buildAssistantMessageFromMessage(Message message) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolUseBlock> toolCalls = new ArrayList<>();
        for (var block : message.content()) {
            if (block.isText()) {
                block.text().ifPresent(t -> {
                    String s = t.text();
                    if (s != null) text.append(s);
                });
            } else if (block.isThinking()) {
                block.thinking().ifPresent(t -> {
                    String s = t.thinking();
                    if (s != null) reasoning.append(s);
                });
            } else if (block.isToolUse()) {
                var tu = block.asToolUse();
                JsonNode input;
                try {
                    JsonNode converted = tu._input() != null ? tu._input().convert(JsonNode.class) : null;
                    input = converted != null ? converted : JSON.createObjectNode();
                } catch (Exception e) {
                    input = JSON.createObjectNode();
                }
                toolCalls.add(new ToolUseBlock(tu.id(), tu.name(), input));
            }
        }
        String finishReason = message.stopReason().map(Object::toString).orElse(null);
        // [DEC-04] 非流式 usage 全字段解析 · CC claude.ts:870-903 non-streaming usage 透传
        // [R32-06] 嵌套字段 (server_tool_use/service_tier/cache_creation) 一并解析 · 对齐
        //   CC agentToolUtils.ts:243-255 usage 7 子字段 (SDK Usage 已暴露三个 Optional 访问器)
        var usage = message.usage();
        AgentUsage agentUsage = new AgentUsage(
            usageLong(usage::inputTokens), usageLong(usage::outputTokens),
            usage.cacheCreationInputTokens().orElse(0L), usage.cacheReadInputTokens().orElse(0L),
            parseServerToolUse(usage),
            usage.serviceTier().map(com.anthropic.models.messages.Usage.ServiceTier::asString).orElse(null),
            parseCacheCreation(usage),
            // [OD-01 provider 接线] 累计 cache_deleted_input_tokens（CC message.usage 顶层字段，
            // microCompact.ts:374；非流式整响应 message.usage 同样携带）
            "", List.of(), "standard", parseCacheDeletedInputTokens(usage));
        if (log.isDebugEnabled()) {
            log.debug("AnthropicSdkProvider 非流式 usage: {} · CC claude.ts:870-903", agentUsage);
        }
        return new AssistantMessage(text.toString(), finishReason, toolCalls,
            reasoning.toString(), null, agentUsage);
    }

    /**
     * [DEC-04] 从 Anthropic SDK Usage 安全提取 long 字段。
     *
     * <p>SDK required accessor（{@code inputTokens()}/{@code outputTokens()}）在响应 JSON 缺字段时抛
     * AnthropicInvalidDataException（网关/测试响应可能只给 output_tokens 不给 input_tokens）；
     * 必须安全提取, 缺省 → 0（对齐 CC 缺省 0, tokens.ts:49-51 ?? 0）。
     *
     * @param required SDK required accessor（可抛 AnthropicInvalidDataException 的 Supplier）
     * @return 字段值; 缺失/异常 → 0
     */
    static long usageLong(java.util.function.Supplier<Long> required) {
        try {
            Long v = required.get();
            return v != null ? v : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * [R32-06] 从 SDK Usage 解析 server_tool_use · 对齐 CC agentToolUtils.ts:243-248
     * {@code server_tool_use: { web_search_requests, web_fetch_requests }}（claude.ts:2947-2953
     * updateUsage 从 message_start usage 读取）。
     *
     * <p>Anthropic SDK {@code Usage.serverToolUse()} 返回 {@code Optional<ServerToolUsage>}（含
     * {@code webSearchRequests()}/{@code webFetchRequests()} 两个 required long）；缺失 → null
     * （对齐 CC nullable）。required 访问器在网关/测试响应缺字段时抛异常 → 经 {@link #usageLong}
     * 安全降 0。
     *
     * @param usage SDK message_start usage（可 null）
     * @return AgentUsage.ServerToolUse；usage 或 server_tool_use 缺失 → null
     */
    static AgentUsage.ServerToolUse parseServerToolUse(com.anthropic.models.messages.Usage usage) {
        if (usage == null) {
            return null;
        }
        return usage.serverToolUse()
            .map(stu -> new AgentUsage.ServerToolUse(
                usageLong(stu::webSearchRequests),
                usageLong(stu::webFetchRequests)))
            .orElse(null);
    }

    /**
     * [OD-01 provider 接线] 从 SDK Usage 解析累计 cache_deleted_input_tokens · CC
     * {@code lastAsst.message.usage.cache_deleted_input_tokens}（microCompact.ts:374 /
     * query.ts:874-878，API 字段累计/sticky，减基线得每次操作 delta）。
     *
     * <p>SDK {@code Usage} 无 cache_deleted_input_tokens 访问器（agentToolUtils.ts:238-256
     * usage 7 子字段不含它）→ 经 {@code usage._additionalProperties()} 读取未知 JSON 字段
     * （anthropic-java-core 2.53.0 javap 自验：Usage 仅有 cache_creation/cache_read/input/output
     * 及 {@code _additionalProperties(): Map<String, JsonValue>}）。
     *
     * @param usage SDK message_start usage（可 null）
     * @return 累计 cache_deleted_input_tokens；usage 或字段缺失/非数值 → 0
     */
    static long parseCacheDeletedInputTokens(com.anthropic.models.messages.Usage usage) {
        if (usage == null) {
            return 0L;
        }
        try {
            Object v = usage._additionalProperties().get("cache_deleted_input_tokens");
            if (v instanceof com.anthropic.core.JsonValue jv) {
                Number n = jv.convert(Number.class);
                if (n != null) {
                    return n.longValue();
                }
            }
        } catch (RuntimeException e) {
            // _additionalProperties 读取/转换异常（SDK 版本差异）→ 0，等价 CC 字段缺失 ?? 0
            log.debug("AnthropicSdkProvider parseCacheDeletedInputTokens 异常: {} → 0", e.toString());
        }
        return 0L;
    }

    /**
     * [R32-06] 从 SDK Usage 解析 cache_creation · 对齐 CC agentToolUtils.ts:250-255
     * {@code cache_creation: { ephemeral_1h_input_tokens, ephemeral_5m_input_tokens }}（claude.ts:2956-2963
     * updateUsage 从 message_start usage 读取；CC 注释明言 SDK BetaMessageDeltaUsage 缺该字段,
     * 故只在 message_start 解析一次）。
     *
     * <p>Anthropic SDK {@code Usage.cacheCreation()} 返回 {@code Optional<CacheCreation>}（含
     * {@code ephemeral1hInputTokens()}/{@code ephemeral5mInputTokens()} 两个 required long）；缺失
     * → null（对齐 CC nullable）。
     *
     * @param usage SDK message_start usage（可 null）
     * @return AgentUsage.CacheCreation；usage 或 cache_creation 缺失 → null
     */
    static AgentUsage.CacheCreation parseCacheCreation(com.anthropic.models.messages.Usage usage) {
        if (usage == null) {
            return null;
        }
        return usage.cacheCreation()
            .map(cc -> new AgentUsage.CacheCreation(
                usageLong(cc::ephemeral1hInputTokens),
                usageLong(cc::ephemeral5mInputTokens)))
            .orElse(null);
    }

    /** SDK RawMessageStreamEvent → StreamState 逐事件映射（含 unknown=ping no-op）。 */
    static void mapStreamEvent(RawMessageStreamEvent event,
                               StreamState state,
                               Consumer<String> onChunk,
                               Consumer<ToolUseBlock> onToolCallComplete,
                               Consumer<String> onReasoningChunk,
                               java.util.Set<String> completedToolIds) {
        if (event.isMessageStart()) {
            RawMessageStartEvent start = event.asMessageStart();
            var usage = start.message() != null ? start.message().usage() : null;
            if (usage != null) {
                // [DEC-04] message_start.usage 携带 input_tokens (最终值) + cache_read/cache_creation + 初始 output_tokens
                state.inputTokens = usageLong(usage::inputTokens);
                state.cacheReadInputTokens = usage.cacheReadInputTokens().orElse(0L);
                state.cacheCreationInputTokens = usage.cacheCreationInputTokens().orElse(0L);
                state.outputTokens = usageLong(usage::outputTokens);
                // [R32-06] 嵌套字段解析 · 对齐 CC claude.ts:2947-2963 updateUsage
                //   (server_tool_use/cache_creation/service_tier 只从 message_start usage 读)
                state.serverToolUse = parseServerToolUse(usage);
                state.serviceTier = usage.serviceTier()
                    .map(com.anthropic.models.messages.Usage.ServiceTier::asString).orElse(null);
                state.cacheCreation = parseCacheCreation(usage);
                // [OD-01 provider 接线] 累计 cache_deleted_input_tokens（input 侧字段随 message_start
                //   usage 上报）· CC microCompact.ts:374 / query.ts:874-878
                state.cacheDeletedInputTokens = parseCacheDeletedInputTokens(usage);
                if (log.isDebugEnabled()) {
                    log.debug("AnthropicSdkProvider message_start usage: input={} output={} cacheRead={} cacheCreation={} "
                            + "serverToolUse={} serviceTier={} cacheCreation(1h/5m)={}/{} cacheDeleted={} "
                            + "· CC claude.ts:2214 updateUsage + microCompact.ts:374",
                        state.inputTokens, state.outputTokens, state.cacheReadInputTokens, state.cacheCreationInputTokens,
                        state.serverToolUse, state.serviceTier,
                        state.cacheCreation != null ? state.cacheCreation.ephemeral1hInputTokens() : null,
                        state.cacheCreation != null ? state.cacheCreation.ephemeral5mInputTokens() : null,
                        state.cacheDeletedInputTokens);
                }
            }
        } else if (event.isMessageDelta()) {
            RawMessageDeltaEvent deltaEvent = event.asMessageDelta();
            var delta = deltaEvent.delta();
            if (delta != null && delta.stopReason().isPresent()) {
                state.finishReason = delta.stopReason().get().toString();
            }
            var usage = deltaEvent.usage();
            if (usage != null) {
                // [DEC-04] message_delta.usage.output_tokens 为最终覆盖值（CC claude.ts:2214 message_delta 分支）
                state.outputTokens = usageLong(usage::outputTokens);
            }
        } else if (event.isContentBlockStart()) {
            RawContentBlockStartEvent cb = event.asContentBlockStart();
            var block = cb.contentBlock();
            if (block.isToolUse()) {
                var tu = block.asToolUse();
                ToolCallAccumulator acc = new ToolCallAccumulator();
                acc.index = (int) cb.index();
                acc.id = tu.id();
                acc.name = tu.name();
                state.toolCalls.put(acc.index, acc);
            } else if (block.isThinking()) {
                state.thinkingBlockIdx = (int) cb.index();
            }
        } else if (event.isContentBlockDelta()) {
            RawContentBlockDeltaEvent d = event.asContentBlockDelta();
            int idx = (int) d.index();
            var delta = d.delta();
            if (delta.isText()) {
                String s = delta.asText().text();
                state.content.append(s);
                if (onChunk != null) onChunk.accept(s);
            } else if (delta.isInputJson()) {
                ToolCallAccumulator acc = state.toolCalls.get(idx);
                if (acc != null) {
                    String partial = delta.asInputJson().partialJson();
                    if (partial != null) {
                        acc.args += partial;
                    }
                }
            } else if (delta.isThinking()) {
                String s = delta.asThinking().thinking();
                if (s != null) {
                    state.reasoning.append(s);
                    if (onReasoningChunk != null) onReasoningChunk.accept(s);
                }
            }
        } else if (event.isContentBlockStop()) {
            int idx = (int) event.asContentBlockStop().index();
            ToolCallAccumulator acc = state.toolCalls.get(idx);
            if (acc != null && acc.isComplete() && completedToolIds != null
                && completedToolIds.add(acc.id)) {
                try {
                    if (onToolCallComplete != null) onToolCallComplete.accept(acc.toBlock());
                } catch (Throwable t) {
                    log.warn("onToolCallComplete callback threw: {}", t.toString());
                }
            }
        } else if (event.isMessageStop()) {
            // 流结束, no-op
        } else {
            // unknown（ping 等）→ no-op · SDK 把未识别 type 落入 _json()（校验通过不抛）
            log.debug("AnthropicSdkProvider unknown event: {}", event._json());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // chat（非流式）· SDK create()
    // ════════════════════════════════════════════════════════════════════

    @Override
    public String chat(ProviderConfig config,
                       String modelName,
                       String systemPrompt,
                       String userMessage) {
        LlmRawResponse raw = chatWithRaw(config, modelName, systemPrompt, userMessage);
        return raw.content();
    }

    @Override
    public LlmRawResponse chatWithRaw(ProviderConfig config,
                                      String modelName,
                                      String systemPrompt,
                                      String userMessage) {
        if (config == null || !config.isUsable()) {
            throw new IllegalStateException(
                "AnthropicSdkProvider.chatWithRaw called without usable ProviderConfig");
        }
        long chatStartMs = System.currentTimeMillis(); // [A-13] per-LLM-call durationMs 起点
        try {
            AnthropicClient client = buildClient(config);
            MessageCreateParams params = buildMessageParams(modelName, toSingleOrgBlock(systemPrompt),
                userMessage == null ? List.of() : List.of(
                    new ChatMessageDto(null, null, Role.user, null, userMessage,
                        null, null, null, null, null, null, null, null, null,
                        null, java.util.List.of(), java.util.List.of())),
                null, null, null, null, null, null, null,
                StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl(config.baseUrl()), config);

            // [DEC-RV-09] 非流式硬 cap 64000（CC claude.ts:857-860 executeNonStreamingRequest 内
            // adjustParamsForNonStreaming(retryParams, MAX_NON_STREAMING_TOKENS)——buildMessageParams 之后、create 之前无条件应用）
            long rawMaxTokens = params.maxTokens();
            params = adjustParamsForNonStreaming(params);
            if (log.isDebugEnabled()) {
                log.debug("AnthropicSdkProvider 非流式 cap: 原始 max_tokens={} → capped={}（CC claude.ts:857-860 MAX_NON_STREAMING_TOKENS）",
                    rawMaxTokens, params.maxTokens());
            }

            // [CC claude.ts:1832 withResponse()] → request-id header (CC _request_id)
            HttpResponseFor<Message> raw = client.messages().withRawResponse().create(params);
            String requestId = raw.requestId().orElse(null);
            Message message = raw.parse();

            String messageId = message.id();
            StringBuilder textBuf = new StringBuilder();
            StringBuilder thinkingBuf = new StringBuilder();
            for (var block : message.content()) {
                if (block.isText()) {
                    String t = block.text().get().text();
                    if (t != null) textBuf.append(t);
                } else if (block.isThinking()) {
                    String t = block.thinking().get().thinking();
                    if (t != null) thinkingBuf.append(t);
                }
            }
            String contentStr = textBuf.toString();
            String thinkingStr = thinkingBuf.length() > 0 ? thinkingBuf.toString() : null;

            if (log.isInfoEnabled()) {
                log.info("[AnthropicSdkProvider] chatWithRaw 提取: messageId={} requestId={} contentLen={} thinkingBlocks={}",
                    messageId, requestId, contentStr.length(),
                    thinkingStr != null ? thinkingStr.length() : 0);
            }
            consumePostCompactionAtApiSuccess(null);
            // [A-13] per-LLM-call 非流式成功事件（chat 系列）· CC claude.ts:2858 logAPISuccessAndDuration
            emitApiTerminalEvent("tengu_api_success", nonStreamApiSuccessAttrs(modelName,
                userMessage == null ? 0 : 1, null, null, null, chatStartMs));
            // [IMP-6 OPD-WF6-02-RV] usage 提取 · 对齐 CC extractUsage（yoloClassifier.ts:609-618）
            //   从 API 响应 message.usage 提取 4 token 字段（缺省 ?? 0，同 line 613-616）。
            //   SDK usage() 为 required accessor：响应缺 usage（网关/测试响应）时抛
            //   AnthropicInvalidDataException → try/catch 视为无 usage（null，如实暴露缺口）。
            LlmProvider.LlmUsage usage = null;
            try {
                var apiUsage = message.usage();
                usage = new LlmProvider.LlmUsage(
                    Math.toIntExact(usageLong(apiUsage::inputTokens)),
                    Math.toIntExact(usageLong(apiUsage::outputTokens)),
                    Math.toIntExact(apiUsage.cacheReadInputTokens().orElse(0L)),
                    Math.toIntExact(apiUsage.cacheCreationInputTokens().orElse(0L)));
            } catch (RuntimeException e) {
                if (log.isDebugEnabled()) {
                    log.debug("AnthropicSdkProvider chatWithRaw: usage 缺失/异常 → null（IMP-6 usage 通道）: {}",
                        e.getMessage());
                }
            }
            return new LlmRawResponse(contentStr, messageId, thinkingStr, requestId, usage);
        } catch (Exception e) {
            log.error("AnthropicSdkProvider.chatWithRaw failed: {}", e.toString());
            emitApiTerminalEvent("tengu_api_error", apiErrorAttrs(modelName,
                userMessage == null ? 0 : 1, e, null, chatStartMs));
            throw new RuntimeException("Anthropic chatWithRaw failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String chatWithOptions(ProviderConfig config, String modelName, String systemPrompt,
                                  String userMessage, LlmProvider.ChatRequestOptions options) {
        if (config == null || !config.isUsable()) {
            throw new IllegalStateException(
                "AnthropicSdkProvider.chatWithOptions called without usable ProviderConfig");
        }
        long chatStartMs = System.currentTimeMillis(); // [A-13] per-LLM-call durationMs 起点
        List<ChatMessageDto> history = new ArrayList<>(); // [A-13] catch 需 messageCount → 提升到 try 外作用域
        try {
            if (options != null && options.history() != null) {
                history.addAll(options.history());
            }
            if (userMessage != null) {
                history.add(new ChatMessageDto(null, null, Role.user, null, userMessage,
                    null, null, null, null, null, null, null, null, null,
                    null, java.util.List.of(), java.util.List.of()));
            }
            ArrayNode tools = options != null ? options.tools() : null;
            // [P2-16] 对齐 CC claude.ts:744-745 abort 消费 — 请求前 signal.aborted 预检
            if (options != null && options.abortController() != null
                && options.abortController().isCancelled()) {
                throw new java.util.concurrent.CancellationException(
                    "AnthropicSdkProvider chatWithOptions aborted (CC claude.ts:744-745)");
            }
            Double temperature = options != null ? options.temperature() : null;
            Integer maxTokens = options != null ? options.maxTokens() : null;
            LlmProvider.ChatRequestOptions.OutputFormat outputFormat =
                options != null ? options.outputFormat() : null;
            Boolean skipCacheWrite = options != null ? options.skipCacheWrite() : null;

            AnthropicClient client = buildClient(config);
            MessageCreateParams params = buildMessageParams(modelName, toSingleOrgBlock(systemPrompt), history, tools,
                maxTokens, null, null, outputFormat, skipCacheWrite,
                options != null ? options.enablePromptCaching() : null,
                StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl(config.baseUrl()), config);
            if (temperature != null) {
                // [P2-16] thinking disabled 时写 temperature（CC claude.ts:1693-1694）
                params = params.toBuilder().temperature(temperature).build();
            }

            // [DEC-RV-09] 非流式硬 cap 64000（CC claude.ts:857-860 executeNonStreamingRequest 内
            // adjustParamsForNonStreaming(retryParams, MAX_NON_STREAMING_TOKENS)——buildMessageParams 之后、create 之前无条件应用）
            long rawMaxTokens = params.maxTokens();
            params = adjustParamsForNonStreaming(params);
            if (log.isDebugEnabled()) {
                log.debug("AnthropicSdkProvider 非流式 cap: 原始 max_tokens={} → capped={}（CC claude.ts:857-860 MAX_NON_STREAMING_TOKENS）",
                    rawMaxTokens, params.maxTokens());
            }

            // [WF3-04 explainer] 强制 tool_choice（CC options.tool_choice）→ ToolChoiceTool
            params = applyToolChoice(params, options);

            Message message = client.messages().create(params);
            StringBuilder textBuf = new StringBuilder();
            // AS-02（rev2）：多 text block 拼接对齐 CC getAssistantMessageText join('\n')
            // （messages.ts:2843-2856）——相邻 text block 之间补 '\n' 分隔；单 block 字节不变。
            // 差异限定 Anthropic 路径（OpenAI extractContent 单字符串无多 block 语义，EV-AS-29）。
            String text = extractAssistantText(message.content());
            textBuf.append(text);
            if (log.isInfoEnabled()) {
                log.info("[AnthropicSdkProvider] chatWithOptions: model={} contentLen={} messages={} temperature={} querySource={} skipCacheWrite={} enablePromptCaching={} agents={} hasAppendSystemPrompt={} mcpTools={}",
                    modelName, textBuf.length(), history.size(),
                    temperature, options != null ? options.querySource() : null,
                    options != null ? options.skipCacheWrite() : null,
                    options != null ? options.enablePromptCaching() : null,
                    options != null && options.agents() != null ? options.agents().size() : 0,
                    options != null ? options.hasAppendSystemPrompt() : null,
                    options != null && options.mcpTools() != null ? options.mcpTools().size() : 0);
            }
            consumePostCompactionAtApiSuccess(history);
            // [A-13] per-LLM-call 非流式成功事件（chat 系列）· CC claude.ts:2858 logAPISuccessAndDuration
            emitApiTerminalEvent("tengu_api_success", nonStreamApiSuccessAttrs(modelName, history.size(),
                null, null, options != null ? options.querySource() : null, chatStartMs));
            return textBuf.toString();
        } catch (java.util.concurrent.CancellationException e) {
            // AS-04（rev2）：abort 预检 CancellationException 原样透传（不包装）——对齐 CC
            // APIUserAbortError 直达服务层（awaySummary.ts:68），服务层 catch(CancellationException)
            // 静默 null 无日志；旧实现包装 RuntimeException 使 abort 落入「generation failed」日志分支
            throw e;
        } catch (AnthropicServiceException e) {
            // [P-1] SDK HTTP 面异常（AnthropicServiceException，含 429/5xx 子类
            // RateLimitException/InternalServerException，javap 实证 statusCode()）→
            // translateSdkError 保留 headers 后透传 —— 对齐 CC isApiErrorMessage 分类
            // （awaySummary.ts:60-65）与 OpenAI 路径 translateSdkError（OpenAiSdkProvider:977-998）；
            // 服务层 catch(LlmApiException)「API error」debug 分支可达。旧实现 emptyMap 丢 headers
            // 使 retry-after / unified-reset 不可达（CC withRetry.ts:519-528 / :814-822 全读 headers）
            // → Path3 429/529 退避失守。AnthropicRetryableException extends AnthropicException
            // （非 ServiceException）不受影响，仍走下方包装面。
            RuntimeException translated = translateSdkError(e);
            // [A-13] per-LLM-call 错误事件 · CC claude.ts:2720/:2776 logAPIError
            emitApiTerminalEvent("tengu_api_error", apiErrorAttrs(modelName, history.size(), translated,
                options != null ? options.querySource() : null, chatStartMs));
            throw translated;
        } catch (Exception e) {
            log.error("AnthropicSdkProvider.chatWithOptions failed: {}", e.toString());
            emitApiTerminalEvent("tengu_api_error", apiErrorAttrs(modelName, history.size(), e,
                options != null ? options.querySource() : null, chatStartMs));
            throw new RuntimeException("Anthropic chatWithOptions failed: " + e.getMessage(), e);
        }
    }

    /**
     * AS-02（rev2）：提取 assistant 消息全部 text block 文本 · 对齐 CC getAssistantMessageText
     * （messages.ts:2843-2856）——过滤 text block 后 {@code join('\n')}。非 text block
     * （thinking/tool_use 等）跳过且不产生分隔符；text 为 null 时按空串占位（CC text 恒 string，
     * null 不可能，行为等价）。
     *
     * @param blocks SDK 响应 content blocks（Message.content()）
     * @return join('\n') 后的文本（无 trim——trim 语义在服务层/调用方表达）
     */
    static String extractAssistantText(List<com.anthropic.models.messages.ContentBlock> blocks) {
        StringBuilder textBuf = new StringBuilder();
        boolean firstText = true;
        for (var block : blocks) {
            if (block.isText()) {
                String t = block.text().get().text();
                if (!firstText) {
                    textBuf.append('\n');
                }
                if (t != null) {
                    textBuf.append(t);
                }
                firstText = false;
            }
        }
        return textBuf.toString();
     }
    /**
     * [WF3-04 explainer] 带选项非流式 chat · 返回完整 AssistantMessage（含 tool_use 块）。
     *
     * <p>对齐 CC sideQuery 返回 content blocks（permissionExplainer.ts:178-186）。强制
     * {@code tool_choice} 下 LLM 以 tool_use 作答，文本 content 为空 —— 本方法经
     * {@link #buildAssistantMessageFromMessage} 提取 tool_use 块供 explainer 读取结构化输入。
     */
    @Override
    public AssistantMessage chatWithOptionsMessage(ProviderConfig config, String modelName,
                                                   String systemPrompt, String userMessage,
                                                   LlmProvider.ChatRequestOptions options) {
        if (config == null || !config.isUsable()) {
            throw new IllegalStateException(
                "AnthropicSdkProvider.chatWithOptionsMessage called without usable ProviderConfig");
        }
        long chatStartMs = System.currentTimeMillis(); // [A-13] per-LLM-call durationMs 起点
        List<ChatMessageDto> history = new ArrayList<>(); // [A-13] catch 需 messageCount → 提升到 try 外作用域
        try {
            if (options != null && options.history() != null) {
                history.addAll(options.history());
            }
            if (userMessage != null) {
                history.add(new ChatMessageDto(null, null, Role.user, null, userMessage,
                    null, null, null, null, null, null, null, null, null,
                    null, java.util.List.of(), java.util.List.of()));
            }
            ArrayNode tools = options != null ? options.tools() : null;
            // [P2-16] 对齐 CC claude.ts:744-745 abort 消费 — 请求前 signal.aborted 预检
            if (options != null && options.abortController() != null
                && options.abortController().isCancelled()) {
                throw new java.util.concurrent.CancellationException(
                    "AnthropicSdkProvider chatWithOptionsMessage aborted (CC claude.ts:744-745)");
            }
            Double temperature = options != null ? options.temperature() : null;
            Integer maxTokens = options != null ? options.maxTokens() : null;
            LlmProvider.ChatRequestOptions.OutputFormat outputFormat =
                options != null ? options.outputFormat() : null;
            Boolean skipCacheWrite = options != null ? options.skipCacheWrite() : null;

            AnthropicClient client = buildClient(config);
            MessageCreateParams params = buildMessageParams(modelName, toSingleOrgBlock(systemPrompt), history, tools,
                maxTokens, null, null, outputFormat, skipCacheWrite,
                options != null ? options.enablePromptCaching() : null,
                StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl(config.baseUrl()), config);
            if (temperature != null) {
                // [P2-16] thinking disabled 时写 temperature（CC claude.ts:1693-1694）
                params = params.toBuilder().temperature(temperature).build();
            }
            params = adjustParamsForNonStreaming(params);
            params = applyToolChoice(params, options);

            Message message = client.messages().create(params);
            AssistantMessage msg = buildAssistantMessageFromMessage(message);
            if (log.isInfoEnabled()) {
                log.info("[AnthropicSdkProvider] chatWithOptionsMessage: model={} contentLen={} toolCalls={} querySource={}",
                    modelName, msg.content() == null ? 0 : msg.content().length(),
                    msg.toolCalls().size(),
                    options != null ? options.querySource() : null);
            }
            consumePostCompactionAtApiSuccess(history);
            // [A-13] per-LLM-call 非流式成功事件（chat 系列，带 usage 全字段）· CC claude.ts:2858
            emitApiTerminalEvent("tengu_api_success", nonStreamApiSuccessAttrs(modelName, history.size(),
                msg.usage(), msg.finishReason(), options != null ? options.querySource() : null, chatStartMs));
            return msg;
        } catch (Exception e) {
            log.error("AnthropicSdkProvider.chatWithOptionsMessage failed: {}", e.toString());
            emitApiTerminalEvent("tengu_api_error", apiErrorAttrs(modelName, history.size(), e,
                options != null ? options.querySource() : null, chatStartMs));
            throw new RuntimeException("Anthropic chatWithOptionsMessage failed: " + e.getMessage(), e);
        }
    }

    /** [Anthropic-SDK T-AN-01] anthropic-java 异常 → LlmApiException（P-1 类型化分类 · Kind.IMAGE 判定）·
     *  镜像 OpenAiSdkProvider.translateSdkError（T-OA-07，OpenAiSdkProvider.java:977-998）。
     *
     * <p><b>WHY（P-1 · CC claude.ts:2501 / withRetry.ts:519-528 / :814-822）</b>：CC 侧 SDK 错误恒为
     * {@code APIError} 且携带 headers——重试链从 {@code error.headers['retry-after']}
     * （withRetry.ts:519-528 getRetryAfter）与 {@code error.headers.get('anthropic-ratelimit-unified-reset')}
     * （withRetry.ts:814-822 getRateLimitResetDelayMs）读取退避载荷，错误消息面也读 headers
     * （errors.ts:489-490）。Java 旧实现把 raw {@link AnthropicServiceException} 交 onError / 用
     * emptyMap 构造 LlmApiException，headers 全丢 → Path3 {@code instanceof LlmApiException} 类型闸
     * （LlmAgentLoop:3923/3988/6473 域）失守 → Anthropic 流式路径 429/529 整链不可重试。本方法
     * 翻译后 headers（retry-after / unified-reset）保留可达。
     *
     * <p>分类语义与 OpenAI 镜像一致：{@link LlmApiException#isImageErrorBody} 命中 →
     * {@link LlmApiException#imageError}（Kind.IMAGE，LlmAgentLoop.isImageError:6204 类型化优先）；
     * {@link RuntimeException} 透传（CancellationException / HttpTimeoutException 等流式判定
     * 依赖的类型不变）；其余包装 RuntimeException。
     *
     * @param e SDK 异常（含 {@link AnthropicServiceException} 子类 RateLimitException/InternalServerException）
     * @return 翻译后 RuntimeException（LlmApiException 保留 status/headers/body）
     */
    static RuntimeException translateSdkError(Throwable e) {
        if (e instanceof AnthropicServiceException se) {
            int status = se.statusCode();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            try {
                for (String name : se.headers().names()) {
                    headers.put(name, se.headers().values(name));
                }
            } catch (Exception ignore) {
                // header 转换失败不阻断错误上浮
            }
            String body = se.body() == null ? "" : String.valueOf(se.body());
            if (log.isDebugEnabled()) {
                log.debug("AnthropicSdkProvider translateSdkError: status={} headers={} body={} · CC APIError headers 保留",
                    status, headers, body);
            }
            return LlmApiException.isImageErrorBody(body)
                ? LlmApiException.imageError(status, headers, body)
                : new LlmApiException(status, headers, body);
        }
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(e);
    }

    // ════════════════════════════════════════════════════════════════════
    // A-13 · per-LLM-call API terminal 遥测（对齐 CC services/api/logging.ts）
    // ════════════════════════════════════════════════════════════════════

    /**
     * [A-13] per-LLM-call API terminal 遥测事件发射 · 对齐 CC logging.ts:294/:461
     * {@code consumeInvokingRequestId()}（error/success 两个 terminal API event 发射点）。
     *
     * <p><b>粒度（A-13 · R-3 边界事件删除后唯一保留）</b>：CC {@code services/api/logging.ts} 仅
     * per-LLM-call 事件（{@code tengu_api_query/error/success}，:196/:304/:463），无子代理边界事件；
     * 原子代理边界发射同名 {@code tengu_api_success/error} 已按 R-3 删除（对齐 CC，不再双轨）。
     * 本方法在 <b>infra/llm 层每次 LLM API 调用</b>终端发射（流式成功/失败、非流式回退 send、
     * chat 系列），对齐 CC claude.ts:2858 {@code logAPISuccessAndDuration} / :2720/:2776 {@code logAPIError}。
     *
     * <p><b>稀疏边消费</b>：CC logging.ts:294/:461 在每个 terminal 事件调
     * {@code consumeInvokingRequestId()}，edge 非 null 时 spread invokingRequestId/invocationKind
     * （:320-327/:493-500）。Java 经 {@link AgentContext#attachInvokingRequestEdge} 同语义——
     * 每次 invocation 的首个 per-LLM-call terminal 事件携带 spawn/resume 边界，之后事件 edge=null
     * （稀疏边，agentContext.ts:159-161）。
     *
     * <p>telemetry 未注入（null）→ 静默跳过 + debug 日志（测试/未接线零行为变化）。
     *
     * @param eventName 事件名（tengu_api_success / tengu_api_error）
     * @param attrs     事件属性（调用方构建；本方法经 attachInvokingRequestEdge 写入
     *                  invokingRequestId/invocationKind 后发射）
     */
    private void emitApiTerminalEvent(String eventName, Map<String, Object> attrs) {
        AgentContext.InvokingRequestEdge edge = AgentContext.attachInvokingRequestEdge(attrs);
        Telemetry t = this.telemetry;
        if (t == null) {
            if (log.isDebugEnabled()) {
                log.debug("[AnthropicSdkProvider] per-LLM-call API 事件跳过（telemetry 未注入）: event={} edge={} "
                        + "· CC logging.ts:294/:461", eventName, edge != null);
            }
            return;
        }
        t.recordEvent(eventName, attrs);
        // [A-13 R-3] OTel 事件名对齐 CC：analytics 用 tengu_api_*（logging.ts:463/:304），
        //   OTel 通道用 api_request/api_error（logging.ts:718/:368）+ snake_case attrs。
        //   CC 双通道分离（logEvent 1P/Statsig vs logOTelEvent OTLP），Java 双发射分名对齐。
        t.logOTelEvent(otelEventName(eventName), otelAttrs(eventName, attrs));
        if (log.isDebugEnabled()) {
            log.debug("[AnthropicSdkProvider] per-LLM-call API 事件已发射: analytics={} otel={} model={} invokingRequestId={} invocationKind={} "
                    + "· CC logging.ts:320-327/:493-500 spread + :368/:718 OTel", eventName, otelEventName(eventName),
                attrs.get("model"),
                edge != null ? edge.invokingRequestId() : null,
                edge != null ? edge.invocationKind() : null);
        }
    }

    /**
     * [A-13 R-3] analytics 事件名 → CC OTel 事件名映射（logging.ts:718 api_request / :368 api_error）。
     * tengu_api_success → api_request；其余（tengu_api_error）→ api_error。
     */
    private static String otelEventName(String analyticsEventName) {
        return "tengu_api_success".equals(analyticsEventName) ? "api_request" : "api_error";
    }

    /**
     * [A-13 R-3] analytics camelCase attrs → CC OTel snake_case attrs（String 值，对齐 logging.ts:718-726/:368-374）。
     * success：model/input_tokens/output_tokens/cache_read_tokens/cache_creation_tokens/duration_ms；
     * error：model/error/status_code/duration_ms/attempt。cost_usd/speed 需额外计算（fastMode）→ 受控省略（concerns 披露）。
     */
    private static Map<String, Object> otelAttrs(String eventName, Map<String, Object> attrs) {
        Map<String, Object> otel = new LinkedHashMap<>();
        Object model = attrs.get("model");
        if (model != null) {
            otel.put("model", model);
        }
        long durationMs = attrs.get("durationMs") instanceof Number n ? n.longValue() : 0L;
        otel.put("duration_ms", String.valueOf(durationMs));
        if ("tengu_api_success".equals(eventName)) {
            otel.put("input_tokens", String.valueOf(attrs.getOrDefault("inputTokens", 0L)));
            otel.put("output_tokens", String.valueOf(attrs.getOrDefault("outputTokens", 0L)));
            otel.put("cache_read_tokens", String.valueOf(attrs.getOrDefault("cachedInputTokens", 0L)));
            otel.put("cache_creation_tokens", String.valueOf(attrs.getOrDefault("uncachedInputTokens", 0L)));
        } else {
            Object err = attrs.get("error");
            if (err != null) {
                otel.put("error", err);
            }
            Object status = attrs.get("status");
            if (status != null) {
                otel.put("status_code", String.valueOf(status));
            }
            Object attempt = attrs.get("attempt");
            if (attempt != null) {
                otel.put("attempt", String.valueOf(attempt));
            }
        }
        return otel;
    }

    /**
     * [A-13] 流式成功事件属性 · 对齐 CC logging.ts:463-576 {@code tengu_api_success} 核心字段
     * （model/messageCount/input/output/cached/uncached tokens/durationMs/requestId/stop_reason/querySource/attempt）。
     * costUSD/gateway/betas/textContentLength 等需额外计算字段暂不发射（受控简化，concerns 披露）。
     */
    private Map<String, Object> apiSuccessAttrs(String model, int messageCount, StreamState state,
                                                String querySource, long startMs) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("model", model);
        attrs.put("provider", "anthropic");
        attrs.put("messageCount", messageCount);
        attrs.put("inputTokens", state != null ? state.inputTokens : 0L);
        attrs.put("outputTokens", state != null ? state.outputTokens : 0L);
        attrs.put("cachedInputTokens", state != null ? state.cacheReadInputTokens : 0L);
        attrs.put("uncachedInputTokens", state != null ? state.cacheCreationInputTokens : 0L);
        attrs.put("durationMs", System.currentTimeMillis() - startMs);
        attrs.put("requestId", state != null ? state.requestId : null);
        attrs.put("stop_reason", state != null ? state.finishReason : null);
        attrs.put("querySource", querySource);
        attrs.put("attempt", 1);
        return attrs;
    }

    /**
     * [A-13] 非流式成功事件属性（chat 系列 / 非流式回退 send）· 对齐 CC logging.ts:463-576 核心字段；
     * usage 可 null（chatWithRaw/chatWithOptions 不解析 usage → token 字段 0，CC 缺省 0 等价）。
     */
    private Map<String, Object> nonStreamApiSuccessAttrs(String model, int messageCount, AgentUsage usage,
                                                         String finishReason, String querySource, long startMs) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("model", model);
        attrs.put("provider", "anthropic");
        attrs.put("messageCount", messageCount);
        attrs.put("inputTokens", usage != null ? usage.inputTokens() : 0L);
        attrs.put("outputTokens", usage != null ? usage.outputTokens() : 0L);
        attrs.put("cachedInputTokens", usage != null && usage.cacheReadInputTokens() != null
            ? usage.cacheReadInputTokens() : 0L);
        attrs.put("uncachedInputTokens", usage != null && usage.cacheCreationInputTokens() != null
            ? usage.cacheCreationInputTokens() : 0L);
        attrs.put("durationMs", System.currentTimeMillis() - startMs);
        attrs.put("stop_reason", finishReason);
        attrs.put("querySource", querySource);
        attrs.put("attempt", 1);
        return attrs;
    }

    /**
     * [A-13] API 错误事件属性 · 对齐 CC logging.ts:304-365 {@code tengu_api_error} 核心字段
     * （model/messageCount/durationMs/querySource/attempt + error/status）。
     * status 仅 LlmApiException 可提取（CC {@code String(error.status)} 缺省 undefined）。
     */
    private Map<String, Object> apiErrorAttrs(String model, int messageCount, Throwable error,
                                              String querySource, long startMs) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("model", model);
        attrs.put("provider", "anthropic");
        attrs.put("messageCount", messageCount);
        attrs.put("durationMs", System.currentTimeMillis() - startMs);
        attrs.put("querySource", querySource);
        attrs.put("attempt", 1);
        attrs.put("error", error != null && error.getMessage() != null
            ? error.getMessage() : (error != null ? error.toString() : "unknown"));
        if (error instanceof LlmApiException lae) {
            attrs.put("status", lae.status());
        }
        return attrs;
    }

    /** [WF3-04 explainer] tool_choice 投影 · CC {type:'tool', name} → Anthropic ToolChoice.ofTool。 */
    private static MessageCreateParams applyToolChoice(
            MessageCreateParams params, LlmProvider.ChatRequestOptions options) {
        LlmProvider.ChatRequestOptions.ToolChoice tc = options != null ? options.toolChoice() : null;
        if (tc == null || tc.name() == null || tc.name().isBlank()) {
            return params;
        }
        return params.toBuilder()
            .toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name(tc.name()).build()))
            .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // buildMessageParams · 迁移自 AnthropicProvider.buildRequestBody
    // ════════════════════════════════════════════════════════════════════

    /**
     * 构造 MessageCreateParams · 迁移自旧 {@code AnthropicProvider.buildRequestBody}（12 重载，旧类已删除）。
     *
     * <p>字段映射：
     * <ul>
     *   <li>model / max_tokens / temperature → SDK 一等字段</li>
     *   <li>system → {@code systemOfTextBlockParams}（cache_control 由 SystemPromptBlocksBuilder 门控）</li>
     *   <li>messages → SDK MessageParam 数组（tool_result / tool_use / 多模态 content blocks）</li>
     *   <li>tools → OpenAI {type:function,...} → SDK ToolUnion</li>
     *   <li>output_config：task_budget / effort / format → OutputConfig + putAdditionalProperty</li>
     *   <li>anthropic-beta header（task-budgets / effort / structured-outputs / prompt-caching-scope，
     *       firstParty gate 追加）→ putAdditionalHeader</li>
     *   <li>messages 通道 exactly-one cache_control marker（skipCacheWrite → 倒数第二，否则最后）</li>
     * </ul>
     * @param skipCacheWrite fork/side-query 不写 API 缓存（可 null = 未显式设置 → marker 落最后一条）
     */

    /**
     * [⊕C-1] chat 域 String 文本 → 单 block ORG 包装（原 String buildMessageParams
     * :1028-1029 逻辑字节等价迁移）：null / blank → null blocks（= 无 system 字段），
     * 否则 {@link CacheScope#ORG} 单 block。chatWithRaw / chatWithOptions 唯一使用点。
     */
    private static List<SystemPromptBlock> toSingleOrgBlock(String sp) {
        return (sp != null && !sp.isBlank())
            ? List.of(new SystemPromptBlock(sp, CacheScope.ORG))
            : null;
    }
    /**
     * 全参构建 · blocks 变体（IMP-SP-06 发送边界契约 · ⊕C-1 后为唯一入口：
     * String systemPrompt 变体已删除，调用方以 null/isBlank → null blocks 表达"无 system 字段"）。
     *
     * <p><b>[COMPACT-40] context_management（context_management 请求参数）登记</b>：
     * SDK 支持传输（anthropic-java MessageCreateParams 无 typed context_management 字段，但外层
     * Builder {@code putAdditionalProperty(String, JsonValue)} 可序列化进请求 JSON，与
     * output_config.task_budget / cache_edits 同机制），但 Java {@code AnthropicSdkProvider}
     * 恒不发送 thinking（buildMessageParams 无 .thinking() 调用，hasThinking 恒 false）。
     * <p>① <b>REQUEST BODY 零行为差异（成立）</b>：CC {@code getAPIContextManagement}
     * （apiMicrocompact.ts:64-153）非 ant 路径唯一策略 clear_thinking_20251015 需
     * {@code hasThinking && !isRedactThinkingActive}（apiMicrocompact.ts:82-84）恒不触发，且
     * 非 ant 提前 return（apiMicrocompact.ts:90）→ contextManagement 恒 undefined → body 注入
     * 门控（claude.ts:1718-1721 {@code contextManagement && useBetas &&
     * betasParams.includes(CONTEXT_MANAGEMENT_BETA_HEADER)}）恒不注入 → 与 CC 非 ant
     * thinking-disabled 基线在 context_management body 上零行为差异。
     * <p>② <b>anthropic-beta header 属明确登记偏离（非零差异）</b>：CC header 门控
     * （utils/betas.ts:300-311）为 {@code shouldIncludeFirstPartyOnlyBetas() &&
     * (antOptedIntoToolClearing || modelSupportsContextManagement(model))}，<b>与 hasThinking 无关</b>；
     * {@code modelSupportsContextManagement}（utils/betas.ts:125-137）对 firstParty 返回
     * {@code !canonical.includes('claude-3-')} → firstParty 非 claude-3 模型（claude-4+）即使
     * thinking disabled 也推送 CONTEXT_MANAGEMENT_BETA_HEADER。Java 不推（3P 安全：忠实推送会
     * 改变真实 firstParty 请求 anthropic-beta 头 → 代理 400 风险）。
     * <p>「未来 Java 启用 thinking 时的对齐接线点」：届时镜像非 ant 分支构建
     * {@code buildContextManagementConfig(hasThinking, isRedactThinkingActive, clearAllThinking)}
     * 返回 {@code JsonValue} 或 null，经 {@code putAdditionalProperty("context_management", ...)}
     * 注入，并以 CONTEXT_MANAGEMENT_BETA_HEADER（'context-management-2025-06-27'，
     * constants/betas.ts:7）门控。本批次不实施注入（Java hasThinking 恒 false →
     * buildContextManagementConfig 恒返回 null → 死代码），不改请求构造代码。
     */
    public static MessageCreateParams buildMessageParams(String modelName,
                                                         List<SystemPromptBlock> systemPromptBlocks,
                                                         List<ChatMessageDto> history,
                                                         ArrayNode tools,
                                                         Integer maxOutputTokensOverride,
                                                         TaskBudgetParam taskBudget,
                                                         String effortValue,
                                                         LlmProvider.ChatRequestOptions.OutputFormat outputFormat,
                                                         Boolean skipCacheWrite) {
        return buildMessageParams(modelName, systemPromptBlocks, history, tools, maxOutputTokensOverride,
            taskBudget, effortValue, outputFormat, skipCacheWrite, null);
    }

    /**
     * [W9-01 OPD-TS-29] 10 参 blocks 变体 · 额外承载 enablePromptCaching（CC claude.ts:1374-1375
     * 覆盖模型级 caching gate 语义，与字符串变体一致）。便捷重载 → 委托 11 参变体（firstParty=false，
     * 3P 默认：strict gate 关闭、beta 无 tool-search / prompt-caching-scope 追加，与旧行为一致）。
     * 注：[⊕C-1] String systemPrompt 变体已删除（master G4 的 String 重载不保留——语义由
     * toSingleOrgBlock + blocks 变体覆盖），发送契约数组态唯一。
     */
    public static MessageCreateParams buildMessageParams(String modelName,
                                                         List<SystemPromptBlock> systemPromptBlocks,
                                                         List<ChatMessageDto> history,
                                                         ArrayNode tools,
                                                         Integer maxOutputTokensOverride,
                                                         TaskBudgetParam taskBudget,
                                                         String effortValue,
                                                         LlmProvider.ChatRequestOptions.OutputFormat outputFormat,
                                                         Boolean skipCacheWrite,
                                                         Boolean enablePromptCaching) {
        return buildMessageParams(modelName, systemPromptBlocks, history, tools, maxOutputTokensOverride,
            taskBudget, effortValue, outputFormat, skipCacheWrite, enablePromptCaching, false);
    }

    /**
     * [G4+IMP-SP2-07 融合] 11 参 blocks 变体 · 额外承载 firstParty 判定（CC api.ts:185-192
     * 模型层门控）。enablePromptCaching 可 null（= 模型级 caching gate）。便捷重载 → 委托
     * 12 参核心（config=null → GlobalCacheScope gate false → beta 无 prompt-caching-scope push）。
     */
    public static MessageCreateParams buildMessageParams(String modelName,
                                                         List<SystemPromptBlock> systemPromptBlocks,
                                                         List<ChatMessageDto> history,
                                                         ArrayNode tools,
                                                         Integer maxOutputTokensOverride,
                                                         TaskBudgetParam taskBudget,
                                                         String effortValue,
                                                         LlmProvider.ChatRequestOptions.OutputFormat outputFormat,
                                                         Boolean skipCacheWrite,
                                                         Boolean enablePromptCaching,
                                                         boolean firstParty) {
        return buildMessageParams(modelName, systemPromptBlocks, history, tools, maxOutputTokensOverride,
            taskBudget, effortValue, outputFormat, skipCacheWrite, enablePromptCaching, firstParty, null);
    }

    /**
     * 全参构建 · blocks 变体 + enablePromptCaching + firstParty 判定 + ProviderConfig config
     * （IMP-SP-06 发送边界契约 + W9-01 缓存 gate + G4 strict gate + IMP-SP2-07 global-cache beta）。
     * firstParty = {@link StructuredOutputsSupport#isFirstPartyAnthropicBaseUrl} 结果（strict gate +
     * tool-search header 语义）；config = 运行时配置（GlobalCacheScope 判定 → prompt-caching-scope
     * header；可 null → gate false 零变化）。
     */
    public static MessageCreateParams buildMessageParams(String modelName,
                                                         List<SystemPromptBlock> systemPromptBlocks,
                                                         List<ChatMessageDto> history,
                                                         ArrayNode tools,
                                                         Integer maxOutputTokensOverride,
                                                         TaskBudgetParam taskBudget,
                                                         String effortValue,
                                                         LlmProvider.ChatRequestOptions.OutputFormat outputFormat,
                                                         Boolean skipCacheWrite,
                                                         Boolean enablePromptCaching,
                                                         boolean firstParty,
                                                         ProviderConfig config) {
        // [OD-01 provider 接线] 请求构造前 consume 一次待下发 cache_edits 块 · 对齐 CC claude.ts:1528-1535
        //   （"Consume pending cache edits ONCE before paramsFromContext is defined"——CC 在 params 构造
        //   前 consume，避免 paramsFromContext 多轮调用（logging/retries）重复取走）。
        //   门控 = CC cachedMCEnabled（claude.ts:1198-1200：feature('CACHED_MICROCOMPACT') &&
        //   isCachedMicrocompactEnabled() && isModelSupportedForCacheEditing(options.model)）。
        //   取走即清空对齐 CC consume 语义；实际注入（insert + pin + pinned 重插）在 msgs 构造后、
        //   b.messages 前执行（injectCacheEditsBlocks，见下）。
        boolean cachedMcEnabled = MicroCompactor.cachedMicrocompactEnabledForModel(modelName);
        MicroCompactResult.CacheEditsBlock consumedCacheEdits =
            cachedMcEnabled ? MicroCompactor.consumePendingCacheEditsBlock() : null;

        MessageCreateParams.Builder b = MessageCreateParams.builder()
            .model(modelName == null ? "" : modelName)
            .maxTokens(maxOutputTokensOverride != null
                ? maxOutputTokensOverride
                // [G-18] 请求体默认与压缩链同源：DB 优先（models.max_tokens）→ CC 家族表回落
                : resolveMaxOutputTokensForModel(modelName));

        // [C-31] output_config: task_budget + effort + format 共享单节点（CC claude.ts:1559-1565）
        OutputConfig.Builder ocb = null;
        if (taskBudget != null) {
            ocb = OutputConfig.builder().putAdditionalProperty("task_budget", JsonValue.from(
                taskBudgetRemainingNode(taskBudget)));
        }
        String resolvedEffort = EffortSupport.resolveAppliedEffort(modelName, effortValue);
        if (modelSupportsEffortLocal(modelName) && resolvedEffort != null) {
            if (ocb == null) ocb = OutputConfig.builder();
            ocb.effort(OutputConfig.Effort.of(resolvedEffort));
        }
        if (outputFormat != null) {
            if (ocb == null) ocb = OutputConfig.builder();
            var fmtBuilder = com.anthropic.models.messages.JsonOutputFormat.builder();
            // [FIX-FR] wire = output_config.format = {type, schema}（CC sideQuery.ts:190）
            fmtBuilder.type(JsonValue.from(outputFormat.type()));
            if (outputFormat.schema() != null) {
                var schemaBuilder = com.anthropic.models.messages.JsonOutputFormat.Schema.builder();
                outputFormat.schema().fields().forEachRemaining(en ->
                    schemaBuilder.putAdditionalProperty(en.getKey(), JsonValue.fromJsonNode(en.getValue())));
                fmtBuilder.schema(schemaBuilder.build());
            }
            ocb.format(fmtBuilder.build());
        }
        if (ocb != null) {
            b.outputConfig(ocb.build());
        }

        // [⊕C-2] system blocks → text block 数组 · 单一实现收敛（IMP-SP2-05）：
        // 构建语义统一走 SystemPromptBlocksBuilder.buildSystemPromptBlocks（对齐 CC claude.ts:3213-3237），
        // 与 prompt state snapshot（recordPromptState）同源——cache_control 门控（caching && scope != NULL）
        // 与 ttl/scope 取值仅存在于 builder 一处，发送侧仅做 ArrayNode → SDK TextBlockParam 机械转换
        // （toSdkSystemBlocks，漂移风险结构性消除，SP-01 §10-4 风险 4）。
        // [W9-01] enablePromptCaching 非空 → 覆盖模型级 gate（CC claude.ts:1374-1375）
        boolean caching = enablePromptCaching != null
            ? enablePromptCaching
            : PromptCaching.getPromptCachingEnabled(modelName);
        if (systemPromptBlocks != null && !systemPromptBlocks.isEmpty()) {
            b.systemOfTextBlockParams(toSdkSystemBlocks(
                SystemPromptBlocksBuilder.buildSystemPromptBlocks(systemPromptBlocks, caching)));
        }

        // messages 数组（tool_result / tool_use / 多模态）
        List<MessageParam> msgs = buildSdkMessages(history);

        // [ODF-B3] messages 通道 exactly-one cache_control marker（CC claude.ts:3078-3091）
        if (!msgs.isEmpty()) {
            boolean cachingEnabled = caching;
            if (cachingEnabled) {
                int markerIndex = (skipCacheWrite != null && skipCacheWrite)
                    ? msgs.size() - 2
                    : msgs.size() - 1;
                if (markerIndex >= 0) {
                    MessageParam target = msgs.get(markerIndex);
                    msgs.set(markerIndex, applyCacheMarker(target));
                }
            }
        }
        // [cached-MC 注入] 重插已钉住 cache_edits + 插入新 cache_edits（解法 A：JsonValue._json
        // 反射构造 ContentBlockParam）· 对齐 CC addCacheBreakpoints（claude.ts:3112-3162）。
        // 位置必须在 cache_control marker 之后、b.messages 之前——CC 顺序 = 先 marker（:3090-3106）
        // 后 cache_edits（:3127-3162）；注入读已附 marker 的 MessageParam content 再重建，marker 保留。
        if (cachedMcEnabled) {
            injectCacheEditsBlocks(msgs, consumedCacheEdits);
            // [cache_reference] marker 之前 user 消息的 tool_result 块附 cache_reference · 对齐 CC
            // claude.ts:3164-3208（"Must be done AFTER cache_edits insertion since that modifies
            // content arrays"）。门控双重：外层 cachedMcEnabled = CC useCachedMC（:3108 早期 return
            // 在 cache_edits 与 cache_reference 之前），内层 caching = CC enablePromptCaching（:3166）。
            if (caching) {
                addCacheReferenceToToolResults(msgs);
            }
        }

        // SDK 校验 messages 必填；空数组与旧 buildRequestBody 恒产出 messages 数组一致
        b.messages(msgs);

        // tools 转换（OpenAI → Anthropic）
        if (tools != null && !tools.isEmpty()) {
            List<ToolUnion> sdkTools = new ArrayList<>();
            // [G4] strict 模型层门控：flag && model != null && firstParty && 白名单
            //   （CC api.ts:185-192 模型层 · betas.ts:142-157，防 Bedrock/Vertex 400）。
            //   意图层（ToolRegistry）已把 flag && tool.strict() 写入 JSON strict 字段。
            boolean strictModelGate = StructuredOutputsSupport.shouldTransmitStrictAnthropic(modelName, firstParty);
            for (JsonNode tool : tools) {
                ToolUnion tu = toSdkTool(tool, strictModelGate);
                if (tu != null) sdkTools.add(tu);
            }
            if (!sdkTools.isEmpty()) {
                b.tools(sdkTools);
            }
        }

        // anthropic-beta header（task-budgets / effort / structured-outputs / tool-search / prompt-caching-scope，逗号分隔）
        java.util.ArrayList<String> betas = new java.util.ArrayList<>(2);
        if (taskBudget != null) betas.add(TASK_BUDGETS_BETA_HEADER);
        if (modelSupportsEffortLocal(modelName)) betas.add(EFFORT_BETA_HEADER);
        if (outputFormat != null) betas.add(STRUCTURED_OUTPUTS_BETA_HEADER);
        // [H4] tool-search beta header · 对齐 CC claude.ts:1174-1177（useToolSearch →
        //   getToolSearchBetaHeader() → betas.push，「required for defer_loading to be accepted」）·
        //   Java 无 API-provider 抽象 → 1P header（betas.ts:13），3P（Vertex/Bedrock）N/A。
        //   判定：tools ArrayNode 含 defer_loading:true 或 ToolSearch 名。
        if (toolsContainToolSearch(tools)) {
            betas.add(ToolNameConstants.TOOL_SEARCH_BETA_HEADER_1P);
        }
        // [IMP-SP2-07 ✗-13] global-scope prompt caching 是 firstParty 专属（betas.ts:227-233 同 gate）·
        //   CC claude.ts:1217-1222：useGlobalCacheFeature && !betas.includes(PROMPT_CACHING_SCOPE_BETA_HEADER)
        //   → push（去重；3P / config null → gate false → 零变化）。FQN 引用 GlobalCacheScope 单实现，
        //   不重写 firstParty 判定（OPD-SP-27 / REQ-R4-1 验收 4）。
        boolean useGlobalCacheFeature =
            com.nexusai.application.agent.compact.fork.GlobalCacheScope.shouldUseGlobalCacheScope(config);
        if (useGlobalCacheFeature && !betas.contains(PROMPT_CACHING_SCOPE_BETA_HEADER)) {
            betas.add(PROMPT_CACHING_SCOPE_BETA_HEADER);
        }
        if (!betas.isEmpty()) {
            b.putAdditionalHeader("anthropic-beta", String.join(",", betas));
        }

        return b.build();
    }

    /** CC original: adjustParamsForNonStreaming (Open-ClaudeCode/src/services/api/claude.ts:3364-3392)。
     *  非流式硬 cap：max_tokens = min(max_tokens, MAX_NON_STREAMING_TOKENS)（无条件 Math.min，仅超限时生效）；
     *  仅当 thinking enabled 且 budget_tokens 存在（&gt;0）时 budget_tokens = min(budget, capped-1)
     *  （满足 API 约束 max_tokens &gt; thinking.budget_tokens，至少比 max_tokens 小 1）。
     *  thinking 类型不改；其余字段经 toBuilder() 全部保留（等价 CC {...params, max_tokens: capped} spread）。
     *  Java 非流式现不传 thinking，budget 钳制为潜伏分支（对齐 CC 完整语义，未来非流式带 thinking 时自动正确）。 */
    public static MessageCreateParams adjustParamsForNonStreaming(MessageCreateParams params) {
        long cappedMaxTokens = Math.min(params.maxTokens(), MAX_NON_STREAMING_TOKENS);
        MessageCreateParams.Builder b = params.toBuilder().maxTokens(cappedMaxTokens);
        // thinking budget 同步（capped-1）——仅 type==='enabled' 且 budget_tokens truthy 才钳制（CC :3370-3385）
        params.thinking().ifPresent(t -> {
            if (t.isEnabled()) {
                ThinkingConfigEnabled enabled = t.asEnabled();
                long budget = enabled.budgetTokens();
                if (budget > 0) {
                    // CC {...thinking, budget_tokens: X} spread 等价：toBuilder() 保留全部字段
                    // （budgetTokens/display/additionalProperties/type），仅改写 budget_tokens。
                    // 不用 builder() 重建（会丢失 additionalProperties 等其余字段）。
                    ThinkingConfigEnabled.Builder enb = enabled.toBuilder()
                        .budgetTokens(Math.min(budget, cappedMaxTokens - 1L));
                    b.thinking(enb.build());
                }
            }
        });
        return b.build();
    }

    /** task_budget → {type:'tokens', total, remaining?}（CC claude.ts:479-500）。 */
    private static Map<String, Object> taskBudgetRemainingNode(TaskBudgetParam taskBudget) {
        Map<String, Object> tb = new LinkedHashMap<>();
        tb.put("type", "tokens");
        tb.put("total", taskBudget.total());
        if (taskBudget.remaining() != null) {
            tb.put("remaining", taskBudget.remaining());
        }
        return tb;
    }

    /**
     * messages 通道 marker：字符串 content → 转数组单 text block + cache_control；数组 → 末 block 附 marker。
     *
     * <p>[DEC-RV-07 REWORK-1] 对齐 CC claude.ts:599-663 —— userMessageToMessageParam /
     * assistantMessageToMessageParam 对末 block 附 cache_control 的规则：<b>除 thinking /
     * redacted_thinking 外任意 block 类型均可附</b>（user 恒附；assistant 排除 thinking 系）。
     * 旧实现仅 text 块附 marker，导致末消息为 tool_result / tool_use / image / document 时
     * 整个 messages 通道丢失唯一 cache 断点（mycro 本地 attention KV 页立即释放，缓存失效）。
     */
    private static MessageParam applyCacheMarker(MessageParam target) {
        var content = target.content();
        if (content.isString()) {
            String s = content.asString();
            List<ContentBlockParam> blocks = new ArrayList<>();
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                .text(s)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build()));
            return MessageParam.builder().role(target.role()).contentOfBlockParams(blocks).build();
        } else if (content.isBlockParams()) {
            List<ContentBlockParam> blocks = new ArrayList<>(content.asBlockParams());
            if (!blocks.isEmpty()) {
                ContentBlockParam marked = withCacheControl(blocks.get(blocks.size() - 1));
                if (marked != null) {
                    blocks.set(blocks.size() - 1, marked);
                }
            }
            return MessageParam.builder().role(target.role()).contentOfBlockParams(blocks).build();
        }
        return target;
    }

    /**
     * 注入 cache_edits 块到 messages（pinned 重插 + 新块插入）· 对齐 CC {@code addCacheBreakpoints}
     * （Open-ClaudeCode/src/services/api/claude.ts:3112-3162）。
     *
     * <p>顺序严格对齐 CC：
     * <ol>
     *   <li>重插所有已钉住块（CC :3127-3140）——for pinned of pinnedEdits → 目标 user 消息
     *       {@code insertBlockAfterToolResults}；跨块去重（seenDeleteRefs，:3112-3125）</li>
     *   <li>插入新块（CC :3141-3157）——从后往前找最后 user 消息 → 插入 → pinCacheEdits
     *       （钉住<b>原始</b>块，非 deduped，CC claude.ts:3153）</li>
     * </ol>
     *
     * @param msgs     SDK MessageParam 列表（目标消息原地重建并 set 回，等效 CC 原地 mutate msg.content）
     * @param newBlock 新待下发块（可 null = 仅重插 pinned，对齐 CC newCacheEdits undefined）
     */
    private static void injectCacheEditsBlocks(List<MessageParam> msgs,
                                               MicroCompactResult.CacheEditsBlock newBlock) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }
        // 跨块去重：同一 tool_use_id 不得被多个 cache_edits 块重复删除（CC claude.ts:3112-3125）
        Set<String> seenDeleteRefs = new HashSet<>();
        // ① 重插已钉住块（CC :3127-3140）
        for (MicroCompactResult.PinnedCacheEdits pinned : MicroCompactor.getPinnedCacheEdits()) {
            int idx = pinned.userMessageIndex();
            if (idx < 0 || idx >= msgs.size()) {
                continue;
            }
            MessageParam msg = msgs.get(idx);
            if (msg.role() != MessageParam.Role.USER) {
                continue;
            }
            MicroCompactResult.CacheEditsBlock deduped = deduplicateEdits(pinned.block(), seenDeleteRefs);
            if (deduped.edits() != null && !deduped.edits().isEmpty()) {
                msgs.set(idx, injectBlockIntoUserMessage(msg, deduped));
            }
        }
        // ② 插入新块（CC :3141-3157）
        if (newBlock != null) {
            MicroCompactResult.CacheEditsBlock dedupedNew = deduplicateEdits(newBlock, seenDeleteRefs);
            if (dedupedNew.edits() != null && !dedupedNew.edits().isEmpty()) {
                for (int i = msgs.size() - 1; i >= 0; i--) {
                    MessageParam msg = msgs.get(i);
                    if (msg.role() == MessageParam.Role.USER) {
                        msgs.set(i, injectBlockIntoUserMessage(msg, dedupedNew));
                        // CC 钉住原始块（claude.ts:3153 pinCacheEdits(i, newCacheEdits)——非 deduped，
                        // 下一请求重插时再行去重）
                        MicroCompactor.pinCacheEdits(i, newBlock);
                        log.info("AnthropicSdkProvider: 已注入 cache_edits 块（{} 个删除）到 messages[{}]"
                                + " · CC claude.ts:3141-3157",
                            dedupedNew.edits().size(), i);
                        return;
                    }
                }
            }
        }
    }

    /**
     * 给 cache_control marker <b>之前</b> user 消息的 tool_result 块附加 cache_reference · 对齐 CC
     * {@code addCacheBreakpoints} 末尾段（Open-ClaudeCode/src/services/api/claude.ts:3164-3208）
     * —— "Add cache_reference to tool_result blocks that are within the cached prefix. Must be
     * done AFTER cache_edits insertion since that modifies content arrays."
     *
     * <p>逐行对齐 CC 语义：
     * <ol>
     *   <li>找最后一个含 cache_control marker 的消息下标 {@code lastCCMsg}（CC :3168-3178，
     *       Java 读 content block 的 {@code cacheControl()}）</li>
     *   <li>对 {@code lastCCMsg} <b>严格之前</b>（i &lt; lastCCMsg）的 user 消息（CC :3190
     *       {@code msg.role !== 'user' || !Array.isArray(msg.content)} → continue）：content 里每个
     *       type==tool_result 块重建并附 {@code cache_reference: block.tool_use_id}
     *       （CC :3201-3203 {@code Object.assign({}, block, {cache_reference: block.tool_use_id})}）</li>
     * </ol>
     *
     * <p><b>strict before 的 WHY</b>（CC :3180-3186 注释）：API 要求 cache_reference 出现在最后一个
     * cache_control "before or on"；CC 用严格 before 避免 cache_edits splice 移位块序的边界问题。
     *
     * <p><b>重建替换的 WHY</b>（CC :3184-3186 注释 "Create new objects instead of mutating in-place
     * to avoid contaminating blocks reused by secondary queries that use models without cache_editing
     * support"）：Java {@link MessageParam}/{@link ToolResultBlockParam} 不可变 → 经
     * {@code toBuilder().putAdditionalProperty("cache_reference", ...)} 重建并 set 回（等效 CC 新建对象），
     * 避免污染被复用块。
     *
     * @param msgs cache_edits 注入后的最终 SDK MessageParam 数组（含 marker；本方法原地重建并 set 回）
     */
    private static void addCacheReferenceToToolResults(List<MessageParam> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }
        // CC :3168-3178 —— 找最后一个含 cache_control marker 的消息下标
        int lastCCMsg = -1;
        for (int i = 0; i < msgs.size(); i++) {
            MessageParam msg = msgs.get(i);
            if (msg == null || !msg.content().isBlockParams()) {
                continue;
            }
            for (ContentBlockParam block : msg.content().asBlockParams()) {
                // 反射构造的 cache_edits 块访问器会抛（见 isCacheEditsBlock）→ 先跳过再读 cacheControl()
                if (block == null || isCacheEditsBlock(block)) {
                    continue;
                }
                if (block.cacheControl() != null && block.cacheControl().isPresent()) {
                    lastCCMsg = i;
                }
            }
        }
        if (lastCCMsg < 0) {
            return;
        }
        // CC :3187-3206 —— 严格 before（i < lastCCMsg）user 消息 tool_result 块附 cache_reference
        for (int i = 0; i < lastCCMsg; i++) {
            MessageParam msg = msgs.get(i);
            if (msg == null || msg.role() != MessageParam.Role.USER || !msg.content().isBlockParams()) {
                continue;
            }
            List<ContentBlockParam> content = msg.content().asBlockParams();
            List<ContentBlockParam> rebuilt = null;
            for (int j = 0; j < content.size(); j++) {
                ContentBlockParam block = content.get(j);
                if (block == null || isCacheEditsBlock(block) || !block.isToolResult()) {
                    continue;
                }
                ToolResultBlockParam tr = block.asToolResult();
                if (rebuilt == null) {
                    rebuilt = new ArrayList<>(content);
                }
                // CC :3201-3203 Object.assign({}, block, {cache_reference: block.tool_use_id})
                rebuilt.set(j, ContentBlockParam.ofToolResult(tr.toBuilder()
                    .putAdditionalProperty("cache_reference", JsonValue.from(tr.toolUseId()))
                    .build()));
            }
            if (rebuilt != null) {
                msgs.set(i, MessageParam.builder().role(msg.role()).contentOfBlockParams(rebuilt).build());
                if (log.isDebugEnabled()) {
                    log.debug("AnthropicSdkProvider: 已为 messages[{}] 的 tool_result 块附加 cache_reference"
                            + " · CC claude.ts:3187-3206", i);
                }
            }
        }
    }

    /**
     * 反射构造的 cache_edits 块安全识别 · 仅读 {@code _json()}（普通字段访问器，不触发 Visitor）——
     * 对未知块（如 cache_edits）调用任何类型访问器（cacheControl()/isToolResult() 等）会抛
     * {@code AnthropicInvalidDataException: Unknown ContentBlockParam}（ContentBlockParam.kt:945
     * Visitor.unknown，反射构造仅设 _json 字段）。镜像 {@link #buildCacheEditsContentBlock} 形状
     * {@code {type:'cache_edits', edits:[...]}}（cachedMicrocompact.ts:9-12）+ 测试先例
     * isCacheEditsBlock（AnthropicSdkProviderCacheEditsInjectionTest）。
     *
     * @param block 待判别 content block（可 null）
     * @return true = 反射构造的 cache_edits 块（遍历/重建时必须跳过，不能调用类型访问器）
     */
    private static boolean isCacheEditsBlock(ContentBlockParam block) {
        if (block == null || !block._json().isPresent()) {
            return false;
        }
        try {
            JsonValue json = block._json().get();
            JsonNode node = json.convert(JsonNode.class);
            return node != null && node.isObject() && "cache_edits".equals(node.path("type").asText());
        } catch (RuntimeException e) {
            // _json 读取/转换异常（防御，不阻断遍历）→ 视为非 cache_edits，走常规访问器（失败由访问器暴露）
            return false;
        }
    }

    /**
     * 跨块去重 cache_edits · 对齐 CC {@code deduplicateEdits(block)}（claude.ts:3116-3125）：
     * 已删除过的 cache_reference（Java tool_use_id）过滤，其余登记进 seenDeleteRefs 防后续块重复。
     *
     * @param block          源块（不修改，返回新块）
     * @param seenDeleteRefs 跨块去重登记表（调用方持有，pinned 与 new 共享）
     * @return 过滤后的新块（edits 可能为空——空则调用方跳过插入）
     */
    private static MicroCompactResult.CacheEditsBlock deduplicateEdits(
        MicroCompactResult.CacheEditsBlock block, Set<String> seenDeleteRefs) {
        List<MicroCompactResult.CacheEditsBlock.CacheEdit> unique = new ArrayList<>();
        for (MicroCompactResult.CacheEditsBlock.CacheEdit edit : block.edits()) {
            if (seenDeleteRefs.contains(edit.toolUseId())) {
                continue;
            }
            seenDeleteRefs.add(edit.toolUseId());
            unique.add(edit);
        }
        return new MicroCompactResult.CacheEditsBlock(block.type(), unique);
    }

    /**
     * 单条 user 消息插入 cache_edits 块 · 对齐 CC claude.ts:3147-3150（String content → 单 text
     * block 数组）+ {@code insertBlockAfterToolResults}（contentArray.ts:21-51）。以新 content 重建
     * MessageParam 返回（SDK MessageParam 不可变 → 重建替换，等效 CC 原地 mutate msg.content）。
     */
    private static MessageParam injectBlockIntoUserMessage(MessageParam msg,
                                                           MicroCompactResult.CacheEditsBlock block) {
        List<ContentBlockParam> content;
        if (msg.content().isBlockParams()) {
            content = new ArrayList<>(msg.content().asBlockParams());
        } else if (msg.content().isString()) {
            content = new ArrayList<>();
            content.add(ContentBlockParam.ofText(TextBlockParam.builder()
                .text(msg.content().asString()).build()));
        } else {
            return msg;
        }
        insertAfterToolResults(content, buildCacheEditsContentBlock(block));
        return MessageParam.builder().role(msg.role()).contentOfBlockParams(content).build();
    }

    /**
     * 在 content 数组最后一个 tool_result 块后插入 block · 对齐 CC
     * {@code insertBlockAfterToolResults}（Open-ClaudeCode/src/utils/contentArray.ts:21-51）：
     * <ul>
     *   <li>有 tool_result → 最后一个 tool_result 后插入；若插入后成为末元素 → 追加
     *       {@code {type:'text', text:'.'}} 延续块（部分 API 不允许 prompt 以非 text 内容结尾）</li>
     *   <li>无 tool_result → 在 content.length-1 位置插入（末块前）</li>
     * </ul>
     */
    private static void insertAfterToolResults(List<ContentBlockParam> content,
                                               ContentBlockParam block) {
        int lastToolResultIndex = -1;
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i).isToolResult()) {
                lastToolResultIndex = i;
            }
        }
        if (lastToolResultIndex >= 0) {
            int insertPos = lastToolResultIndex + 1;
            content.add(insertPos, block);
            if (insertPos == content.size() - 1) {
                content.add(ContentBlockParam.ofText(TextBlockParam.builder().text(".").build()));
            }
        } else {
            int insertIndex = Math.max(0, content.size() - 1);
            content.add(insertIndex, block);
        }
    }

    /**
     * 反射构造 cache_edits 内容块（解法 A）· 对齐 CC {@code CacheEditsBlock}
     * （cachedMicrocompact.ts:9-12）序列化形状 {@code {type:'cache_edits',
     * edits:[{type:'delete_tool_result', tool_use_id}]}}。
     *
     * <p><b>WHY 反射</b>: anthropic-java 2.53.0 {@link ContentBlockParam} 为 sealed Kotlin 类，
     * 无 cache_edits 变体；SDK 序列化走 {@code _json} 通道（各模型类经 {@code _json()} 原样输出）。
     * 故用 ObjectMapper 构造 JSON → {@link JsonValue#fromJsonNode} → 反射调私有构造器
     * （18 参：17 个 block 参数 + 末参 {@code _json}）。<b>不得</b>调 {@code validate()}/
     * 内部构造器（会抛 Unknown ContentBlockParam）——仅反射构造，不触碰其他 SDK 逻辑。
     */
    private static ContentBlockParam buildCacheEditsContentBlock(MicroCompactResult.CacheEditsBlock block) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", block.type());
        ArrayNode edits = node.putArray("edits");
        for (MicroCompactResult.CacheEditsBlock.CacheEdit edit : block.edits()) {
            ObjectNode e = edits.addObject();
            e.put("type", edit.type());
            e.put("tool_use_id", edit.toolUseId());
        }
        JsonValue json = JsonValue.fromJsonNode(node);
        try {
            Constructor<ContentBlockParam> ctor = ContentBlockParam.class.getDeclaredConstructor(
                TextBlockParam.class, ImageBlockParam.class, DocumentBlockParam.class,
                SearchResultBlockParam.class, ThinkingBlockParam.class, RedactedThinkingBlockParam.class,
                ToolUseBlockParam.class, ToolResultBlockParam.class, ServerToolUseBlockParam.class,
                WebSearchToolResultBlockParam.class, WebFetchToolResultBlockParam.class,
                CodeExecutionToolResultBlockParam.class, BashCodeExecutionToolResultBlockParam.class,
                TextEditorCodeExecutionToolResultBlockParam.class, ToolSearchToolResultBlockParam.class,
                ContainerUploadBlockParam.class, MidConversationSystemBlockParam.class, JsonValue.class);
            ctor.setAccessible(true);
            return ctor.newInstance(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, json);
        } catch (Exception e) {
            throw new IllegalStateException(
                "反射构造 cache_edits ContentBlockParam 失败（_json 通道）", e);
        }
    }

    /**
     * builder 产物（ArrayNode）→ SDK {@link TextBlockParam} 列表 · [⊕C-2] 纯机械字段映射。
     *
     * <p>逐 block 读 {@code text} / {@code cache_control.ttl} / {@code cache_control.scope}
     * 构造 SDK 对象；{@code cache_control} 缺失则不附。不含任何门控/ttl/scope 语义决策
     * （语义唯一实现 = {@link SystemPromptBlocksBuilder}，本方法仅做 JSON → SDK 形状转换）。
     */
    private static List<TextBlockParam> toSdkSystemBlocks(ArrayNode built) {
        List<TextBlockParam> out = new ArrayList<>(built.size());
        for (JsonNode n : built) {
            String text = n.has("text") ? n.get("text").asText() : "";
            TextBlockParam.Builder tbb = TextBlockParam.builder().text(text);
            JsonNode cc = n.get("cache_control");
            if (cc != null && cc.isObject()) {
                CacheControlEphemeral.Builder ccb = CacheControlEphemeral.builder();
                JsonNode ttl = cc.get("ttl");
                if (ttl != null && ttl.isTextual()) {
                    ccb.ttl(CacheControlEphemeral.Ttl.of(ttl.asText()));
                }
                JsonNode scope = cc.get("scope");
                if (scope != null && scope.isTextual()) {
                    ccb.putAdditionalProperty("scope", JsonValue.from(scope.asText()));
                }
                tbb.cacheControl(ccb.build());
            }
            out.add(tbb.build());
        }
        return out;
    }

    /** 单 block 附 cache_control · thinking/redacted_thinking 等不可附类型返回 null（CC claude.ts:599-663 排除规则）。 */
    private static ContentBlockParam withCacheControl(ContentBlockParam block) {
        if (block.isText()) {
            return ContentBlockParam.ofText(block.asText().toBuilder()
                .cacheControl(CacheControlEphemeral.builder().build()).build());
        }
        if (block.isToolResult()) {
            return ContentBlockParam.ofToolResult(block.asToolResult().toBuilder()
                .cacheControl(CacheControlEphemeral.builder().build()).build());
        }
        if (block.isToolUse()) {
            return ContentBlockParam.ofToolUse(block.asToolUse().toBuilder()
                .cacheControl(CacheControlEphemeral.builder().build()).build());
        }
        if (block.isImage()) {
            return ContentBlockParam.ofImage(block.asImage().toBuilder()
                .cacheControl(CacheControlEphemeral.builder().build()).build());
        }
        if (block.isDocument()) {
            return ContentBlockParam.ofDocument(block.asDocument().toBuilder()
                .cacheControl(CacheControlEphemeral.builder().build()).build());
        }
        return null;
    }

    /** ChatMessageDto 数组 → SDK MessageParam 数组（tool_result / tool_use / 多模态）。 */
    private static List<MessageParam> buildSdkMessages(List<ChatMessageDto> history) {
        List<MessageParam> msgs = new ArrayList<>();
        if (history == null) return msgs;
        for (ChatMessageDto m : history) {
            if (m == null || m.role() == null) continue;
            if (m.role() == Role.system) {
                continue; // system 已在 top-level 处理
            }
            if (m.role() == Role.tool) {
                if (m.toolCallId() == null) {
                    log.warn("skipping tool message without toolCallId");
                    continue;
                }
                List<ContentBlockParam> blocks = new ArrayList<>();
                ToolResultBlockParam.Builder trb = ToolResultBlockParam.builder()
                    .toolUseId(m.toolCallId());
                // [CC 双形态] tool 消息的 contentBlocks 在 CC 中按块类型分两种形状：
                //  1) tool_reference 块 → 嵌套进 tool_result.content 数组
                //     （ToolSearchTool.ts:462-469；content 非空时 text(content) 前置，纯 toolRef 时无 text）；
                //  2) image/text/document 块 → tool_result 的兄弟顶层块
                //     （toolExecution.ts:1418-1438 addToolResult allow 注入、1029-1046 拒绝路径均如此），
                //     绝不嵌套进 content。
                // 故按块类型拆分（仅 tool_reference 走 [X-1] 嵌套语义，其余块回退到 CC 兄弟形态）。
                List<ToolResultBlockParam.Content.Block> toolContent = null; // lazy：嵌套 tool_result.content 的块
                List<JsonNode> siblingBlocks = new ArrayList<>();            // 兄弟顶层块（image/text/document）
                // [IMP-C5] 空 payload + contentBlocks 非空 = per-tool mapper 产出的块数组
                //   （toolResultMessage serializeToolResultBlocks → payload=""）。典型为 FileReadTool
                //   image 独立块（CC FileReadTool.ts:654-669 tool_result.content=[image]）与
                //   ToolSearchTool tool_reference。此场景块须<b>嵌套进 tool_result.content</b>
                //   （CC 真源）；非空 payload（permission allow/ask 注入）时 image/text/document
                //   仍是兄弟顶层块（CC toolExecution.ts:1418-1438 addToolResult allow 注入）。
                boolean payloadEmpty = m.content() == null || m.content().isEmpty();
                if (m.contentBlocks() != null && !m.contentBlocks().isEmpty()) {
                    for (Object blockObj : m.contentBlocks()) {
                        if (!(blockObj instanceof JsonNode block) || !block.isObject()) continue;
                        String btype = block.has("type") ? block.get("type").asText() : null;
                        if ("tool_reference".equals(btype) || payloadEmpty) {
                            if (toolContent == null) {
                                toolContent = new ArrayList<>();
                                if (m.content() != null && !m.content().isEmpty()) {
                                    toolContent.add(ToolResultBlockParam.Content.Block.ofText(
                                        TextBlockParam.builder().text(m.content()).build()));
                                }
                            }
                            appendToolResultContentBlock(toolContent, block);
                        } else {
                            siblingBlocks.add(block);
                        }
                    }
                }
                if (toolContent != null) {
                    trb.contentOfBlocks(toolContent);
                } else {
                    trb.content(m.content() == null ? "" : m.content());
                }
                blocks.add(ContentBlockParam.ofToolResult(trb.build()));
                // [R32-b9-fix Fix E] acceptFeedback 独立 text block
                if (m.acceptFeedback() != null && !m.acceptFeedback().isBlank()) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text(m.acceptFeedback()).build()));
                }
                // [CC toolExecution.ts:1432-1438] 非 tool_reference 块按原顺序追加为兄弟顶层块
                for (JsonNode block : siblingBlocks) {
                    appendSdkContentBlock(blocks, block);
                }
                msgs.add(MessageParam.builder().role(MessageParam.Role.USER)
                    .contentOfBlockParams(blocks).build());
                continue;
            }
            if (m.role() == Role.assistant && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<ContentBlockParam> blocks = new ArrayList<>();
                if (m.content() != null && !m.content().isEmpty()) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text(m.content()).build()));
                }
                for (var tc : m.toolCalls()) {
                    ToolUseBlockParam.Input.Builder inb = ToolUseBlockParam.Input.builder();
                    JsonNode inputJson;
                    try {
                        inputJson = (tc.arguments() == null || tc.arguments().isEmpty())
                            ? JSON.createObjectNode()
                            : JSON.readTree(tc.arguments());
                    } catch (Exception e) {
                        // [_raw fail-loud 2026-09-04] arguments 非法 JSON → 记录（含工具名/长度/异常），
                        //   执行层拦截 {_raw} 引导模型拆小（见 StreamingToolExecutor _raw 拦截）。
                        log.warn("AnthropicSdkProvider: tool_call arguments 非法 JSON（readTree 失败）"
                                + "name={} len={} err={} → _raw 兜底", tc.name(),
                            tc.arguments() == null ? 0 : tc.arguments().length(), e.toString());
                        var wrapper = JSON.createObjectNode();
                        wrapper.put("_raw", tc.arguments() == null ? "" : tc.arguments());
                        inputJson = wrapper;
                    }
                    if (inputJson.isObject()) {
                        inputJson.fields().forEachRemaining(en ->
                            inb.putAdditionalProperty(en.getKey(), JsonValue.fromJsonNode(en.getValue())));
                    }
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(tc.id())
                        .name(tc.name())
                        .input(inb.build())
                        .build()));
                }
                msgs.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(blocks).build());
                continue;
            }
            // 普通 user / assistant
            if (m.role() == Role.user
                && m.contentBlocks() != null && !m.contentBlocks().isEmpty()) {
                List<ContentBlockParam> blocks = new ArrayList<>();
                for (Object blockObj : m.contentBlocks()) {
                    if (!(blockObj instanceof JsonNode block) || !block.isObject()) continue;
                    appendSdkContentBlock(blocks, block);
                }
                msgs.add(MessageParam.builder().role(MessageParam.Role.USER)
                    .contentOfBlockParams(blocks).build());
            } else {
                MessageParam.Role r = m.role() == Role.assistant
                    ? MessageParam.Role.ASSISTANT : MessageParam.Role.USER;
                msgs.add(MessageParam.builder().role(r)
                    .content(m.content() == null ? "" : m.content()).build());
            }
        }
        return msgs;
    }

    /** 单块渲染 · document/image/text JsonNode → SDK ContentBlockParam（迁移自 AnthropicProvider.appendContentBlock）。 */
    private static void appendSdkContentBlock(List<ContentBlockParam> contentArr, JsonNode block) {
        String btype = block.has("type") ? block.get("type").asText() : null;
        if ("document".equals(btype)) {
            JsonNode src = block.has("source") ? block.get("source") : null;
            if (src != null && src.isObject() && src.has("type") && "base64".equals(src.get("type").asText())) {
                contentArr.add(ContentBlockParam.ofDocument(
                    com.anthropic.models.messages.DocumentBlockParam.builder()
                        .source(com.anthropic.models.messages.Base64PdfSource.builder()
                            .data(src.path("data").asText())
                            .mediaType(src.has("media_type") ? JsonValue.from(src.get("media_type").asText()) : JsonValue.from("application/pdf"))
                            .build())
                        .build()));
            } else if (src != null && src.isObject()) {
                contentArr.add(ContentBlockParam.ofDocument(
                    com.anthropic.models.messages.DocumentBlockParam.builder()
                        .title(block.has("title") ? block.get("title").asText() : null)
                        .source(com.anthropic.models.messages.DocumentBlockParam.Source.ofUrl(
                            com.anthropic.models.messages.UrlPdfSource.builder()
                                .url(src.path("url").asText())
                                .build()))
                        .build()));
            } else if (block.has("data") && block.has("media_type")) {
                contentArr.add(ContentBlockParam.ofDocument(
                    com.anthropic.models.messages.DocumentBlockParam.builder()
                        .source(com.anthropic.models.messages.Base64PdfSource.builder()
                            .data(block.get("data").asText())
                            .mediaType(JsonValue.from(block.get("media_type").asText()))
                            .build())
                        .build()));
            }
        } else if ("image".equals(btype)) {
            JsonNode src = block.has("source") ? block.get("source") : null;
            if (src != null && src.isObject() && src.has("type") && "base64".equals(src.get("type").asText())) {
                contentArr.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(ImageBlockParam.Source.ofBase64(
                        com.anthropic.models.messages.Base64ImageSource.builder()
                            .data(src.path("data").asText())
                            .mediaType(com.anthropic.models.messages.Base64ImageSource.MediaType.of(src.path("media_type").asText()))
                            .build()))
                    .build()));
            } else if (src != null && src.isObject() && src.has("type") && "url".equals(src.get("type").asText())) {
                contentArr.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(ImageBlockParam.Source.ofUrl(
                        com.anthropic.models.messages.UrlImageSource.builder()
                            .url(src.path("url").asText())
                            .build()))
                    .build()));
            } else if (block.has("data") && block.has("media_type")) {
                contentArr.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(ImageBlockParam.Source.ofBase64(
                        com.anthropic.models.messages.Base64ImageSource.builder()
                            .data(block.get("data").asText())
                            .mediaType(com.anthropic.models.messages.Base64ImageSource.MediaType.of(block.get("media_type").asText()))
                            .build()))
                    .build()));
            } else {
                // fallback: 整块作为 source（兼容 url 类型，R32B9 透传语义）
                if (src != null && src.isObject()) {
                    contentArr.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(ImageBlockParam.Source.ofUrl(
                            com.anthropic.models.messages.UrlImageSource.builder()
                                .url(src.has("url") ? src.get("url").asText() : src.toString())
                                .build()))
                        .build()));
                }
            }
        } else if ("text".equals(btype)) {
            String t = block.has("text") ? block.get("text").asText() : "";
            contentArr.add(ContentBlockParam.ofText(TextBlockParam.builder().text(t).build()));
        }
    }

    /** [X-1] tool_result.content 单块渲染 · text/image/document/tool_reference JsonNode
     *  → SDK ToolResultBlockParam.Content.Block（CC tool_result.content 嵌套块数组）。
     *  与 {@link #appendSdkContentBlock}（user 分支顶层块）镜像，差异仅在块嵌套进 tool_result.content 而非兄弟顶层块。 */
    private static void appendToolResultContentBlock(List<ToolResultBlockParam.Content.Block> contentArr, JsonNode block) {
        String btype = block.has("type") ? block.get("type").asText() : null;
        if ("document".equals(btype)) {
            JsonNode src = block.has("source") ? block.get("source") : null;
            if (src != null && src.isObject() && src.has("type") && "base64".equals(src.get("type").asText())) {
                contentArr.add(ToolResultBlockParam.Content.Block.ofDocument(
                    com.anthropic.models.messages.DocumentBlockParam.builder()
                        .source(com.anthropic.models.messages.Base64PdfSource.builder()
                            .data(src.path("data").asText())
                            .mediaType(src.has("media_type") ? JsonValue.from(src.get("media_type").asText()) : JsonValue.from("application/pdf"))
                            .build())
                        .build()));
            } else if (src != null && src.isObject()) {
                contentArr.add(ToolResultBlockParam.Content.Block.ofDocument(
                    com.anthropic.models.messages.DocumentBlockParam.builder()
                        .title(block.has("title") ? block.get("title").asText() : null)
                        .source(com.anthropic.models.messages.DocumentBlockParam.Source.ofUrl(
                            com.anthropic.models.messages.UrlPdfSource.builder()
                                .url(src.path("url").asText())
                                .build()))
                        .build()));
            } else if (block.has("data") && block.has("media_type")) {
                contentArr.add(ToolResultBlockParam.Content.Block.ofDocument(
                    com.anthropic.models.messages.DocumentBlockParam.builder()
                        .source(com.anthropic.models.messages.Base64PdfSource.builder()
                            .data(block.get("data").asText())
                            .mediaType(JsonValue.from(block.get("media_type").asText()))
                            .build())
                        .build()));
            }
        } else if ("image".equals(btype)) {
            JsonNode src = block.has("source") ? block.get("source") : null;
            if (src != null && src.isObject() && src.has("type") && "base64".equals(src.get("type").asText())) {
                contentArr.add(ToolResultBlockParam.Content.Block.ofImage(ImageBlockParam.builder()
                    .source(ImageBlockParam.Source.ofBase64(
                        com.anthropic.models.messages.Base64ImageSource.builder()
                            .data(src.path("data").asText())
                            .mediaType(com.anthropic.models.messages.Base64ImageSource.MediaType.of(src.path("media_type").asText()))
                            .build()))
                    .build()));
            } else if (src != null && src.isObject() && src.has("type") && "url".equals(src.get("type").asText())) {
                contentArr.add(ToolResultBlockParam.Content.Block.ofImage(ImageBlockParam.builder()
                    .source(ImageBlockParam.Source.ofUrl(
                        com.anthropic.models.messages.UrlImageSource.builder()
                            .url(src.path("url").asText())
                            .build()))
                    .build()));
            } else if (block.has("data") && block.has("media_type")) {
                contentArr.add(ToolResultBlockParam.Content.Block.ofImage(ImageBlockParam.builder()
                    .source(ImageBlockParam.Source.ofBase64(
                        com.anthropic.models.messages.Base64ImageSource.builder()
                            .data(block.get("data").asText())
                            .mediaType(com.anthropic.models.messages.Base64ImageSource.MediaType.of(block.get("media_type").asText()))
                            .build()))
                    .build()));
            } else if (src != null && src.isObject()) {
                // fallback: 整块作为 source（兼容 url 类型，R32B9 透传语义）
                contentArr.add(ToolResultBlockParam.Content.Block.ofImage(ImageBlockParam.builder()
                    .source(ImageBlockParam.Source.ofUrl(
                        com.anthropic.models.messages.UrlImageSource.builder()
                            .url(src.has("url") ? src.get("url").asText() : src.toString())
                            .build()))
                    .build()));
            }
        } else if ("text".equals(btype)) {
            String t = block.has("text") ? block.get("text").asText() : "";
            contentArr.add(ToolResultBlockParam.Content.Block.ofText(TextBlockParam.builder().text(t).build()));
        } else if ("tool_reference".equals(btype)) {
            // [X-1] tool_reference 块：仅 type + tool_name 两字段（CC ToolSearchTool.ts:462-469）。
            String toolName = block.has("tool_name") ? block.get("tool_name").asText() : null;
            if (toolName == null || toolName.isBlank()) {
                log.warn("appendToolResultContentBlock: tool_reference 块 tool_name 缺失/空白，跳过该块（fail-loud，不静默丢弃）");
                return;
            }
            contentArr.add(ToolResultBlockParam.Content.Block.ofToolReference(
                ToolReferenceBlockParam.builder().toolName(toolName).build()));
            if (log.isDebugEnabled()) {
                log.debug("tool_reference 块序列化进 tool_result.content：tool_name={}", toolName);
            }
        } else {
            // 未知块类型：显式失败（fail-loud），不静默丢弃
            log.warn("appendToolResultContentBlock: 未知块类型 {}，跳过该块（fail-loud，不静默丢弃）", btype);
        }
    }

    /** OpenAI {type:function,function:{name,description,parameters}} → SDK ToolUnion（迁移自 buildRequestBody tools 转换）。
     *  @param strictModelGate [G4] 模型层门控结果（flag && model != null && firstParty && 白名单，
     *     由 buildMessageParams 循环计算 · CC api.ts:185-192 模型层）。 */
    private static ToolUnion toSdkTool(JsonNode tool, boolean strictModelGate) {
        if (tool == null || !tool.isObject()) return null;
        if (!tool.has("type") || !"function".equals(tool.get("type").asText())) return null;
        JsonNode fn = tool.get("function");
        if (fn == null || !fn.isObject()) return null;
        Tool.Builder tb = Tool.builder();
        if (fn.has("name")) tb.name(fn.get("name").asText());
        if (fn.has("description")) {
            tb.description(fn.get("description").asText());
        } else {
            tb.description("");
        }
        Tool.InputSchema.Builder isb = Tool.InputSchema.builder();
        JsonNode params = fn.get("parameters");
        if (params != null && params.isObject()) {
            // input_schema = parameters（type/properties/required 全字段透传）
            params.fields().forEachRemaining(en ->
                isb.putAdditionalProperty(en.getKey(), JsonValue.fromJsonNode(en.getValue())));
        }
        tb.inputSchema(isb.build());
        // [G4] strict 透传：JSON strict:true（意图层 flag && tool.strict()）且模型层门控通过 → SDK .strict(true)。
        //   门控失败静默降级不传（防 Bedrock/Vertex 400 · CC api.ts:219 per-request strict）。
        boolean strictMarked = fn.has("strict") && fn.get("strict").isBoolean() && fn.get("strict").asBoolean(false);
        if (strictMarked && strictModelGate) {
            tb.strict(true);
            if (log.isDebugEnabled()) {
                log.debug("AnthropicSdkProvider.toSdkTool: tool '{}' 透传 strict=true（模型层门控通过）",
                    fn.get("name").asText());
            }
        }
        // [H4] defer_loading 消费 · 对齐 CC api.ts:223-225（schema.defer_loading=true）·
        //   SDK Tool.Builder.deferLoading(boolean) 存在（javap 自验 anthropic-java 2.53.0
        //   com.anthropic.models.messages.Tool$Builder）。wrapper 顶层字段（与 type/function 同层）。
        JsonNode deferLoading = tool.get("defer_loading");
        if (deferLoading != null && deferLoading.isBoolean() && deferLoading.asBoolean(false)) {
            tb.deferLoading(true);
            if (log.isDebugEnabled()) {
                log.debug("AnthropicSdkProvider.toSdkTool: tool '{}' defer_loading=true 透传 SDK（CC api.ts:223-225）",
                    fn.get("name").asText());
            }
        }
        return ToolUnion.ofTool(tb.build());
    }

    /**
     * tools ArrayNode 是否含 tool-search 相关工具 · [H4] beta header 派生判定
     * （CC claude.ts:1174-1177 等价语义）· 任一 wrapper 顶层 {@code defer_loading:true}
     * 或 function.name == ToolSearch → true。
     *
     * @param tools OpenAI 风格 tools 数组（wrapper 顶层 = type/function/defer_loading）
     * @return true = 需推送 TOOL_SEARCH_BETA_HEADER_1P
     */
    private static boolean toolsContainToolSearch(ArrayNode tools) {
        if (tools == null) {
            return false;
        }
        for (JsonNode tool : tools) {
            JsonNode defer = tool.get("defer_loading");
            if (defer != null && defer.isBoolean() && defer.asBoolean(false)) {
                return true;
            }
            JsonNode fn = tool.get("function");
            if (fn != null && fn.isObject() && fn.has("name")
                && ToolNameConstants.TOOL_SEARCH_TOOL_NAME.equals(fn.get("name").asText())) {
                return true;
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    // 静态 max-token 工具 · 迁移自旧 AnthropicProvider（K1-K4，该类已删除）
    // ════════════════════════════════════════════════════════════════════

    private static boolean modelSupportsEffortLocal(String model) {
        return EffortSupport.modelSupportsEffort(model);
    }

    /** 模型族 max output tokens 解析结果 · CC original: getModelMaxOutputTokens 返回值 {default, upperLimit} (context.ts:149-210)。 */
    private record ModelMaxOutputTokens(int defaultTokens, int upperLimit) {}

    /**
     * 模型族 default / upperLimit 解析（含 cap 覆盖）· 对齐 CC {@code utils/context.ts:149-210
     * getModelMaxOutputTokens}。
     *
     * <p>[G-17] cap 来源 = DB {@code models.max_tokens}（前端可配，经 G-18
     * {@link #bridgeDbMappersToStatic()} 静态桥接的 mapper）· 对齐 CC {@code context.ts:203-207}：
     * {@code cap.max_tokens ≥ 4096 → upperLimit = cap.max_tokens，default = min(default, upperLimit)}
     * （upperLimit 可被 DB 抬升，env override 可命中更高上限）。mapper 未接线（null）→ 纯家族表
     * （等价 CC {@code getModelCapability} 未命中分支）。
     *
     * <p>省略 ant 分支（{@code USER_TYPE==='ant'}）。
     *
     * @param model 模型名（null → 默认 32k/64k）
     */
    static ModelMaxOutputTokens getModelMaxOutputTokens(String model) {
        return getModelMaxOutputTokens(model, staticModelMapper, staticProviderMapper);
    }

    /**
     * 模型族 default / upperLimit 解析（显式 mapper 变体）· cap 覆盖同上（G-17，CC context.ts:203-207）。
     *
     * @param model          模型名（null → 默认 32k/64k）
     * @param modelMapper    模型 mapper（null → 无 cap 覆盖，纯家族表）
     * @param providerMapper 提供商 mapper（null → resolveMaxTokens 走按 name 兼容路径）
     */
    static ModelMaxOutputTokens getModelMaxOutputTokens(String model, ModelMapper modelMapper, ProviderMapper providerMapper) {
        ModelMaxOutputTokens base = familyTableMaxOutputTokens(model);
        // CC context.ts:203-207 — cap.max_tokens ≥ 4096 → upperLimit = cap.max_tokens，default = min(default, upperLimit)
        Integer cap = ModelNameResolver.resolveMaxTokens(modelMapper, providerMapper, model);
        if (cap != null && cap >= MAX_OUTPUT_TOKENS_CAP_MIN) {
            int upperLimit = cap;
            int defaultTokens = Math.min(base.defaultTokens(), upperLimit);
            if (log.isDebugEnabled()) {
                log.debug("[AnthropicSdkProvider] max_tokens 能力覆盖生效: model={} cap={} default={}→{} upperLimit={}→{} · CC context.ts:203-207",
                    model, cap, base.defaultTokens(), defaultTokens, base.upperLimit(), upperLimit);
            }
            return new ModelMaxOutputTokens(defaultTokens, upperLimit);
        }
        return base;
    }

    /** 家族表核心（CC context.ts:156-201 模型族 default/upperLimit 表，不含 context.ts:203-207 cap 覆盖）。 */
    private static ModelMaxOutputTokens familyTableMaxOutputTokens(String model) {
        if (model == null) {
            return new ModelMaxOutputTokens(MAX_OUTPUT_TOKENS_DEFAULT, MAX_OUTPUT_TOKENS_UPPER_LIMIT);
        }
        String m = model.toLowerCase();
        if (m.contains("opus-4-6")) {
            return new ModelMaxOutputTokens(64_000, 128_000);
        } else if (m.contains("sonnet-4-6")) {
            return new ModelMaxOutputTokens(32_000, 128_000);
        } else if (m.contains("opus-4-5") || m.contains("sonnet-4") || m.contains("haiku-4")) {
            return new ModelMaxOutputTokens(32_000, 64_000);
        } else if (m.contains("opus-4-1") || m.contains("opus-4")) {
            return new ModelMaxOutputTokens(32_000, 32_000);
        } else if (m.contains("claude-3-opus")) {
            return new ModelMaxOutputTokens(4_096, 4_096);
        } else if (m.contains("claude-3-sonnet")) {
            return new ModelMaxOutputTokens(8_192, 8_192);
        } else if (m.contains("claude-3-haiku")) {
            return new ModelMaxOutputTokens(4_096, 4_096);
        } else if (m.contains("3-5-sonnet") || m.contains("3-5-haiku")) {
            return new ModelMaxOutputTokens(8_192, 8_192);
        } else if (m.contains("3-7-sonnet")) {
            return new ModelMaxOutputTokens(32_000, 64_000);
        }
        return new ModelMaxOutputTokens(MAX_OUTPUT_TOKENS_DEFAULT, MAX_OUTPUT_TOKENS_UPPER_LIMIT);
    }

    /**
     * [IMP-15] max_tokens 按模型解析 · 对齐 CC {@code services/api/claude.ts:3399-3419 getMaxOutputTokensForModel}。
     *
     * <p>D-29 删除硬编码默认 max_tokens 的替代实现：
     * <ol>
     *   <li>{@link #getModelMaxOutputTokens(String)} 取模型族 default / upperLimit（[G-17] 含 DB cap 覆盖）</li>
     *   <li>{@code tengu_otk_slot_v1} gate 开启时 default 被 cap 到
     *       {@code CAPPED_DEFAULT_MAX_TOKENS=8000}（CC claude.ts:3408-3410）</li>
     *   <li>{@code settings.maxOutputTokens} 有界 override 生效（CC envValidation.ts:9-38，迁移自
     *       {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} env）</li>
     * </ol>
     *
     * <p>[G-17] 经 G-18 {@link #bridgeDbMappersToStatic()} 静态桥接的 DB mapper 应用 cap 覆盖；
     * mapper 未接线 → 纯家族表（CC getModelCapability 未命中等价）。
     *
     * @param model 模型名
     * @return max_tokens 值（override 未显式传入时的请求体默认）
     */
    public static int getMaxOutputTokensForModel(String model) {
        return getMaxOutputTokensForModel(model, staticModelMapper, staticProviderMapper);
    }

    /**
     * [IMP-15] max_tokens 按模型解析（显式 mapper 变体）· 对齐 CC {@code services/api/claude.ts:3399-3419
     * getMaxOutputTokensForModel} + {@code utils/context.ts:203-207}（G-17 cap 覆盖）。
     *
     * <p>解析链：
     * <ol>
     *   <li>{@link #getModelMaxOutputTokens(String, ModelMapper, ProviderMapper)} 取模型族 default /
     *       upperLimit，并应用 DB {@code models.max_tokens ≥ 4096} 抬 upperLimit（G-17，CC context.ts:203-207）</li>
     *   <li>{@code tengu_otk_slot_v1} gate 开启时 default 被 cap 到
     *       {@code CAPPED_DEFAULT_MAX_TOKENS=8000}（CC claude.ts:3408-3410）</li>
     *   <li>{@code settings.maxOutputTokens} 有界 override 生效（CC envValidation.ts:9-38，迁移自
     *       {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} env；upperLimit 已含 DB cap → 可命中更高上限）</li>
     * </ol>
     *
     * @param model          模型名
     * @param modelMapper    模型 mapper（null → 纯家族表，无 cap）
     * @param providerMapper 提供商 mapper（null → resolveMaxTokens 走按 name 兼容路径）
     * @return max_tokens 值（override 未显式传入时的请求体默认）
     */
    public static int getMaxOutputTokensForModel(String model, ModelMapper modelMapper, ProviderMapper providerMapper) {
        ModelMaxOutputTokens maxOutputTokens = getModelMaxOutputTokens(model, modelMapper, providerMapper);
        int defaultTokens = isMaxTokensCapEnabled()
            ? Math.min(maxOutputTokens.defaultTokens(), CAPPED_DEFAULT_MAX_TOKENS)
            : maxOutputTokens.defaultTokens();
        Integer settingsMaxOutputTokens = readSettingsMaxOutputTokens();
        if (settingsMaxOutputTokens != null && log.isDebugEnabled()) {
            log.debug("[AnthropicSdkProvider] settings.maxOutputTokens 命中: value={} upperLimit={} default={}"
                    + "（>0 生效 / 超 upperLimit 封顶 / 非法回落 default）· 迁移自 CLAUDE_CODE_MAX_OUTPUT_TOKENS env · CC envValidation.ts:9-38",
                settingsMaxOutputTokens, maxOutputTokens.upperLimit(), defaultTokens);
        }
        return validateBoundedIntEnvVar(
            "settings.maxOutputTokens",
            settingsMaxOutputTokens,
            defaultTokens, maxOutputTokens.upperLimit());
    }

    /** [F1] settings.maxOutputTokens 单源读取（V27 列 · 迁移自 {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} env）·
     *  mapper 未接线（null）或行缺失 → null（等价 CC env 未设置分支）。public 供 MaxTokensHandler
     *  64k 升级 gate 复用（E4 发现 C：env 残留改 settings 单源）。 */
    public static Integer readSettingsMaxOutputTokens() {
        if (staticSettingsMapper == null) {
            return null;
        }
        SettingsRecord row = staticSettingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
        return row == null ? null : row.getMaxOutputTokens();
    }

    /**
     * [G-18] max_tokens 单源解析（DB 优先 → CC 家族表回落）· 请求体与压缩链同源。
     *
     * <p><b>WHY</b>: 修复前请求体（buildMessageParams max_tokens）纯家族表链
     * （{@link #getMaxOutputTokensForModel(String)}），压缩链（{@link
     * com.nexusai.application.agent.compact.CompactThresholdSystem#getMaxOutputTokensForModel}）
     * DB 优先 → 同模型两值（G-18 DB 优先取值分裂，CC claude.ts:1591-1594 与 autoCompact.ts:33-37
     * 本为同一函数）。本方法为唯一"DB 优先（models.max_tokens，前端可配）→ CC 家族表全链回落"
     * 实现：请求体 buildMessageParams 与 CompactThresholdSystem 均委托本方法，两处同源。
     *
     * @param modelMapper    DB 模型 mapper（null → DB 跳过，回落家族表；无 Spring 上下文静默回落）
     * @param providerMapper DB 提供商 mapper（null → 按 name 兼容路径，无全名拆分）
     * @param model          模型名（可 null → DB 跳过）
     * @return max_tokens 解析值（DB 命中值 / CC 家族表全链）
     */
    public static int resolveMaxOutputTokensForModel(ModelMapper modelMapper, ProviderMapper providerMapper, String model) {
        Integer dbMaxTokens = ModelNameResolver.resolveMaxTokens(modelMapper, providerMapper, model);
        if (dbMaxTokens != null) {
            // [E4-A] DB 命中仍套 settings.maxOutputTokens 有界 override（default=upperLimit=dbMaxTokens →
            //   settings 只能收窄 DB 值、不可超 DB）。对齐 CC claude.ts:3399-3419 "cap 抬上限 + env bound"
            //   组合语义：models.max_tokens（前端可配）承载 CC cap 角色，settings.maxOutputTokens
            //   （F1 迁移自 CLAUDE_CODE_MAX_OUTPUT_TOKENS env）承载 env 语义恒最后生效。修复前 DB 命中
            //   直返原值、settings override 静默失效（E4 发现 A / 复验报告三.A）。
            Integer settingsMaxOutputTokens = readSettingsMaxOutputTokens();
            int effective = validateBoundedIntEnvVar(
                "settings.maxOutputTokens", settingsMaxOutputTokens, dbMaxTokens, dbMaxTokens);
            if (log.isDebugEnabled()) {
                log.debug("[AnthropicSdkProvider] max_tokens DB 命中优先: model={} dbMaxTokens={} settingsOverride={} effective={}"
                        + "（models.max_tokens 前端可配 · settings 有界收窄 · G-18 单源）",
                    model, dbMaxTokens, settingsMaxOutputTokens, effective);
            }
            return effective;
        }
        if (log.isDebugEnabled()) {
            log.debug("[AnthropicSdkProvider] max_tokens DB 未命中/无效, 回落 CC 家族表: model={}",
                model);
        }
        return getMaxOutputTokensForModel(model);
    }

    /**
     * [G-18] 无显式 mapper 版本 · 请求体侧调用（static buildMessageParams）。
     *
     * <p>用 {@link #bridgeDbMappersToStatic()} @PostConstruct 静态持有的 DB mapper；未接线
     * （plain JUnit / 无 Spring 上下文）→ null → 回落 CC 家族表，与 CompactThresholdSystem
     * 未注入语义一致（单源：同一 models.max_tokens 列）。
     *
     * @param model 模型名
     * @return max_tokens 解析值
     */
    public static int resolveMaxOutputTokensForModel(String model) {
        return resolveMaxOutputTokensForModel(staticModelMapper, staticProviderMapper, model);
    }

    /**
     * [IMP-15] 64k 升级 gate · 对齐 CC {@code claude.ts:3394-3397 isMaxTokensCapEnabled}
     * （{@code tengu_otk_slot_v1} growthbook flag，3P 默认 false）。
     *
     * <p>Java 端以系统属性 {@code nexusai.feature.tengu-otk-slot-v1} 承载（默认 false，
     * 对齐 CC 3P 默认关闭）。
     *
     * @return true = 开启 slot-reservation cap（首次请求 default 被 cap 到 8000，截断后走 64k 升级）
     */
    public static boolean isMaxTokensCapEnabled() {
        return Boolean.parseBoolean(System.getProperty(TENGU_OTK_SLOT_V1_PROPERTY, "false"));
    }

    /**
     * 有界整数 env 解析 · 对齐 CC {@code utils/envValidation.ts:9-38 validateBoundedIntEnvVar}。
     *
     * <p>语义：env 缺失/空 → default；非数字/≤0 → default（invalid）；&gt; upperLimit → upperLimit
     * （capped）；否则原值。Java 端暴露为 package-private 便于测试直接驱动（Java 无法在测试中改 env）。
     *
     * @param name          env 名（仅日志语义）
     * @param value         env 值（null/空 = 未设置）
     * @param defaultValue  未设置/非法时的回落值
     * @param upperLimit    上限（超过封顶）
     * @return effective 值
     */
    static int validateBoundedIntEnvVar(String name, String value, int defaultValue, int upperLimit) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.debug("{} 非法值 \"{}\" (回落默认: {}) · CC envValidation.ts", name, value, defaultValue);
            return defaultValue;
        }
        if (parsed <= 0) {
            log.debug("{} 非法值 \"{}\" (回落默认: {}) · CC envValidation.ts", name, value, defaultValue);
            return defaultValue;
        }
        if (parsed > upperLimit) {
            log.debug("{} 超上限封顶: {} → {}", name, parsed, upperLimit);
            return upperLimit;
        }
        return parsed;
    }

    /**
     * [F1] 有界整数 settings 解析（Integer 变体）· 同 CC {@code utils/envValidation.ts:9-38
     * validateBoundedIntEnvVar} 语义：null → default；≤0 → default（invalid）；&gt; upperLimit →
     * upperLimit（capped）；否则原值。委托 String 变体（复用封顶/非法日志），name 仅日志语义
     * （settings.maxOutputTokens）。
     *
     * @param name         settings 名（仅日志语义）
     * @param value        settings 值（null = 未配置，回落默认）
     * @param defaultValue 未配置/非法时的回落值
     * @param upperLimit   上限（超过封顶）
     * @return effective 值
     */
    static int validateBoundedIntEnvVar(String name, Integer value, int defaultValue, int upperLimit) {
        if (value == null) {
            return defaultValue;
        }
        return validateBoundedIntEnvVar(name, String.valueOf(value), defaultValue, upperLimit);
    }

    // ════════════════════════════════════════════════════════════════════
    // OD-17 消费压缩后标记 + PromptCacheBreak（K5/K6 迁移）
    // ════════════════════════════════════════════════════════════════════

    private static void consumePostCompactionAtApiSuccess(List<ChatMessageDto> history) {
        String sessionId = resolveSessionId(history);
        if (sessionId == null) {
            sessionId = RequestContext.sessionId();
        }
        boolean isPostCompaction = PostCompactionState.consumePostCompaction(sessionId);
        if (isPostCompaction) {
            if (log.isInfoEnabled()) {
                log.info("[AnthropicSdkProvider] 压缩后首次 API 成功事件 isPostCompaction=true "
                        + "（sessionId={}）· 对齐 CC logging.ts:452/573",
                    sessionId);
            }
        }
    }

    private static String resolveSessionId(List<ChatMessageDto> history) {
        if (history != null) {
            for (ChatMessageDto m : history) {
                if (m != null && m.sessionId() != null && !m.sessionId().isBlank()) {
                    return m.sessionId();
                }
            }
        }
        return null;
    }

    private com.nexusai.application.agent.lsp.PromptCacheBreakDetection promptCacheBreakDetector() {
        com.nexusai.application.agent.lsp.PromptCacheBreakDetection d = promptCacheBreak;
        if (d == null) {
            synchronized (this) {
                if (promptCacheBreak == null) {
                    promptCacheBreak =
                        com.nexusai.application.agent.lsp.PromptCacheBreakDetection.gatedBy(featureFlags);
                }
                d = promptCacheBreak;
            }
        }
        return d;
    }

    private boolean promptCacheBreakEnabled() {
        return featureFlags != null && featureFlags.promptCacheBreakDetection();
    }

    private static com.nexusai.application.agent.lsp.PromptCacheBreakDetection.PromptStateSnapshot
            buildPromptStateSnapshot(List<SystemPromptBlock> systemPromptBlocks,
                                     ArrayNode tools,
                                     String querySource,
                                     String modelName,
                                     String effortValue) {
        java.util.List<java.util.Map<String, Object>> system = new java.util.ArrayList<>();
        if (systemPromptBlocks != null && !systemPromptBlocks.isEmpty()) {
            var blocks = SystemPromptBlocksBuilder.buildSystemPromptBlocks(
                systemPromptBlocks, PromptCaching.getPromptCachingEnabled(modelName));
            for (var b : blocks) {
                system.add(JSON.convertValue(b, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
            }
        }
        java.util.List<java.util.Map<String, Object>> toolSchemas = new java.util.ArrayList<>();
        if (tools != null) {
            for (var t : tools) {
                toolSchemas.add(JSON.convertValue(t, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
            }
        }
        return new com.nexusai.application.agent.lsp.PromptCacheBreakDetection.PromptStateSnapshot(
            system, toolSchemas, querySource, modelName, null, false, null,
            java.util.List.of(), false, false, false, effortValue, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部累积器（SDK 事件 → AssistantMessage 映射中间态 · 迁移自 AnthropicProvider）
    // ════════════════════════════════════════════════════════════════════

    static class StreamState {
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        int thinkingBlockIdx = -1;
        String finishReason = null;
        // [D-4] 流式 request_id 头（CC claude.ts:1834 streamRequestId=result.request_id · req_xxx 格式，
        //   非 message id msg_xxx）· 透传到 AssistantMessage.requestId 供 invokingRequestId 归因。
        String requestId = null;
        long inputTokens = 0L;          // DEC-04 · message_start usage.input_tokens
        long cacheReadInputTokens = 0L;
        long cacheCreationInputTokens = 0L;
        long outputTokens = 0L;         // message_delta usage.output_tokens 最终覆盖
        // [OD-01 provider 接线] 累计 cache_deleted_input_tokens（微压缩 baseline/边界 delta 用）·
        //   CC microCompact.ts:374 / query.ts:874-878 从 lastAsst.message.usage 读取；SDK Usage 无
        //   等价访问器 → 经 usage.getAdditionalProperties() 解析（message_start，input 侧字段）。
        long cacheDeletedInputTokens = 0L;
        // [R32-06] 嵌套字段 · 只从 message_start usage 解析 (CC claude.ts:2947-2963 updateUsage,
        //   service_tier 恒取 message_start 值不随 delta 覆盖; BetaMessageDeltaUsage 缺 cache_creation)
        AgentUsage.ServerToolUse serverToolUse = null;
        String serviceTier = null;
        AgentUsage.CacheCreation cacheCreation = null;
    }

    static class ToolCallAccumulator {
        int index;
        String id;
        String name;
        String args = "";

        ToolUseBlock toBlock() {
            JsonNode input;
            try {
                input = (args == null || args.isEmpty())
                    ? JSON.createObjectNode()
                    : JSON.readTree(args);
            } catch (Exception e) {
                // [_raw fail-loud 2026-09-04] arguments 非法 JSON → 记录（fail loud，见外层同款）
                log.warn("AnthropicSdkProvider.ToolCallAccumulator: tool_call arguments 非法 JSON"
                        + "（readTree 失败）name={} len={} err={} → _raw 兜底", name,
                    args == null ? 0 : args.length(), e.toString());
                var wrapper = JSON.createObjectNode();
                wrapper.put("_raw", args == null ? "" : args);
                input = wrapper;
            }
            return new ToolUseBlock(id, name, input);
        }

        boolean isComplete() {
            return id != null && !id.isEmpty()
                && name != null && !name.isEmpty();
        }
    }

    static AssistantMessage buildAssistantMessage(StreamState state) {
        List<ToolUseBlock> blocks = new ArrayList<>(state.toolCalls.size());
        for (ToolCallAccumulator acc : state.toolCalls.values()) {
            blocks.add(acc.toBlock());
        }
        String finishReason = state.finishReason == null ? "end_turn" : state.finishReason;
        // [ER-IMP-07] finishReason 归一化 · 对齐 CC claude.ts:2266-2292：
        //   max_tokens / model_context_window_exceeded → apiError='max_output_tokens'
        String apiError = ("max_tokens".equals(finishReason)
            || "model_context_window_exceeded".equals(finishReason))
            ? "max_output_tokens" : null;
        if (apiError != null && log.isDebugEnabled()) {
            log.debug("AnthropicSdkProvider finishReason 归一化: raw stop_reason={} → apiError={} · CC claude.ts:2266-2292",
                finishReason, apiError);
        }
        // [DEC-04] usage 全字段透传: input/output/cache_read/cache_creation + [R32-06] 嵌套 3 字段
        //   (server_tool_use/service_tier/cache_creation · CC agentToolUtils.ts:243-255)
        //   [OD-01 provider 接线] + 累计 cache_deleted_input_tokens（CC message.usage 顶层字段，
        //   microCompact.ts:374；非 agentToolResultSchema 7 子字段，经 AgentUsage 扩展承载）
        AgentUsage usage = new AgentUsage(
            state.inputTokens, state.outputTokens,
            state.cacheCreationInputTokens, state.cacheReadInputTokens,
            state.serverToolUse, state.serviceTier, state.cacheCreation,
            "", List.of(), "standard", state.cacheDeletedInputTokens);
        if (log.isDebugEnabled()) {
            log.debug("AnthropicSdkProvider 流式完成 usage: {} · CC finalizeAgentTool usage 透传 (agentToolUtils.ts:355)",
                usage);
        }
        if (log.isDebugEnabled()) {
            log.debug("AnthropicSdkProvider 流式产出 message requestId={} · CC AssistantMessage.requestId=streamRequestId(claude.ts:2201)",
                state.requestId);
        }
        return new AssistantMessage(
            state.content.toString(),
            finishReason,
            blocks,
            state.reasoning.toString(),
            apiError,
            usage,
            state.requestId
        );
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("baseUrl is empty");
        }
        String u = baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
