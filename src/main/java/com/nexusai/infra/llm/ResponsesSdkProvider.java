package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
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
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputImageContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputRefusal;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponsesClientEvent;
import com.openai.models.responses.ResponsesServerEvent;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceOptions;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** Responses over WebSocket 会话池（codex 端点专用）。 */
    private final ResponsesWsManager wsManager = new ResponsesWsManager();

    /** codex `DEFAULT_STREAM_MAX_RETRIES`（model-provider-info/src/lib.rs:74）。 */
    private static final int WS_STREAM_MAX_RETRIES = readWsMaxRetries();

    /**
     * [codex 对齐] 续接失效/连接上限的快速重试上限。
     *
     * <p>codex 对 {@code previous_response_not_found} 与 {@code websocket_connection_limit_reached}
     * 的处置是「作废续接 + 立即重发全量」（responses_websocket.rs:432-434），不占 stream 重试预算。
     * 这里用独立小预算防死循环（全量请求仍报同错 = 真故障，交由后续通用重试/降级）。
     */
    private static final int WS_CONTINUATION_MAX_RETRIES = 2;

    @jakarta.annotation.PreDestroy
    public void shutdownWs() {
        wsManager.closeAll();
    }

    private static int readWsMaxRetries() {
        String raw = System.getenv("NEXUSAI_RESPONSES_WS_MAX_RETRIES");
        if (raw != null && !raw.isBlank()) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (v >= 0) {
                    return Math.min(v, 100);   // codex MAX_STREAM_MAX_RETRIES
                }
            } catch (NumberFormatException ignore) {
                // 非法值 → 默认
            }
        }
        return 5;
    }

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
            aborted, onError, onComplete, scope, streamIdleControl, abortController,
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
                          AbortController abortController,
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

        // [responses-ws] codex 端点优先走 Responses over WebSocket（对齐 codex 0.162.1：
        //   同一会话一条连接 + previous_response_id 增量 + include reasoning.encrypted_content）。
        //   返回 true = 本次尝试已完整处理（含成功/失败回调）；false = 该会话已 sticky 切 HTTP。
        boolean forceNonStreaming = streamIdleControl != null && streamIdleControl.forceNonStreaming();
        if (!forceNonStreaming && isCodexEndpoint(config.baseUrl())) {
            String wsKey = wsKey(config, sessionId);
            if (!wsManager.isDisabled(wsKey)) {
                boolean handled = doStreamWs(config, modelName, wsKey, sessionId, instructions,
                    input, sdkTools, maxOutputTokensOverride, resolvedEffort,
                    onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
                    aborted, onError, onComplete, scope, streamIdleControl, abortController, authRetried);
                if (handled) {
                    return;
                }
            }
        }

        // 本次尝试的累积状态（失败/中断出口也要能看到部分 usage）
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<String, StringBuilder> argBuffers = new LinkedHashMap<>();
        Map<String, PendingCall> pendingCalls = new LinkedHashMap<>();
        List<ToolUseBlock> toolCalls = new ArrayList<>();
        Set<String> emittedCallKeys = new HashSet<>();
        Set<String> textDeltaItems = new HashSet<>();
        AtomicReference<Response> finalResponse = new AtomicReference<>();

        try {
            Long idleTimeoutMs = streamIdleControl != null && streamIdleControl.idleTimeoutMs() > 0
                ? streamIdleControl.idleTimeoutMs() : null;
            OpenAIClient client = OpenAiSdkProvider.buildClient(config, sessionId, idleTimeoutMs);
            ResponseCreateParams params = buildParams(modelName, instructions, input, sdkTools,
                maxOutputTokensOverride, sessionId, resolvedEffort, isCodexEndpoint(config.baseUrl()));
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
                        toolCalls, emittedCallKeys, textDeltaItems, finalResponse, onChunk, onReasoningChunk,
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
                        scope, streamIdleControl, abortController, authRetried);
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

    // ===================== WebSocket（codex 端点） =====================

    /** 是否 ChatGPT Codex 订阅端点（与 {@link OpenAiSdkProvider} 同判据）。 */
    private static boolean isCodexEndpoint(String baseUrl) {
        return baseUrl != null
            && baseUrl.toLowerCase(java.util.Locale.ROOT).contains("/backend-api/codex");
    }

    /** WS 会话键：端点 + 会话（OAuth 刷新换 token 不应换连接，故 apiKey 不参与）。 */
    private static String wsKey(ProviderConfig config, String sessionId) {
        String base = config == null || config.baseUrl() == null ? "" : config.baseUrl();
        return base + "|" + (sessionId == null ? "" : sessionId);
    }

    /**
     * Responses over WebSocket 主路径（对齐 codex 0.162.1）。
     *
     * <p>返回 true = 本次尝试已完整处理（成功终止或已回 onError）；false = 该会话应改用 HTTP SSE
     * （sticky 降级 / 握手不支持 / 传输重试耗尽）。
     */
    private boolean doStreamWs(ProviderConfig config,
                               String modelName,
                               String wsKey,
                               String sessionId,
                               String instructions,
                               List<ResponseInputItem> input,
                               List<Tool> sdkTools,
                               Integer maxOutputTokens,
                               String resolvedEffort,
                               Consumer<String> onChunk,
                               Consumer<AssistantMessage> onAssistantMessage,
                               Consumer<ToolUseBlock> onToolCallComplete,
                               Consumer<String> onReasoningChunk,
                               AtomicBoolean aborted,
                               Consumer<Throwable> onError,
                               Runnable onComplete,
                               LlmCallScope scope,
                               StreamIdleControl streamIdleControl,
                               AbortController abortController,
                               AtomicBoolean authRetried) {
        ResponsesWsManager.SessionState st = wsManager.state(wsKey);
        long idleMs = streamIdleControl != null ? streamIdleControl.idleTimeoutMs() : 0L;
        String fingerprint = requestFingerprint(modelName, instructions, sdkTools,
            maxOutputTokens, sessionId, resolvedEffort);
        List<String> fullDigests = digestInput(input);

        synchronized (st.lock) {
            // ── 增量判定（对齐 pi requestBodiesMatchExceptInput：除 input/previous_response_id 外全等） ──
            String previousResponseId = null;
            List<ResponseInputItem> toSend = input;
            if (st.session != null && !st.session.isClosed()
                && st.lastResponseId != null
                && !st.conversationDigests.isEmpty()
                && fingerprint.equals(st.fingerprint)
                && fullDigests.size() > st.conversationDigests.size()
                && fullDigests.subList(0, st.conversationDigests.size()).equals(st.conversationDigests)) {
                previousResponseId = st.lastResponseId;
                toSend = new ArrayList<>(input.subList(st.conversationDigests.size(), input.size()));
            }

            int transportRetries = 0;
            // [codex 对齐] 续接失效/连接上限的快速重试次数（不占传输重试预算）
            int continuationRetries = 0;
            while (true) {
                if (aborted.get()) {
                    return true;   // onError 已由 abort listener 交付
                }
                boolean emittedContent = false;
                try {
                    ResponsesWsSession session = st.session;
                    if (session == null || session.isClosed()) {
                        // 连接不可用 ⇒ 续接状态作废（previous_response_id 依附会话状态）
                        st.invalidateContinuation();
                        previousResponseId = null;
                        toSend = input;
                        session = ResponsesWsSession.connect(buildWsClient(config, sessionId),
                            ResponsesWsSession.CONNECT_TIMEOUT_MS);
                        st.session = session;
                        if (abortController != null) {
                            abortController.onCancel(ac -> st.closeSession());
                        }
                    }

                    ResponsesClientEvent.ResponseCreate request = buildWsRequest(modelName, instructions,
                        toSend, sdkTools, maxOutputTokens, sessionId, resolvedEffort, previousResponseId);
                    if (log.isDebugEnabled()) {
                        log.debug("[responses-ws] session={} model={} input={} send={} prev={} retry={}",
                            sessionId, modelName, input.size(), toSend.size(), previousResponseId, transportRetries);
                    }
                    session.send(request);

                    StringBuilder content = new StringBuilder();
                    StringBuilder reasoning = new StringBuilder();
                    Map<String, StringBuilder> argBuffers = new LinkedHashMap<>();
                    Map<String, PendingCall> pendingCalls = new LinkedHashMap<>();
                    List<ToolUseBlock> toolCalls = new ArrayList<>();
                    Set<String> emittedCallKeys = new HashSet<>();
                    Set<String> textDeltaItems = new HashSet<>();
                    AtomicReference<Response> finalResponse = new AtomicReference<>();

                    boolean sawEvent = false;
                    boolean sawOutput = false;
                    boolean sawReasoning = false;
                    boolean sawToolCall = false;

                    while (true) {
                        if (aborted.get()) {
                            return true;
                        }
                        ResponsesServerEvent ev;
                        try {
                            ev = session.next(idleMs);
                        } catch (TimeoutException te) {
                            st.closeSession();
                            throw new StreamIdleTimeoutError(
                                progressOf(sawEvent, sawOutput, sawToolCall, sawReasoning), false,
                                "responses websocket idle timeout " + idleMs + "ms");
                        }
                        if (ev == null) {
                            throw new IllegalStateException("responses websocket closed");
                        }
                        ResponsesWireSignal sig = ResponsesWireSignal.of(ev);
                        if (sig.kind == ResponsesWireSignal.Kind.IGNORED) {
                            continue;   // codex.rate_limits / codex.response.metadata / websocket_timing…
                        }
                        sawEvent = true;
                        if (sig.kind == ResponsesWireSignal.Kind.TEXT_DELTA
                            || sig.kind == ResponsesWireSignal.Kind.TEXT_DONE
                            || sig.kind == ResponsesWireSignal.Kind.REFUSAL_DELTA) {
                            sawOutput = true;
                        }
                        if (sig.kind == ResponsesWireSignal.Kind.REASONING_SUMMARY_DELTA
                            || sig.kind == ResponsesWireSignal.Kind.REASONING_TEXT_DELTA) {
                            sawReasoning = true;
                        }
                        if (sig.kind == ResponsesWireSignal.Kind.ARGS_DONE
                            || sig.kind == ResponsesWireSignal.Kind.ITEM_DONE) {
                            sawToolCall = true;
                        }
                        emittedContent = sawOutput || sawToolCall;
                        String failure = handleWsEvent(ev, content, reasoning, argBuffers, pendingCalls,
                            toolCalls, emittedCallKeys, textDeltaItems, finalResponse,
                            onChunk, onReasoningChunk, onToolCallComplete);
                        if (failure != null) {
                            // [codex 对齐] 续接失效/连接上限是**可恢复**的：codex 明确为它们
                            //   作废 previous_response_id 后重发（responses_websocket.rs:432-434
                            //   PREVIOUS_RESPONSE_NOT_FOUND_CODE / WEBSOCKET_CONNECTION_LIMIT_REACHED_CODE），
                            //   不能按终态失败处理。抛可重试异常交给下方 catch。
                            if (isRecoverableWsReconnect(failure)) {
                                throw new IllegalStateException("responses ws recoverable: " + failure);
                            }
                            // 其余服务端终态失败（response.failed / error 事件）：codex 归为 Never
                            //   （不重试、不切传输）——重放同一请求只会得到同样结果并重复计费。
                            throw new WsTerminalException(failure);
                        }
                        Response done = finalResponse.get();
                        if (done != null) {
                            // 成功终止：更新增量状态（会话内容摘要 = 本次全量 input ++ 本轮 output）
                            st.lastResponseId = done.id();
                            st.fingerprint = fingerprint;
                            st.conversationDigests = concat(fullDigests, digestSignatureItems(outputSignature(done)));
                            terminate(done, toolCalls, content, reasoning, scope,
                                onAssistantMessage, onComplete);
                            return true;
                        }
                    }
                } catch (StreamIdleTimeoutError ite) {
                    LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
                    onError.accept(ite);
                    return true;
                } catch (WsTerminalException terminal) {
                    LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
                    onError.accept(new IllegalStateException(terminal.getMessage()));
                    return true;
                } catch (Throwable e) {
                    if (aborted.get()) {
                        return true;
                    }
                    // 已见到输出 ⇒ 不重试、不降级（重放会重复计费/重复正文，对齐 codex websocket_fallback_close）
                    if (emittedContent) {
                        LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
                        onError.accept(OpenAiSdkProvider.translateSdkError(e));
                        return true;
                    }
                    // [codex 对齐] 续接失效（previous_response_not_found）/ 连接上限
                    //   （websocket_connection_limit_reached）：codex 的处置是作废续接、立即重发全量
                    //   （responses_websocket.rs:432-434）。不走背退避、不占传输重试预算。
                    if (isRecoverableWsReconnect(messagesOf(e))
                        && continuationRetries < WS_CONTINUATION_MAX_RETRIES) {
                        continuationRetries++;
                        log.info("[responses-ws] session={} 续接失效/连接上限，立即全量重连 {}/{}: {}",
                            sessionId, continuationRetries, WS_CONTINUATION_MAX_RETRIES, e.toString());
                        st.closeSession();
                        previousResponseId = null;
                        toSend = input;
                        continue;
                    }
                    // 426 Upgrade Required / 消息超限 ⇒ 立即 sticky 切 HTTP（codex client.rs:2435-2439 / ws:1267）
                    if (isUpgradeRequired(e) || isMessageTooLarge(e)) {                        log.info("[responses-ws] session={} 端点要求 HTTP 或请求超限，永久切 HTTP: {}",
                            sessionId, e.toString());
                        wsManager.disable(wsKey);
                        return false;
                    }
                    // 显式访问拒绝：终态，不重试、不切传输（codex RetryPolicy::Never）
                    if (isExplicitAccessDenial(e)) {
                        LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
                        onError.accept(OpenAiSdkProvider.translateSdkError(e));
                        return true;
                    }
                    // 401 自愈：刷新后同传输重试一次（codex handle_unauthorized）
                    if (isAuthError(e) && authRetried.compareAndSet(false, true)) {
                        String refreshed = forceRefreshAccess(config.apiKey());
                        if (refreshed != null) {
                            log.info("[responses-ws] access token 失效，刷新后重建连接重试 model={}", modelName);
                            ProviderConfig retried = new ProviderConfig(config.baseUrl(), refreshed,
                                config.extraHeaders(), config.extraBody());
                            st.closeSession();
                            return doStreamWs(retried, modelName, wsKey, sessionId, instructions, input,
                                sdkTools, maxOutputTokens, resolvedEffort, onChunk, onAssistantMessage,
                                onToolCallComplete, onReasoningChunk, aborted, onError, onComplete,
                                scope, streamIdleControl, abortController, authRetried);
                        }
                    }
                    // 其余传输/流错误：WS 内重试（codex stream_max_retries 默认 5），耗尽 ⇒ sticky HTTP
                    if (transportRetries < WS_STREAM_MAX_RETRIES) {
                        transportRetries++;
                        long delay = backoffMs(transportRetries);
                        log.warn("[responses-ws] 流失败，重连重试 {}/{}（{}ms）: {}",
                            transportRetries, WS_STREAM_MAX_RETRIES, delay, e.toString());
                        st.closeSession();
                        sleepQuietly(delay);
                        previousResponseId = null;
                        toSend = input;
                        continue;
                    }
                    log.warn("[responses-ws] 重试耗尽，永久切 HTTP: {}", e.toString());
                    wsManager.disable(wsKey);
                    return false;
                }
            }
        }
    }

    private static StreamIdleTimeoutError.Progress progressOf(boolean sawEvent, boolean sawOutput,
                                                              boolean sawToolCall, boolean sawReasoning) {
        if (!sawEvent) {
            return StreamIdleTimeoutError.Progress.NOTHING;
        }
        if (sawOutput) {
            return StreamIdleTimeoutError.Progress.PARTIAL_OUTPUT;
        }
        if (sawToolCall) {
            return StreamIdleTimeoutError.Progress.PARTIAL_OUTPUT;
        }
        if (sawReasoning) {
            return StreamIdleTimeoutError.Progress.THINKING_ONLY;
        }
        return StreamIdleTimeoutError.Progress.STARTED;
    }

    /** codex 握手/连接超时 15s；读空闲由 {@code StreamIdleControl} 独立负责（不设 okhttp readTimeout）。 */
    private static OpenAIClient buildWsClient(ProviderConfig config, String sessionId) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
            .apiKey(config.apiKey())
            .maxRetries(0);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(config.baseUrl());
        }
        java.net.Proxy outbound = OutboundProxy.httpProxyFor(config.baseUrl());
        if (outbound != null) {
            builder.proxy(outbound);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.putHeader("session_id", sessionId);
            builder.putHeader("x-client-request-id", sessionId);   // 对齐 pi/codex 的请求级关联头
        }
        // codex 官方 WS beta 头（二进制/源码同值）
        builder.putHeader("OpenAI-Beta", "responses_websockets=2026-02-06");
        ProviderHeaderInjector.apply(builder::putHeader, config.extraHeaders(), sessionId);
        return builder.build();
    }

    /** WS 版请求体（字段与 {@link #buildParams} 对齐；包内可见供回归测试断言 wire 形态）。 */
    static ResponsesClientEvent.ResponseCreate buildWsRequest(String modelName,
                                                                      String instructions,
                                                                      List<ResponseInputItem> input,
                                                                      List<Tool> sdkTools,
                                                                      Integer maxOutputTokens,
                                                                      String promptCacheKey,
                                                                      String reasoningEffort,
                                                                      String previousResponseId) {
        ResponsesClientEvent.ResponseCreate.Builder b = ResponsesClientEvent.ResponseCreate.builder()
            .model(modelName)
            .inputOfResponse(input == null ? List.of() : input)
            .store(false)
            .stream(true)
            .parallelToolCalls(true)
            .toolChoice(ToolChoiceOptions.AUTO)
            .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
        if (instructions != null && !instructions.isBlank()) {
            b.instructions(instructions);
        }
        if (sdkTools != null && !sdkTools.isEmpty()) {
            b.tools(sdkTools);
        }
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            b.maxOutputTokens(maxOutputTokens.longValue());
        }
        if (promptCacheKey != null && !promptCacheKey.isBlank()) {
            b.promptCacheKey(promptCacheKey);
        }
        // [codex 对齐] client_metadata.session_id —— codex 的 ResponsesApiRequest 每次都带
        //   （codex-rs/codex-api/src/common.rs:432；值来自 responses_metadata.client_metadata）。
        //   服务端用于会话路由/遥测；可选字段，缺失不影响协议正确性。
        if (promptCacheKey != null && !promptCacheKey.isBlank()) {
            ObjectNode meta = JSON.createObjectNode();
            meta.put("session_id", promptCacheKey);
            b.putAdditionalProperty("client_metadata", JsonValue.fromJsonNode(meta));
        }
        Reasoning reasoning = reasoningOf(modelName, reasoningEffort);
        if (reasoning != null) {
            b.reasoning(reasoning);
        }
        if (previousResponseId != null && !previousResponseId.isBlank()) {
            b.previousResponseId(previousResponseId);
        }
        return b.build();
    }

    /** 除 input / previous_response_id 外的字段指纹（任一变化则不增量）。 */
    private static String requestFingerprint(String modelName, String instructions, List<Tool> tools,
                                             Integer maxOutputTokens, String promptCacheKey,
                                             String reasoningEffort) {
        return modelName + "\u0001"
            + (instructions == null ? "" : instructions) + "\u0001"
            + (tools == null ? "" : tools.toString()) + "\u0001"
            + (maxOutputTokens == null ? "" : maxOutputTokens) + "\u0001"
            + (promptCacheKey == null ? "" : promptCacheKey) + "\u0001"
            + (reasoningEffort == null ? "" : reasoningEffort);
    }

    /** codex 退避：首 5s 起、翻倍、封顶 60s（responses_retry.rs:23-24）。 */
    private static long backoffMs(int attempt) {
        long delay = 5_000L;
        for (int i = 1; i < attempt && delay < 60_000L; i++) {
            delay *= 2;
        }
        return Math.min(delay, 60_000L);
    }

    /**
     * [codex 对齐] 续接失效/连接上限判据（字符串含错误码或文案）。
     *
     * <p>codex 常量逐字：
     * {@code previous_response_not_found}（responses_websocket.rs:432）与
     * {@code websocket_connection_limit_reached}（:433），两者都要求作废
     * {@code previous_response_id} 后重发全量。
     */
    static boolean isRecoverableWsReconnect(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("previous_response_not_found")
            || m.contains("previous response was not found")
            || m.contains("websocket_connection_limit_reached")
            || m.contains("responses websocket connection limit reached");
    }

    /** 拼接异常 cause 链消息（上限 4KB），供错误码判据使用。 */
    static String messagesOf(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 4096; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
        }
        return sb.toString();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 握手 426 Upgrade Required（codex client.rs:2435-2439）。 */
    private static boolean isUpgradeRequired(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 16; depth++) {
            if (cur instanceof com.openai.errors.OpenAIServiceException se && se.statusCode() == 426) {
                return true;
            }
            if (cur instanceof LlmApiException api && api.status() == 426) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("426") && lower.contains("upgrade")) {
                    return true;
                }
            }
            Throwable next = cur.getCause();
            if (next == cur) {
                break;
            }
            cur = next;
        }
        return false;
    }

    /** WebSocket 消息超限（codex WebsocketMessageTooLarge）。 */
    private static boolean isMessageTooLarge(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 16; depth++) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("message size") || lower.contains("too large")
                    || lower.contains("websocketmessagetoolarge") || lower.contains("closecode.size")) {
                    return true;
                }
            }
            Throwable next = cur.getCause();
            if (next == cur) {
                break;
            }
            cur = next;
        }
        return false;
    }

    /**
     * 显式访问拒绝（codex `RetryPolicy::Never`）：403 + 明确的 workspace/access-program 码。
     *
     * <p>这些不是瞬时故障，重试或换传输都会得到同样结果；codex 明确不重试、不切传输
     * （core/tests/suite/websocket_fallback.rs 的 forbidden_responses_retry_only_without_an_explicit_access_denial）。
     */
    private static boolean isExplicitAccessDenial(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 16; depth++) {
            String text = null;
            if (cur instanceof LlmApiException api) {
                if (api.status() != 403) {
                    return false;
                }
                text = api.body();
            } else if (cur instanceof com.openai.errors.OpenAIServiceException se) {
                if (se.statusCode() != 403) {
                    return false;
                }
                text = serviceBodyText(se);
            } else if (cur instanceof com.openai.core.http.ResponseWebSocketException rwe) {
                text = String.valueOf(rwe.event());
            }
            if (text != null) {
                return text.contains("invalid_workspace_selected")
                    || text.contains("codex_workspace_access_denied")
                    || text.contains("access_program_not_enabled");
            }
            Throwable next = cur.getCause();
            if (next == cur) {
                break;
            }
            cur = next;
        }
        return false;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    /**
     * 逐项摘要（本仓自造的稳定指纹，用于判断「新 input 的前缀是否与上一轮完全一致」）。
     *
     * <p>不用 SDK 对象序列化：Kotlin 模型没有稳定的 public bean 形状；这里只取参与前缀判定
     * 的语义字段，且 {@link #replayFromSignature} 产出的 item 与 {@link #digestSignatureItems}
     * 用同一套字段，保证自洽。
     */
    static List<String> digestInput(List<ResponseInputItem> items) {
        List<String> out = new ArrayList<>(items == null ? 0 : items.size());
        if (items == null) {
            return out;
        }
        for (ResponseInputItem it : items) {
            out.add(digestItem(it));
        }
        return out;
    }

    private static String digestItem(ResponseInputItem it) {
        if (it == null) {
            return "null";
        }
        if (it.isReasoning()) {
            ResponseReasoningItem r = it.asReasoning();
            return "rs|" + r.id() + "|" + r.encryptedContent().orElse("") + "|" + summaryText(r.summary());
        }
        if (it.isResponseOutputMessage()) {
            ResponseOutputMessage m = it.asResponseOutputMessage();
            StringBuilder t = new StringBuilder();
            for (ResponseOutputMessage.Content c : m.content()) {
                if (c.isOutputText()) {
                    t.append(c.asOutputText().text());
                } else if (c.isRefusal()) {
                    t.append(c.asRefusal().refusal());
                }
            }
            return "om|" + m.id() + "|" + t;
        }
        if (it.isFunctionCall()) {
            ResponseFunctionToolCall fc = it.asFunctionCall();
            return "fc|" + fc.id().orElse("") + "|" + fc.callId() + "|" + fc.name() + "|" + fc.arguments();
        }
        if (it.isEasyInputMessage()) {
            EasyInputMessage m = it.asEasyInputMessage();
            return "msg|" + m.role() + "|" + m.content();
        }
        if (it.isMessage()) {
            ResponseInputItem.Message m = it.asMessage();
            StringBuilder t = new StringBuilder();
            for (ResponseInputContent c : m.content()) {
                if (c.isInputText()) {
                    t.append(c.asInputText().text());
                } else if (c.isInputImage()) {
                    t.append(c.asInputImage().imageUrl());
                }
            }
            return "msg|" + m.role() + "|" + t;
        }
        if (it.isFunctionCallOutput()) {
            ResponseInputItem.FunctionCallOutput o = it.asFunctionCallOutput();
            return "fco|" + o.callId() + "|" + o.output();
        }
        return "other|" + it;
    }

    private static String summaryText(List<ResponseReasoningItem.Summary> summary) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ResponseReasoningItem.Summary s : summary) {
            if (s != null && s.text() != null) {
                sb.append(s.text());
            }
        }
        return sb.toString();
    }

    /** 上一轮 output → 可回放签名（thinkingSignature 列承载；kind 区分 Anthropic 的 thinking 块）。 */
    static String outputSignature(Response response) {
        if (response == null || response.output() == null || response.output().isEmpty()) {
            return null;
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", "responses_output");
        ArrayNode items = root.putArray("items");
        for (ResponseOutputItem item : response.output()) {
            if (item == null) {
                continue;
            }
            if (item.isReasoning()) {
                var r = item.asReasoning();
                ObjectNode n = items.addObject();
                n.put("type", "reasoning");
                if (r.id() != null) {
                    n.put("id", r.id());
                }
                r.encryptedContent().ifPresent(v -> n.put("encrypted_content", v));
                ArrayNode sum = n.putArray("summary");
                for (var s : r.summary()) {
                    if (s != null && s.text() != null) {
                        sum.add(s.text());
                    }
                }
            } else if (item.isMessage()) {
                var m = item.asMessage();
                StringBuilder t = new StringBuilder();
                for (var c : m.content()) {
                    if (c.isOutputText()) {
                        t.append(c.asOutputText().text());
                    } else if (c.isRefusal()) {
                        t.append(c.asRefusal().refusal());
                    }
                }
                if (t.length() == 0) {
                    continue;
                }
                ObjectNode n = items.addObject();
                n.put("type", "message");
                if (m.id() != null) {
                    n.put("id", m.id());
                }
                n.put("text", t.toString());
            } else if (item.isFunctionCall()) {
                var fc = item.asFunctionCall();
                if (fc.callId() == null || fc.name() == null) {
                    continue;
                }
                ObjectNode n = items.addObject();
                n.put("type", "function_call");
                fc.id().ifPresent(v -> n.put("id", v));
                n.put("call_id", fc.callId());
                n.put("name", fc.name());
                n.put("arguments", fc.arguments() == null ? "{}" : fc.arguments());
            }
        }
        return items.isEmpty() ? null : root.toString();
    }

    /** 签名 → input items（不可解析 / 非 responses 形状 → null，走旧 EasyInputMessage 逻辑）。 */
    static List<ResponseInputItem> replayFromSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(signature);
            if (root == null || !"responses_output".equals(root.path("kind").asText())) {
                return null;
            }
            List<ResponseInputItem> out = new ArrayList<>();
            for (JsonNode n : root.path("items")) {
                String type = n.path("type").asText("");
                switch (type) {
                    case "reasoning" -> {
                        ResponseReasoningItem.Builder b = ResponseReasoningItem.builder();
                        if (n.hasNonNull("id")) {
                            b.id(n.get("id").asText());
                        }
                        if (n.hasNonNull("encrypted_content")) {
                            b.encryptedContent(n.get("encrypted_content").asText());
                        }
                        List<ResponseReasoningItem.Summary> summary = new ArrayList<>();
                        for (JsonNode s : n.path("summary")) {
                            summary.add(ResponseReasoningItem.Summary.builder().text(s.asText()).build());
                        }
                        b.summary(summary);
                        // ⛔ 不设 status：codex 端点拒绝 input item 上的 status 字段
                        //   （实测 `Invalid response.create payload: Unknown parameter: 'input[127].status'`）。
                        //   input 语义里 status 无意义；SDK 该字段可选。
                        out.add(ResponseInputItem.ofReasoning(b.build()));
                    }
                    case "message" -> {
                        // 对齐 pi openai-responses-shared.js: text block →
                        //   {type:"message", role:"assistant", content:[{type:"output_text",...}],
                        //    status:"completed", id:"msg_..."}
                        // codex 接受 message 上的 status（拒绝的是 reasoning/function_call 上的）。
                        String text = n.path("text").asText("");
                        if (text.isEmpty()) {
                            break;
                        }
                        if (!n.hasNonNull("id")) {
                            // 无 id 时 SDK 的 ResponseOutputMessage 无法构造（id/status 均必填）
                            //   ⇒ 退化成无 id 的 easy message（digest 侧同判据，保持自洽）。
                            out.add(ResponseInputItem.ofEasyInputMessage(
                                EasyInputMessage.builder()
                                    .role(EasyInputMessage.Role.ASSISTANT)
                                    .content(text)
                                    .build()));
                            break;
                        }
                        ResponseOutputMessage.Builder b = ResponseOutputMessage.builder()
                            .id(n.get("id").asText())
                            .role(JsonValue.from("assistant"))
                            .status(ResponseOutputMessage.Status.COMPLETED)
                            .type(JsonValue.from("message"))
                            .content(List.of(ResponseOutputMessage.Content.ofOutputText(
                                ResponseOutputText.builder()
                                    .annotations(List.of())
                                    .text(text)
                                    .build())));
                        out.add(ResponseInputItem.ofResponseOutputMessage(b.build()));
                    }
                    case "function_call" -> {
                        ResponseFunctionToolCall.Builder b = ResponseFunctionToolCall.builder()
                            .callId(n.path("call_id").asText())
                            .name(n.path("name").asText())
                            .arguments(n.path("arguments").asText("{}"));
                        // ⛔ 不设 status（codex 端点拒绝 input[].status；SDK 该字段可选）
                        if (n.hasNonNull("id")) {
                            b.id(n.get("id").asText());
                        }
                        out.add(ResponseInputItem.ofFunctionCall(b.build()));
                    }
                    default -> {
                        // 未知条目忽略（向前兼容）
                    }
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            log.debug("ResponsesSdkProvider: thinkingSignature 非 responses 形状，忽略: {}", e.toString());
            return null;
        }
    }

    /** 签名条目的摘要（必须与 {@link #digestItem} 对同类条目的输出逐字一致）。 */
    static List<String> digestSignatureItems(String signature) {
        List<String> out = new ArrayList<>();
        if (signature == null || signature.isBlank()) {
            return out;
        }
        try {
            JsonNode root = JSON.readTree(signature);
            if (root == null) {
                return out;
            }
            for (JsonNode n : root.path("items")) {
                String type = n.path("type").asText("");
                switch (type) {
                    case "reasoning" -> {
                        StringBuilder sum = new StringBuilder();
                        for (JsonNode s : n.path("summary")) {
                            sum.append(s.asText());
                        }
                        out.add("rs|" + n.path("id").asText("") + "|"
                            + n.path("encrypted_content").asText("") + "|" + sum);
                    }
                    // message：有 id ⇒ "om|id|text"（ResponseOutputMessage 回放）；
                    //   无 id ⇒ "msg|assistant|text"（replayFromSignature 的同判据退化分支）。
                    case "message" -> out.add(
                        n.hasNonNull("id") && !n.path("text").asText("").isEmpty()
                            ? "om|" + n.get("id").asText() + "|" + n.path("text").asText("")
                            : "msg|assistant|" + n.path("text").asText(""));
                    case "function_call" -> out.add("fc|" + n.path("id").asText("") + "|"
                        + n.path("call_id").asText("") + "|" + n.path("name").asText("")
                        + "|" + n.path("arguments").asText("{}"));
                    default -> {
                        // 忽略
                    }
                }
            }
        } catch (Exception ignore) {
            // 摘要失败 → 空 ⇒ 下一轮回全量（安全）
        }
        return out;
    }

    /**
     * SSE 单事件映射 → 统一 {@link ResponsesWireSignal} 消费（与 WS 共用 {@link #handleSignal}）。
     *
     * <p>返回非 null = 该事件是终止性失败（调用方停止消费并走 onError）。
     *
     * <pre>
     * response.output_text.delta                -> onChunk
     * response.reasoning_summary_text.delta     -> onReasoningChunk
     * response.reasoning_text.delta             -> onReasoningChunk
     * response.refusal.delta                    -> onChunk（拒答也是可见正文）
     * response.output_item.added                -> 记 function_call 的 name / call_id
     * response.function_call_arguments.delta    -> 累积参数
     * response.function_call_arguments.done     -> ToolUseBlock -> onToolCallComplete
     * response.output_item.done                 -> 兜底收尾（arguments.done 缺失 / 不发 delta 的端点）
     * response.output_text.done                 -> 兜底补发（按 itemId 去重）
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
                               Set<String> textDeltaItems,
                               AtomicReference<Response> finalResponse,
                               Consumer<String> onChunk,
                               Consumer<String> onReasoningChunk,
                               Consumer<ToolUseBlock> onToolCallComplete) {
        return handleSignal(ResponsesWireSignal.of(ev), content, reasoning, argBuffers, pendingCalls,
            toolCalls, emittedCallKeys, textDeltaItems, finalResponse, onChunk, onReasoningChunk,
            onToolCallComplete);
    }

    /** WebSocket 单事件映射 → 同一 {@link #handleSignal}。 */
    private String handleWsEvent(ResponsesServerEvent ev,
                                 StringBuilder content,
                                 StringBuilder reasoning,
                                 Map<String, StringBuilder> argBuffers,
                                 Map<String, PendingCall> pendingCalls,
                                 List<ToolUseBlock> toolCalls,
                                 Set<String> emittedCallKeys,
                                 Set<String> textDeltaItems,
                                 AtomicReference<Response> finalResponse,
                                 Consumer<String> onChunk,
                                 Consumer<String> onReasoningChunk,
                                 Consumer<ToolUseBlock> onToolCallComplete) {
        return handleSignal(ResponsesWireSignal.of(ev), content, reasoning, argBuffers, pendingCalls,
            toolCalls, emittedCallKeys, textDeltaItems, finalResponse, onChunk, onReasoningChunk,
            onToolCallComplete);
    }

    /**
     * 统一事件消费（SSE / WS 单源）。
     *
     * <p>`textDeltaItems`：已经收到正文增量的 itemId 集合。`output_text.done` 与
     * `output_item.done` 的兜底补发都靠它去重 —— 端点正常发 delta 时不会重复吐正文；
     * 兼容端点只发 done 时也能整段交付（否则表现为「流式没输出」）。
     */
    private String handleSignal(ResponsesWireSignal sig,
                                StringBuilder content,
                                StringBuilder reasoning,
                                Map<String, StringBuilder> argBuffers,
                                Map<String, PendingCall> pendingCalls,
                                List<ToolUseBlock> toolCalls,
                                Set<String> emittedCallKeys,
                                Set<String> textDeltaItems,
                                AtomicReference<Response> finalResponse,
                                Consumer<String> onChunk,
                                Consumer<String> onReasoningChunk,
                                Consumer<ToolUseBlock> onToolCallComplete) {
        switch (sig.kind) {
            case TEXT_DELTA -> {
                if (sig.delta == null) {
                    return null;
                }
                content.append(sig.delta);
                onChunk.accept(sig.delta);
                if (sig.itemId != null) {
                    textDeltaItems.add(sig.itemId);
                }
                return null;
            }
            case REASONING_SUMMARY_DELTA, REASONING_TEXT_DELTA -> {
                if (sig.delta != null) {
                    reasoning.append(sig.delta);
                    onReasoningChunk.accept(sig.delta);
                }
                return null;
            }
            case REFUSAL_DELTA -> {
                // 模型拒答：text 在 output_text 之外走 refusal 通道 ⇒ 必须当可见正文
                if (sig.delta != null) {
                    content.append(sig.delta);
                    onChunk.accept(sig.delta);
                }
                return null;
            }
            case ITEM_ADDED -> {
                ResponseOutputItem item = sig.item;
                if (item != null && item.isFunctionCall()) {
                    ResponseFunctionToolCall fc = item.asFunctionCall();
                    pendingCalls.put(callKey(fc), new PendingCall(fc.callId(), fc.name()));
                }
                return null;
            }
            case ARGS_DELTA -> {
                if (sig.itemId != null && sig.delta != null) {
                    argBuffers.computeIfAbsent(sig.itemId, k -> new StringBuilder()).append(sig.delta);
                }
                return null;
            }
            case ARGS_DONE -> {
                emitToolCall(sig.itemId, sig.arguments, pendingCalls, argBuffers, toolCalls,
                    emittedCallKeys, onToolCallComplete);
                return null;
            }
            case ITEM_DONE -> {
                ResponseOutputItem item = sig.item;
                if (item != null && item.isFunctionCall()) {
                    ResponseFunctionToolCall fc = item.asFunctionCall();
                    pendingCalls.putIfAbsent(callKey(fc), new PendingCall(fc.callId(), fc.name()));
                    // 兜底：部分兼容端点不发 function_call_arguments.done，只在 output_item.done 给全量参数
                    emitToolCall(callKey(fc), fc.arguments(), pendingCalls, argBuffers, toolCalls,
                        emittedCallKeys, onToolCallComplete);
                } else if (item != null && item.isMessage()) {
                    emitWholeMessageIfNoDelta(item.asMessage(), content, textDeltaItems, onChunk);
                }
                return null;
            }
            case TEXT_DONE -> {
                // 兜底：本 item 一个 delta 都没收到 → 整段补发一次
                if (sig.itemId != null && sig.text != null && !sig.text.isEmpty()
                    && textDeltaItems.add(sig.itemId)) {
                    content.append(sig.text);
                    onChunk.accept(sig.text);
                }
                return null;
            }
            case COMPLETED, INCOMPLETE -> {
                finalResponse.set(sig.response);
                return null;
            }
            case FAILED -> {
                return "Responses API response.failed: " + describeError(sig.response);
            }
            case ERROR -> {
                return "Responses API error event: " + sig.message;
            }
            default -> {
                if (log.isDebugEnabled()) {
                    log.debug("ResponsesSdkProvider 忽略未映射流事件: {}", sig.kind);
                }
                return null;
            }
        }
    }

    /** 兜底：message item 没发过 delta 时把它的正文整段补发一次（按 item id 去重）。 */
    private static void emitWholeMessageIfNoDelta(ResponseOutputMessage msg,
                                                  StringBuilder content,
                                                  Set<String> textDeltaItems,
                                                  Consumer<String> onChunk) {
        if (msg == null || msg.id() == null || textDeltaItems.contains(msg.id())) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (ResponseOutputMessage.Content c : msg.content()) {
            if (c.isOutputText()) {
                text.append(c.asOutputText().text());
            } else if (c.isRefusal()) {
                text.append(c.asRefusal().refusal());
            }
        }
        if (text.length() == 0) {
            return;
        }
        textDeltaItems.add(msg.id());
        content.append(text);
        onChunk.accept(text.toString());
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
            outputSignature(response));
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
        return buildParams(modelName, instructions, input, toolsList, maxOutputTokens,
            promptCacheKey, reasoningEffort, false);
    }

    private ResponseCreateParams buildParams(String modelName,
                                             String instructions,
                                             List<ResponseInputItem> input,
                                             List<Tool> toolsList,
                                             Integer maxOutputTokens,
                                             String promptCacheKey,
                                             String reasoningEffort,
                                             boolean codexEndpoint) {
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
        // [responses-ws] 对齐 codex / pi：拿回 encrypted reasoning 供下一轮原样回放。
        //   ⚠️ 仅 codex 端点发送（非 codex 的兼容端点未验证该 include 值，保持原 wire 不变）。
        if (codexEndpoint) {
            b.addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
                .parallelToolCalls(true)
                .toolChoice(ToolChoiceOptions.AUTO);
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
        // [responses-ws] 对齐 pi（openai-codex-responses.js:257-259）：effort 存在时同时请求 summary=auto；
        //   否则上游不会发 reasoning_summary_text.delta（思考面板无流式内容）。
        return Reasoning.builder()
            .effort(ReasoningEffort.of(openAiEffort))
            .summary(Reasoning.Summary.AUTO)
            .build();
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
                    // [responses-ws] 有上一轮原样输出签名 ⇒ 逐项回放（reasoning item + message + function_call），
                    //   这是 previous_response_id 增量前缀比对的成立前提（否则我们从历史重建的 item 与
                    //   服务端 output 不等价，增量永远退化为全量）。
                    List<ResponseInputItem> replay = replayFromSignature(m.thinkingSignature());
                    if (replay != null) {
                        items.addAll(replay);
                        continue;
                    }
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

    /** 非流式响应中的全部 output_text（含 refusal —— 拒答也是可见正文）。 */
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
                } else if (c.isRefusal()) {
                    sb.append(c.asRefusal().refusal());
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
        // [responses-ws] 对齐 SDK 字段：inputTokensDetails 同时有 cachedTokens / cacheWriteTokens；
        //   后者映射到内部 cacheCreationInputTokens（此前恒漏读 ⇒ 统计少一列）。
        Long cacheWrite = u.inputTokensDetails() != null && u.inputTokensDetails().cacheWriteTokens() > 0
            ? u.inputTokensDetails().cacheWriteTokens() : null;
        return new AgentUsage(u.inputTokens(), u.outputTokens(), cacheWrite, cacheRead, null, null, null);
    }

    /** response.failed 的人类可读描述。 */
    private static String describeError(Response response) {
        if (response == null) {
            return "no response";
        }
        return response.error().map(e -> e.message()).orElse("unknown error");
    }

    /** WS 服务端终态失败（对齐 codex RetryPolicy::Never：不重试、不切传输）。 */
    private static final class WsTerminalException extends RuntimeException {
        WsTerminalException(String message) {
            super(message);
        }
    }

    /** 流内待发工具调用（output_item.added 给出 name / call_id，参数由后续事件拼出）。 */
    private record PendingCall(String callId, String name) {
    }
}
