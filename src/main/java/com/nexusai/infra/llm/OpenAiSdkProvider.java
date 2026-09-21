package com.nexusai.infra.llm;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.lsp.PromptCacheBreakDetection;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseBlock;
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
 *       用请求侧自建 ID（原 = 裸 MDC 的 reqId 槽 = userMessageId；<b>[批 3c] 该载体已随批删除 ⇒ 本类
 *       现无可用的显式 requestId 来源，两处兜底改为 null（CC「无归因上下文」语义），待
 *       {@code LlmProvider.stream} / {@link #chatWithRaw} 增显式 requestId 载体后接线</b>）。</li>
 * </ul>
 *
 * <h2>推理字段</h2>
 * 推理字段名由 {@link NexusProperties#getOpenaiReasoningField()} 配置，不再硬编码。
 */
@Component
public class OpenAiSdkProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSdkProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * [批 3c] requestId 兜底「无显式来源 → 置 null」的首次告警闸（两个调用点各一）。
     *
     * <p>WHY：两条兜底路径都在热路径（每次流式 / 每次 chatWithRaw 调用）⇒ 逐调用 WARN 会刷屏；
     * 用一次性闸保证「禁只 DEBUG」（首次 WARN 披露，后续降 debug）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean STREAM_REQUEST_ID_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean CHAT_RAW_REQUEST_ID_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

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
                       Runnable onComplete,
                       Boolean skipCacheWrite,
                       com.nexusai.application.agent.subagent.AgentContext agentContext) {
        // [C] skipCacheWrite 签名跟随（wire 无 marker 语义 · openai-compatible 端点无 prompt cache
        //   条目写入移位的对应物 → 忽略，行为零改动）。CC 侧本参数只在 Anthropic 通道
        //   claude.ts:3243 markerIndex 消费。
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
                       Runnable onComplete,
                       Boolean skipCacheWrite,
                       com.nexusai.application.agent.subagent.AgentContext agentContext) {
        // [C] skipCacheWrite 签名跟随（openai-compatible 无 prompt cache marker 语义 → 忽略）
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
     * [CCJ-EXEC-08] blocks stream + thinkingConfig · blocks 连接后走
     * {@link #stream}（openai-compatible 端点 system 为 String 单值）。
     * ⚠️ 原文的形参表（17 项）与标题的「19-arg」都与本类现存重载（19 参 :117 / 20 参 :208）不符，
     * 且类型列表也漂移（真实首段为 {@code List<SystemPromptBlock>}）⇒ 取<b>不写形参表</b>的
     * 合法形态指向 stream 方法族，⛔ 不臆造某个具体重载。
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
                       Runnable onComplete,
                       Boolean skipCacheWrite,
                       com.nexusai.application.agent.subagent.AgentContext agentContext) {
        // [C] skipCacheWrite 签名跟随（openai-compatible 无 prompt cache marker 语义 → 忽略）
        String joined = systemPromptBlocks == null ? null : systemPromptBlocks.stream()
            .filter(java.util.Objects::nonNull)
            .map(SystemPromptBlock::text)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.joining("\n\n"));
        stream(config, modelName, joined, history, tools, maxOutputTokensOverride, taskBudget,
            effortValue, thinkingConfig, onChunk, onAssistantMessage, onToolCallComplete,
            onReasoningChunk, onStreamingFallback, abortController, onError, onComplete,
            skipCacheWrite, agentContext);   // [A#3] 显式归因上下文透传（OpenAI 侧不发射该边，签名跟随）
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
            // [provider-custom-headers 任务 7] 主链 sessionId 来源 = history（DB 真值，必中）。
            //   ⚠️ 刻意**不**在此处兜底环境态会话槽（裸 MDC，已随批 3c 删除）；该类槽可能残留别会话 id，
            //   详见 SessionIdResolver 类 javadoc 与 ProviderSessionIdWiringGuardTest 的接线级护栏。
            //   （护栏按**字面量**判，故连注释里都不留该调用形态 —— 它正是被照抄的来源。）
            String sessionId = SessionIdResolver.resolve(history, null);
            OpenAIClient client = buildClient(config, sessionId);
            // [DEC-04] 流式请求开启 stream_options.include_usage=true → final chunk 携带 usage
            // （OpenAI streaming 默认不返回 usage；对齐 CC Anthropic 流式 always-on usage）
            ChatCompletionCreateParams params = buildRequestParams(
                sdkModelName(modelName), systemPrompt, history, tools,
                null, thinkingConfig != null && "disabled".equals(thinkingConfig.type()),
                null, effortValue, null,
                // includeUsage=true（流式 usage 采集）+ streamingMainChain=true（探针链标记：
                // 本路径是唯一会在 :343 打「响应」探针的路径 ⇒ 与响应行同桶，1:1 可对排）。
                true, true);

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
            //   响应侧 x-request-id 头不可达 → 与非流式 chatWithRaw 一致走请求侧自建 ID
            //   （旧实现 = 裸 MDC 的 reqId 槽，值源为 ChatService 入口写入的 userMessageId）
            //   · 子 agent invokingRequestId 归因值源（AgentTool.tsx:723/:778）。
            // [批 3c] 该 MDC 槽与载体已删，且 doStream 签名内**没有** requestId / userMessageId 形参或
            //   字段（上游 LlmProvider.stream 亦无）⇒ 按批规则「无显式来源不自行发明」置 null ——
            //   null 即 CC 的「无归因上下文」合法语义（同 AnthropicSdkProvider 无 request-id 头时的
            //   同一语义，见 AnthropicSdkProviderStreamRequestIdTest「无头 → null 对齐 ?? undefined」）。
            //   ⚠ 待接线（批 3c 登记项）：LlmProvider.stream 增显式 requestId 载体后由上游传入
            //   （源值 = state.lastUserMessageId()，与旧 MDC reqId 同源）。故本处 WARN 披露（禁只 DEBUG）。
            state.requestId = null;
            if (STREAM_REQUEST_ID_WARNED.compareAndSet(false, true)) {
                log.warn("OpenAiSdkProvider 流式 requestId 无显式来源（批 3c：裸 MDC 的 reqId 槽已删，"
                    + "doStream 无 requestId/userMessageId 形参）→ 置 null（CC 无归因上下文语义）；"
                    + "待 LlmProvider.stream 增显式载体后接线 —— 本条为首次告警，后续降为 debug");
            } else if (log.isDebugEnabled()) {
                log.debug("OpenAiSdkProvider 流式 requestId 仍置 null（首次告警已发出）");
            }
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
            // [前缀缓存头部探针 · 步骤 1] 响应侧 cacheRead 掉幅（与 [usage-push] 逐条对排）。
            //   sessionId 与出站探针同源（同一 history 经 SessionIdResolver 解析 ⇒ 同桶）。
            //   ⛔ 非流式 chatWithOptions* 不接此线：那条链路没有 [usage-push] 对排需求，
            //   且侧查询（标题/解释器/分类器）会把同会话的「上一条 input」基线踩脏。
            logHeadProbeResponse(sessionId, state.inputTokens,
                state.cacheReadInputTokens, state.cacheCreationInputTokens);
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
        // [A#3] chat() 入口不携带归因上下文（本批 12 个 terminal 发射点在 chatWithRaw /
        //   chatWithOptions / chatWithOptionsMessage），内部委托显式传 null。
        LlmRawResponse raw = chatWithRaw(config, modelName, systemPrompt, userMessage, null);
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
     *       DEC-RV-14a：请求侧自建 ID；<b>[批 3c] 原值源（裸 MDC 的 reqId 槽）已随批删除 ⇒ 本方法现无
     *       显式来源，按批规则置 null（CC「无归因上下文」语义），待形参增显式 requestId 载体</b>）</li>
     * </ul>
     */
    @Override
    public LlmRawResponse chatWithRaw(ProviderConfig config,
                                      String modelName,
                                      String systemPrompt,
                                      String userMessage,
                                      com.nexusai.application.agent.subagent.AgentContext agentContext) {
        if (config == null || !config.isUsable()) {
            throw new IllegalStateException(
                "OpenAiSdkProvider.chatWithRaw 调用时 ProviderConfig 不可用");
        }
        try {
            // [provider-custom-headers 任务 7] chatWithRaw **既没有 history 也没有 options**
            //   （自造历史里 sessionId 恒 null）→ resolve(null, null) 恒 null → 占位符落兜底常量。
            //   ⚠️ 不要为了"好看"去翻 MDC —— 见 SessionIdResolver 类 javadoc。
            String sessionId = SessionIdResolver.resolve(null, null);
            OpenAIClient client = buildClient(config, sessionId);
            ChatCompletionCreateParams params = buildRequestParams(
                sdkModelName(modelName), systemPrompt,
                userMessage == null ? List.of() : List.of(newUserMessage(userMessage)),
                null, null, false, null, null, null);
            ChatCompletion resp = client.chat().completions().create(params);

            String content = extractContent(resp);
            String responseId = resp.id() == null || resp.id().isBlank() ? null : resp.id();
            String thinking = extractThinking(resp);
            // [OpenAI-SDK] R-REQ-1 兜底（DEC-RV-14a）· SDK 0.25.0 无法提取 x-request-id →
            // 响应 requestId 恒 null，原改用请求侧自建 ID（裸 MDC 的 reqId 槽 = ChatService 的
            // userMessageId）作兜底 · 对齐 CC extractRequestId 语义（请求侧追踪 ID，非 message id；
            // 响应 id 已进 LlmRawResponse.id = stageMsgId）。
            // [批 3c] 该 MDC 槽与载体已删，且 chatWithRaw 的形参（config/model/systemPrompt/userMessage/
            //   agentContext）里没有 requestId / userMessageId；`agentContext.invokingRequestId` 是
            //   **调用方**（spawn/resume 方）的 request_id，与「本请求 userMessageId」不是同一值 ⇒
            //   按批规则「无显式来源不自行发明」置 null（CC 无归因上下文语义）。
            //   ⚠ 待接线（批 3c 登记项）：ChatRequestOptions / chatWithRaw 增显式 requestId 载体。
            String fallbackReqId = null;
            if (CHAT_RAW_REQUEST_ID_WARNED.compareAndSet(false, true)) {
                log.warn("OpenAiSdkProvider.chatWithRaw requestId 无显式来源（批 3c：裸 MDC 的 reqId 槽已删，"
                    + "形参无 requestId/userMessageId）→ 置 null（CC 无归因上下文语义）"
                    + "—— 本条为首次告警，后续降为 debug");
            } else if (log.isDebugEnabled()) {
                log.debug("OpenAiSdkProvider.chatWithRaw requestId 仍置 null（首次告警已发出）");
            }
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

            // [provider-custom-headers 任务 7] chatWithOptions 系列：签名里没有 history 形参，
            //   ② 级来源 = options.history()（第 ① 级结构上不可达）。options 为 null → null → 落常量。
            //   ⚠️ 不读 MDC（见 SessionIdResolver 类 javadoc）。
            String sessionId = SessionIdResolver.resolve(null,
                options != null ? options.history() : null);
            OpenAIClient client = buildClient(config, sessionId);
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

            // [provider-custom-headers 任务 7] chatWithOptions 系列：签名里没有 history 形参，
            //   ② 级来源 = options.history()（第 ① 级结构上不可达）。options 为 null → null → 落常量。
            //   ⚠️ 不读 MDC（见 SessionIdResolver 类 javadoc）。
            String sessionId = SessionIdResolver.resolve(null,
                options != null ? options.history() : null);
            OpenAIClient client = buildClient(config, sessionId);
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
     * 单参重载：会话上下文缺失时用（OpenAI 侧目前无外部调用点，保留作降级安全网）。
     *
     * <p>仍会带上 {@code extraHeaders}（占位符落兜底常量）——<b>有意的降级安全网</b>：
     * 即便某调用点漏接 sessionId，自定义 header 也不会整体丢失。但 4 个真实调用点必须逐个显式
     * 传入解析出的 sessionId（见 {@link #buildClient(ProviderConfig, String)}），否则主链白白丢失缓存亲和。
     */
    static OpenAIClient buildClient(ProviderConfig config) {
        return buildClient(config, null);
    }

    /**
     * 构建 OpenAI SDK client · 对齐 CC {@code maxRetries: 0}（claude.ts:1781，
     * "Disabled auto-retry in favor of manual implementation"）+ Anthropic 先例。
     *
     * <p>每次请求按 {@link ProviderConfig} 构建（与 AnthropicSdkProvider 先例同构）。
     *
     * <p>[provider-custom-headers 任务 7] 按 provider 的 {@code extraHeaders} 注入自定义请求头 ——
     * 展开/敏感头过滤统一走 {@link ProviderHeaderInjector}（与 AnthropicSdkProvider 共用同一判据，
     * 不是两套；本仓有「同一能力两套判据」的 R7 前科）。
     * 加在 {@code .apiKey()} 之后只是可读性顺序：D5 已在写侧禁止撞名凭据头，顺序无安全语义
     * （T2 实测 {@code putHeader} 恒胜过 {@code .apiKey()}，且与调用顺序无关）。
     *
     * @param config    运行时配置（apiKey + 可选 baseUrl + extraHeaders）
     * @param sessionId 本次请求的会话 ID（见 {@link SessionIdResolver}）；
     *                  null → 占位符落 {@link DynamicHeaderExpander#STATIC_FALLBACK}
     */
    static OpenAIClient buildClient(ProviderConfig config, String sessionId) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
            .apiKey(config.apiKey())
            // [CC claude.ts:1781] Disabled auto-retry in favor of manual implementation
            .maxRetries(0);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(normalizeBaseUrl(config.baseUrl()));
        }
        ProviderHeaderInjector.apply(builder::putHeader, config.extraHeaders(), sessionId);
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
     * [DEC-04] 带 includeUsage 的 buildRequestParams（10 参 · 保留旧签名）。
     *
     * <p>行为与 11 参重载<b>完全一致</b>，只差探针记账口径：本重载不带「是否流式主链」载荷 ⇒
     * 出站探针按<b>侧查询</b>记账（走独立桶 {@link #HEAD_PROBE_SIDE_SUFFIX}，{@code chain=side}）。
     * 流式主链（{@link #doStream}）改调 11 参重载并显式传 {@code streamingMainChain=true}，
     * 使主链出站行与响应行同桶 ⇒ 严格 1:1 可对排。见
     * {@link #buildRequestParams(String, String, List, ArrayNode, JsonNode, boolean, Double, String, Integer, boolean, boolean)}。
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
        return buildRequestParams(modelName, systemPrompt, history, tools, outputFormatSchema,
            thinkingDisabled, temperature, effortValue, maxTokens, includeUsage, false);
    }

    /**
     * [DEC-04] buildRequestParams · 11-param 主实现（含探针链标记）。
     *
     * <p><b>includeUsage</b>：流式路径传 {@code true} 写入 {@code stream_options.include_usage}
     * （OpenAI streaming 默认不返回 usage，需显式开启）；非流式路径传 false（stream_options 对
     * non-streaming 无效）。CC 侧 Anthropic 流式 usage 恒返回，本 flag 为 OpenAI 协议等价。
     *
     * <p><b>streamingMainChain</b>（[前缀缓存头部探针 · 可判读化]）：本请求是否走
     * <b>流式主链</b>（{@link #doStream}，即唯一会打「响应」探针的那条路）。⛔ 它<b>只</b>影响探针
     * 的记账分桶与日志标记（{@code chain=stream|side}），<b>不改任何一个 wire 字节</b>：
     * 出站 params 的构造逐字与传入 false 时相同。WHY 必须显式传而不复用 includeUsage 推断：
     * 二者语义不同（一个管 {@code stream_options}，一个管日志可判读性），复用会在将来任一侧
     * 变更时静默串味。见 {@link #HEAD_PROBE_SIDE_SUFFIX}。
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
                                                         boolean includeUsage,
                                                         boolean streamingMainChain) {
        ChatCompletionCreateParams.Builder b = ChatCompletionCreateParams.builder()
            .model(modelName == null ? "" : modelName);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            b.addSystemMessage(systemPrompt);
        }
        // [前缀缓存头部探针 · 步骤 1] 出站消息列表提到外层：探针需在同一处拿到「配对修复后的真实出站列表」
        //   （hash 的是它，不是入参 history）—— 语义与条件与原来完全一致，仅变量作用域外扩。
        List<ChatMessageDto> outbound = history == null
            ? null : ToolResultPairingRepair.ensureToolResultPairing(history);
        int sentMessageCount = 0;
        if (outbound != null) {
            // [P1 发送边界配对修复] CC original: ensureToolResultPairing（claude.ts:1324 —— 主线程与 fork
            //   共用同一处；forkedAgent.ts:538-541 明确「不在 fork 侧 filter 悬挂 tool_use，下游统一修」）。
            //   Java 侧唯一 DTO→wire 转换点即此处（stream/chat/chatWithRaw/chatWithOptions 共用），
            //   fork 经 ProductionForkedQuery.streamOnce → provider.stream 同样落到这里。
            if (outbound != history && log.isDebugEnabled()) {
                log.debug("OpenAiSdkProvider 发送边界配对修复: {} → {} 条（悬挂 tool_use/孤儿 tool_result）",
                    history.size(), outbound.size());
            }
            for (ChatMessageDto m : outbound) {
                ChatCompletionMessageParam param = toSdkMessage(m);
                if (param != null) {
                    b.addMessage(param);
                    sentMessageCount++;
                }
            }
        }
        // [G4] strict 模型层门控（OpenAI · Java 多 provider 扩展 ⊕）：flag && model != null && 白名单。
        //   意图层（ToolRegistry）已把 flag && tool.strict() 写入 JSON strict 字段。
        //   [前缀缓存头部探针] 提到 tools 块外：探针要按「同一门控」做线级投影（strict 门控不通过时
        //   strict 不上 wire，投影须同步省略），tools 为空时也需有值（纯函数，零副作用）。
        boolean strictModelGate = StructuredOutputsSupport.shouldTransmitStrictOpenAi(modelName);
        if (tools != null && !tools.isEmpty()) {
            int added = 0;
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
        // [前缀缓存头部探针 · 步骤 1] 出站头部指纹（每次请求一行 INFO，只打 hash 与长度）·
        //   落点即本方法 = 本类唯一 DTO→wire 转换点 ⇒ stream / chatWithRaw / chatWithOptions /
        //   chatWithOptionsMessage 四条路径全覆盖，不漏请求。
        //   [可判读化] streamingMainChain 由调用方显式声明（doStream=true，其余=false）：
        //   主链行 chain=stream 且与响应行同桶 ⇒ 1:1 可对排；侧查询行 chain=side 走独立桶。
        logHeadProbeOutbound(systemPrompt, outbound, sentMessageCount, tools, strictModelGate,
            streamingMainChain);
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
        // [P1 发送边界配对修复] 与 buildRequestParams(:660) 同源同语义 —— 测试驱动路径与生产路径
        //   共用 ToolResultPairingRepair.ensureToolResultPairing（CC claude.ts:1324 唯一调用点同款）。
        for (ChatMessageDto m : ToolResultPairingRepair.ensureToolResultPairing(history)) {
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
                // [R4 收尾] 空串 == 缺失：toolCallId 为 "" 时 wire 上会带空 tool_call_id，
                //   被 OpenAI 兼容端点拒（"must be followed by tool messages responding to each
                //   'tool_call_id'"）→ 与 null 同等丢弃（防御性；上游 ToolResultPairingRepair
                //   已按 null/isBlank 剥离，此处为直连 toSdkMessage 的最后一道闸）。
                if (m.toolCallId() == null || m.toolCallId().isBlank()) {
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
        String finishReason = state.finishReason == null ? "stop" : state.finishReason;
        // [对齐 CC 2026-09-09 · length→apiError 洞修复] OpenAI/DeepSeek finish_reason='length'（单响应输出顶到
        //   models.max_tokens=384K；thinking 也计入输出预算）→ apiError='max_output_tokens'，使 LlmAgentLoop
        //   的 max_tokens recovery（8K→64K 升级 + Resume 续调，上限 3）真正触发。此前本文件两处 apiError 恒
        //   null → deepseek 截断永不续调、尾部静默丢失（洞）。镜像 AnthropicSdkProvider:2861-2863（Anthropic
        //   max_tokens/model_context_window_exceeded）与 CC openai/index.ts:199-209（stopReason==='max_tokens'）。
        String apiError = "length".equals(finishReason) ? "max_output_tokens" : null;
        if (apiError != null && log.isDebugEnabled()) {
            log.debug("OpenAiSdkProvider finishReason 归一化: finish_reason={} → apiError=max_output_tokens（触发 max_tokens recovery）· CC openai/index.ts:199-209",
                finishReason);
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

    // ════════════════════════════════════════════════════════════════════
    // [前缀缓存头部探针 · 步骤 1] 出站头部指纹 + 响应侧 cacheRead 掉幅（零行为变化观测）
    //
    //   目的：指认「每发一次消息（= 每起一个 run）头部哪个字节在漂」——
    //        system 串 / tools 线级投影 / messages[0..2] 线级投影 逐项 hash + 长度，与 [usage-push] 对排。
    //   纪律：只打 hash 与长度，⛔ 绝不打正文（防泄漏 + 防日志爆炸）。
    //   ⚠️ [判读口径 2026-09-21] **只把 `system` / `tools` / `messages[0..2]` 三项当「稳定前缀」判据**，
    //        ⛔ **不要把「每轮全字段逐字节相同」当预期**：CC 自己每轮也会改字节 —— 一条
    //        mid-conversation `role:"system"` 消息一旦从「本轮尾」变成**历史**，CC 会把它的 `content`
    //        从 **ARRAY 改成裸 JSON 字符串并丢掉 cache_control**（`"content":[{"type":"text",...}]` →
    //        `"content":"# Environment\n..."`；c7 行为轴实测，确定性可复现）。该变化落在**尾部**、不破前缀。
    //        ⇒ 若把「全字段相同」当判据，在这个点上**必然判错**；三项口径已实测跨 turn 稳定。
    //   生效范围：仅本类 = openai_compatible / openai_sdk 路由（LlmProviderFactory.getProvider
    //        case "openai_compatible"/"openai_sdk"），Anthropic / Mock 链路零影响。
    //   hash 能力来源：{@link PromptCacheBreakDetection#probeDigest(Object)} —— 与 CC
    //        services/api/promptCacheBreakDetection.ts:170 computeHash 同源（SHA-256 取前 32 位），
    //        ⛔ 不另造第二套（同语义禁止双实现）。
    //   ⭐ [F1 修复] **hash 输入口径 = 线级投影，不是 DTO 的 toString**：
    //        messages[i] 是每轮用 UUID.randomUUID() + OffsetDateTime.now() 新造的 userContext 元消息
    //        （AgentLoopContext.metaUserMessage / prependUserContext + result.add(0,..)），
    //        而 ChatMessageDto 是未覆写 toString 的 record ⇒ id/createdAt/time 会进 hash
    //        ⇒ 即使 wire 字节完全不变，msg0 hash 也每次请求都变（假阳性，且方向指向最关心的那一格）。
    //        tools 同理：源数组里的 defer_loading 从不被 toOpenAiSdkTool 读取 ⇒ 不进 wire。
    //        ⇒ 统一经 {@link OutboundWireProjection}（生产唯一投影实现）投影后再 hash。
    //        ⛔ 任何地方都不得再对 ChatMessageDto / tools 源节点直接 probeDigest。
    //   ⭐ [可判读化] **出站行带 chain=stream|side 标记 + 主链/侧查询分桶**：出站探针四条发送路径
    //        全打，响应探针只在流式主链打 ⇒ 若共用一个会话桶，侧查询会把主链的 turn 顶掉、
    //        出站↔响应无法 1:1 对排。现：主链行 chain=stream（桶 = sessionId）、侧查询行 chain=side
    //        （桶 = sessionId+"#side"）；响应行带 turn，与主链出站行按 sessionId+turn 逐条对排。
    //   ⚠️ 有意**不**接 PROMPT_CACHE_BREAK_DETECTION 门控：该 flag 默认关（OPD-SP-14），
    //        门控会让本探针恒静默 ⇒ 步骤 1 拿不到任何证据；判据（CACHE_READ_HIT_RATIO /
    //        MIN_CACHE_MISS_TOKENS）仍逐字复用 PromptCacheBreakDetection 的常量与语义。
    // ════════════════════════════════════════════════════════════════════

    /** [前缀缓存头部探针] 探针态 LRU 上限（纯观察态，不影响任何请求行为）。 */
    private static final int HEAD_PROBE_MAX_SESSIONS = 256;

    /** [前缀缓存头部探针] 无 sessionId 的请求（chatWithRaw 无 history）共用桶 · ⛔ 绝不编造会话 id。 */
    private static final String HEAD_PROBE_NO_SESSION = "<no-session>";

    /**
     * [前缀缓存头部探针] <b>侧查询（非流式主链）的桶后缀</b> · 与主链桶隔离。
     *
     * <p><b>WHY 必须隔离（本后缀的存在理由）</b>：出站探针在<b>四条</b>发送路径都打
     * （stream / chatWithRaw / chatWithOptions / chatWithOptionsMessage —— 落点都是
     * {@code buildRequestParams} 这个唯一 DTO→wire 转换点），而响应探针<b>只在流式主链</b>
     * （{@link #doStream}）打。若二者共用同一会话桶，则本会话每发一次侧查询（标题 / 分类器 /
     * 解释器）就把主链桶的 {@code requestSeq} 顶掉一格 ⇒ 主链的「出站 turn=N」与「响应」
     * 不再 1:1（日志上表现为<b>主链 turn 跳号</b>，且无法判断某条响应属于哪一次出站请求）。
     * 隔离后：主链桶<b>只</b>由（主链出站 + 主链响应）读写 ⇒ 严格 1:1；侧查询走
     * {@code <sessionId>#side} 独立桶，序号自成一列，互不顶号。
     */
    private static final String HEAD_PROBE_SIDE_SUFFIX = "#side";

    /**
     * [前缀缓存头部探针] 按（会话, 链）分区的观察态：
     * 请求序号 + 最近一次出站 turn + 上一次响应的 input / cacheRead。
     */
    private static final class HeadProbeState {
        long requestSeq;
        /** 最近一次<b>出站</b>的 turn · 响应行据此与出站行逐条对排（见 {@link #logHeadProbeResponse}）。 */
        long lastTurn;
        long lastInputTokens = -1L;
        long lastCacheReadTokens = -1L;
    }

    /**
     * [前缀缓存头部探针] 会话级观察态表 · accessOrder LRU，超过 {@link #HEAD_PROBE_MAX_SESSIONS}
     * 淘汰最久未访问项（会话结束无需显式清理，且保证不无界增长）。
     * <p>读写一律在 {@code synchronized (HEAD_PROBE_STATE)} 内（该 map 自身即互斥量）。
     */
    private static final Map<String, HeadProbeState> HEAD_PROBE_STATE =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, HeadProbeState> eldest) {
                return size() > HEAD_PROBE_MAX_SESSIONS;
            }
        });

    /**
     * 取（必要时建）会话/链 探针态 · <b>调用方必须已持有 {@link #HEAD_PROBE_STATE} 锁</b>。
     *
     * @param sessionId          会话 id（null/空白 → {@link #HEAD_PROBE_NO_SESSION}）
     * @param streamingMainChain true = 流式主链（与响应探针<b>同一桶</b>；
     *                           false = 侧查询（独立桶，见 {@link #HEAD_PROBE_SIDE_SUFFIX}）
     */
    private static HeadProbeState headProbeStateLocked(String sessionId, boolean streamingMainChain) {
        String base = (sessionId == null || sessionId.isBlank()) ? HEAD_PROBE_NO_SESSION : sessionId;
        String key = streamingMainChain ? base : base + HEAD_PROBE_SIDE_SUFFIX;
        return HEAD_PROBE_STATE.computeIfAbsent(key, k -> new HeadProbeState());
    }

    /**
     * [前缀缓存头部探针] 单片头部构件（已投影的线级串）指纹 → 日志片段。
     *
     * @param data 已投影的线级串（system 串 / 工具投影串 / 消息投影串；null = 该项不存在）
     * @return {@code h=<hash>,len=<串长度>}；null → {@code "-"}
     */
    private static String headProbeField(Object data) {
        if (data == null) {
            return "-";
        }
        PromptCacheBreakDetection.ProbeDigest d = PromptCacheBreakDetection.probeDigest(data);
        return "h=" + d.hash() + ",len=" + d.length();
    }

    /**
     * [前缀缓存头部探针] {@code messages[index]} 的指纹 → 日志片段。
     *
     * <p>⭐ <b>先经 {@link OutboundWireProjection#projectMessage} 投影再 hash</b>（F1 修复）：
     * 直接 hash {@code ChatMessageDto} 会把不进 wire 的 {@code id/createdAt/time} 算进去 ⇒
     * 每轮新造的元消息恒假阳性。见本段开头注释。
     *
     * @param messages 出站消息列表（配对修复后）
     * @param index    消息下标
     * @return {@code h=<hash>,len=<投影串长度>}；越界/为 null → {@code "-"}
     */
    private static String headProbeMessageField(List<ChatMessageDto> messages, int index) {
        if (messages == null || index >= messages.size()) {
            return "-";
        }
        ChatMessageDto m = messages.get(index);
        if (m == null) {
            return "-";
        }
        return headProbeField(OutboundWireProjection.projectMessage(m));
    }

    /**
     * [前缀缓存头部探针] 头部三项构件的一次完整指纹（<b>纯计算 · 无 IO · 无状态</b>）。
     *
     * <p>日志（{@link #logHeadProbeOutbound}）与单测共用本方法来保证「同一口径」——
     * 单测直接对本方法断言，即等于对真实探针的 hash 输入断言（⛔ 不是复制一份口径去断言）。
     *
     * @param systemPrompt    出站 system 串（可 null = 不发送）
     * @param outbound        配对修复后的出站消息列表（可 null）
     * @param sentCount       真实写入请求体的消息条数
     * @param tools           出站 tools 数组源 JSON（可 null）
     * @param strictModelGate strict 模型层门控（见 {@link OutboundWireProjection#projectToolNode}）
     * @return 三个构件 + 条数的指纹快照
     */
    static HeadProbeFingerprint fingerprintHead(String systemPrompt, List<ChatMessageDto> outbound,
                                                int sentCount, ArrayNode tools, boolean strictModelGate) {
        return new HeadProbeFingerprint(
            headProbeField(systemPrompt),
            tools == null ? "-" : headProbeField(OutboundWireProjection.projectTools(tools, strictModelGate)),
            headProbeMessageField(outbound, 0),
            headProbeMessageField(outbound, 1),
            headProbeMessageField(outbound, 2),
            sentCount);
    }

    /**
     * [前缀缓存头部探针] 头部指纹快照 · 每项形如 {@code h=<hash>,len=<len>}，缺失为 {@code "-"}。
     *
     * @param system    出站 system 串指纹
     * @param tools     出站工具线级投影指纹
     * @param msg0      {@code messages[0]} 线级投影指纹
     * @param msg1      {@code messages[1]} 线级投影指纹
     * @param msg2      {@code messages[2]} 线级投影指纹
     * @param sentCount 真实写入请求体的消息条数
     */
    record HeadProbeFingerprint(String system, String tools, String msg0, String msg1, String msg2,
                                int sentCount) {}

    /**
     * [前缀缓存头部探针 · 出站] 每次 OpenAI 请求一行 INFO · 头部三项指纹（<b>只打 hash 与长度</b>）。
     *
     * <p>打印项（固定顺序）：{@code sessionId / turn / chain / 线程名 / sys / tools / msg0 / msg1 / msg2 / 消息总数}。
     * {@code sys} 取<b>出站 system 串</b>（openai-compatible 端点 system 为单字符串，见
     * {@link #stream} 的 {@code \n\n} join —— block 划分与 cache_control 不落 wire，故此处不打 block 维度）；
     * {@code tools} 取<b>出站 tools 的线级投影</b>；{@code msgN} 取出站消息（配对修复后）的
     * <b>线级投影</b>（两者均经 {@link OutboundWireProjection}，见本段开头 F1 说明）。
     *
     * <p>{@code turn} = <b>本探针自维护的「会话内请求序号」</b>（1 起）：provider 签名链
     * （{@code LlmProvider.stream} / {@code doStream}）没有任何 run/turn 载体，故用会话内请求序号
     * 作为 run 边界的可对排锚点（同一 sessionId 相邻两行的 turn 连续 ⇒ 就是相邻两次模型请求）。
     *
     * <p>调用点 = {@link #buildRequestParams}（本类唯一 DTO→wire 转换点）⇒ 四条发送路径全覆盖。
     *
     * <p><b>已知边界（如实登记，不粉饰）</b>：tools 的线级投影重建的是
     * {@code toOpenAiSdkTool} 的<b>字段集</b>，不是 SDK 对象本身（SDK 对象 {@code toString} 的
     * 确定性未验证，直接 hash 有产生假漂移的风险）⇒「转换环节丢掉某个畸形 tool」这一格由投影的
     * {@link OutboundWireProjection#DROPPED_TOOL} 占位覆盖，但 SDK 内部序列化差异（若有）本行看不见。
     * 若 tools 指纹跨 run 全同而 cacheRead 仍塌，须排除该项后再下结论。
     *
     * @param systemPrompt       出站 system 串（可 null = 不发送 system）
     * @param outbound           配对修复后的出站消息列表（可 null）
     * @param sentCount          真实写入请求体的消息条数（{@code toSdkMessage} 过滤 system 角色后计数）
     * @param tools              出站 tools 数组源 JSON（可 null）
     * @param strictModelGate    strict 模型层门控（{@code toOpenAiSdkTool} 的同一入参）
     * @param streamingMainChain true = 流式主链请求（{@link #doStream}，其响应行也打）；
     *                           false = 侧查询（标题 / 分类器 / 解释器，无响应行）
     *
     * <p><b>⭐ 判读方式</b>：日志行上有 {@code chain=stream|side} 标记 + {@code turn} 序号。
     * 同一 sessionId 下：<b>主链</b>行（{@code chain=stream}）的 turn 从 1 起<b>连号</b>，且与
     * 「响应」行（带同一 turn）<b>逐条 1:1</b>；<b>侧查询</b>行（{@code chain=side}）走独立桶
     * （{@link #HEAD_PROBE_SIDE_SUFFIX}）⇒ 其 turn 自成一列，<b>不会</b>把主链序号顶掉
     * （这正是本标记 + 分桶要修的可判读性缺陷）。对排方法：按 sessionId 分组 → 只看
     * {@code chain=stream} 的行 → 相邻两行 turn 应连续，且每条都能在响应行里找到同 turn 的那条。
     *
     * <p>包级可见（原 private）供单测直接驱动：主链/侧查询的<b>分桶与连号</b>是纯记账逻辑，
     * 不经真实网络即可断言（与 {@link #fingerprintHead} 同先例）。
     */
    static void logHeadProbeOutbound(String systemPrompt, List<ChatMessageDto> outbound,
                                     int sentCount, ArrayNode tools, boolean strictModelGate,
                                     boolean streamingMainChain) {
        if (!log.isInfoEnabled()) {
            return;
        }
        try {
            String sessionId = SessionIdResolver.resolve(outbound, null);
            long turn;
            synchronized (HEAD_PROBE_STATE) {
                HeadProbeState st = headProbeStateLocked(sessionId, streamingMainChain);
                turn = ++st.requestSeq;
                // 响应行靠 lastTurn 与本行对排（只记主链桶；侧查询桶无响应行，记了也无用）
                st.lastTurn = turn;
            }
            HeadProbeFingerprint fp = fingerprintHead(systemPrompt, outbound, sentCount, tools, strictModelGate);
            log.info("[前缀缓存探针] 出站 sessionId={} turn={} chain={} thread={} sys[{}] tools[{}] "
                    + "msg0[{}] msg1[{}] msg2[{}] msgCount={}",
                sessionId, turn, streamingMainChain ? "stream" : "side",
                java.lang.Thread.currentThread().getName(),
                fp.system(), fp.tools(), fp.msg0(), fp.msg1(), fp.msg2(), fp.sentCount());
        } catch (Exception e) {
            log.warn("[前缀缓存探针] 出站指纹计算失败: {}", e.toString());
        }
    }

    /**
     * [前缀缓存头部探针 · 响应] cacheRead 掉幅 + cacheBreak 同判据标记（供与 {@code [usage-push]} 对排）。
     *
     * <p><b>掉幅口径</b>（派单书原话「cacheRead 相对上一条请求 input 的掉幅」）：
     * {@code 掉幅 = 上一条请求 input - 本次 cacheRead}（首条 / 上一条缺失 → {@code -1}）。
     * 另附「cacheRead 相对上一条 cacheRead 的跌幅」，并用 {@link PromptCacheBreakDetection} 的
     * {@link PromptCacheBreakDetection#CACHE_READ_HIT_RATIO} 与
     * {@link PromptCacheBreakDetection#MIN_CACHE_MISS_TOKENS} 按
     * {@code checkResponseForCacheBreak} <b>同一判据</b>标记是否构成 cache break
     * （真源 CC promptCacheBreakDetection.ts:487-488，⛔ 不另造阈值）。
     *
     * <p>只有本类流式主链（{@code doStream}）接此线：非流式 chatWithOptions* 属侧查询，
     * 无 {@code [usage-push]} 对排需求，且会把同会话的「上一条 input」基线踩脏。
     *
     * <p><b>⭐ 判读方式</b>：本行带 {@code turn}，取值 = 该会话<b>主链桶</b>最近一次<b>出站</b>的 turn
     * （{@link HeadProbeState#lastTurn}）⇒ 「主链出站行 ↔ 响应行」按 {@code sessionId + turn}
     * <b>逐条 1:1 对排</b>。本方法读写的是<b>主链桶</b>（{@code streamingMainChain=true}）——
     * 侧查询走 {@code #side} 独立桶（{@link #HEAD_PROBE_SIDE_SUFFIX}），既不会顶掉主链 turn，
     * 也不会踩脏本行的「上一条 input / 上一条 cacheRead」基线。
     *
     * @param sessionId     会话 id（可 null → "<no-session>" 桶）
     * @param inputTokens   本次请求 input tokens（{@code usage.prompt_tokens}）
     * @param cacheRead     本次 cacheRead tokens（{@code prompt_cache_hit_tokens} / cached_tokens）
     * @param cacheCreation 本次 cacheCreation tokens（{@code prompt_cache_miss_tokens}；OpenAI 无等价 → 0）
     */
    static void logHeadProbeResponse(String sessionId, long inputTokens,
                                     long cacheRead, long cacheCreation) {
        if (!log.isInfoEnabled()) {
            return;
        }
        try {
            long prevInput;
            long prevRead;
            long turn;
            synchronized (HEAD_PROBE_STATE) {
                // 恒主链桶：本方法只在 doStream（流式主链）被调，其出站行也记在主链桶
                HeadProbeState st = headProbeStateLocked(sessionId, true);
                prevInput = st.lastInputTokens;
                prevRead = st.lastCacheReadTokens;
                turn = st.lastTurn;
                st.lastInputTokens = inputTokens;
                st.lastCacheReadTokens = cacheRead;
            }
            long dropVsPrevInput = prevInput < 0L ? -1L : prevInput - cacheRead;
            long dropVsPrevRead = prevRead < 0L ? -1L : prevRead - cacheRead;
            boolean cacheBreakLike = prevRead > 0L
                && cacheRead < prevRead * PromptCacheBreakDetection.CACHE_READ_HIT_RATIO
                && (prevRead - cacheRead) >= PromptCacheBreakDetection.MIN_CACHE_MISS_TOKENS;
            String hitRate = inputTokens > 0L
                ? String.format(Locale.ROOT, "%.1f%%", cacheRead * 100.0 / inputTokens)
                : "-";
            log.info("[前缀缓存探针] 响应 sessionId={} turn={} input={} cacheRead={} cacheCreate={} 命中率={} "
                    + "上一条input={} 掉幅(上一条input-本次cacheRead)={} 上一条cacheRead={} "
                    + "cacheRead跌幅={} cacheBreak判定={}",
                sessionId, turn, inputTokens, cacheRead, cacheCreation, hitRate,
                prevInput, dropVsPrevInput, prevRead, dropVsPrevRead, cacheBreakLike);
        } catch (Exception e) {
            log.warn("[前缀缓存探针] 响应掉幅计算失败: {}", e.toString());
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
