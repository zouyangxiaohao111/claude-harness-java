package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputImageContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * OpenAI <b>Responses 协议</b>（{@code POST /v1/responses}）provider · 官方 openai-java SDK 4.101.0。
 *
 * <p>与 {@link OpenAiSdkProvider}（Completions，{@code /v1/chat/completions}）并列，由
 * {@link LlmProviderFactory} 按 {@code providerType == "openai_response"} 分发。请求/响应形状差异
 * （设计文档 §3）：
 * <ul>
 *   <li>消息容器是 {@code input[]}（不是 {@code messages[]}），文本块类型 {@code input_text}</li>
 *   <li>system 走顶层 {@code instructions}（多 block 按空行 join，{@code cacheScope} 无对应物）</li>
 *   <li>工具定义<b>扁平</b>：{@code {type:"function", name, description, parameters, strict}}</li>
 *   <li>工具调用/结果都是<b>平铺 item</b>：{@code function_call} / {@code function_call_output}，
 *       {@code call_id} 必填</li>
 *   <li>流式是语义事件（{@code response.*}），不是 {@code choices[].delta}</li>
 * </ul>
 *
 * <p><b>本阶段范围</b>：文本 + 工具调用 + 流式 + 非流式。<b>图片</b>（用户消息 input_image /
 * 工具结果 output 数组）属后续 P2/P3，本类对 image 块只做 debug 留痕并跳过。
 *
 * <p><b>复用</b>：客户端构造与异常翻译复用 {@link OpenAiSdkProvider} 的 static 方法（同一
 * baseUrl/自定义 header/读空闲超时判据，不另写一套）；会话亲和走 {@link SessionIdResolver}；
 * 用量记账走 {@link LlmCallScope}。
 */
