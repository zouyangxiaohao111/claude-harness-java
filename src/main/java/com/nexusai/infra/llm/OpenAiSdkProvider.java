package com.nexusai.infra.llm;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.properties.NexusProperties;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.*;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * OpenAI SDK Provider · 基于官方 openai-java SDK 的 LlmProvider 实现。
 *
 * <p>[OpenAI-SDK 迁移 · 类比 DEC-RV-07] 迁移自旧 {@code OpenAiProvider}（手写 HTTP + SSE，
 * 该类已删除）。补齐能力：
 * <ul>
 *   <li><b>chatWithRaw</b>（resp.id + reasoning_content + requestId=null，DEC-OA-1 方案 C）</li>
 *   <li><b>chatWithOptions</b>（response_format json_schema / temperature / max_tokens /
 *       thinking disabled / abort 预检）</li>
 *   <li><b>blocks stream 硬中断</b>（H13-GAP-4，对齐 CC createCombinedAbortSignal）</li>
 *   <li><b>blocks stream effort → reasoning_effort</b>（C-31 · Java 多 provider 扩展 ⊕）</li>
 *   <li><b>assistant tool_calls 回放</b>（关闭 R1 多轮工具调用 400 风险）</li>
 *   <li><b>user contentBlocks image/text 渲染</b>（P-AL-01 · PDF 页图送达）</li>
 *   <li><b>maxRetries(0)</b>（CC claude.ts:1781 · Disabled auto-retry in favor of manual implementation）</li>
 *   <li><b>SDK 异常 → LlmApiException</b>（R27-6 类型化错误分类）</li>
 * </ul>
 *
 * <p><b>受控残留（SDK 0.25.0 API 约束，grep javap 实证）</b>：
 * <ul>
 *   <li>R-T-1：tool 消息 content 数组仅支持 {@code ChatCompletionContentPartText} → tool 结果中的
 *       image/document 块被跳过（warn 日志）；acceptFeedback / text 块正常序列化。
 *       [IT-6] structuredOutput 不再序列化（停发模型）→ 载荷走 structured_output attachment 通道</li>
 *   <li>R-U-1：user 消息无 document content part（SDK 0.25.0 无 document 类型）→ document 块跳过；
 *       image → image_url / text → text 正常渲染</li>
 *   <li>R-REQ-1：SDK 0.25.0 无 {@code withRawResponse}（Anthropic 有），
 *       {@code OpenAIOkHttpClient$Builder} 亦无 {@code httpClient} 注入点（OkHttp 拦截器方案不可用，
 *       DEC-OA-1 方案 C）→ 响应侧 requestId 无法提取。DEC-RV-14a 兜底：响应 requestId 恒 null 时
 *       用请求侧自建 ID（{@link RequestContext#requestId()}，MDC reqId = userMessageId）。</li>
 * </ul>
 *
 * <h2>推理字段</h2>
 * 推理字段名由 {@link NexusProperties#getOpenaiReasoningField()} 配置，不再硬编码。
 */
@Component
public class OpenAiSdkProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSdkProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Resource NexusProperties properties;

    /** [W2-3] DB models.max_tokens 解析（前端可配）· required=false：无 Spring 上下文时静默回落（不发送 max_tokens）。 */
    @Autowired(required = false) private ModelMapper modelMapper;
    /** [W2-3] DB 提供商 mapper（max_tokens 全名感知解析用，可 null → 按 name 兼容路径）。 */
    @Autowired(required = false) private ProviderMapper providerMapper;
    /** [AM-CC-20260825] 共享模型解析（resolveSdkModelName 剥 provider 前缀）· 摘要/explainer 等调用点
     *  带前缀 deepseek/deepseek-v4-flash 发 API 400（2026-08-25 实测），统一在 provider 层剥。 */
    @Autowired(required = false) private ModelConfigResolver modelConfigResolver;

    @Override
    public String type() {
        return "openai_sdk";
    }

    // ===================== stream =====================
    /**
     * [⊕C-1] blocks 唯一重载 · String systemPrompt 兼容路径已删除（发送契约数组态唯一）。
     *
     * <p>OpenAI 兼容端点（DeepSeek 等）请求体 system 为单字符串 —— 按 CC 数组 join 语义
     * （原 LlmProvider blocks default 的 {@code \n\n} 连接，splitSysPromptPrefix 拆分亦如此）
     * 把 blocks 连接后委托私有 doStream；null/空 blocks = 不发送 system。
     * maxOutputTokensOverride/taskBudget 沿既有 blocks 路径行为不写入 OpenAI 请求体
     * （Java 扩展仅 effort 经 {@code reasoning_effort} 表达，见 {@link #mapToOpenAiReasoningEffort}）。
     *
     * <p>[H13-GAP-4 v3] AbortController 硬中断：注册 abort listener → 置 aborted 标志 →
     * SDK 流消费循环在 chunk 边界检查 aborted 停止，并以 {@link CancellationException} 调 onError。
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
        String joined = systemPromptBlocks == null ? null : systemPromptBlocks.stream()
            .filter(java.util.Objects::nonNull)
            .map(SystemPromptBlock::text)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.joining("\n\n"));
        doStream(config, modelName, joined, history, tools, effortValue, null,
            onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
            aborted, onError, onComplete);
    }

    /**
     * [CCJ-EXEC-08] 18-arg stream · 带 thinkingConfig 透传（hook agent 请求）。
     *
     * <p>对齐 CC queryModelWithStreaming（execAgentHook.ts:134 thinkingConfig:{type:'disabled'}
     * → query.ts:662）——openai-compatible 端点需显式 {@code thinking:{type:'disabled'}}
     * 关闭推理（复用 chatWithOptions :490-495 先例）。默认实现（未覆写 provider / mock）
     * 忽略该参数，本类覆写为写入请求体。
     */
    @Override
    public void stream(ProviderConfig config,
                       String modelName,
                       String systemPrompt,
                       List<ChatMessageDto> history,
                       ArrayNode tools,
                       Integer maxOutputTokensOverride,
                       TaskBudgetParam taskBudget,
                       String effortValue,
                       ChatRequestOptions.ThinkingConfig thinkingConfig,
                       Consumer<String> onChunk,
                       Consumer<AssistantMessage> onAssistantMessage,
                       Consumer<ToolUseBlock> onToolCallComplete,
                       Consumer<String> onReasoningChunk,
                       Runnable onStreamingFallback,
                       AbortController abortController,
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
        doStream(config, modelName, systemPrompt, history, tools, effortValue, thinkingConfig,
            onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
            aborted, onError, onComplete);
    }

    /**
     * [CCJ-EXEC-08] 19-arg blocks stream + thinkingConfig · blocks 连接后走
     * {@link #stream(ProviderConfig, String, String, List, ArrayNode, Integer, TaskBudgetParam,
     * String, ChatRequestOptions.ThinkingConfig, Consumer, Consumer, Consumer, Consumer, Runnable,
     * AbortController, Consumer, Runnable)}（openai-compatible 端点 system 为 String 单值）。
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
                       ChatRequestOptions.ThinkingConfig thinkingConfig,
                       Consumer<String> onChunk,
                       Consumer<AssistantMessage> onAssistantMessage,
                       Consumer<ToolUseBlock> onToolCallComplete,
                       Consumer<String> onReasoningChunk,
                       Runnable onStreamingFallback,
                       AbortController abortController,
                       Consumer<Throwable> onError,
                       Runnable onComplete) {
        String joined = systemPromptBlocks == null ? null : systemPromptBlocks.stream()
            .filter(java.util.Objects::nonNull)
            .map(SystemPromptBlock::text)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.joining("\n\n"));
        stream(config, modelName, joined, history, tools, maxOutputTokensOverride, taskBudget,
            effortValue, thinkingConfig, onChunk, onAssistantMessage, onToolCallComplete,
            onReasoningChunk, onStreamingFallback, abortController, onError, onComplete);
    }

    /** 流式核心 · SDK createStreaming + 迭代器消费（[H13-GAP-4 v3] chunk 边界检查 aborted）. */
    private void doStream(ProviderConfig config,
                          String modelName,
                          String systemPrompt,
                          List<ChatMessageDto> history,
                          ArrayNode tools,
                          String effortValue,
                          ChatRequestOptions.ThinkingConfig thinkingConfig,
                          Consumer<String> onChunk,
                          Consumer<AssistantMessage> onAssistantMessage,
                          Consumer<ToolUseBlock> onToolCallComplete,
                          Consumer<String> onReasoningChunk,
                          AtomicBoolean aborted,
                          Consumer<Throwable> onError,
                          Runnable onComplete) {
        if (config == null || !config.isUsable()) {
            onError.accept(new IllegalStateException(
                "OpenAiSdkProvider.stream 调用时 ProviderConfig 不可用"));
            return;
        }
        try {
            OpenAIClient client = buildClient(config);
            // [DEC-04] 流式请求开启 stream_options.include_usage=true → final chunk 携带 usage
            // （OpenAI streaming 默认不返回 usage；对齐 CC Anthropic 流式 always-on usage）
            ChatCompletionCreateParams params = buildRequestParams(
                sdkModelName(modelName), systemPrompt, history, tools,
                null, thinkingConfig != null && "disabled".equals(thinkingConfig.type()),
                null, effortValue, null, true);

            String resolvedEffort = EffortSupport.resolveAppliedEffort(modelName, effortValue);
            if (log.isDebugEnabled()) {
                log.debug("OpenAiSdkProvider stream → model={} messages={} tools={} effort={}",
                    modelName,
                    history == null ? 0 : history.size(),
                    tools == null ? 0 : tools.size(),
                    resolvedEffort);
            }

            StreamResponse<ChatCompletionChunk> response =
                client.chat().completions().createStreaming(params);

            OpenAiStreamState state = new OpenAiStreamState();
            // [D-4] requestId 兜底（DEC-RV-14a）· openai-java 0.25.0 无 withRawResponse（R-REQ-1），
            //   响应侧 x-request-id 头不可达 → 与非流式 chatWithRaw 一致走请求侧自建 ID（MDC reqId =
            //   userMessageId）· 子 agent invokingRequestId 归因值源（AgentTool.tsx:723/:778）。
            state.requestId = RequestContext.requestId();
            if (log.isDebugEnabled()) {
                log.debug("OpenAiSdkProvider 流式 requestId 兜底={} · DEC-RV-14a 请求侧自建 ID（SDK 无 withRawResponse）",
                    state.requestId);
            }
            AtomicBoolean finished = new AtomicBoolean(false);
            java.util.Set<String> completedToolIds =
                onToolCallComplete == null ? null : java.util.concurrent.ConcurrentHashMap.newKeySet();

            // [H13-GAP-4 v3] 用迭代器 + aborted chunk 边界检查（forEach 无法中途中断）
            java.util.Iterator<ChatCompletionChunk> it = response.stream().iterator();
            while (it.hasNext()) {
                if (aborted != null && aborted.get()) {
                    break; // 硬中断: 不再消费
                }
                if (finished.get()) break;
                try {
                    parseChunk(it.next(), state, onChunk, onToolCallComplete,
                        onReasoningChunk, completedToolIds);
                } catch (Exception e) {
                    log.warn("OpenAI SDK chunk 解析失败: {}", e.toString());
                }
            }

            // abort 后不触发 onAssistantMessage / onComplete（onError 已由 abort listener 发出）
            if (aborted != null && aborted.get()) {
                log.info("OpenAiSdkProvider stream aborted: SDK 流消费已中断, 跳过 onComplete");
                return;
            }

            if (onAssistantMessage != null) {
                onAssistantMessage.accept(buildAssistantMessage(state));
            }
            finished.set(true);
            try {
                response.close();
            } catch (Exception closeErr) {
                log.warn("OpenAI SDK 响应关闭失败: {}", closeErr.toString());
            }
            onComplete.run();
        } catch (Exception e) {
            if (aborted != null && aborted.get()) {
                return; // abort 已发出 onError, 不重复报错
            }
            log.error("OpenAiSdkProvider 流式调用失败: {}", e.toString());
            onError.accept(translateSdkError(e));
        }
    }

    // ===================== chat (non-stream) =====================

    @Override
    public String chat(ProviderConfig config,
                       String modelName,
                       String systemPrompt,
                       String userMessage) {
        LlmRawResponse raw = chatWithRaw(config, modelName, systemPrompt, userMessage);
        return raw.content();
    }

    /**
     * [M3.2 + D P1-7] chatWithRaw · 返回完整 LlmRawResponse。
     *
     * <p>对齐 CC yoloClassifier.ts:795-924 sideQuery 返回值：
     * <ul>
     *   <li>{@link LlmRawResponse#id()} ← {@code ChatCompletion.id()}（chatcmpl-xxx）</li>
     *   <li>{@link LlmRawResponse#content()} ← choices[0].message.content</li>
     *   <li>{@link LlmRawResponse#thinking()} ← message._additionalProperties() 中按
     *       {@link NexusProperties#getOpenaiReasoningField()} 优先级匹配（DeepSeek R1 reasoning_content）</li>
     *   <li>{@link LlmRawResponse#requestId()} ← 请求侧自建 ID 兜底（[OpenAI-SDK] R-REQ-1 ·
     *       openai-java 0.25.0 无 withRawResponse / OkHttp 拦截器注入不可用 · DEC-OA-1 方案 C +
     *       DEC-RV-14a：{@link RequestContext#requestId()}，MDC reqId = userMessageId）</li>
     * </ul>
     */
    @Override
    public LlmRawResponse chatWithRaw(ProviderConfig config,
                                      String modelName,
                                      String systemPrompt,
                                      String userMessage) {
        if (config == null || !config.isUsable()) {
            throw new IllegalStateException(
                "OpenAiSdkProvider.chatWithRaw 调用时 ProviderConfig 不可用");
        }
        try {
            OpenAIClient client = buildClient(config);
            ChatCompletionCreateParams params = buildRequestParams(
                sdkModelName(modelName), systemPrompt,
                userMessage == null ? List.of() : List.of(newUserMessage(userMessage)),
                null, null, false, null, null, null);
            ChatCompletion resp = client.chat().completions().create(params);

            String content = extractContent(resp);
            String responseId = resp.id() == null || resp.id().isBlank() ? null : resp.id();
            String thinking = extractThinking(resp);
            // [OpenAI-SDK] R-REQ-1 兜底（DEC-RV-14a）· SDK 0.25.0 无法提取 x-request-id →
            // 响应 requestId 恒 null，改用请求侧自建 ID（RequestContext.requestId() = MDC reqId =
            // ChatService 的 userMessageId）作兜底 · 对齐 CC extractRequestId 语义（请求侧追踪 ID，
            // 非 message id；响应 id 已进 LlmRawResponse.id = stageMsgId）
            String fallbackReqId = RequestContext.requestId();
            if (log.isInfoEnabled()) {
                log.info("[OpenAiSdkProvider] chatWithRaw 提取: responseId={} requestId={}(SDK-0.25.0无withRawResponse→请求侧兜底) contentLen={} thinkingLen={}",
                    responseId, fallbackReqId, content.length(),
                    thinking != null ? thinking.length() : 0);
            }
            return new LlmRawResponse(content, responseId, thinking, fallbackReqId);
        } catch (Exception e) {
            log.error("OpenAiSdkProvider.chatWithRaw failed: {}", e.toString());
            throw translateSdkError(e);
        }
    }

    /**
     * [H13-GAP-3 v3] chatWithOptions · 带选项非流式 chat · 对齐 CC queryModelWithoutStreaming 的
     * outputFormat / thinkingConfig / history / tools / temperature / max_tokens / abort。
     *
     * <ul>
     *   <li>outputFormat json_schema → {@code response_format:{type:'json_schema',
     *       json_schema:{name:'hook', schema:{...}}}}（CC execPromptHook.ts:87-98）</li>
     *   <li>thinkingConfig disabled → {@code thinking:{type:'disabled'}}
     *       （deepseek/openai-compatible 推理关闭约定, CC execPromptHook.ts:71）</li>
     *   <li>abort 预检 → {@link CancellationException}（CC claude.ts:744-745）</li>
     *   <li>maxTokens 侧信道 → {@code max_tokens}（CC sideQuery max_tokens:256）</li>
     * </ul>
     */
    @Override
    public String chatWithOptions(ProviderConfig config, String modelName, String systemPrompt,
                                  String userMessage, LlmProvider.ChatRequestOptions options) {
        if (config == null || !config.isUsable()) {
            throw new IllegalStateException(
                "OpenAiSdkProvider.chatWithOptions 调用时 ProviderConfig 不可用");
        }
        try {
            List<ChatMessageDto> history = new ArrayList<>();
            if (options != null && options.history() != null) {
                history.addAll(options.history());
            }
            if (userMessage != null) {
                history.add(newUserMessage(userMessage));
            }
            ArrayNode tools = options != null ? options.tools() : null;
            JsonNode outputFormatSchema =
                options != null && options.outputFormat() != null ? options.outputFormat().schema() : null;
            boolean thinkingDisabled = options != null && options.thinkingConfig() != null
                && "disabled".equals(options.thinkingConfig().type());
            // [P2-16] 对齐 CC claude.ts:744-745 abort 消费 — 请求前 signal.aborted 预检
            if (options != null && options.abortController() != null
                && options.abortController().isCancelled()) {
                throw new java.util.concurrent.CancellationException(
                    "OpenAiSdkProvider chatWithOptions aborted (CC claude.ts:744-745)");
            }
            Double temperature = options != null ? options.temperature() : null;
            // [IMP-M-P1-2] maxTokens 侧信道 → max_tokens（CC sideQuery max_tokens:256）
            Integer maxTokens = options != null ? options.maxTokens() : null;
            // [W2-3] maxTokens 未显式传入 → DB models.max_tokens 默认（前端可配，DB 未命中回落模型缺省）
            maxTokens = resolveDefaultMaxTokens(modelName, maxTokens);

            OpenAIClient client = buildClient(config);
            ChatCompletionCreateParams params = buildRequestParams(
                sdkModelName(modelName), systemPrompt, history, tools,
                outputFormatSchema, thinkingDisabled, temperature, null, maxTokens);
            // [WF3-04 explainer] 强制 tool_choice（CC options.tool_choice）→ named function choice
            params = applyToolChoice(params, options);
            ChatCompletion resp = client.chat().completions().create(params);

            String content = extractContent(resp);
            if (log.isInfoEnabled()) {
                log.info("[OpenAiSdkProvider] chatWithOptions: model={} contentLen={} messages={} outputFormat={} thinkingDisabled={} temperature={} maxTokens={} querySource={}",
                    modelName, content.length(), history.size(),
                    outputFormatSchema != null, thinkingDisabled,
                    temperature, maxTokens,
                    options != null ? options.querySource() : null);
            }
            return content;
        } catch (Exception e) {
            log.error("OpenAiSdkProvider.chatWithOptions failed: {}", e.toString());
            throw translateSdkError(e);
        }
    }

    /**
     * [WF3-04 explainer] 带选项非流式 chat · 返回完整 AssistantMessage（含 tool_use 块）。
     *
     * <p>对齐 CC sideQuery 返回 content blocks（permissionExplainer.ts:178-186）。强制
     * {@code tool_choice} 下 LLM 以 tool_use 作答，文本 content 为空 —— 本方法额外提取
     * {@code message.toolCalls()} 供 explainer 读取结构化输入。
     */
    @Override
    public AssistantMessage chatWithOptionsMessage(ProviderConfig config, String modelName,
                                                   String systemPrompt, String userMessage,
                                                   LlmProvider.ChatRequestOptions options) {
        if (config == null || !config.isUsable()) {
            throw new IllegalStateException(
                "OpenAiSdkProvider.chatWithOptionsMessage 调用时 ProviderConfig 不可用");
        }
        try {
            List<ChatMessageDto> history = new ArrayList<>();
            if (options != null && options.history() != null) {
                history.addAll(options.history());
            }
            if (userMessage != null) {
                history.add(newUserMessage(userMessage));
            }
            ArrayNode tools = options != null ? options.tools() : null;
            JsonNode outputFormatSchema =
                options != null && options.outputFormat() != null ? options.outputFormat().schema() : null;
            boolean thinkingDisabled = options != null && options.thinkingConfig() != null
                && "disabled".equals(options.thinkingConfig().type());
            // [P2-16] 对齐 CC claude.ts:744-745 abort 消费 — 请求前 signal.aborted 预检
            if (options != null && options.abortController() != null
                && options.abortController().isCancelled()) {
                throw new java.util.concurrent.CancellationException(
                    "OpenAiSdkProvider chatWithOptionsMessage aborted (CC claude.ts:744-745)");
            }
            Double temperature = options != null ? options.temperature() : null;
            Integer maxTokens = options != null ? options.maxTokens() : null;
            // [W2-3] maxTokens 未显式传入 → DB models.max_tokens 默认（前端可配，DB 未命中回落模型缺省）
            maxTokens = resolveDefaultMaxTokens(modelName, maxTokens);

            OpenAIClient client = buildClient(config);
            ChatCompletionCreateParams params = buildRequestParams(
                sdkModelName(modelName), systemPrompt, history, tools,
                outputFormatSchema, thinkingDisabled, temperature, null, maxTokens);
            params = applyToolChoice(params, options);
            ChatCompletion resp = client.chat().completions().create(params);

            String content = extractContent(resp);
            List<ToolUseBlock> toolCalls = extractToolCalls(resp);
            String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
            // [IMP-SUB-26 A6] 非流式 usage 解析 · CC original: message.usage
            //   (agentToolUtils.ts:238-256) · 对齐 CC executeNonStreamingRequest non-streaming usage 透传
            //   （Anthropic claude.ts:870-903 同语义；OpenAI 非流式 ChatCompletion 响应携带 usage，
            //   旧实现丢弃 → ChatMessageDto.usage=null → 回退 fromInputOutput/EMPTY，DEC-04 残留缺口收口）
            AgentUsage usage = extractUsage(resp);
            if (log.isInfoEnabled()) {
                log.info("[OpenAiSdkProvider] chatWithOptionsMessage: model={} contentLen={} toolCalls={} querySource={} usage={}",
                    modelName, content.length(), toolCalls.size(),
                    options != null ? options.querySource() : null, usage);
            }
            return new AssistantMessage(content, finishReason, toolCalls, "", null, usage);
        } catch (Exception e) {
            log.error("OpenAiSdkProvider.chatWithOptionsMessage failed: {}", e.toString());
            throw translateSdkError(e);
        }
    }

    /**
     * [W2-3] max_tokens 默认解析 · 显式传入优先；未显式传入时按 modelName 读 DB models.max_tokens
     * （models 表列，前端可配）。DB 未命中/无效 → null（不发送 max_tokens，回落模型缺省）。
     *
     * @param modelName         模型名（可 null）
     * @param explicitMaxTokens 调用方显式传入的 max_tokens（非 null → 直接采用）
     * @return 实际 max_tokens；null = 不发送（回落模型缺省）
     */
    private Integer resolveDefaultMaxTokens(String modelName, Integer explicitMaxTokens) {
        if (explicitMaxTokens != null) {
            return explicitMaxTokens;
        }
        Integer dbMaxTokens = ModelNameResolver.resolveMaxTokens(modelMapper, providerMapper, modelName);
        if (dbMaxTokens != null) {
            if (log.isDebugEnabled()) {
                log.debug("[OpenAiSdkProvider] max_tokens 未显式传入, DB 命中默认: model={} maxTokens={}（models.max_tokens 前端可配）",
                    modelName, dbMaxTokens);
            }
            return dbMaxTokens;
        }
        if (log.isDebugEnabled()) {
            log.debug("[OpenAiSdkProvider] max_tokens 未显式传入且 DB 未命中/无效, 不发送（回落模型缺省）: model={}",
                modelName);
        }
        return null;
    }

    /** [WF3-04 explainer] tool_choice 投影 · CC {type:'tool', name} → OpenAI named function choice。 */
    private static ChatCompletionCreateParams applyToolChoice(
            ChatCompletionCreateParams params, LlmProvider.ChatRequestOptions options) {
        LlmProvider.ChatRequestOptions.ToolChoice tc = options != null ? options.toolChoice() : null;
        if (tc == null || tc.name() == null || tc.name().isBlank()) {
            return params;
        }
        return params.toBuilder()
            .toolChoice(ChatCompletionToolChoiceOption.ofNamedToolChoice(
                ChatCompletionNamedToolChoice.builder()
                    .function(ChatCompletionNamedToolChoice.Function.builder()
                        .name(tc.name())
                        .build())
                    .build()))
            .build();
    }

    /** [WF3-04 explainer] 非流式响应 tool_calls 提取 · 对齐 CC sideQuery content 中 tool_use 块。 */
    private static List<ToolUseBlock> extractToolCalls(ChatCompletion resp) {
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
            return List.of();
        }
        var choice = resp.choices().get(0);
        if (choice == null || choice.message() == null) {
            return List.of();
        }
        var toolCallsOpt = choice.message().toolCalls();
        if (toolCallsOpt == null || toolCallsOpt.isEmpty()) {
            return List.of();
        }
        List<ToolUseBlock> blocks = new ArrayList<>();
        for (ChatCompletionMessageToolCall tc : toolCallsOpt.get()) {
            if (tc == null || tc.id() == null || tc.id().isBlank()
                || tc.function() == null || tc.function().name() == null
                || tc.function().name().isBlank()) {
                continue;
            }
            String args = tc.function().arguments();
            JsonNode input;
            try {
                input = (args == null || args.isBlank()) ? JSON.createObjectNode() : JSON.readTree(args);
            } catch (Exception e) {
                input = JSON.createObjectNode();
            }
            blocks.add(new ToolUseBlock(tc.id(), tc.function().name(), input));
        }
        return blocks;
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * 构建 OpenAI SDK client · 对齐 CC {@code maxRetries: 0}（claude.ts:1781，
     * "Disabled auto-retry in favor of manual implementation"）+ Anthropic 先例。
     *
     * <p>每次请求按 {@link ProviderConfig} 构建（与 AnthropicSdkProvider 先例同构）。
     */
    static OpenAIClient buildClient(ProviderConfig config) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
            .apiKey(config.apiKey())
            // [CC claude.ts:1781] Disabled auto-retry in favor of manual implementation
            .maxRetries(0);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(normalizeBaseUrl(config.baseUrl()));
        }
        return builder.build();
    }

    /** [AM-CC-20260825] SDK model 名剥 provider 前缀（resolveSdkModelName，未命中回落）·
     *  deepseek/deepseek-v4-flash → deepseek-v4-flash（API 400「supported API model names are
     *  deepseek-v4-pro...」修复，同 ModelCaller/classifier；2026-08-25 摘要/explainer 实测）。
     *  仅影响 API model 参数；DB 解析（maxTokens/effort）用原始 modelName。 */
    private String sdkModelName(String modelName) {
        if (modelConfigResolver == null || modelName == null) {
            return modelName;
        }
        String sdk = modelConfigResolver.resolveSdkModelName(modelName);
        return sdk != null ? sdk : modelName;
    }

    /**
     * [OpenAI-SDK T-OA-06] 构建 ChatCompletionCreateParams · 供 stream/chat/chatWithRaw/
     * chatWithOptions 共用 · public static 供测试驱动（与 Anthropic buildMessageParams 同构）。
     *
     * @param outputFormatSchema 非 null → response_format json_schema（name='hook' 占位）
     * @param thinkingDisabled   true → thinking:{type:'disabled'}
     * @param temperature        非 null → temperature
     * @param effortValue        非 null → resolveAppliedEffort + mapToOpenAiReasoningEffort + 模型门控注入 reasoning_effort
     * @param maxTokens          非 null → max_tokens（CC sideQuery max_tokens:256）
     */
    public static ChatCompletionCreateParams buildRequestParams(String modelName,
                                                         String systemPrompt,
                                                         List<ChatMessageDto> history,
                                                         ArrayNode tools,
                                                         JsonNode outputFormatSchema,
                                                         boolean thinkingDisabled,
                                                         Double temperature,
                                                         String effortValue,
                                                         Integer maxTokens) {
        return buildRequestParams(modelName, systemPrompt, history, tools, outputFormatSchema,
            thinkingDisabled, temperature, effortValue, maxTokens, false);
    }

    /**
     * [DEC-04] 带 includeUsage 的 buildRequestParams · 10-param 主实现。
     *
     * <p>流式路径传 {@code includeUsage=true} 写入 {@code stream_options.include_usage}
     * （OpenAI streaming 默认不返回 usage，需显式开启）；非流式路径传 false（stream_options 对
     * non-streaming 无效）。CC 侧 Anthropic 流式 usage 恒返回，本 flag 为 OpenAI 协议等价。
     */
    public static ChatCompletionCreateParams buildRequestParams(String modelName,
                                                         String systemPrompt,
                                                         List<ChatMessageDto> history,
                                                         ArrayNode tools,
                                                         JsonNode outputFormatSchema,
                                                         boolean thinkingDisabled,
                                                         Double temperature,
                                                         String effortValue,
                                                         Integer maxTokens,
                                                         boolean includeUsage) {
        ChatCompletionCreateParams.Builder b = ChatCompletionCreateParams.builder()
            .model(modelName == null ? "" : modelName);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            b.addSystemMessage(systemPrompt);
        }
        if (history != null) {
            for (ChatMessageDto m : history) {
                ChatCompletionMessageParam param = toSdkMessage(m);
                if (param != null) {
                    b.addMessage(param);
                }
            }
        }
        if (tools != null && !tools.isEmpty()) {
            int added = 0;
            // [G4] strict 模型层门控（OpenAI · Java 多 provider 扩展 ⊕）：flag && model != null && 白名单。
            //   意图层（ToolRegistry）已把 flag && tool.strict() 写入 JSON strict 字段。
            boolean strictModelGate = StructuredOutputsSupport.shouldTransmitStrictOpenAi(modelName);
            for (JsonNode toolNode : tools) {
                ChatCompletionTool sdkTool = toOpenAiSdkTool(toolNode, strictModelGate);
                if (sdkTool != null) {
                    b.addTool(sdkTool);
                    added++;
                }
            }
            if (added > 0 && log.isDebugEnabled()) {
                log.debug("OpenAiSdkProvider: 已添加 {} 个 tool 到 SDK 请求", added);
            }
        }
        // [H13-GAP-3 v3] outputFormat json_schema → response_format（CC execPromptHook.ts:87-98）.
        //   OpenAI-compatible json_schema 需 name 字段（OpenAI API 要求）→ 'hook' 占位.
        // [2026-08-25 title 400 修复] DeepSeek 等模型不支持 json_schema response_format（400
        //   "This response_format type is unavailable now"）→ 退化 json_object（OpenAI 兼容标准，
        //   prompt 已含 JSON 约束，强制 JSON 输出）。仅 DeepSeek 受影响，其余模型保持 json_schema。
        if (outputFormatSchema != null) {
            if (supportsJsonSchemaResponseFormat(modelName)) {
                ResponseFormatJsonSchema.JsonSchema.Schema.Builder schemaB =
                    ResponseFormatJsonSchema.JsonSchema.Schema.builder();
                outputFormatSchema.fields().forEachRemaining(en ->
                    schemaB.putAdditionalProperty(en.getKey(), JsonValue.fromJsonNode(en.getValue())));
                b.responseFormat(ResponseFormatJsonSchema.builder()
                    .jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder()
                        .name("hook")
                        .schema(schemaB.build())
                        .build())
                    .build());
            } else {
                Map<String, Object> rf = new LinkedHashMap<>();
                rf.put("type", "json_object");
                b.putAdditionalBodyProperty("response_format", JsonValue.from(rf));
            }
        }
        // [H13-GAP-3 v3] thinkingConfig disabled → thinking:{type:'disabled'}（CC execPromptHook.ts:71）.
        if (thinkingDisabled) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", "disabled");
            b.putAdditionalBodyProperty("thinking", JsonValue.from(thinking));
        }
        // [P2-16] temperature（非 null 才发送，对齐 CC claude.ts:1717）
        if (temperature != null) {
            b.temperature(temperature);
        }
        // [IMP-M-P1-2] max_tokens 侧信道（null = 不发送，回落模型缺省）
        if (maxTokens != null) {
            b.maxTokens(maxTokens.longValue());
        }
        // [C-31] effort → OpenAI reasoning_effort 注入（Java 多 provider 扩展 ⊕ · CC original 无对应）
        String resolvedEffort = EffortSupport.resolveAppliedEffort(modelName, effortValue);
        String reasoningEffort = mapToOpenAiReasoningEffort(resolvedEffort);
        if (reasoningEffort != null && EffortSupport.modelSupportsEffort(modelName)) {
            b.reasoningEffort(ChatCompletionReasoningEffort.of(reasoningEffort));
            if (log.isDebugEnabled()) {
                log.debug("OpenAI 请求注入 reasoning_effort={} model={} · CC original 无对应，Java 多 provider 扩展 ⊕",
                    reasoningEffort, modelName);
            }
        }
        // [DEC-04] 流式 usage 采集: stream_options.include_usage=true → final chunk 携带 usage
        // （OpenAI 流式默认不返回 usage；对齐 CC Anthropic 流式恒返回。非流式传 false 不注入）
        if (includeUsage) {
            b.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        }
        return b.build();
    }

    /** [2026-08-25 title 400 修复] 模型是否支持 OpenAI {@code json_schema} response_format。DeepSeek 等
     *  国产模型不支持（400 "This response_format type is unavailable now"），须退化 json_object
     *  （prompt 已含 JSON 约束，强制 JSON 输出）。命中退化仅影响标题/memory/hook 的 openai_compatible
     *  调用点，主循环（不传 outputFormat）零影响。 */
    private static boolean supportsJsonSchemaResponseFormat(String modelName) {
        if (modelName == null) {
            return true;
        }
        String lower = modelName.toLowerCase();
        return !lower.contains("deepseek");
    }

    /**
     * [OpenAI-SDK T-OA-06] ChatMessageDto → SDK 消息数组 · public static 供测试驱动。
     * 对齐旧 OpenAiProvider.buildRequestBody 的 messages 序列化（assistant tool_calls 回放 /
     * user contentBlocks / tool acceptFeedback+text 块；[IT-6] structuredOutput 停发、
     * 由 ToolResultApplier 产出 structured_output attachment）。
     */
    public static List<ChatCompletionMessageParam> buildSdkMessages(List<ChatMessageDto> history) {
        List<ChatCompletionMessageParam> msgs = new ArrayList<>();
        if (history == null) return msgs;
        for (ChatMessageDto m : history) {
            ChatCompletionMessageParam param = toSdkMessage(m);
            if (param != null) msgs.add(param);
        }
        return msgs;
    }

    static ChatCompletionMessageParam toSdkMessage(ChatMessageDto m) {
        if (m == null || m.role() == null) return null;
        final String text = m.content() == null ? "" : m.content();
        return switch (m.role()) {
            case system -> {
                // [2026-08-15 error-recovery 对齐 CC normalizeMessagesForAPI:2066-2072] 非
                //   local_command system 消息出站过滤 —— CC 模型上下文永不包含 system 消息
                //   （normalizeMessagesForAPI 过滤 system，仅 local_command 转 user 保留）；
                //   ChatMessageDto 无 local_command 概念（全仓 grep 实证）→ 直接过滤返回
                //   null（buildRequestBody :604 / buildSdkMessages :682 调用侧已判空跳过）。
                //   系统提示本身走 buildRequestParams systemPrompt 参数（:598-600），不受影响。
                if (log.isDebugEnabled()) {
                    log.debug("对齐 CC normalizeMessagesForAPI:2066-2072：非 local_command system 消息出站过滤");
                }
                yield null;
            }
            case user -> {
                // [P-AL-01] role=user contentBlocks（isMeta document/image 送达 · CC
                //   createUserMessage({content: 块数组, isMeta:true})）→ content 数组渲染；
                //   无 contentBlocks → 维持 content 字符串（既有行为）
                if (m.contentBlocks() != null && !m.contentBlocks().isEmpty()) {
                    List<ChatCompletionContentPart> parts = new ArrayList<>();
                    for (Object blockObj : m.contentBlocks()) {
                        if (!(blockObj instanceof JsonNode block) || !block.isObject()) continue;
                        ChatCompletionContentPart part = toSdkUserContentPart(block);
                        if (part != null) parts.add(part);
                    }
                    if (!parts.isEmpty()) {
                        if (log.isDebugEnabled()) {
                            log.debug("OpenAiSdkProvider role=user contentBlocks 渲染: blocks={} parts={}",
                                m.contentBlocks().size(), parts.size());
                        }
                        yield ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder()
                                .contentOfArrayOfContentParts(parts)
                                .build());
                    }
                }
                yield ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(text)
                        .build());
            }
            case assistant -> {
                ChatCompletionAssistantMessageParam.Builder b =
                    ChatCompletionAssistantMessageParam.builder()
                        .content(text);
                // [OpenAI-SDK T-OA-06] assistant tool_calls 回放 · 关闭 R1（OpenAI 要求 tool 消息的
                //   tool_call_id 必须存在于前置 assistant 消息的 tool_calls —— 多轮 loop 下丢弃即 400）
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    List<ChatCompletionMessageToolCall> tcs = new ArrayList<>();
                    for (ToolCallDto tc : m.toolCalls()) {
                        if (tc == null || tc.id() == null || tc.id().isBlank()
                            || tc.name() == null || tc.name().isBlank()) {
                            continue;
                        }
                        tcs.add(ChatCompletionMessageToolCall.builder()
                            .id(tc.id())
                            .type(JsonValue.from("function"))
                            .function(ChatCompletionMessageToolCall.Function.builder()
                                .name(tc.name())
                                .arguments(tc.arguments() == null ? "{}" : tc.arguments())
                                .build())
                            .build());
                    }
                    if (!tcs.isEmpty()) {
                        b.toolCalls(tcs);
                    }
                }
                yield ChatCompletionMessageParam.ofAssistant(b.build());
            }
            case tool -> {
                if (m.toolCallId() == null) {
                    log.warn("跳过缺少 toolCallId 的 tool 消息: 内容={}",
                        truncate(m.content(), 50));
                    yield null;
                }
                ChatCompletionToolMessageParam.Builder tb =
                    com.openai.models.ChatCompletionToolMessageParam.builder()
                        .toolCallId(m.toolCallId());
                // [R32-b9 / R32-b14] acceptFeedback + contentBlocks(text 块) → content 数组
                //   （独立 text part · Fix E 结构化注入）。
                //   [IT-6] structuredOutput 不再序列化（停发模型 · CC normalizeAttachmentForAPI
                //   structured_output→[], messages.ts:4258-4261）→ 载荷走 structured_output attachment
                //   通道（ToolResultApplier 产出），模型侧 content 回落纯文本。
                //   [OpenAI-SDK R-T-1] SDK 0.25.0 tool content 数组仅支持 ChatCompletionContentPartText
                //   → image/document 块跳过（受控残留，warn 日志）
                //   [X-1 / WF-8] tool_reference 块 N/A 登记：OpenAI 协议无 tool_reference 原生块
                //   （openai-java 0.25.0 content part 仅 text/image/input_audio/refusal）→ 跳过该块、
                //   回落 content 文本。CC original: stripToolReferenceBlocksFromUserMessage
                //   （Open-ClaudeCode/src/utils/messages.ts:1676-1720，tool search 禁用时剥离
                //   tool_reference 并回落占位文本）语义等价。OpenAI 路径 ToolSearchTool 恒不进工具列表，
                //   本 turn 不产生 tool_reference，此登记为跨 provider 历史导入边缘场景的防御性 N/A。
                boolean hasFeedback = m.acceptFeedback() != null && !m.acceptFeedback().isBlank();
                List<ChatCompletionContentPartText> parts = null;
                if (hasFeedback || hasTextBlock(m.contentBlocks())) {
                    parts = new ArrayList<>();
                    if (!text.isEmpty()) {
                        parts.add(ChatCompletionContentPartText.builder().text(text).build());
                    }
                    if (hasFeedback) {
                        parts.add(ChatCompletionContentPartText.builder().text(m.acceptFeedback()).build());
                    }
                    if (m.contentBlocks() != null) {
                        for (Object blockObj : m.contentBlocks()) {
                            if (!(blockObj instanceof JsonNode block) || !block.isObject()) continue;
                            String btype = block.has("type") ? block.get("type").asText() : null;
                            if ("text".equals(btype)) {
                                parts.add(ChatCompletionContentPartText.builder()
                                    .text(block.has("text") ? block.get("text").asText() : "")
                                    .build());
                            } else if ("tool_reference".equals(btype)) {
                                // [X-1 / WF-8] N/A 登记：OpenAI 协议无 tool_reference 原生块
                                // （openai-java 0.25.0 content part 仅 text/image/input_audio/refusal），
                                // 跳过该块并回落 content 文本 —— CC stripToolReferenceBlocksFromUserMessage
                                // （Open-ClaudeCode/src/utils/messages.ts:1676-1720）语义等价。
                                // 仅在混合 content（tool_reference + text）场景可达；纯 tool_reference
                                // tool 消息经 hasTextBlock 门控回落标量 text，同样不产生 tool_reference 字段。
                                if (log.isDebugEnabled()) {
                                    log.debug("OpenAiSdkProvider role=tool 跳过 tool_reference 块（OpenAI 协议无 tool_reference 原生块 · 回退 content 文本 · N/A 登记）tool_name={}",
                                        block.has("tool_name") ? block.get("tool_name").asText() : null);
                                }
                            } else {
                                log.warn("OpenAiSdkProvider role=tool 跳过 {} 块（SDK 0.25.0 tool content 仅支持 text part · R-T-1 受控残留）",
                                    btype);
                            }
                        }
                    }
                }
                if (parts != null) {
                    tb.contentOfArrayOfContentParts(parts);
                } else {
                    tb.content(text);
                }
                yield ChatCompletionMessageParam.ofTool(tb.build());
            }
        };
    }

    /** [P-AL-01] user contentBlocks 单块 → SDK content part · image→image_url / text→text；
     *  document→null（[OpenAI-SDK R-U-1] SDK 0.25.0 无 document part，受控残留）。 */
    static ChatCompletionContentPart toSdkUserContentPart(JsonNode block) {
        String btype = block.has("type") ? block.get("type").asText() : null;
        if ("text".equals(btype)) {
            return ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                .text(block.has("text") ? block.get("text").asText() : "")
                .build());
        }
        if ("image".equals(btype)) {
            String url = resolveImageUrl(block);
            if (url != null) {
                return ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                    .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                        .url(url)
                        .build())
                    .build());
            }
        }
        log.warn("OpenAiSdkProvider role=user 跳过 {} 块（SDK 0.25.0 无对应 content part · R-U-1 受控残留）",
            btype);
        return null;
    }

    /** 迁移自 OpenAiProvider.appendContentPart image 分支 · CC {type:'image', source:{...}} → OpenAI image_url.url. */
    static String resolveImageUrl(JsonNode block) {
        if (block.has("url") && block.get("url").isTextual()) {
            return block.get("url").asText();
        }
        JsonNode source = block.get("source");
        if (source != null) {
            if (source.has("url") && source.get("url").isTextual()) {
                return source.get("url").asText();
            }
            if (source.has("data") && source.has("media_type")) {
                return "data:" + source.get("media_type").asText()
                    + ";base64," + source.get("data").asText();
            }
            if (source.isTextual()) {
                return source.asText();
            }
        }
        return null;
    }

    private static boolean hasTextBlock(List<?> contentBlocks) {
        if (contentBlocks == null) return false;
        for (Object blockObj : contentBlocks) {
            if (blockObj instanceof JsonNode block && block.isObject()
                && "text".equals(block.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    /** OpenAI tools JSON → SDK ChatCompletionTool。
     *  @param strictModelGate [G4] 模型层门控结果（flag && model != null && 白名单，
     *     由 buildRequestParams 循环计算 · CC api.ts:185-192 模型层语义）。
     *  <p>[H4] wrapper 顶层 {@code defer_loading}（CC api.ts:223-225）OpenAI 兼容端点
     *  无对应概念 → 本方法仅读 type/function，天然忽略该字段（不报错、不透传）；
     *  与 CC 3P header（Vertex/Bedrock）N/A 同一登记项。 */
    static ChatCompletionTool toOpenAiSdkTool(JsonNode toolNode, boolean strictModelGate) {
        if (toolNode == null || !toolNode.isObject()) {
            log.warn("OpenAiSdkProvider: 跳过非对象 tool 条目: {}",
                truncate(toolNode == null ? "null" : toolNode.toString(), 80));
            return null;
        }
        try {
            JsonValue typeJson = toolNode.has("type") && toolNode.get("type") != null
                ? JsonValue.fromJsonNode(toolNode.get("type"))
                : JsonValue.from("function");

            JsonNode fnNode = toolNode.get("function");
            if (fnNode == null || !fnNode.isObject()) {
                log.warn("OpenAiSdkProvider: tool 缺少 'function' 对象: {}",
                    truncate(toolNode.toString(), 80));
                return null;
            }
            JsonNode nameNode = fnNode.get("name");
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
                log.warn("OpenAiSdkProvider: tool function 缺少 'name': {}",
                    truncate(toolNode.toString(), 80));
                return null;
            }

            FunctionDefinition.Builder fnBuilder = FunctionDefinition.builder()
                .name(nameNode.asText());
            JsonNode descNode = fnNode.get("description");
            if (descNode != null && descNode.isTextual()) {
                fnBuilder.description(descNode.asText());
            }

            JsonNode paramsNode = fnNode.get("parameters");
            if (paramsNode != null && paramsNode.isObject()) {
                Map<String, JsonValue> paramsMap = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> it = paramsNode.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    paramsMap.put(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
                }
                fnBuilder.parameters(
                    FunctionParameters.builder().additionalProperties(paramsMap).build());
            }

            // [G4] strict 透传：JSON strict:true（意图层 flag && tool.strict()）且模型层门控通过 → SDK .strict(true)。
            //   门控失败静默降级不传（防不支持 strict 的模型/网关 400 · CC api.ts:185-192 语义）。
            boolean strictMarked = fnNode.has("strict") && fnNode.get("strict").isBoolean()
                && fnNode.get("strict").asBoolean(false);
            if (strictMarked && strictModelGate) {
                fnBuilder.strict(true);
                if (log.isDebugEnabled()) {
                    log.debug("OpenAiSdkProvider.toOpenAiSdkTool: tool '{}' 透传 strict=true（模型层门控通过）",
                        nameNode.asText());
                }
            }

            return ChatCompletionTool.builder()
                .type(typeJson)
                .function(fnBuilder.build())
                .build();
        } catch (Exception e) {
            log.warn("OpenAiSdkProvider: 转换 tool 条目失败 {} (已跳过): {}",
                truncate(toolNode.toString(), 200), e.getMessage(), e);
            return null;
        }
    }

    static String extractContent(ChatCompletion resp) {
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) return "";
        var choice = resp.choices().get(0);
        if (choice == null || choice.message() == null) return "";
        var contentOpt = choice.message().content();
        return contentOpt == null || contentOpt.isEmpty() ? "" : contentOpt.get();
    }

    /**
     * [IMP-SUB-26 A6] 非流式 ChatCompletion 响应 usage 解析 · CC original: message.usage
     * (agentToolUtils.ts:238-256, 7 子字段) · 对齐 CC 非流式 usage 透传（claude.ts:870-903）。
     *
     * <p>映射与流式 {@code parseChunk} 一致：OpenAI {@code prompt_tokens} → inputTokens、
     * {@code completion_tokens} → outputTokens、{@code prompt_tokens_details.cached_tokens} →
     * cacheReadInputTokens（OpenAI cache read 等价）。OpenAI 无 server_tool_use / service_tier /
     * cache_creation 等价 → 嵌套 3 字段 null（如实暴露缺口，S4-2b，不伪造）。
     *
     * <p>响应缺 usage 字段（{@code Optional.empty}）→ 返回 null，由 {@link AssistantMessage}
     * 规范化为 {@code AgentUsage.EMPTY} 零初始化哨兵（对齐 CC emptyUsage.ts:8）。
     *
     * @param resp 非流式 ChatCompletion 响应（可 null）
     * @return AgentUsage；resp 或 usage 缺失 → null（AssistantMessage 归一化 EMPTY）
     */
    static AgentUsage extractUsage(ChatCompletion resp) {
        if (resp == null || resp.usage() == null || resp.usage().isEmpty()) {
            return null;
        }
        var u = resp.usage().get();
        long cacheRead = 0L;
        long cacheCreation = 0L;
        // [AM-CC-20260825] DeepSeek/openai-compatible 顶层 cache 字段（prompt_cache_hit_tokens /
        //   prompt_cache_miss_tokens）——OpenAI 标准 usage 无 cache_creation 等价，DeepSeek 用这两个
        //   字段表达缓存读/写（2026-08-25 用户提供实际响应：{prompt_cache_hit_tokens:0,
        //   prompt_cache_miss_tokens:10}）→ 映射 cacheReadInputTokens / cacheCreationInputTokens。
        var addProps = u._additionalProperties();
        if (addProps != null) {
            cacheRead = jsonValueLong(addProps.get("prompt_cache_hit_tokens"));
            cacheCreation = jsonValueLong(addProps.get("prompt_cache_miss_tokens"));
        }
        // OpenAI 标准 prompt_tokens_details.cached_tokens 作为 cacheRead 兜底（hit==0 且标准字段存在）
        if (cacheRead == 0L && u.promptTokensDetails() != null && u.promptTokensDetails().isPresent()
            && u.promptTokensDetails().get().cachedTokens() != null
            && u.promptTokensDetails().get().cachedTokens().isPresent()) {
            cacheRead = u.promptTokensDetails().get().cachedTokens().get();
        }
        if (log.isDebugEnabled()) {
            log.debug("OpenAiSdkProvider 非流式 usage: input={} output={} cacheRead={} cacheCreation={} · CC message.usage 透传 (claude.ts:870-903)",
                u.promptTokens(), u.completionTokens(), cacheRead, cacheCreation);
        }
        return new AgentUsage(u.promptTokens(), u.completionTokens(), cacheCreation, cacheRead, null, null, null);
    }

    /** [AM-CC-20260825] JsonValue → long（DeepSeek cache 字段读取；extractThinking 同款 toString 模式）·
     *  数值原样解析；字符串剥引号；非法 → 0。 */
    private static long jsonValueLong(JsonValue v) {
        if (v == null) {
            return 0L;
        }
        String s = v.toString();
        if (s != null && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s == null || s.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** [M3.2] 从 ChatCompletion message._additionalProperties() 按配置优先级提取 reasoning 字段（DeepSeek R1）.
     *  非 static（依赖实例 {@link #properties} 的推理字段配置）。 */
    String extractThinking(ChatCompletion resp) {
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) return null;
        var choice = resp.choices().get(0);
        if (choice == null || choice.message() == null) return null;
        var addProps = choice.message()._additionalProperties();
        if (addProps == null) return null;
        for (String field : properties.getOpenaiReasoningField()) {
            JsonValue v = addProps.get(field);
            if (v != null) {
                String rc = v.toString();
                if (rc != null && rc.length() > 2 && rc.startsWith("\"") && rc.endsWith("\"")) {
                    rc = rc.substring(1, rc.length() - 1);
                }
                if (rc != null && !rc.isEmpty()) {
                    return rc;
                }
            }
        }
        return null;
    }

    /** [OpenAI-SDK T-OA-07] openai-java 异常 → LlmApiException（R27-6 类型化分类 · Kind.IMAGE 判定）。 */
    static RuntimeException translateSdkError(Throwable e) {
        if (e instanceof OpenAIServiceException se) {
            int status = se.statusCode();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            try {
                for (String name : se.headers().names()) {
                    headers.put(name, se.headers().values(name));
                }
            } catch (Exception ignore) {
                // header 转换失败不阻断错误上浮
            }
            String body = se.body() == null ? "" : se.body();
            return LlmApiException.isImageErrorBody(body)
                ? LlmApiException.imageError(status, headers, body)
                : new LlmApiException(status, headers, body);
        }
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(e);
    }

    /** 单条 user 消息（chatWithRaw / chatWithOptions 追加 userMessage 用）。 */
    private static ChatMessageDto newUserMessage(String content) {
        return new ChatMessageDto(
            null, null, Role.user, null, content,
            null, null, null, null, null, null, null, null, null,
            null, java.util.List.of(), java.util.List.of());
    }

    /**
     * 解析一个 ChatCompletionChunk → 累积到 state + 触发回调。
     * 推理字段从 {@link NexusProperties#getOpenaiReasoningField()} 读取。
     */
    void parseChunk(ChatCompletionChunk chunk,
                    OpenAiStreamState state,
                    Consumer<String> onChunk,
                    Consumer<ToolUseBlock> onToolCallComplete,
                    Consumer<String> onReasoningChunk,
                    java.util.Set<String> completedToolIds) {
        if (chunk == null) {
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("流式请求返回：{}", JSONUtil.toJsonStr(chunk));
        }
        // [DEC-04] final chunk usage → AgentUsage 数据源（stream_options.include_usage=true 时出现；
        //   OpenAI SDK 以 Optional<CompletionUsage> 表达，缺失 = 该 chunk 无 usage）
        var chunkUsage = chunk.usage();
        if (chunkUsage != null && chunkUsage.isPresent()) {
            var u = chunkUsage.get();
            state.inputTokens = u.promptTokens();
            state.outputTokens = u.completionTokens();
            // [B2-R1/R2] DeepSeek/openai-compatible 顶层 cache 字段（prompt_cache_hit_tokens /
            //   prompt_cache_miss_tokens）——镜像非流式 extractUsage (:1062-1078) 的 additionalProperties
            //   读取：hit→cacheRead、miss→cacheCreation。SDK CompletionUsage 以 _additionalProperties()
            //   承载未类型化字段（openai-java 0.25.0），与 extractUsage 同源实现。
            var addProps = u._additionalProperties();
            if (addProps != null) {
                state.cacheReadInputTokens = jsonValueLong(addProps.get("prompt_cache_hit_tokens"));
                state.cacheCreationInputTokens = jsonValueLong(addProps.get("prompt_cache_miss_tokens"));
            }
            // OpenAI 标准 prompt_tokens_details.cached_tokens 作为 cacheRead 兜底（hit==0 且标准字段存在）
            if (state.cacheReadInputTokens == 0L && u.promptTokensDetails() != null
                && u.promptTokensDetails().isPresent()
                && u.promptTokensDetails().get().cachedTokens() != null
                && u.promptTokensDetails().get().cachedTokens().isPresent()) {
                state.cacheReadInputTokens = u.promptTokensDetails().get().cachedTokens().get();
            }
            if (log.isDebugEnabled()) {
                log.debug("OpenAiSdkProvider final chunk usage: input={} output={} cacheRead={} cacheCreation={} · CC message.usage 数据源对齐（B2 流式 cache 读取）",
                    state.inputTokens, state.outputTokens, state.cacheReadInputTokens, state.cacheCreationInputTokens);
            }
        }
        for (var choice : chunk.choices()) {
            if (choice == null || choice.delta() == null) continue;
            var delta = choice.delta();
            // content
            String content = delta.content().isEmpty()
                ? null : delta.content().get();
            if (StrUtil.isNotBlank(content)) {
                state.content.append(content);
                if (onChunk != null) {
                    onChunk.accept(content);
                }
            }

            // reasoning_content (DeepSeek R1 等)
            // SDK delta 不暴露 reasoning 字段 (需 _additionalProperties 取)
            if (onReasoningChunk != null) {
                var addProps = delta._additionalProperties();
                for (String key : properties.getOpenaiReasoningField()) {
                    var reasoningContent = addProps.get(key);
                    if (Objects.nonNull(reasoningContent)) {
                        String rc = reasoningContent.toString();
                        if (rc != null && rc.length() > 2 && rc.startsWith("\"") && rc.endsWith("\"")) {
                            rc = rc.substring(1, rc.length() - 1);
                        }
                        // [联调修复 2026-08-24] 过滤 "null" 字面串（deepseek thinking 边界输出 JSON null，
                        //   原样推前端会被 cleanReasoning/replace(null) 清空 → 推理流内容空）
                        if (rc != null && !rc.isEmpty() && !"null".equals(rc)) {
                            state.reasoning.append(rc);
                            onReasoningChunk.accept(rc);
                            break;
                        }
                    }
                }
            }

            // tool_calls · openai-java 0.25.0 toolCalls() 是 @JsonProperty 类型化字段，绝不含于
            //   _additionalProperties（旧实现 delta._additionalProperties().get("tool_calls") 恒 null →
            //   流式 tool_calls 全丢弃 → 主循环 toolCalls=0 纯文本 NORMAL 退出）。修复改读 typed 字段。
            Optional<List<ChatCompletionChunk.Choice.Delta.ToolCall>> typedCalls = delta.toolCalls();
            if (typedCalls.isPresent()) {
                for (var tc : typedCalls.get()) {
                    int idx = (int) tc.index();
                    OpenAiToolCallAccumulator toolCall = state.toolCalls.computeIfAbsent(idx, k -> {
                        OpenAiToolCallAccumulator tca = new OpenAiToolCallAccumulator();
                        tca.index = k;
                        return tca;
                    });
                    tc.id().ifPresent(id -> toolCall.id = id);
                    if (tc.type().isPresent()) {
                        toolCall.type = tc.type().get().toString();
                    }
                    tc.function().ifPresent(fn -> {
                        fn.name().ifPresent(n -> toolCall.name = n);
                        fn.arguments().ifPresent(a -> toolCall.args += a);
                    });
                }
            }
            // per-tool 实时回调
            if (onToolCallComplete != null && completedToolIds != null) {
                for (OpenAiToolCallAccumulator acc : state.toolCalls.values()) {
                    if (acc.isComplete() && completedToolIds.add(acc.id)) {
                        try {
                            onToolCallComplete.accept(acc.toBlock());
                        } catch (Throwable t) {
                            log.warn("onToolCallComplete 回调抛出异常: {}", t.toString());
                        }
                    }
                }
            }
            // finish_reason
            if (choice.finishReason().isPresent()) {
                state.finishReason = choice.finishReason().get().toString();
            }
        }
        // [fix-toolcalls-400 A-2] 流结束补发空参/残缺参 tool_call（finish_reason 置位后）
        //   WHY: isComplete() 无法区分"空参工具"（arguments:""）与"参数块尚未到达"（with-args
        //   工具的 chunk1 就是 arguments:""）。若直接放宽 isComplete 会把带参工具提前回调成空参
        //   （completedToolIds 守卫后永不补发）；只有 finish_reason 才可断定无后续参数块 → 此时
        //   对本轮已具 id+name 但参数仍未完整的 accumulator 补发（对齐 CC 无参 tool_use 照常进执行器，
        //   AnthropicSdkProvider:2813 宽松语义）。toBlock() 对 "" 已有空对象兜底。completedToolIds.add
        //   守卫保证不双发（A-1 已发过的进不了）。
        if (state.finishReason != null && onToolCallComplete != null && completedToolIds != null) {
            for (OpenAiToolCallAccumulator acc : state.toolCalls.values()) {
                boolean hasIdentity = acc.id != null && !acc.id.isEmpty()
                    && acc.name != null && !acc.name.isEmpty();
                if (hasIdentity && !acc.isComplete() && completedToolIds.add(acc.id)) {
                    onToolCallComplete.accept(acc.toBlock());
                    if (log.isDebugEnabled()) {
                        log.debug("OpenAiSdkProvider 流结束补发空参 tool_call: id={} name={} finishReason={} · 对齐 CC 无参 tool_use 进执行器",
                            acc.id, acc.name, state.finishReason);
                    }
                }
            }
        }
    }

    /** 把 state 打包成 AssistantMessage（流结束时调用）。 */
    AssistantMessage buildAssistantMessage(OpenAiStreamState state) {
        List<ToolUseBlock> blocks = new ArrayList<>(state.toolCalls.size());
        for (OpenAiToolCallAccumulator acc : state.toolCalls.values()) {
            blocks.add(acc.toBlock());
        }
        // [DEC-04] usage 全字段透传（OpenAI prompt/completion/cached → CC input/output/cache_read）
        // [B2-R1/R2] cache_creation_input_tokens 现由 DeepSeek prompt_cache_miss_tokens 填充
        //   （非流式 extractUsage 已于 2026-08-25 接入，本处补流式对称）。
        // [R32-06] OpenAI usage 无 server_tool_use / service_tier / cache_creation 等价 → 嵌套 3 字段
        //   恒 null, 如实暴露缺口 (S4-2b), 不伪造 (对齐 CC agentToolUtils.ts:243-255 仅 Anthropic 有)
        AgentUsage usage = new AgentUsage(
            state.inputTokens, state.outputTokens, state.cacheCreationInputTokens, state.cacheReadInputTokens,
            null, null, null); // server_tool_use / service_tier / cache_creation 无 OpenAI 等价 → null (S4-2b)
        if (log.isDebugEnabled()) {
            log.debug("OpenAiSdkProvider 流式完成 usage: {} · CC message.usage 透传", usage);
        }
        if (log.isDebugEnabled()) {
            log.debug("OpenAiSdkProvider 流式产出 message requestId={} · CC AssistantMessage.requestId 归因（DEC-RV-14a 兜底）",
                state.requestId);
        }
        return new AssistantMessage(
            state.content.toString(),
            state.finishReason == null ? "stop" : state.finishReason,
            blocks,
            state.reasoning.toString(),
            null,
            usage,
            state.requestId
        );
    }

    /**
     * CC EFFORT_LEVELS ['low','medium','high','max'] → OpenAI {@code reasoning_effort}
     * （仅认 low/medium/high）。
     *
     * <p><b>Java 多 provider 扩展（⊕）· CC original 无对应</b> · 迁移自旧 OpenAiProvider.
     *
     * @param resolvedEffort resolveAppliedEffort 解析后的 effort level（可 null）
     * @return OpenAI reasoning_effort 值或 null（不注入）
     */
    static String mapToOpenAiReasoningEffort(String resolvedEffort) {
        if (resolvedEffort == null) {
            return null;
        }
        switch (resolvedEffort) {
            case "low":
            case "medium":
            case "high":
                return resolvedEffort;
            case "max":
                return "high";
            default:
                return null;
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("baseUrl is empty");
        }
        String u = baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