@Component
public class ResponsesSdkProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ResponsesSdkProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Responses 协议 provider 类型标识（{@code ProviderType.openai_response}）。 */
    public static final String PROVIDER_TYPE = "openai_response";

    /** 模型/提供商映射（可空：无 Spring 上下文时图片能力门控保守为 false）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelMapper modelMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProviderMapper providerMapper;

    /**
     * 401 自愈：强制刷新 OAuth 凭据（可空：无 Spring 上下文 / 单测时降级为「不重试」）。
     *
     * <p>infra → domain 的依赖在本仓已有先例；⛔ 不注入 ProviderMapper + CryptoUtil 自己解密 ——
     * 加密/刷新口径必须单一（见 {@code ProviderService#saveOAuthCredential}）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.domain.provider.ProviderService providerService;

    @Override
    public String type() {
        return PROVIDER_TYPE;
    }

    // ===================== 流式 =====================

    /**
     * 20 参流式入口（签名逐位对齐 {@link LlmProvider#stream} 抽象重载，⛔ 不得重排）。
     *
     * <p>取消语义与 {@link OpenAiSdkProvider} 同形：注册 abort listener → 置标志 → 消费循环在
     * 事件边界检查；取消以 {@link CancellationException} 交 onError（只交一次）。
     */
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
                       AbortController abortController,
                       Consumer<Throwable> onError,
                       Runnable onComplete,
                       Boolean skipCacheWrite,
                       LlmCallScope scope,
                       StreamIdleControl streamIdleControl) {
        // skipCacheWrite / taskBudget / querySource 在 Responses 协议无对应 wire 字段：
        // 缓存由 promptCacheKey(sessionId) 承担，故签名跟随、行为忽略。
        AtomicBoolean aborted = new AtomicBoolean(false);
        if (abortController != null) {
            abortController.onCancel(ac -> {
                if (aborted.compareAndSet(false, true)) {
                    onError.accept(new CancellationException(
                        ac.reason() != null ? ac.reason() : "stream aborted"));
                }
            });
        }
        doStream(config, modelName, systemPromptBlocks, history, tools, maxOutputTokensOverride,
            effortValue, onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
            aborted, onError, onComplete, scope, streamIdleControl,
            new AtomicBoolean(false));
    }

    /** 流式核心 · {@code responses().createStreaming} + 事件映射。 */
    private void doStream(ProviderConfig config,
                          String modelName,
                          List<SystemPromptBlock> systemPromptBlocks,
                          List<ChatMessageDto> history,
                          ArrayNode tools,
                          Integer maxOutputTokensOverride,
                          String effortValue,
                          Consumer<String> onChunk,
                          Consumer<AssistantMessage> onAssistantMessage,
                          Consumer<ToolUseBlock> onToolCallComplete,
                          Consumer<String> onReasoningChunk,
                          AtomicBoolean aborted,
                          Consumer<Throwable> onError,
                          Runnable onComplete,
                          LlmCallScope scope,
                          StreamIdleControl streamIdleControl,
                          AtomicBoolean authRetried) {
        if (config == null || !config.isUsable()) {
            LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
            onError.accept(new IllegalStateException(
                "ResponsesSdkProvider.stream 调用时 ProviderConfig 不可用"));
            return;
        }
        if (aborted.get()) {
            // 取消已在 stream() 入口经 onError 交付，这里静默退出（不重复回调）。
            return;
        }

        String sessionId = SessionIdResolver.resolve(history, null);
        String instructions = joinSystem(systemPromptBlocks);
        // 图片：detail 取 provider 配置（默认 auto）；能力门控与既有 provider 同源（模型多模态才发图）。
        String imageDetail = resolveImageDetail(config);
        boolean supportsImage = ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, modelName);
        List<ResponseInputItem> input = buildInput(history, imageDetail, supportsImage);
        List<Tool> sdkTools = buildTools(tools);
        String resolvedEffort = EffortSupport.resolveAppliedEffort(modelName, effortValue);

        // 本次尝试的累积状态（失败/中断出口也要能看到部分 usage）
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<String, StringBuilder> argBuffers = new LinkedHashMap<>();
        Map<String, PendingCall> pendingCalls = new LinkedHashMap<>();
        List<ToolUseBlock> toolCalls = new ArrayList<>();
        Set<String> emittedCallKeys = new HashSet<>();
        AtomicReference<Response> finalResponse = new AtomicReference<>();

        try {
            Long idleTimeoutMs = streamIdleControl != null && streamIdleControl.idleTimeoutMs() > 0
                ? streamIdleControl.idleTimeoutMs() : null;
            OpenAIClient client = OpenAiSdkProvider.buildClient(config, sessionId, idleTimeoutMs);
            ResponseCreateParams params = buildParams(modelName, instructions, input, sdkTools,
                maxOutputTokensOverride, sessionId, resolvedEffort);
            if (log.isDebugEnabled()) {
                log.debug("ResponsesSdkProvider stream -> model={} inputItems={} tools={} effort={} session={}",
                    modelName, input.size(), sdkTools.size(), resolvedEffort, sessionId);
            }

            // [流空闲看门狗 · 降级非流式] forceNonStreaming=true → 跳过流式直接走非流式发送，
            // 事件语义对齐流式：整段文本作为一次 onChunk 交付。
            if (streamIdleControl != null && streamIdleControl.forceNonStreaming()) {
                Response response = client.responses().create(params);
                emitWholeResponse(response, onChunk, onReasoningChunk, onToolCallComplete, toolCalls);
                terminate(response, toolCalls, content, reasoning, scope,
                    onAssistantMessage, onComplete);
                return;
            }

            String streamFailure = null;
            try (StreamResponse<ResponseStreamEvent> stream =
                     client.responses().createStreaming(params, RequestOptions.none())) {
                Iterator<ResponseStreamEvent> it = stream.stream().iterator();
                while (it.hasNext()) {
                    if (aborted.get()) {
                        return;   // 取消已交付，不重复 onError / onComplete
                    }
                    ResponseStreamEvent ev = it.next();
                    streamFailure = handleEvent(ev, content, reasoning, argBuffers, pendingCalls,
                        toolCalls, emittedCallKeys, finalResponse, onChunk, onReasoningChunk,
                        onToolCallComplete);
                    if (streamFailure != null) {
                        break;
                    }
                }
            }
            if (streamFailure != null) {
                LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
                onError.accept(new IllegalStateException(streamFailure));
                return;
            }
            if (finalResponse.get() == null) {
                LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
                onError.accept(new IllegalStateException(
                    "ResponsesSdkProvider: 流在 response.completed 之前结束（无最终响应）"));
                return;
            }
            terminate(finalResponse.get(), toolCalls, content, reasoning, scope,
                onAssistantMessage, onComplete);
        } catch (Throwable e) {
            if (aborted.get()) {
                return;   // 用户取消：onError 已由 abort listener 交付
            }
            // [C2e-2 401 自愈] access token 被上游判失效 ⇒ 强制刷新一次并用新 token 原样重试。
            if (isAuthError(e)) {
                String refreshedAccess = authRetried.compareAndSet(false, true)
                    ? forceRefreshAccess(config.apiKey()) : null;
                if (refreshedAccess != null) {
                    log.info("ResponsesSdkProvider: access token 已失效，刷新后重试一次 model={}", modelName);
                    // 只换 apiKey：baseUrl / extraHeaders / extraBody 必须与首次尝试完全一致。
                    ProviderConfig retried = new ProviderConfig(
                        config.baseUrl(), refreshedAccess, config.extraHeaders(), config.extraBody());
                    doStream(retried, modelName, systemPromptBlocks, history, tools,
                        maxOutputTokensOverride, effortValue, onChunk, onAssistantMessage,
                        onToolCallComplete, onReasoningChunk, aborted, onError, onComplete,
                        scope, streamIdleControl, authRetried);
                    return;
                }
                // 拿不到新 token（或已经重试过）⇒ 可读错误，不再二次刷新。
                LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
                onError.accept(withReloginHint(OpenAiSdkProvider.translateSdkError(e)));
                return;
            }
            LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
            onError.accept(OpenAiSdkProvider.translateSdkError(e));
        }
    }

    /**
     * 单事件映射。返回非 null = 该事件是终止性失败（调用方停止消费并走 onError）。
     *
     * <pre>
     * response.output_text.delta                -> onChunk
     * response.reasoning_summary_text.delta     -> onReasoningChunk
     * response.output_item.added                -> 记 function_call 的 name / call_id
     * response.function_call_arguments.delta    -> 累积参数
     * response.function_call_arguments.done     -> ToolUseBlock -> onToolCallComplete
     * response.output_item.done                 -> 兜底收尾（arguments.done 缺失时补发工具块）
     * response.completed / response.incomplete  -> 记最终响应（usage 来源）
     * response.failed / error                   -> 返回失败描述
     * 其余                                      -> debug 忽略
     * </pre>
     */
    private String handleEvent(ResponseStreamEvent ev,
                               StringBuilder content,
                               StringBuilder reasoning,
                               Map<String, StringBuilder> argBuffers,
                               Map<String, PendingCall> pendingCalls,
                               List<ToolUseBlock> toolCalls,
                               Set<String> emittedCallKeys,
                               AtomicReference<Response> finalResponse,
                               Consumer<String> onChunk,
                               Consumer<String> onReasoningChunk,
                               Consumer<ToolUseBlock> onToolCallComplete) {
        if (ev.isOutputTextDelta()) {
            String delta = ev.asOutputTextDelta().delta();
            content.append(delta);
            onChunk.accept(delta);
            return null;
        }
        if (ev.isReasoningSummaryTextDelta()) {
            String delta = ev.asReasoningSummaryTextDelta().delta();
            reasoning.append(delta);
            onReasoningChunk.accept(delta);
            return null;
        }
        if (ev.isOutputItemAdded()) {
            ResponseOutputItem item = ev.asOutputItemAdded().item();
            if (item.isFunctionCall()) {
                ResponseFunctionToolCall fc = item.asFunctionCall();
                pendingCalls.put(callKey(fc), new PendingCall(fc.callId(), fc.name()));
            }
            return null;
        }
        if (ev.isFunctionCallArgumentsDelta()) {
            var e = ev.asFunctionCallArgumentsDelta();
            argBuffers.computeIfAbsent(e.itemId(), k -> new StringBuilder()).append(e.delta());
            return null;
        }
        if (ev.isFunctionCallArgumentsDone()) {
            var e = ev.asFunctionCallArgumentsDone();
            emitToolCall(e.itemId(), e.arguments(), pendingCalls, argBuffers, toolCalls,
                emittedCallKeys, onToolCallComplete);
            return null;
        }
        if (ev.isOutputItemDone()) {
            ResponseOutputItem item = ev.asOutputItemDone().item();
            if (item.isFunctionCall()) {
                ResponseFunctionToolCall fc = item.asFunctionCall();
                pendingCalls.putIfAbsent(callKey(fc), new PendingCall(fc.callId(), fc.name()));
                // 兜底：部分兼容端点不发 function_call_arguments.done，只在 output_item.done 给全量参数
                emitToolCall(callKey(fc), fc.arguments(), pendingCalls, argBuffers, toolCalls,
                    emittedCallKeys, onToolCallComplete);
            }
            return null;
        }
        if (ev.isCompleted()) {
            finalResponse.set(ev.asCompleted().response());
            return null;
        }
        if (ev.isIncomplete()) {
            finalResponse.set(ev.asIncomplete().response());
            return null;
        }
        if (ev.isFailed()) {
            return "Responses API response.failed: " + describeError(ev.asFailed().response());
        }
        if (ev.isError()) {
            return "Responses API error event: " + ev.asError().message();
        }
        if (ev.isCreated()) {
            // 只留痕：终态与 usage 一律从 completed / incomplete 取（created 里的 status 仍是 in_progress）
            if (log.isDebugEnabled()) {
                log.debug("ResponsesSdkProvider response.created id={}",
                    ev.asCreated().response().id());
            }
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("ResponsesSdkProvider 忽略未映射流事件: {}", ev);
        }
        return null;
    }

    /** 收尾（成功路径）：usage 记账 -> onAssistantMessage -> onComplete。 */
    private void terminate(Response response,
                           List<ToolUseBlock> toolCalls,
                           StringBuilder content,
                           StringBuilder reasoning,
                           LlmCallScope scope,
                           Consumer<AssistantMessage> onAssistantMessage,
                           Runnable onComplete) {
        AgentUsage usage = toAgentUsage(response != null ? response.usage().orElse(null) : null);
        LlmCallScope.recordSettled(scope, usage);

        String finishReason = "stop";
        String apiError = null;
        if (response != null) {
            var incomplete = response.incompleteDetails();
            if (incomplete.isPresent()) {
                var reason = incomplete.get().reason();
                if (reason.isPresent() && reason.get() ==
                    Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS) {
                    finishReason = "length";
                    apiError = "max_output_tokens";
                } else {
                    finishReason = "incomplete";
                }
            }
        }
        AssistantMessage msg = new AssistantMessage(
            content.toString(),
            finishReason,
            toolCalls,
            reasoning.toString(),
            apiError,
            usage,
            response != null ? response.id() : null,
            null);
        onAssistantMessage.accept(msg);
        onComplete.run();
    }

    /** 非流式响应 → 与流式同形的回调序列（整段文本一次 onChunk）。 */
    private void emitWholeResponse(Response response,
                                   Consumer<String> onChunk,
                                   Consumer<String> onReasoningChunk,
                                   Consumer<ToolUseBlock> onToolCallComplete,
                                   List<ToolUseBlock> outToolCalls) {
        if (response == null) {
            return;
        }
        String text = extractText(response);
        if (!text.isEmpty()) {
            onChunk.accept(text);
        }
        String reasoningText = extractReasoning(response);
        if (!reasoningText.isEmpty()) {
            onReasoningChunk.accept(reasoningText);
        }
        if (response.output() == null) {
            return;
        }
        for (ResponseOutputItem item : response.output()) {
            if (item == null || !item.isFunctionCall()) {
                continue;
            }
            ResponseFunctionToolCall fc = item.asFunctionCall();
            if (fc.callId() == null || fc.callId().isBlank()
                || fc.name() == null || fc.name().isBlank()) {
                continue;
            }
            ToolUseBlock block = new ToolUseBlock(fc.callId(), fc.name(),
                parseArguments(fc.arguments()));
            outToolCalls.add(block);
            onToolCallComplete.accept(block);
        }
    }

    // ===================== 非流式 =====================

    /**
     * 非流式 {@code responses().create()} · 返回 output 中的文本；无文本返回空串。
     *
     * <p>遗留便捷入口（标题生成等）；带 usage 的统一入口是 {@link LlmProvider#invoke}，
     * 默认实现经 {@code chatWithOptionsMessage} 委托，本阶段未覆写（非流式 usage 由
     * {@link #emitWholeResponse} 路径在流式回退通道内采集）。
     */
    @Override
    public String chat(ProviderConfig config, String modelName, String systemPrompt, String userMessage) {
        if (config == null || !config.isUsable()) {
            throw new IllegalStateException("ResponsesSdkProvider.chat 调用时 ProviderConfig 不可用");
        }
        ProviderConfig attempt = config;
        boolean authRetried = false;
        while (true) {
            try {
                Response response = createOnce(attempt, modelName, systemPrompt, userMessage);
                String text = extractText(response);
                if (log.isDebugEnabled()) {
                    log.debug("ResponsesSdkProvider chat -> model={} outputItems={} textLen={}",
                        modelName, response.output() == null ? 0 : response.output().size(), text.length());
                }
                return text;
            } catch (RuntimeException e) {
                // [C2e-2 401 自愈] 同流式：只允许刷新重试一次，第二次认证失败直接交出可读错误。
                if (isAuthError(e)) {
                    String refreshedAccess = authRetried ? null : forceRefreshAccess(attempt.apiKey());
                    authRetried = true;
                    if (refreshedAccess != null) {
                        log.info("ResponsesSdkProvider chat: access token 已失效，刷新后重试一次 model={}",
                            modelName);
                        attempt = new ProviderConfig(attempt.baseUrl(), refreshedAccess,
                            attempt.extraHeaders(), attempt.extraBody());
                        continue;
                    }
                    throw withReloginHint(OpenAiSdkProvider.translateSdkError(e));
                }
                throw e;
            }
        }
    }

    /** 非流式单次发送（chat 的重试循环复用；⛔ 不做凭据处理）。 */
    private Response createOnce(ProviderConfig config, String modelName,
                                String systemPrompt, String userMessage) {
        OpenAIClient client = OpenAiSdkProvider.buildClient(config, null);
        List<ResponseInputItem> input = new ArrayList<>();
        input.add(buildUserItem(null, userMessage, resolveImageDetail(config),
            ModelCapabilityResolver.supportsImage(modelMapper, providerMapper, modelName)));
        ResponseCreateParams params = buildParams(modelName, systemPrompt, input, List.of(), null, null, null);
        return client.responses().create(params);
    }

    // ===================== 401 自愈 =====================

    /**
     * 认证类失败判定（401 自愈的唯一判据）。
     *
     * <p>沿 cause 链找可读状态：401 一律算；403 只有 body 明确说 token 被撤销时才算
     * （{@code token has been revoked}）—— 其余 403 是权限问题，重新登录无用。
     * 入参可能是 {@link LlmApiException}（翻译后）或 SDK 原生
     * {@code OpenAIServiceException}（doStream 未翻译前），也可能被包在别的异常里。
     */
    static boolean isAuthError(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 16; depth++) {
            if (cur instanceof LlmApiException api) {
                if (api.status() == 401) return true;
                if (api.status() == 403 && isRevokedBody(api.body())) return true;
            } else if (cur instanceof com.openai.errors.OpenAIServiceException se) {
                int status = se.statusCode();
                if (status == 401) return true;
                if (status == 403 && isRevokedBody(serviceBodyText(se))) return true;
            }
            Throwable next = cur.getCause();
            if (next == cur) break;
            cur = next;
        }
        return false;
    }

    private static boolean isRevokedBody(String body) {
        return body != null && body.toLowerCase(java.util.Locale.ROOT).contains("token has been revoked");
    }

    /** {@code OpenAIServiceException.body()} → 文本（JsonValue 是字符串就直取，否则退回 toString）。 */
    private static String serviceBodyText(com.openai.errors.OpenAIServiceException se) {
        try {
            JsonValue body = se.body();
            if (body == null || body.isNull() || body.isMissing()) {
                return "";
            }
            try {
                return body.asStringOrThrow();
            } catch (Exception ignore) {
                return String.valueOf(body);
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 认证失效的可读错误：保留原 HTTP 状态，消息追加「重新登录」提示。
     *
     * <p>{@link LlmApiException} 的 message 由 body 截断构造（300 字符），故提示追加到 body 尾部
     * 再重建异常，而不是另起一层包装（保住 status 让上游重试/分类逻辑继续可用）。
     */
    static RuntimeException withReloginHint(RuntimeException translated) {
        String hint = "（登录已失效，请重新登录 ChatGPT 订阅）";
        if (translated instanceof LlmApiException api) {
            if (api.body() != null && api.body().contains("登录已失效")) {
                return api;
            }
            return new LlmApiException(api.status(), api.headers(),
                (api.body() == null ? "" : api.body() + "\n") + hint, api.kind());
        }
        String msg = translated.getMessage() == null ? "" : translated.getMessage();
        return new IllegalStateException(msg + hint, translated);
    }

    /** 反查并强制刷新 OAuth 凭据；未接线 / 查找失败 / 刷新失败 → null（⛔ 不抛）。 */
    private String forceRefreshAccess(String currentAccess) {
        com.nexusai.domain.provider.ProviderService service = this.providerService;
        if (service == null || currentAccess == null || currentAccess.isBlank()) {
            return null;
        }
        try {
            return service.forceRefreshByAccessToken(currentAccess);
        } catch (Exception e) {
            log.warn("ResponsesSdkProvider: 401 后强制刷新 OAuth 凭据失败: {}", e.getMessage());
            return null;
        }
    }

    // ===================== 请求构造 =====================

    /**
     * 组装 {@link ResponseCreateParams}。{@code store(false)}：本仓自行持久化历史，
     * 不依赖上游 response 存储；{@code promptCacheKey=sessionId} 承担会话级缓存亲和。
     *
     * <p>⚠️ 4.101.0 的 {@code ResponseCreateParams.Builder} <b>没有 stream(...)</b> 访问器
     * （javap 实测）；流式标记由 {@code createStreaming} 通道自行注入，非流式走 {@code create}。
     *
     * @param reasoningEffort 已解析的 effort 档（{@code EffortSupport.resolveAppliedEffort} 结果）；
     *                        合法档由 {@link OpenAiSdkProvider#mapToOpenAiReasoningEffort} 映射后写入
     *                        {@code reasoning.effort}，null / {@code off} / 模型不支持 → 不发（走上游默认）
     */
    private ResponseCreateParams buildParams(String modelName,
                                             String instructions,
                                             List<ResponseInputItem> input,
                                             List<Tool> toolsList,
                                             Integer maxOutputTokens,
                                             String promptCacheKey,
                                             String reasoningEffort) {
        ResponseCreateParams.Builder b = ResponseCreateParams.builder()
            .model(modelName)
            .inputOfResponse(input == null ? List.of() : input)
            .store(false);
        if (instructions != null && !instructions.isBlank()) {
            b.instructions(instructions);
        }
        if (toolsList != null && !toolsList.isEmpty()) {
            b.tools(toolsList);
        }
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            b.maxOutputTokens(maxOutputTokens.longValue());
        }
        if (promptCacheKey != null && !promptCacheKey.isBlank()) {
            b.promptCacheKey(promptCacheKey);
        }
        // [C-31 ⊕] effort → Responses {@code reasoning.effort}（Completions 侧是 reasoning_effort）。
        //   判据与 {@link OpenAiSdkProvider} 完全同源：mapToOpenAiReasoningEffort（max→high、
        //   非法档→null）+ modelSupportsEffort，⛔ 不另写一套映射（本仓有「同一能力两套判据」前科）。
        //   「关思考」（effortValue='off'）在 Responses 协议无等价字段 → 映射为 null 即不发。
        Reasoning reasoning = reasoningOf(modelName, reasoningEffort);
        if (reasoning != null) {
            b.reasoning(reasoning);
            if (log.isDebugEnabled()) {
                log.debug("ResponsesSdkProvider 请求注入 reasoning.effort={} model={}",
                    reasoning.effort().map(Object::toString).orElse("?"), modelName);
            }
        }
        return b.build();
    }

    /**
     * effort 档 → Responses {@code reasoning} 对象（null = 不注入）。
     *
     * <p>判据单点复用 {@link OpenAiSdkProvider#mapToOpenAiReasoningEffort(String)}
     * + {@code EffortSupport.modelSupportsEffort}，与 Completions 侧逐字同源。
     *
     * @param modelName       模型名（能力门控）
     * @param reasoningEffort 已解析 effort（低/中/高/max/xhigh/off/null）
     * @return Reasoning 或 null；{@code off} / 空 / 模型不支持 → null（不发，走上游默认）
     */
    static Reasoning reasoningOf(String modelName, String reasoningEffort) {
        String openAiEffort = OpenAiSdkProvider.mapToOpenAiReasoningEffort(reasoningEffort);
        if (openAiEffort == null || !EffortSupport.modelSupportsEffort(modelName)) {
            return null;
        }
        return Reasoning.builder().effort(ReasoningEffort.of(openAiEffort)).build();
    }

    /** systemPromptBlocks → 顶层 instructions（多块按空行 join；cacheScope 无对应物 → 忽略）。 */
    static String joinSystem(List<SystemPromptBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (SystemPromptBlock block : blocks) {
            if (block == null || block.text() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(block.text());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 内部 history → {@code input[]}。
     *
     * <ul>
     *   <li>user → message item，content 数组（text → {@code input_text}；image 本阶段跳过）</li>
     *   <li>assistant → 文本 EasyInputMessage + 每个 toolCall 一个 {@code function_call} item</li>
     *   <li>tool → {@code function_call_output} item（{@code call_id} = {@code toolCallId}）</li>
     *   <li>system → 不出站（与 Completions 通道的 normalizeMessagesForAPI 语义一致）</li>
     * </ul>
     */
    static List<ResponseInputItem> buildInput(List<ChatMessageDto> history) {
        return buildInput(history, "auto", true);
    }

    /**
     * 带图片参数的 {@code input[]} 组装（{@code imageDetail} 与 {@code supportsImage} 由调用方按
     * provider 配置 / 模型能力算出）。
     *
     * <ul>
     *   <li>user → message item，content 数组（text → {@code input_text}；image → {@code input_image}）</li>
     *   <li>assistant → 文本 EasyInputMessage + 每个 toolCall 一个 {@code function_call} item</li>
     *   <li>tool → {@code function_call_output} item（纯文本用字符串；带图用 item 数组）</li>
     *   <li>system → 不出站（与 Completions 通道的 normalizeMessagesForAPI 语义一致）</li>
     * </ul>
     */
    static List<ResponseInputItem> buildInput(List<ChatMessageDto> history,
                                              String imageDetail,
                                              boolean supportsImage) {
        List<ResponseInputItem> items = new ArrayList<>();
        if (history == null) {
            return items;
        }
        for (ChatMessageDto m : history) {
            if (m == null || m.role() == null) {
                continue;
            }
            switch (m.role()) {
                case user -> items.add(buildUserItem(
                    m.contentBlocks(), m.content(), imageDetail, supportsImage));
                case assistant -> {
                    String text = m.content() == null ? "" : m.content();
                    if (!text.isEmpty()) {
                        items.add(ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder()
                                .role(EasyInputMessage.Role.ASSISTANT)
                                .content(text)
                                .build()));
                    }
                    if (m.toolCalls() != null) {
                        for (ToolCallDto tc : m.toolCalls()) {
                            if (tc == null || tc.id() == null || tc.id().isBlank()
                                || tc.name() == null || tc.name().isBlank()) {
                                continue;
                            }
                            items.add(ResponseInputItem.ofFunctionCall(
                                ResponseFunctionToolCall.builder()
                                    .callId(tc.id())
                                    .name(tc.name())
                                    .arguments(tc.arguments() == null ? "{}" : tc.arguments())
                                    .build()));
                        }
                    }
                }
                case tool -> {
                    if (m.toolCallId() == null || m.toolCallId().isBlank()) {
                        if (log.isDebugEnabled()) {
                            log.debug("ResponsesSdkProvider: 跳过缺少 toolCallId 的 tool 消息");
                        }
                        continue;
                    }
                    items.add(ResponseInputItem.ofFunctionCallOutput(
                        buildToolOutput(m, imageDetail, supportsImage)));
                }
                case system -> {
                    if (log.isDebugEnabled()) {
                        log.debug("ResponsesSdkProvider: 非 local_command system 消息出站过滤");
                    }
                }
            }
        }
        return items;
    }

    /**
     * user 消息 → message item（content 数组）。
     *
     * <p>本阶段仅 text 块：{@code {type:"text"}} → {@code input_text}；image 块<b>跳过</b>并
     * debug 留痕（图片是 P2/P3）。无 contentBlocks 时用 content 字符串构造单块。
     */
    static ResponseInputItem buildUserItem(List<?> contentBlocks, String text) {
        return buildUserItem(contentBlocks, text, "auto", true);
    }

    /**
     * user 消息 → message item（content 数组）。
     *
     * <p>块映射：{@code {type:"text"}} → {@code input_text}；{@code {type:"image"}} → {@code input_image}
     * （{@code image_url} 为<b>裸字符串</b>，base64 走 data URI；{@code detail} 必填）。
     * 模型不支持图片时 image 块跳过并 debug 留痕（与 Completions 的 supportsImage 门控同源）。
     */
    static ResponseInputItem buildUserItem(List<?> contentBlocks, String text,
                                           String imageDetail, boolean supportsImage) {
        List<ResponseInputContent> parts = new ArrayList<>();
        if (contentBlocks != null && !contentBlocks.isEmpty()) {
            for (Object blockObj : contentBlocks) {
                if (!(blockObj instanceof JsonNode block) || !block.isObject()) {
                    continue;
                }
                String btype = block.has("type") ? block.get("type").asText() : null;
                if ("text".equals(btype)) {
                    parts.add(ResponseInputContent.ofInputText(ResponseInputText.builder()
                        .text(block.has("text") ? block.get("text").asText() : "")
                        .build()));
                } else if ("image".equals(btype)) {
                    if (!supportsImage) {
                        if (log.isDebugEnabled()) {
                            log.debug("ResponsesSdkProvider role=user 跳过 image 块（模型不支持图片）");
                        }
                        continue;
                    }
                    String url = OpenAiSdkProvider.resolveImageUrl(block);
                    if (url == null) {
                        if (log.isDebugEnabled()) {
                            log.debug("ResponsesSdkProvider role=user 跳过无法解析的 image 块");
                        }
                        continue;
                    }
                    parts.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                        .detail(ResponseInputImage.Detail.of(imageDetail))
                        .imageUrl(url)
                        .build()));
                } else if (log.isDebugEnabled()) {
                    log.debug("ResponsesSdkProvider role=user 跳过 {} 块", btype);
                }
            }
        }
        if (parts.isEmpty()) {
            parts.add(ResponseInputContent.ofInputText(ResponseInputText.builder()
                .text(text == null ? "" : text)
                .build()));
        }
        return ResponseInputItem.ofMessage(ResponseInputItem.Message.builder()
            .role(ResponseInputItem.Message.Role.USER)
            .content(parts)
            .build());
    }

    /** 图片 detail 档位 · 读 provider extraBody 的约定键，白名单校验后回落 {@code auto}。 */
    static String resolveImageDetail(ProviderConfig config) {
        Object v = (config == null || config.extraBody() == null)
            ? null : config.extraBody().get("responses_image_detail");
        String s = v == null ? "auto" : String.valueOf(v);
        return switch (s) {
            case "low", "high", "auto", "original" -> s;
            default -> "auto";
        };
    }

    /**
     * tool 消息 → {@code function_call_output}。
     *
     * <p>三态：纯文本 → {@code output} 字符串；文本+图 → item 数组
     * （{@code ofResponseFunctionCallOutputItemList}）；只有图且模型不支持 → 字符串占位
     * （⛔ 空数组会 400）。图与 {@code call_id} 同属一个 item，归属不脱钩。
     */
    static ResponseInputItem.FunctionCallOutput buildToolOutput(ChatMessageDto m,
                                                               String imageDetail,
                                                               boolean supportsImage) {
        String text = m.content() == null ? "" : m.content();
        List<JsonNode> images = collectImageBlocks(m.contentBlocks());
        ResponseInputItem.FunctionCallOutput.Builder b =
            ResponseInputItem.FunctionCallOutput.builder().callId(m.toolCallId());

        if (images.isEmpty()) {
            return b.output(ResponseInputItem.FunctionCallOutput.Output.ofString(text)).build();
        }
        if (!supportsImage) {
            String placeholder = text.isBlank()
                ? "(see attached image)" : text + "\n(see attached image)";
            return b.output(ResponseInputItem.FunctionCallOutput.Output.ofString(placeholder)).build();
        }
        List<ResponseFunctionCallOutputItem> parts = new ArrayList<>();
        if (!text.isEmpty()) {
            parts.add(ResponseFunctionCallOutputItem.ofInputText(
                ResponseInputTextContent.builder().text(text).build()));
        }
        for (JsonNode img : images) {
            String url = OpenAiSdkProvider.resolveImageUrl(img);
            if (url == null) {
                continue;
            }
            parts.add(ResponseFunctionCallOutputItem.ofInputImage(
                ResponseInputImageContent.builder()
                    .imageUrl(url)
                    .detail(ResponseInputImageContent.Detail.of(imageDetail))
                    .build()));
        }
        if (parts.isEmpty()) {
            return b.output(ResponseInputItem.FunctionCallOutput.Output.ofString(text)).build();
        }
        return b.output(ResponseInputItem.FunctionCallOutput.Output
            .ofResponseFunctionCallOutputItemList(parts)).build();
    }

    /** 从 contentBlocks 收集 image 块（保序）。 */
    private static List<JsonNode> collectImageBlocks(List<?> contentBlocks) {
        List<JsonNode> out = new ArrayList<>();
        if (contentBlocks == null) {
            return out;
        }
        for (Object o : contentBlocks) {
            if (o instanceof JsonNode n && n.isObject() && "image".equals(n.path("type").asText())) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * 内部 tools（{@code {type:"function", function:{...}}}）→ 扁平 Responses 工具定义。
     *
     * <p>⛔ 不套 {@code function} 外层：{@code {type:"function", name, description, parameters, strict}}。
     * {@code parameters} 直接就是我们自己的 JSON Schema（逐键拷进 SDK 的 free-form 参数对象）。
     */
    static List<Tool> buildTools(ArrayNode tools) {
        List<Tool> out = new ArrayList<>();
        if (tools == null || tools.isEmpty()) {
            return out;
        }
        for (JsonNode toolNode : tools) {
            if (toolNode == null || !toolNode.isObject()) {
                continue;
            }
            JsonNode fn = toolNode.has("function") && toolNode.get("function").isObject()
                ? toolNode.get("function") : toolNode;
            String name = fn.path("name").asText("");
            if (name.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("ResponsesSdkProvider: 跳过缺少 name 的工具定义");
                }
                continue;
            }
            FunctionTool.Builder builder = FunctionTool.builder()
                .name(name)
                .strict(false);
            String description = fn.path("description").asText("");
            if (!description.isEmpty()) {
                builder.description(description);
            }
            JsonNode schema = fn.get("parameters");
            if (schema != null && schema.isObject()) {
                // [schema-pattern-guard 2026-11-16] OpenAI 服务端与 Anthropic/RE2 一样<b>不认</b>
                //   反斜杠加 u 四位十六进制的 Unicode 转义（线上实测：rea MCP 的
                //   「排除代理项区间 U+D800..U+DFFF」pattern ⇒ HTTP 200 但 body 报
                //   invalid_function_parameters ... is not a "regex" ⇒ 整轮工具不可用）。
                //   复用 Anthropic 侧的守卫：RE2 能编译就原样发，不能则改写/剔除。
                //   ⚠️ 早前 Anthropic 侧注释写「openai 侧不改」是错的——OpenAI 不是 JS 正则。
                schema = AnthropicSdkProvider.sanitizeAnthropicSchemaPatterns(schema, name);
                FunctionTool.Parameters.Builder pb = FunctionTool.Parameters.builder();
                Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> en = fields.next();
                    pb.putAdditionalProperty(en.getKey(), JsonValue.fromJsonNode(en.getValue()));
                }
                builder.parameters(pb.build());
            }
            out.add(Tool.ofFunction(builder.build()));
        }
        if (log.isDebugEnabled()) {
            log.debug("ResponsesSdkProvider: 已添加 {} 个扁平 function 工具", out.size());
        }
        return out;
    }

    // ===================== 响应解析 =====================

    /** 工具调用 item 的键：优先 item id（arguments 事件按 item_id 关联），否则 call_id。 */
    private static String callKey(ResponseFunctionToolCall fc) {
        return fc.id().orElse(fc.callId());
    }

    /**
     * 发一个工具块。同一 item 只发一次（arguments.done 与 output_item.done 双触发去重）；
     * 缺 name/call_id 的直接跳过（{@link ToolUseBlock} 拒绝空 id/name）。
     */
    private void emitToolCall(String itemId,
                              String argumentsJson,
                              Map<String, PendingCall> pendingCalls,
                              Map<String, StringBuilder> argBuffers,
                              List<ToolUseBlock> outToolCalls,
                              Set<String> emittedCallKeys,
                              Consumer<ToolUseBlock> onToolCallComplete) {
        if (itemId == null || emittedCallKeys.contains(itemId)) {
            return;
        }
        PendingCall meta = pendingCalls.get(itemId);
        if (meta == null || meta.callId() == null || meta.callId().isBlank()
            || meta.name() == null || meta.name().isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("ResponsesSdkProvider: 工具块缺少 name/call_id，跳过 item={}", itemId);
            }
            return;
        }
        String args = argumentsJson;
        if (args == null || args.isBlank()) {
            StringBuilder buffered = argBuffers.get(itemId);
            args = buffered == null ? "{}" : buffered.toString();
        }
        ToolUseBlock block = new ToolUseBlock(meta.callId(), meta.name(), parseArguments(args));
        emittedCallKeys.add(itemId);
        outToolCalls.add(block);
        onToolCallComplete.accept(block);
    }

    /** JSON 参数串 → JsonNode；坏 JSON 回落空对象（不让一个畸形工具块打断整条流）。 */
    static JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode parsed = JSON.readTree(argumentsJson);
            return parsed == null ? JSON.createObjectNode() : parsed;
        } catch (Exception e) {
            log.warn("ResponsesSdkProvider: 工具参数不是合法 JSON，回落空对象: {}", e.toString());
            return JSON.createObjectNode();
        }
    }

    /** 非流式响应中的全部 output_text。 */
    static String extractText(Response response) {
        StringBuilder sb = new StringBuilder();
        if (response == null || response.output() == null) {
            return "";
        }
        for (ResponseOutputItem item : response.output()) {
            if (item == null || !item.isMessage()) {
                continue;
            }
            ResponseOutputMessage msg = item.asMessage();
            for (ResponseOutputMessage.Content c : msg.content()) {
                if (c.isOutputText()) {
                    sb.append(c.asOutputText().text());
                }
            }
        }
        return sb.toString();
    }

    /** 非流式响应中的推理摘要（summary 优先，其次 content）。 */
    static String extractReasoning(Response response) {
        StringBuilder sb = new StringBuilder();
        if (response == null || response.output() == null) {
            return "";
        }
        for (ResponseOutputItem item : response.output()) {
            if (item == null || !item.isReasoning()) {
                continue;
            }
            var ri = item.asReasoning();
            for (var part : ri.summary()) {
                if (part.text() != null) {
                    sb.append(part.text());
                }
            }
            var content = ri.content();
            if (content.isPresent()) {
                for (var part : content.get()) {
                    if (part.text() != null) {
                        sb.append(part.text());
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Responses usage → 内部 {@link AgentUsage}（cachedTokens → cacheRead；无嵌套字段等价物 → null）。 */
    static AgentUsage toAgentUsage(com.openai.models.responses.ResponseUsage u) {
        if (u == null) {
            return AgentUsage.EMPTY;
        }
        Long cacheRead = u.inputTokensDetails() != null && u.inputTokensDetails().cachedTokens() > 0
            ? u.inputTokensDetails().cachedTokens() : null;
        return new AgentUsage(u.inputTokens(), u.outputTokens(), null, cacheRead, null, null, null);
    }

    /** response.failed 的人类可读描述。 */
    private static String describeError(Response response) {
        if (response == null) {
            return "no response";
        }
        return response.error().map(e -> e.message()).orElse("unknown error");
    }

    /** 流内待发工具调用（output_item.added 给出 name / call_id，参数由后续事件拼出）。 */
    private record PendingCall(String callId, String name) {
    }
}
