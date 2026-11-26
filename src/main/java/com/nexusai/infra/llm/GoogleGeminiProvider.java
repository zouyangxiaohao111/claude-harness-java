package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ContentBlockDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Google Cloud Code Assist（Gemini 订阅）协议 provider · type = {@code gemini_code_assist}。
 *
 * <p><b>官方基线</b>：google-gemini/gemini-cli main @ 08e9a20f749aefcbda38622ac757899c3d6216e0
 * （npm 0.71.1）。取证副本落盘 D:/tmp/google-subscription-evidence/official-src/。
 * 生产端点锁定 {@code https://cloudcode-pa.googleapis.com/v1internal}（server.ts:73-74），
 * ⛔ 不接受用户 baseUrl 改写（订阅 host 与 API-key Gemini host 是两条协议）；
 * 测试经 {@link Transport} 接缝注入桩，生产构造器始终走官方 host。
 *
 * <p><b>信封</b>（converter.ts:33-51/129-143）：
 * {@code {model, project, user_prompt_id, request:{contents, systemInstruction, tools,
 * toolConfig, generationConfig, session_id}}}；响应解 {@code response} 包装
 * （converter.ts:145-161），{@code traceId} → requestId。
 *
 * <p><b>签名持久化（方案 A，主会话 2026-11-25 裁决）</b>：thoughtSignature 经既有
 * {@link ContentBlockDto#signature()} 槽落库（messages.content_blocks V331），跨重启回放。
 * 槽内值为自描述封装 {@code gca1|gemini_code_assist|<model>|<base64url(raw)>}——
 * 出站解封装并核对 provider/model 身份，⛔ 封装原样不发 Google、不打日志、不前端展示。
 * 回放仅读「与 canonical 本轮 content/reasoning/toolCalls 核对等价」的 blocks 取 part 边界与签名；
 * 用户编辑/裁剪致不匹配 → 弃签名走 canonical 重建。⛔ blocks 不整份序列化为请求体。
 *
 * <p><b>历史回放规则（官方源码级取证）</b>：
 * <ul>
 *   <li>thought part 剥离不回放（geminiChat.ts:1412-1413 stripThoughts + coalesceConsecutiveRoles）；</li>
 *   <li>每 model 轮首个 functionCall 必须有 thoughtSignature，缺失注入合成令牌
 *       {@code skip_thought_signature_validator}（geminiChat.ts:123/:1527-1565/:2271-2285）；</li>
 *   <li>并行工具批 = 单 model Content + 单 user functionResponse Content，相邻同角色合并
 *       （geminiChat.ts:2209-2234），⛔ 不交错造新 step；</li>
 *   <li>未应答 functionCall 以合成 error response 收口（geminiChat.ts:129-130/:2070-2082）。</li>
 * </ul>
 *
 * <p><b>规范性偏离（相对官方 parser，主会话裁定）</b>：SSE 按规范接受 {@code data:} 后可选单空格、
 * 多行 data 拼接、EOF flush；官方 server.ts:524 只认 "data: " 前缀且 :510-518 静默跳坏 JSON——
 * 本类坏 JSON / 流内 error 事件一律 fail-loud，⛔ 不吞成成功。
 *
 * <p><b>thinking 映射（defaultModelConfigs.ts:31-63 + models.ts:180 取证）</b>：
 * 2.5 系 thinkingBudget（chat 默认 8192；Flash 可 0 关闭；<b>Pro 禁关</b>，disabled 落文档最小值 128，
 * 该最小值为文档级证据，官方源码仅证实 Pro 不可 0）；3 系 thinkingLevel（默认 HIGH，disabled→LOW）。
 * effort 档位（low/medium/high…）官方无→Gemini 数值映射，除 off 外不投影（不编造）。
 *
 * <p><b>配额错误分类（googleQuotaErrors.ts:229-483 取证）</b>：429/499/503 先分终态
 * （日限/QUOTA_EXHAUSTED/容量耗尽/花费上限/延迟&gt;300s）vs 可重试（分钟限/RATE_LIMIT_EXCEEDED≤300s）；
 * 非流式可重试≤3 次（server.ts:431-440），流式不重试（server.ts:488），⛔ 无限 retry。
 * 401 经 {@link com.nexusai.domain.provider.ProviderService#forceRefreshByAccessToken} 刷新一次
 * （ResponsesSdkProvider:1474-1485 先例），⛔ 不自读凭据库。
 *
 * <p><b>ToS 风险</b>：官方 docs/resources/tos-privacy.md:10-14 限制第三方直访；用户 2026-11-25
 * 知情「接受风险」，范围=代码+本地模拟测试，真实账号登录另行确认。见实施计划 §13/§14。
 */
@Component("googleGeminiProvider")
public class GoogleGeminiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleGeminiProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 协议类型标识（工厂分发键，LlmProviderFactory 按此路由）。 */
    public static final String PROVIDER_TYPE = "gemini_code_assist";

    /** 生产 host 锁定（server.ts:73-74）。⛔ 不读 config.baseUrl()。 */
    static final String OFFICIAL_HOST = "https://cloudcode-pa.googleapis.com";
    static final String API_VERSION = "v1internal";

    /** 官方合成签名令牌（geminiChat.ts:123）——历史轮首 FC 缺签名时的唯一官方 fallback。 */
    static final String SYNTHETIC_THOUGHT_SIGNATURE = "skip_thought_signature_validator";
    /** 官方中断占位（historyHardening.ts:19-20）。 */
    static final String INTERRUPTED_RESPONSE_PLACEHOLDER =
        "[The previous response was interrupted before it completed.]";
    /** 官方未应答工具收口文案（geminiChat.ts:129-130）。 */
    static final String INTERRUPTED_TOOL_RESPONSE_ERROR = "Response was lost or interrupted.";

    /** 签名封装前缀（自描述：provider + model 身份，防跨 provider/跨模型误回放）。 */
    static final String SIG_PREFIX = "gca1|";

    /** 2.5 系 chat 默认思考预算（models.ts:180 DEFAULT_THINKING_MODE=8192）。 */
    static final int DEFAULT_THINKING_BUDGET_25 = 8192;
    /** 2.5 Pro 文档最小预算（文档级证据；官方源码证实 Pro 不可 0，defaultModelConfigs.ts:401-407）。 */
    static final int MIN_THINKING_BUDGET_25_PRO = 128;
    /** 可重试延迟上限（googleQuotaErrors.ts:120 MAX_RETRYABLE_DELAY_SECONDS=300）。 */
    static final long MAX_RETRYABLE_DELAY_MS = 300_000L;
    /** 非流式重试上限（server.ts:433 retry:3）。 */
    static final int NON_STREAM_MAX_ATTEMPTS = 3;

    private static final String USER_AGENT_VERSION = "0.71.1";

    /** 流空闲看门狗调度器（daemon，全 provider 共享）。 */
    private static final ScheduledExecutorService IDLE_SCHEDULER =
        Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "gemini-idle-watchdog");
            t.setDaemon(true);
            return t;
        });

    /**
     * 401 自愈（可空：无 Spring 上下文 / 单测降级为不重试）。
     * ⛔ 不注入 ProviderMapper/CryptoUtil 自解密——刷新口径单一（ResponsesSdkProvider:102-109 先例）。
     */
    @Autowired(required = false)
    private com.nexusai.domain.provider.ProviderService providerService;

    private final Transport transport;

    /** 生产构造：官方 host + java.net.http 传输。 */
    public GoogleGeminiProvider() {
        this.transport = new HttpTransport();
    }

    /** 测试构造：注入桩传输（⛔ 生产路径不可达——Spring 用无参构造）。 */
    GoogleGeminiProvider(Transport transport) {
        this.transport = transport;
    }

    /** 测试接缝：注入 401 刷新服务（生产经 @Autowired）。 */
    void setProviderService(com.nexusai.domain.provider.ProviderService service) {
        this.providerService = service;
    }

    @Override
    public String type() {
        return PROVIDER_TYPE;
    }

    // ===================== 传输接缝 =====================

    /** 出站请求（url 恒由本类锁定官方 host 构造；桩可改目的地址）。 */
    public record TransportRequest(String url, Map<String, String> headers, String body) {}

    /** 出站响应（body 为流式读取通道；close 断开 HTTP）。 */
    public interface TransportResponse extends AutoCloseable {
        int status();
        Map<String, List<String>> headers();
        InputStream body() throws IOException;
        @Override void close();
    }

    /** 传输接缝：生产 = java.net.http；测试 = 本地桩（⛔ 桩不得成为生产默认）。 */
    public interface Transport {
        TransportResponse send(TransportRequest request) throws IOException, InterruptedException;
    }

    /** 生产传输：每次发送构建 client（代理读源运行时可变，OutboundProxy 动态判定）。 */
    static final class HttpTransport implements Transport {
        @Override
        public TransportResponse send(TransportRequest request) throws IOException, InterruptedException {
            HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
            ProxySelector proxy = OutboundProxy.selectorFor(request.url());
            if (proxy != null) {
                builder.proxy(proxy); // JDK 25：proxySelector 更名 proxy（javap 实证）
            }
            HttpClient client = builder.build();
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
            for (Map.Entry<String, String> h : request.headers().entrySet()) {
                rb.header(h.getKey(), h.getValue());
            }
            HttpResponse<InputStream> resp =
                client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new TransportResponse() {
                @Override public int status() { return resp.statusCode(); }
                @Override public Map<String, List<String>> headers() { return resp.headers().map(); }
                @Override public InputStream body() { return resp.body(); }
                @Override public void close() {
                    try {
                        resp.body().close();
                    } catch (IOException ignored) {
                        // 关闭失败不影响终态（对齐 OpenAI response.close 容错）
                    }
                }
            };
        }
    }

    // ===================== stream 三重载（全覆写，防 default 丢 thinkingConfig） =====================

    /** 20 参 blocks 抽象（唯一发送契约）→ 委托 21 参实现（thinkingConfig=null）。 */
    @Override
    public void stream(ProviderConfig config, String modelName,
                       List<SystemPromptBlock> systemPromptBlocks, List<ChatMessageDto> history,
                       ArrayNode tools, Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                       String effortValue, String querySource,
                       Consumer<String> onChunk, Consumer<AssistantMessage> onAssistantMessage,
                       Consumer<ToolUseBlock> onToolCallComplete, Consumer<String> onReasoningChunk,
                       Runnable onStreamingFallback, AbortController abortController,
                       Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite,
                       LlmCallScope scope, StreamIdleControl streamIdleControl) {
        stream(config, modelName, systemPromptBlocks, history, tools, maxOutputTokensOverride,
            taskBudget, effortValue, querySource, null,
            onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
            onStreamingFallback, abortController, onError, onComplete, skipCacheWrite,
            scope, streamIdleControl);
    }

    /** 18 参 String+thinkingConfig → 折单 block 路由 21 参（default 实现会丢 thinkingConfig，必须覆写）。 */
    @Override
    public void stream(ProviderConfig config, String modelName,
                       String systemPrompt, List<ChatMessageDto> history,
                       ArrayNode tools, Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                       String effortValue, ChatRequestOptions.ThinkingConfig thinkingConfig,
                       Consumer<String> onChunk, Consumer<AssistantMessage> onAssistantMessage,
                       Consumer<ToolUseBlock> onToolCallComplete, Consumer<String> onReasoningChunk,
                       Runnable onStreamingFallback, AbortController abortController,
                       Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite,
                       LlmCallScope scope, StreamIdleControl streamIdleControl) {
        stream(config, modelName,
            systemPrompt == null ? null : List.of(new SystemPromptBlock(systemPrompt, CacheScope.NULL)),
            history, tools, maxOutputTokensOverride, taskBudget, effortValue, null, thinkingConfig,
            onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
            onStreamingFallback, abortController, onError, onComplete, skipCacheWrite,
            scope, streamIdleControl);
    }

    /** 21 参 blocks+thinkingConfig = 流式核心入口。 */
    @Override
    public void stream(ProviderConfig config, String modelName,
                       List<SystemPromptBlock> systemPromptBlocks, List<ChatMessageDto> history,
                       ArrayNode tools, Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                       String effortValue, String querySource,
                       ChatRequestOptions.ThinkingConfig thinkingConfig,
                       Consumer<String> onChunk, Consumer<AssistantMessage> onAssistantMessage,
                       Consumer<ToolUseBlock> onToolCallComplete, Consumer<String> onReasoningChunk,
                       Runnable onStreamingFallback, AbortController abortController,
                       Consumer<Throwable> onError, Runnable onComplete, Boolean skipCacheWrite,
                       LlmCallScope scope, StreamIdleControl streamIdleControl) {
        // skipCacheWrite / querySource：Gemini 协议无客户端 prompt-cache marker / 查询来源线字段
        //   → 显式不投影（OpenAI 同款处理，非静默丢弃：协议无对应物）。
        // taskBudget：Gemini 协议无 task_budget 线字段（Anthropic output_config 专属）→ 显式不投影。
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicBoolean terminated = new AtomicBoolean(false);
        AtomicReference<TransportResponse> activeResponse = new AtomicReference<>();
        CountDownLatch abortLatch = new CountDownLatch(1);
        if (abortController != null) {
            abortController.onCancel(ac -> {
                if (aborted.compareAndSet(false, true)) {
                    abortLatch.countDown(); // [IND-D] 退避等待可被 abort 打断
                    // AbortController 断开 HTTP（读阻塞经 close 解阻塞）
                    TransportResponse r = activeResponse.get();
                    if (r != null) {
                        r.close();
                    }
                    if (terminated.compareAndSet(false, true)) {
                        onError.accept(new CancellationException(
                            ac.reason() != null ? ac.reason() : "stream aborted"));
                    }
                }
            });
        }
        doStream(config, modelName, systemPromptBlocks, history, tools, maxOutputTokensOverride,
            effortValue, thinkingConfig, onChunk, onAssistantMessage, onToolCallComplete,
            onReasoningChunk, aborted, terminated, activeResponse, abortLatch, onError, onComplete,
            scope, streamIdleControl);
    }

    // ===================== 流式核心 =====================

    private void doStream(ProviderConfig config, String modelName,
                          List<SystemPromptBlock> systemPromptBlocks, List<ChatMessageDto> history,
                          ArrayNode tools, Integer maxOutputTokensOverride,
                          String effortValue, ChatRequestOptions.ThinkingConfig thinkingConfig,
                          Consumer<String> onChunk, Consumer<AssistantMessage> onAssistantMessage,
                          Consumer<ToolUseBlock> onToolCallComplete, Consumer<String> onReasoningChunk,
                          AtomicBoolean aborted, AtomicBoolean terminated,
                          AtomicReference<TransportResponse> activeResponse,
                          CountDownLatch abortLatch,
                          Consumer<Throwable> onError, Runnable onComplete,
                          LlmCallScope scope, StreamIdleControl streamIdleControl) {
        if (config == null || !config.isUsable()) {
            recordAttempt(scope, AgentUsage.EMPTY, LlmUsagePhase.FAILED);
            failOnce(terminated, onError, new IllegalStateException(
                "GoogleGeminiProvider.stream 调用时 ProviderConfig 不可用（access token 为空）"));
            return;
        }
        if (config.oauthProject() == null || config.oauthProject().isBlank()) {
            recordAttempt(scope, AgentUsage.EMPTY, LlmUsagePhase.FAILED);
            failOnce(terminated, onError, new IllegalStateException(
                "Google 订阅未完成项目初始化（oauthProject 为空）：账号可能无订阅资格或需指定 GCP project，请重新登录 Google 订阅"));
            return;
        }
        // [IND-F] 预取消预检：零 HTTP、终态一次（onError 已由 stream() listener 发出），与非流式 pre-check 一致
        if (aborted.get()) {
            recordAttempt(scope, AgentUsage.EMPTY, LlmUsagePhase.ABORTED);
            return;
        }
        // [看门狗·降级非流式] 对齐 OpenAI/Anthropic forceNonStreaming 语义
        if (streamIdleControl != null && streamIdleControl.forceNonStreaming()) {
            try {
                AssistantMessage msg = generateContent(config, modelName, systemPromptBlocks, history,
                    tools, maxOutputTokensOverride, effortValue, thinkingConfig, null, aborted, abortLatch);
                if (aborted.get()) {
                    recordAttempt(scope, AgentUsage.EMPTY, LlmUsagePhase.ABORTED);
                    return;
                }
                if (onAssistantMessage != null) {
                    onAssistantMessage.accept(msg);
                }
                LlmCallScope.recordSettled(scope, msg.usage());
                if (terminated.compareAndSet(false, true)) {
                    onComplete.run();
                }
            } catch (Exception e) {
                if (aborted.get()) {
                    recordAttempt(scope, AgentUsage.EMPTY, LlmUsagePhase.ABORTED);
                    return;
                }
                recordAttempt(scope, AgentUsage.EMPTY, LlmUsagePhase.FAILED);
                failOnce(terminated, onError, e);
            }
            return;
        }

        // [MODEL-MAP] 官方无 flag 默认映射；签名 capture/replay 身份绑定最终 wire model
        String wireModel = resolveBackendModel(modelName);
        Accumulator acc = new Accumulator(wireModel);
        AtomicBoolean stalledByWatchdog = new AtomicBoolean(false);
        ScheduledFuture<?> idleTask = null;
        TransportResponse response = null;
        boolean authRetried = false;
        try {
            String sessionId = SessionIdResolver.resolve(history, null);
            ObjectNode envelope = buildEnvelope(config, wireModel, systemPromptBlocks, history,
                tools, maxOutputTokensOverride, effortValue, thinkingConfig, null, sessionId);
            String url = OFFICIAL_HOST + "/" + API_VERSION + ":streamGenerateContent?alt=sse";
            String token = config.apiKey();
            while (true) {
                TransportRequest req = new TransportRequest(url,
                    buildHeaders(token, wireModel, sessionId, config, true), envelope.toString());
                response = transport.send(req);
                activeResponse.set(response);
                if (response.status() == 401) {
                    // [IND-B] 至多刷新一次；刷新后仍 401 → 中文重登提示 + 单一终态，⛔ 不再 refresh/retry
                    String body401 = readErrorBody(response);
                    response.close();
                    if (!authRetried) {
                        String refreshed = forceRefreshAccess(token);
                        if (refreshed != null && !refreshed.isBlank()) {
                            authRetried = true;
                            token = refreshed;
                            continue;
                        }
                    }
                    throw withReloginHint(new LlmApiException(401, response.headers(), body401));
                }
                if (response.status() != 200) {
                    String body = readErrorBody(response);
                    LlmApiException api = new LlmApiException(response.status(), response.headers(), body);
                    throw classifyAndTranslate(api);
                }
                break;
            }

            // [看门狗] 读空闲超时：静默 ≥ idleTimeoutMs → 标记 + 关流解阻塞
            final TransportResponse watched = response;
            final long idleMs = streamIdleControl != null ? streamIdleControl.idleTimeoutMs() : 0L;
            final long[] lastActivity = {System.nanoTime()};
            if (idleMs > 0) {
                idleTask = IDLE_SCHEDULER.scheduleAtFixedRate(() -> {
                    long idle = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivity[0]);
                    if (idle >= idleMs) {
                        if (stalledByWatchdog.compareAndSet(false, true)) {
                            log.warn("[GoogleGeminiProvider] 流空闲看门狗触发（idle={}ms ≥ {}ms）→ 关流", idle, idleMs);
                            watched.close();
                        }
                    }
                }, Math.max(1, idleMs / 2), Math.max(1, idleMs / 2), TimeUnit.MILLISECONDS);
            }
            if (streamIdleControl != null && streamIdleControl.watchdogController() != null) {
                streamIdleControl.watchdogController().onCancel(ac -> {
                    if (stalledByWatchdog.compareAndSet(false, true)) {
                        watched.close();
                    }
                });
            }

            // SSE 读循环（规范解析；坏 JSON fail-loud）
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                List<String> dataBuffer = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lastActivity[0] = System.nanoTime();
                    if (aborted.get() || stalledByWatchdog.get()) {
                        break;
                    }
                    if (line.isEmpty()) {
                        if (!dataBuffer.isEmpty()) {
                            String payload = String.join("\n", dataBuffer);
                            dataBuffer.clear();
                            handleSseEvent(payload, acc, onChunk, onToolCallComplete, onReasoningChunk);
                        }
                    } else if (line.startsWith("data:")) {
                        // SSE 规范：data: 后可选单空格（仅剥一个，⛔ 不 trim 全部——偏离官方 slice(6).trim()）
                        String value = line.substring(5);
                        if (value.startsWith(" ")) {
                            value = value.substring(1);
                        }
                        dataBuffer.add(value);
                    }
                    // 其余行（":" 注释 / event: / id: / retry:）按 SSE 规范忽略
                }
                // EOF flush（官方 server.ts:533 同款；多行 data 跨块在此收口）
                if (!dataBuffer.isEmpty() && !aborted.get() && !stalledByWatchdog.get()) {
                    handleSseEvent(String.join("\n", dataBuffer), acc,
                        onChunk, onToolCallComplete, onReasoningChunk);
                }
            }

            if (stalledByWatchdog.get()) {
                recordAttempt(scope, acc.usage(), LlmUsagePhase.FAILED);
                failOnce(terminated, onError, buildStreamIdleError(acc));
                return;
            }
            if (aborted.get()) {
                recordAttempt(scope, acc.usage(), LlmUsagePhase.ABORTED);
                return; // onError(CancellationException) 已由 abort listener 发出
            }
            // 流完整性：Gemini 正常流末 chunk 必带 finishReason；未见 = 断流 fail-loud
            //   （对齐 OpenAI stream-integrity 判据；⛔ 不把截断当成功）
            if (acc.finishReasonRaw == null) {
                throw new IOException("GoogleGeminiProvider stream truncated: 流结束但未收到 finishReason"
                    + "（contentLen=" + acc.content.length() + ", reasoningLen=" + acc.reasoning.length()
                    + ", toolCalls=" + acc.toolCalls.size() + "）");
            }
            AssistantMessage msg = acc.toAssistantMessage();
            if (onAssistantMessage != null) {
                onAssistantMessage.accept(msg);
            }
            LlmCallScope.recordSettled(scope, msg.usage());
            if (terminated.compareAndSet(false, true)) {
                onComplete.run();
            }
        } catch (Exception e) {
            if (aborted.get()) {
                recordAttempt(scope, acc.usage(), LlmUsagePhase.ABORTED);
                return;
            }
            if (stalledByWatchdog.get() || isIdleReadTimeout(e, streamIdleControl)) {
                recordAttempt(scope, acc.usage(), LlmUsagePhase.FAILED);
                failOnce(terminated, onError, buildStreamIdleError(acc));
                return;
            }
            recordAttempt(scope, acc.usage(), LlmUsagePhase.FAILED);
            RuntimeException translated = (e instanceof RuntimeException re) ? re
                : new IllegalStateException("Google Gemini 流式调用失败: " + e.getMessage(), e);
            log.error("[GoogleGeminiProvider] 流式调用失败: {}", translated.toString());
            failOnce(terminated, onError, translated);
        } finally {
            if (idleTask != null) {
                idleTask.cancel(false);
            }
            if (response != null) {
                response.close();
            }
            activeResponse.set(null);
        }
    }

    /** SSE 单事件处理：解 response 包装 → parts / finishReason / usageMetadata；error 事件 fail-loud。 */
    private void handleSseEvent(String payload, Accumulator acc,
                                Consumer<String> onChunk, Consumer<ToolUseBlock> onToolCallComplete,
                                Consumer<String> onReasoningChunk) throws IOException {
        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (Exception parseErr) {
            // 规范性偏离官方 server.ts:510-518（静默跳坏 JSON）：坏 JSON 不得吞成成功
            throw new IOException("GoogleGeminiProvider: SSE 事件 JSON 解析失败（fail-loud，不静默跳过）", parseErr);
        }
        if (node == null || !node.isObject()) {
            throw new IOException("GoogleGeminiProvider: SSE 事件非 JSON 对象（fail-loud）");
        }
        if (node.has("error")) {
            // 流内错误事件（HTTP 200 但 data 携带 error）——⛔ 不当成功
            JsonNode err = node.get("error");
            int code = err.path("code").asInt(500);
            throw classifyAndTranslate(new LlmApiException(code, Map.of(), node.toString()));
        }
        acc.anyEvent = true;
        if (node.hasNonNull("traceId")) {
            acc.requestId = node.get("traceId").asText();
        }
        JsonNode response = node.path("response");
        if (response.isMissingNode()) {
            return; // 仅 traceId/credits 的心跳帧（官方 fromGenerateContentResponse 同形）
        }
        JsonNode usage = response.path("usageMetadata");
        if (usage.isObject()) {
            acc.updateUsage(usage);
        }
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return;
        }
        JsonNode candidate = candidates.get(0);
        if (candidate.hasNonNull("finishReason")) {
            acc.finishReasonRaw = candidate.get("finishReason").asText();
            acc.anyBlockFinished = true;
        }
        JsonNode parts = candidate.path("content").path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                acc.processPart(part, onChunk, onToolCallComplete, onReasoningChunk);
            }
        }
    }

    // ===================== 非流式核心（chat 家族共用） =====================

    /**
     * 非流式 {@code :generateContent}（同一信封）· 重试规则：可重试配额/5xx ≤3 次
     * （server.ts:431-440），终态配额立即抛，401 刷新一次。
     */
    private AssistantMessage generateContent(ProviderConfig config, String modelName,
                                           List<SystemPromptBlock> systemPromptBlocks,
                                           List<ChatMessageDto> history, ArrayNode tools,
                                           Integer maxOutputTokensOverride, String effortValue,
                                           ChatRequestOptions.ThinkingConfig thinkingConfig,
                                           ChatRequestOptions options,
                                           AtomicBoolean aborted,
                                           CountDownLatch abortSignal) throws IOException, InterruptedException {
        if (config == null || !config.isUsable()) {
            throw new IllegalStateException("GoogleGeminiProvider 非流式调用时 ProviderConfig 不可用（access token 为空）");
        }
        if (config.oauthProject() == null || config.oauthProject().isBlank()) {
            throw new IllegalStateException(
                "Google 订阅未完成项目初始化（oauthProject 为空）：账号可能无订阅资格或需指定 GCP project，请重新登录 Google 订阅");
        }
        String sessionId = SessionIdResolver.resolve(history, options != null ? options.history() : null);
        // [MODEL-MAP] 与 stream 同源：统一 resolveBackendModel（官方无 flag 默认分支）
        String wireModel = resolveBackendModel(modelName);
        ObjectNode envelope = buildEnvelope(config, wireModel, systemPromptBlocks, history, tools,
            maxOutputTokensOverride, effortValue, thinkingConfig, options, sessionId);
        String url = OFFICIAL_HOST + "/" + API_VERSION + ":generateContent";
        String token = config.apiKey();
        boolean authRetried = false;
        IOException lastError = null;
        for (int attempt = 0; attempt < NON_STREAM_MAX_ATTEMPTS; attempt++) {
            if (aborted != null && aborted.get()) {
                throw new CancellationException("aborted");
            }
            TransportRequest req = new TransportRequest(url,
                buildHeaders(token, wireModel, sessionId, config, false), envelope.toString());
            try (TransportResponse response = transport.send(req)) {
                if (response.status() == 401) {
                    // [IND-C] 同 IND-B：刷新一次，仍 401 → 重登提示，⛔ 不再 refresh/retry
                    String body401 = readErrorBody(response);
                    if (!authRetried) {
                        String refreshed = forceRefreshAccess(token);
                        if (refreshed != null && !refreshed.isBlank()) {
                            authRetried = true;
                            token = refreshed;
                            continue;
                        }
                    }
                    throw withReloginHint(new LlmApiException(401, response.headers(), body401));
                }
                if (response.status() == 200) {
                    // [IND-A] 成功响应全量解析（Jackson 直接读输入流），⛔ 不用 64KB 截断。
                    //   64KB 上限仅用于诊断 errorBody（readErrorBody）；本地 JSON 解析失败 = 受控拒绝
                    //   （IllegalStateException，不被 catch(IOException) 当可重试网络故障）。
                    JsonNode node;
                    try {
                        node = MAPPER.readTree(response.body());
                    } catch (com.fasterxml.jackson.core.JsonProcessingException pe) {
                        throw new IllegalStateException(
                            "Google Gemini 非流式响应 JSON 解析失败（受控拒绝，不重试）", pe);
                    }
                    if (node.has("error")) {
                        JsonNode err = node.get("error");
                        throw classifyAndTranslate(
                            new LlmApiException(err.path("code").asInt(500), response.headers(), node.toString()));
                    }
                    Accumulator acc = new Accumulator(wireModel);
                    if (node.hasNonNull("traceId")) {
                        acc.requestId = node.get("traceId").asText();
                    }
                    JsonNode resp = node.path("response");
                    JsonNode usage = resp.path("usageMetadata");
                    if (usage.isObject()) {
                        acc.updateUsage(usage);
                    }
                    JsonNode candidates = resp.path("candidates");
                    if (candidates.isArray() && !candidates.isEmpty()) {
                        JsonNode candidate = candidates.get(0);
                        if (candidate.hasNonNull("finishReason")) {
                            acc.finishReasonRaw = candidate.get("finishReason").asText();
                        }
                        JsonNode parts = candidate.path("content").path("parts");
                        if (parts.isArray()) {
                            for (JsonNode part : parts) {
                                acc.processPart(part, null, null, null);
                            }
                        }
                    }
                    return acc.toAssistantMessage();
                }
                // 非 200：分类终态/可重试
                String body = readErrorBody(response);
                LlmApiException api = new LlmApiException(response.status(), response.headers(), body);
                RuntimeException translated = classifyAndTranslate(api);
                QuotaClassification qc = classifyQuota(api.status(), body);
                boolean retryableStatus = api.status() == 429 || api.status() == 499
                    || (api.status() >= 500 && api.status() <= 599);
                if (qc != null && qc.terminal) {
                    throw translated; // 终态配额/容量/日限 → 立即抛，⛔ 不重试
                }
                if (retryableStatus && attempt < NON_STREAM_MAX_ATTEMPTS - 1) {
                    long delay = qc != null && qc.retryDelayMs > 0 ? qc.retryDelayMs : 1000L;
                    lastError = new IOException(translated.getMessage(), translated);
                    awaitBackoff(Math.min(delay, MAX_RETRYABLE_DELAY_MS), abortSignal);
                    continue;
                }
                throw translated;
            } catch (IOException e) {
                // 连接类 IOException（LlmApiException 是 RuntimeException，不在此捕获）：
                //   官方 noResponseRetries:3 → 有限重试
                if (attempt < NON_STREAM_MAX_ATTEMPTS - 1) {
                    lastError = e;
                    awaitBackoff(1000L, abortSignal);
                    continue;
                }
                throw e;
            }
        }
        throw lastError != null ? lastError : new IOException("GoogleGeminiProvider 非流式重试耗尽");
    }

    // ===================== chat 家族（全覆写，同一非流式协议路径） =====================

    @Override
    public String chat(ProviderConfig config, String modelName, String systemPrompt, String userMessage) {
        return chatWithOptionsMessage(config, modelName, systemPrompt, userMessage, null).content();
    }

    @Override
    public LlmRawResponse chatWithRaw(ProviderConfig config, String modelName,
                                      String systemPrompt, String userMessage,
                                      com.nexusai.application.agent.subagent.AgentContext agentContext) {
        AssistantMessage msg = chatWithOptionsMessage(config, modelName, systemPrompt, userMessage, null);
        AgentUsage u = msg.usage();
        LlmUsage usage = new LlmUsage(
            (int) u.inputTokens(), (int) u.outputTokens(),
            u.cacheReadInputTokens() != null ? u.cacheReadInputTokens().intValue() : 0, 0);
        return new LlmRawResponse(msg.content(), null,
            msg.reasoning() == null || msg.reasoning().isEmpty() ? null : msg.reasoning(),
            msg.requestId(), usage);
    }

    @Override
    public String chatWithOptions(ProviderConfig config, String modelName, String systemPrompt,
                                  String userMessage, ChatRequestOptions options) {
        return chatWithOptionsMessage(config, modelName, systemPrompt, userMessage, options).content();
    }

    @Override
    public AssistantMessage chatWithOptionsMessage(ProviderConfig config, String modelName,
                                                 String systemPrompt, String userMessage,
                                                 ChatRequestOptions options) {
        AtomicBoolean aborted = new AtomicBoolean(false);
        CountDownLatch abortLatch = new CountDownLatch(1);
        if (options != null && options.abortController() != null) {
            if (options.abortController().isCancelled()) {
                throw new CancellationException("aborted before send");
            }
            options.abortController().onCancel(ac -> {
                aborted.set(true);
                abortLatch.countDown(); // [IND-D] 退避可被 abort 打断
            });
        }
        List<SystemPromptBlock> blocks = systemPrompt == null ? null
            : List.of(new SystemPromptBlock(systemPrompt, CacheScope.NULL));
        // userMessage 作为最后一条 user 追加（history 非空时亦追加，对齐接口契约）
        List<ChatMessageDto> history = new ArrayList<>(options != null ? options.history() : List.of());
        if (userMessage != null && !userMessage.isEmpty()) {
            history.add(new ChatMessageDto(null, null, Role.user, "user", userMessage,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, false));
        }
        Integer maxTokens = options != null ? options.maxTokens() : null;
        ChatRequestOptions.ThinkingConfig tc = options != null ? options.thinkingConfig() : null;
        ArrayNode tools = options != null ? options.tools() : null;
        try {
            return generateContent(config, modelName, blocks, history, tools, maxTokens,
                null, tc, options, aborted, abortLatch);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw (e instanceof RuntimeException re) ? re
                : new IllegalStateException("Google Gemini 非流式调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public LlmInvocationResult invoke(ProviderConfig config, String modelName, String systemPrompt,
                                      String userMessage, ChatRequestOptions options, LlmCallScope scope) {
        long startedAt = System.currentTimeMillis();
        AssistantMessage msg;
        try {
            msg = chatWithOptionsMessage(config, modelName, systemPrompt, userMessage, options);
        } catch (RuntimeException e) {
            LlmCallScope.record(scope, AgentUsage.EMPTY, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED);
            throw e;
        }
        AgentUsage usage = msg.usage();
        LlmCallScope.recordSettled(scope, usage);
        return new LlmInvocationResult(
            msg.content(), msg.finishReason(), msg.toolCalls(), usage,
            null,
            msg.reasoning() == null || msg.reasoning().isEmpty() ? null : msg.reasoning(),
            msg.requestId(), modelName, System.currentTimeMillis() - startedAt);
    }

    // ===================== 请求构造 =====================

    /** 出站头：Content-Type + Bearer + 官方形态 UA（contentGenerator.ts:269-272）+ 安全注入 extraHeaders。 */
    private Map<String, String> buildHeaders(String token, String modelName, String sessionId,
                                             ProviderConfig config, boolean sse) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", sse ? "text/event-stream" : "application/json");
        headers.put("User-Agent", userAgent(modelName));
        // 敏感头过滤 + 占位符展开统一走 ProviderHeaderInjector（与 OpenAI/Anthropic 同源判据）
        ProviderHeaderInjector.apply(headers::put, config.extraHeaders(), sessionId);
        return headers;
    }

    /**
     * [MODEL-MAP] 官方后端模型映射 · <b>固定无 flag 默认分支</b>（models.ts:144-156
     * {@code getBackendModelMappings(context, isCodeAssistBackend=true)}，
     * {@code hasLatestFlashGAAccess} 缺省 false）：{@code gemini-3.5-flash → gemini-3-flash}
     * （BASE_GEMINI_FLASH_MODEL → LEGACY_CCPA_FLASH_MODEL）。
     *
     * <p><b>边界（主会话 2026-11-25 裁决）</b>：
     * <ul>
     *   <li>⛔ 无证据不升级到 gemini-3.8-flash（需 GA flag，本批不持久化 flag/不做第二轮 DB 改动）；</li>
     *   <li>⛔ 不声称自动识别 GA 资格——GA 自动分档<b>未实现</b>，真实模型资格<b>未实测</b>；</li>
     *   <li>⛔ 不凭数字/substring 猜新别名：仅精确匹配官方表项；其余模型已是 wire ID，原样发送
     *       （flash-lite 无 flag 分支无映射；Pro 系列按确切官方表不动）。</li>
     * </ul>
     * stream 与所有非流请求统一经本方法；签名封装/解封装身份绑定<b>最终 wire model</b>（非 display alias）。
     */
    static String resolveBackendModel(String modelName) {
        if ("gemini-3.5-flash".equals(modelName)) {
            return "gemini-3-flash";
        }
        return modelName;
    }

    static String userAgent(String modelName) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String platform = os.contains("windows") ? "win32" : os.contains("mac") ? "darwin" : "linux";
        String arch = System.getProperty("os.arch", "");
        String normalizedArch = "amd64".equals(arch) || "x86_64".equals(arch) ? "x64"
            : "aarch64".equals(arch) ? "arm64" : arch;
        return "GeminiCLI/" + USER_AGENT_VERSION + "/" + modelName
            + " (" + platform + "; " + normalizedArch + "; cli)";
    }

    /** 完整信封（converter.ts:129-143 同构）。 */
    private ObjectNode buildEnvelope(ProviderConfig config, String modelName,
                                     List<SystemPromptBlock> systemPromptBlocks,
                                     List<ChatMessageDto> history, ArrayNode tools,
                                     Integer maxOutputTokensOverride, String effortValue,
                                     ChatRequestOptions.ThinkingConfig thinkingConfig,
                                     ChatRequestOptions options, String sessionId) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("model", modelName);
        envelope.put("project", config.oauthProject());
        envelope.put("user_prompt_id", UUID.randomUUID().toString());
        ObjectNode request = envelope.putObject("request");

        // systemInstruction：blocks join "\n\n" + history role=system 文本（不丢内容）
        StringBuilder system = new StringBuilder();
        if (systemPromptBlocks != null) {
            for (SystemPromptBlock b : systemPromptBlocks) {
                if (b != null && b.text() != null && !b.text().isEmpty()) {
                    if (system.length() > 0) {
                        system.append("\n\n");
                    }
                    system.append(b.text());
                }
            }
        }
        if (history != null) {
            for (ChatMessageDto m : history) {
                if (m != null && m.role() == Role.system && m.content() != null && !m.content().isEmpty()) {
                    if (system.length() > 0) {
                        system.append("\n\n");
                    }
                    system.append(m.content());
                }
            }
        }
        if (system.length() > 0) {
            ObjectNode si = request.putObject("systemInstruction");
            si.putArray("parts").addObject().put("text", system.toString());
        }

        request.set("contents", buildContents(history, modelName));

        // tools：OpenAI function 形态 → functionDeclarations（官方当前代码 parametersJsonSchema 透传，
        //   tools.ts:528-536 / tool-registry.ts:733-743；⛔ 不任意删字段。官方另有循环 schema 预检
        //   geminiChat.ts:1576-1595，本类未实现客户端预检 → 服务端拒绝时原样上抛，登记偏离）
        ArrayNode declarations = buildFunctionDeclarations(tools);
        if (declarations != null && !declarations.isEmpty()) {
            ObjectNode tool = request.putArray("tools").addObject();
            tool.set("functionDeclarations", declarations);
        }
        // toolChoice（强制工具）→ toolConfig.functionCallingConfig（Gemini 原生形态）
        if (options != null && options.toolChoice() != null && options.toolChoice().name() != null) {
            ObjectNode fc = request.putObject("toolConfig").putObject("functionCallingConfig");
            fc.put("mode", "ANY");
            fc.putArray("allowedFunctionNames").add(options.toolChoice().name());
        }

        ObjectNode generationConfig = request.putObject("generationConfig");
        if (maxOutputTokensOverride != null) {
            generationConfig.put("maxOutputTokens", maxOutputTokensOverride.intValue());
        }
        if (options != null && options.temperature() != null) {
            generationConfig.put("temperature", options.temperature().doubleValue());
        }
        ObjectNode thinking = mapThinkingConfig(modelName, thinkingConfig, effortValue);
        if (thinking != null) {
            generationConfig.set("thinkingConfig", thinking);
        }
        // 结构化输出（chatWithOptions outputFormat json_schema）
        if (options != null && options.outputFormat() != null && options.outputFormat().schema() != null) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.set("responseJsonSchema", options.outputFormat().schema());
        }
        if (sessionId != null && !sessionId.isBlank()) {
            request.put("session_id", sessionId);
        }
        // 模型级 extraBody：逐键合并进内层 request（只增/覆盖同名键，ProviderConfig.extraBody 语义）
        if (config.extraBody() != null && !config.extraBody().isEmpty()) {
            JsonNode extra = MAPPER.valueToTree(config.extraBody());
            if (extra.isObject()) {
                extra.fields().forEachRemaining(e -> request.set(e.getKey(), e.getValue()));
            }
        }
        return envelope;
    }

    /** OpenAI tools JSON → Gemini functionDeclarations（parametersJsonSchema 原样透传）。 */
    static ArrayNode buildFunctionDeclarations(ArrayNode tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        ArrayNode declarations = MAPPER.createArrayNode();
        for (JsonNode toolNode : tools) {
            if (toolNode == null || !toolNode.isObject()) {
                continue;
            }
            JsonNode fn = toolNode.has("function") && toolNode.get("function").isObject()
                ? toolNode.get("function") : toolNode;
            if (!fn.hasNonNull("name")) {
                log.warn("[GoogleGeminiProvider] 跳过无 name 的工具条目");
                continue;
            }
            ObjectNode decl = declarations.addObject();
            decl.put("name", fn.get("name").asText());
            if (fn.hasNonNull("description")) {
                decl.put("description", fn.get("description").asText());
            }
            JsonNode params = fn.get("parameters");
            if (params != null && params.isObject()) {
                decl.set("parametersJsonSchema", params);
            }
        }
        return declarations;
    }

    /**
     * thinking 映射（按模型家族分支，defaultModelConfigs.ts:31-63 取证）。
     * 优先级：显式 thinkingConfig &gt; effortValue off &gt; 模型家族默认。
     * effort 非 off 档位官方无 Gemini 数值映射 → 不投影（不编造 budget/level 公式）。
     */
    static ObjectNode mapThinkingConfig(String modelName, ChatRequestOptions.ThinkingConfig tc,
                                        String effortValue) {
        String m = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        boolean family3 = m.startsWith("gemini-3");
        boolean family25 = m.startsWith("gemini-2.5");
        if (!family3 && !family25) {
            return null; // 未知家族（gemma 等）→ 不发 thinkingConfig，走上游默认
        }
        ChatRequestOptions.ThinkingConfig resolved = tc;
        if (resolved == null && EffortSupport.isThinkingOff(effortValue)) {
            resolved = ChatRequestOptions.ThinkingConfig.disabled();
        }
        ObjectNode node = MAPPER.createObjectNode();
        if (family25) {
            int budget;
            if (resolved == null) {
                budget = DEFAULT_THINKING_BUDGET_25; // 官方 chat-base-2.5 默认
            } else {
                switch (resolved.type() == null ? "" : resolved.type()) {
                    case "disabled":
                        // 2.5 Pro 禁关思考（⛔ budget:0）→ 文档最小值 128；Flash/Flash-Lite 可 0
                        budget = m.contains("pro") ? MIN_THINKING_BUDGET_25_PRO : 0;
                        break;
                    case "enabled":
                        budget = resolved.budgetTokens() != null ? resolved.budgetTokens() : DEFAULT_THINKING_BUDGET_25;
                        // [IND-E] Pro 禁 0：enabled(0) 归一文档最小值 128（与 disabled→128 同源策略；
                        //   defaultModelConfigs.ts:401-407 Pro thinking:false 源码级，最小值 128 文档级）。
                        //   Flash 允许 0；sentinel -1 与显式合法 budget 不乱改。
                        if (m.contains("pro") && budget == 0) {
                            budget = MIN_THINKING_BUDGET_25_PRO;
                        }
                        break;
                    case "adaptive":
                        budget = -1; // SDK types.ts:3141：-1 = AUTOMATIC
                        break;
                    default:
                        budget = DEFAULT_THINKING_BUDGET_25;
                        break;
                }
            }
            node.put("thinkingBudget", budget);
            node.put("includeThoughts", true);
        } else {
            // 3 系：thinkingLevel（默认 HIGH；disabled→LOW，3 系不可完全关思考）
            String level = (resolved != null && "disabled".equals(resolved.type())) ? "LOW" : "HIGH";
            node.put("thinkingLevel", level);
            node.put("includeThoughts", true);
        }
        return node;
    }

    // ===================== 历史 → contents（方案 A 签名回放） =====================

    /**
     * 历史组装：user/assistant/tool 次序保留；tool 批合并单 user Content；相邻同角色合并；
     * thought 剥离；首 FC 缺签名注入合成令牌；未应答 FC 合成 error response 收口。
     */
    private ArrayNode buildContents(List<ChatMessageDto> history, String modelName) {
        ArrayNode contents = MAPPER.createArrayNode();
        if (history == null || history.isEmpty()) {
            return contents;
        }
        // toolCallId → 调用名/入参（全历史 assistant toolCalls 预扫描，id 为持久化真值）
        Map<String, ToolCallDto> callById = new HashMap<>();
        for (ChatMessageDto m : history) {
            if (m != null && m.role() == Role.assistant && m.toolCalls() != null) {
                for (ToolCallDto tc : m.toolCalls()) {
                    if (tc != null && tc.id() != null) {
                        callById.put(tc.id(), tc);
                    }
                }
            }
        }
        List<JsonNode> pendingToolParts = new ArrayList<>();
        for (ChatMessageDto m : history) {
            if (m == null || m.role() == Role.system) {
                continue; // system 已并入 systemInstruction
            }
            if (m.role() == Role.tool) {
                pendingToolParts.add(buildFunctionResponsePart(m, callById));
                // acceptFeedback / contentBlocks（权限允许附带）→ 同 user Content 独立 parts（不丢内容）
                if (m.acceptFeedback() != null && !m.acceptFeedback().isEmpty()) {
                    pendingToolParts.add(MAPPER.createObjectNode().put("text", m.acceptFeedback()));
                }
                appendUserContentBlocks(pendingToolParts, m.contentBlocks());
                continue;
            }
            if (!pendingToolParts.isEmpty()) {
                ObjectNode userContent = contents.addObject();
                userContent.put("role", "user");
                ArrayNode parts = userContent.putArray("parts");
                pendingToolParts.forEach(parts::add);
                pendingToolParts.clear();
            }
            if (m.role() == Role.user) {
                ObjectNode userContent = contents.addObject();
                userContent.put("role", "user");
                ArrayNode parts = userContent.putArray("parts");
                if (m.contentBlocks() != null && !m.contentBlocks().isEmpty()) {
                    appendUserContentBlocks(parts, m.contentBlocks());
                } else if (m.content() != null && !m.content().isEmpty()) {
                    parts.addObject().put("text", m.content());
                }
            } else if (m.role() == Role.assistant) {
                ObjectNode modelContent = contents.addObject();
                modelContent.put("role", "model");
                modelContent.set("parts", buildModelParts(m, modelName));
            }
        }
        if (!pendingToolParts.isEmpty()) {
            ObjectNode userContent = contents.addObject();
            userContent.put("role", "user");
            ArrayNode parts = userContent.putArray("parts");
            pendingToolParts.forEach(parts::add);
        }
        coalesceConsecutiveRoles(contents);
        closeUnansweredToolCalls(contents);
        return contents;
    }

    /** user contentBlocks（R32-b9 List&lt;JsonNode&gt;）→ Gemini parts：text/inlineData；URL 图显式报不兼容。 */
    private void appendUserContentBlocks(List<JsonNode> parts, List<?> contentBlocks) {
        if (contentBlocks == null) {
            return;
        }
        for (Object o : contentBlocks) {
            if (!(o instanceof JsonNode block) || !block.isObject()) {
                continue;
            }
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                parts.add(MAPPER.createObjectNode().put("text", block.path("text").asText("")));
            } else if ("image".equals(type)) {
                JsonNode source = block.path("source");
                if (source.hasNonNull("data") && source.hasNonNull("media_type")) {
                    ObjectNode inline = MAPPER.createObjectNode();
                    ObjectNode inlineData = inline.putObject("inlineData");
                    inlineData.put("mimeType", source.get("media_type").asText());
                    inlineData.put("data", source.get("data").asText());
                    parts.add(inline);
                } else {
                    // Gemini 不支持任意 URL 图片（fileData 仅限 Files API URI）→ 显式报不兼容，⛔ 不静默丢
                    throw new IllegalStateException(
                        "Gemini 订阅不支持 URL 图片输入（仅支持 base64 内联图片），请改用粘贴/上传的图片附件");
                }
            } else {
                log.warn("[GoogleGeminiProvider] user contentBlocks 跳过不支持的块类型: {}", type);
            }
        }
    }

    /** overload：ArrayNode parts 版（tool 批用 List，user 用 ArrayNode）。 */
    private void appendUserContentBlocks(ArrayNode parts, List<?> contentBlocks) {
        List<JsonNode> tmp = new ArrayList<>();
        appendUserContentBlocks(tmp, contentBlocks);
        tmp.forEach(parts::add);
    }

    /** role=tool → functionResponse part（name 经 toolCallId 精确解析；未知 id fail-loud）。 */
    private JsonNode buildFunctionResponsePart(ChatMessageDto toolMsg, Map<String, ToolCallDto> callById) {
        String toolCallId = toolMsg.toolCallId();
        ToolCallDto call = toolCallId != null ? callById.get(toolCallId) : null;
        if (call == null) {
            throw new IllegalStateException("GoogleGeminiProvider: 工具结果无法配对调用（toolCallId="
                + toolCallId + "），历史可能损坏，拒绝发送以免错配工具响应");
        }
        ObjectNode part = MAPPER.createObjectNode();
        ObjectNode fr = part.putObject("functionResponse");
        fr.put("name", call.name());
        if (toolCallId != null) {
            fr.put("id", toolCallId);
        }
        ObjectNode response = fr.putObject("response");
        String content = toolMsg.content() == null ? "" : toolMsg.content();
        if (toolMsg.isError()) {
            response.put("error", content);
        } else {
            response.put("output", content); // 官方 sessionUtils.ts:262-269 字符串结果形态
        }
        return part;
    }

    /**
     * assistant → model parts（方案 A）：blocks 与 canonical 核对等价 → 按块序还原 part 边界 + 解封装签名；
     * 不匹配/无 blocks → canonical 重建（无签名）。thought 块剥离不回放（官方 stripThoughts）。
     * 首个 functionCall 缺签名 → 合成令牌（官方唯一 fallback）。
     */
    private ArrayNode buildModelParts(ChatMessageDto dto, String modelName) {
        ArrayNode parts = MAPPER.createArrayNode();
        List<ContentBlockDto> blocks = dto.blocks();
        boolean verified = blocksMatchCanonical(dto, blocks);
        boolean firstCallPatched = false;
        if (verified) {
            for (ContentBlockDto b : blocks) {
                if (b == null || b.type() == null) {
                    continue;
                }
                switch (b.type()) {
                    case "thinking":
                        // 官方 stripThoughts：thought part 不回放（签名亦不随 thought 回放）
                        continue;
                    case "text": {
                        if (b.text() == null || b.text().isEmpty()) {
                            continue;
                        }
                        ObjectNode part = parts.addObject();
                        part.put("text", b.text());
                        String sig = unwrapSignature(b.signature(), modelName);
                        if (sig != null) {
                            part.put("thoughtSignature", sig);
                        }
                        break;
                    }
                    case "tool_use": {
                        ObjectNode part = parts.addObject();
                        ObjectNode fc = part.putObject("functionCall");
                        fc.put("name", b.name());
                        if (b.id() != null) {
                            fc.put("id", b.id());
                        }
                        fc.set("args", parseArgs(b.input()));
                        String sig = unwrapSignature(b.signature(), modelName);
                        if (sig == null && !firstCallPatched) {
                            sig = SYNTHETIC_THOUGHT_SIGNATURE;
                        }
                        if (sig != null) {
                            part.put("thoughtSignature", sig);
                        }
                        firstCallPatched = true;
                        break;
                    }
                    default:
                        // 未知块类型：保守不回放（verified 已要求全块类型已知，此分支不可达）
                        break;
                }
            }
        } else {
            // canonical 重建：text（dto.content）+ toolCalls 序；⛔ 不复用不匹配签名
            if (dto.content() != null && !dto.content().isEmpty()) {
                parts.addObject().put("text", dto.content());
            }
            if (dto.toolCalls() != null) {
                for (ToolCallDto tc : dto.toolCalls()) {
                    if (tc == null) {
                        continue;
                    }
                    ObjectNode part = parts.addObject();
                    ObjectNode fc = part.putObject("functionCall");
                    fc.put("name", tc.name());
                    if (tc.id() != null) {
                        fc.put("id", tc.id());
                    }
                    fc.set("args", parseArgs(tc.arguments()));
                    if (!firstCallPatched) {
                        part.put("thoughtSignature", SYNTHETIC_THOUGHT_SIGNATURE);
                        firstCallPatched = true;
                    }
                }
            }
        }
        // 官方 stripThoughts：model 轮被剥空且前邻是工具响应 → 中断占位（避免空 model Content）
        if (parts.isEmpty()) {
            parts.addObject().put("text", INTERRUPTED_RESPONSE_PLACEHOLDER);
        }
        return parts;
    }

    /** blocks 与 canonical 等价核对（text 拼接==content、thinking 拼接==reasoning、tool_use 逐 id/name/args）。 */
    static boolean blocksMatchCanonical(ChatMessageDto dto, List<ContentBlockDto> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<ContentBlockDto> toolBlocks = new ArrayList<>();
        for (ContentBlockDto b : blocks) {
            if (b == null || b.type() == null) {
                return false;
            }
            switch (b.type()) {
                case "text":
                    text.append(b.text() == null ? "" : b.text());
                    break;
                case "thinking":
                    thinking.append(b.thinking() == null ? "" : b.thinking());
                    break;
                case "tool_use":
                    toolBlocks.add(b);
                    break;
                default:
                    return false; // 未知类型 → 保守判不匹配
            }
        }
        String content = dto.content() == null ? "" : dto.content();
        String reasoning = dto.reasoning() == null ? "" : dto.reasoning();
        if (!text.toString().equals(content) || !thinking.toString().equals(reasoning)) {
            return false;
        }
        List<ToolCallDto> calls = dto.toolCalls() == null ? List.of() : dto.toolCalls();
        if (toolBlocks.size() != calls.size()) {
            return false;
        }
        for (int i = 0; i < calls.size(); i++) {
            ContentBlockDto tb = toolBlocks.get(i);
            ToolCallDto tc = calls.get(i);
            if (tc == null || !java.util.Objects.equals(tb.id(), tc.id())
                || !java.util.Objects.equals(tb.name(), tc.name())
                || !jsonEquals(tb.input(), tc.arguments())) {
                return false;
            }
        }
        return true;
    }

    private static boolean jsonEquals(String a, String b) {
        try {
            JsonNode na = (a == null || a.isBlank()) ? MAPPER.createObjectNode() : MAPPER.readTree(a);
            JsonNode nb = (b == null || b.isBlank()) ? MAPPER.createObjectNode() : MAPPER.readTree(b);
            return na.equals(nb);
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonNode parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.isObject() ? node : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** 相邻同角色 Content 合并（官方 coalesceConsecutiveRoles，geminiChat.ts:2209-2234）。 */
    static void coalesceConsecutiveRoles(ArrayNode contents) {
        for (int i = contents.size() - 1; i > 0; i--) {
            JsonNode prev = contents.get(i - 1);
            JsonNode cur = contents.get(i);
            if (prev.path("role").asText("").equals(cur.path("role").asText(""))) {
                ArrayNode prevParts = (ArrayNode) prev.path("parts");
                for (JsonNode p : cur.path("parts")) {
                    prevParts.add(p);
                }
                contents.remove(i);
            }
        }
    }

    /** 未应答 functionCall 收口（官方 closeUnansweredToolCallsTurn / buildInterruptedToolResponseParts）。 */
    static void closeUnansweredToolCalls(ArrayNode contents) {
        for (int i = 0; i < contents.size(); i++) {
            JsonNode content = contents.get(i);
            if (!"model".equals(content.path("role").asText(""))) {
                continue;
            }
            List<JsonNode> calls = new ArrayList<>();
            for (JsonNode p : content.path("parts")) {
                if (p.has("functionCall")) {
                    calls.add(p);
                }
            }
            if (calls.isEmpty()) {
                continue;
            }
            // 收集后邻 user Content 已应答 id
            java.util.Set<String> answered = new java.util.HashSet<>();
            if (i + 1 < contents.size() && "user".equals(contents.get(i + 1).path("role").asText(""))) {
                for (JsonNode p : contents.get(i + 1).path("parts")) {
                    JsonNode fr = p.path("functionResponse");
                    if (fr.hasNonNull("id")) {
                        answered.add(fr.get("id").asText());
                    }
                }
            }
            List<JsonNode> missing = new ArrayList<>();
            for (JsonNode call : calls) {
                String id = call.path("functionCall").path("id").asText(null);
                if (id == null || !answered.contains(id)) {
                    ObjectNode part = MAPPER.createObjectNode();
                    ObjectNode fr = part.putObject("functionResponse");
                    fr.put("name", call.path("functionCall").path("name").asText("generic_tool"));
                    if (id != null) {
                        fr.put("id", id);
                    }
                    fr.putObject("response").put("error", INTERRUPTED_TOOL_RESPONSE_ERROR);
                    missing.add(part);
                }
            }
            if (!missing.isEmpty()) {
                if (i + 1 < contents.size() && "user".equals(contents.get(i + 1).path("role").asText(""))) {
                    ArrayNode parts = (ArrayNode) contents.get(i + 1).path("parts");
                    missing.forEach(parts::add);
                } else {
                    ObjectNode userContent = MAPPER.createObjectNode();
                    userContent.put("role", "user");
                    ArrayNode parts = userContent.putArray("parts");
                    missing.forEach(parts::add);
                    contents.insert(i + 1, userContent);
                    i++; // 跳过新插入的 user Content
                }
            }
        }
    }

    // ===================== 签名封装（方案 A 身份核） =====================

    /** 封装：gca1|provider|model|base64url(raw)。⛔ 封装值不发 Google、不打日志。 */
    static String wrapSignature(String rawSignature, String modelName) {
        if (rawSignature == null || rawSignature.isEmpty()) {
            return null;
        }
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawSignature.getBytes(StandardCharsets.UTF_8));
        return SIG_PREFIX + PROVIDER_TYPE + "|" + modelName + "|" + encoded;
    }

    /** 解封装 + provider/model 身份核对；外来/跨模型/损坏 → null（⛔ 不误回放）。 */
    static String unwrapSignature(String stored, String currentModel) {
        if (stored == null || !stored.startsWith(SIG_PREFIX)) {
            return null;
        }
        String[] seg = stored.split("\\|", 4);
        if (seg.length != 4 || !PROVIDER_TYPE.equals(seg[1]) || !java.util.Objects.equals(seg[2], currentModel)) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(seg[3]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ===================== 流累积器 =====================

    /** 单次调用累积态（流式/非流式共用；blocks 按到达序，签名封装落块）。 */
    static final class Accumulator {
        final String modelName;
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final List<ToolUseBlock> toolCalls = new ArrayList<>();
        final List<ContentBlockDto> blocks = new ArrayList<>();
        String finishReasonRaw;
        String requestId;
        long promptTokens;
        long toolUsePromptTokens;
        long candidatesTokens;
        long thoughtsTokens;
        Long cachedTokens;
        boolean anyEvent;
        boolean anyOutputShown;
        boolean anyBlockFinished;
        private int toolIndex;

        Accumulator(String modelName) {
            this.modelName = modelName;
        }

        void processPart(JsonNode part, Consumer<String> onChunk,
                         Consumer<ToolUseBlock> onToolCallComplete, Consumer<String> onReasoningChunk) {
            if (part == null || !part.isObject()) {
                return;
            }
            String sig = part.hasNonNull("thoughtSignature") ? part.get("thoughtSignature").asText() : null;
            String wrapped = wrapSignature(sig, modelName);
            if (part.path("thought").asBoolean(false)) {
                String text = part.path("text").asText("");
                if (!text.isEmpty()) {
                    reasoning.append(text);
                    if (onReasoningChunk != null) {
                        onReasoningChunk.accept(text);
                    }
                }
                blocks.add(ContentBlockDto.thinking(text, wrapped, blocks.size()));
                return;
            }
            if (part.has("functionCall")) {
                JsonNode fc = part.get("functionCall");
                String name = fc.path("name").asText("");
                if (name.isEmpty()) {
                    log.warn("[GoogleGeminiProvider] functionCall 缺 name，跳过该 part");
                    return;
                }
                JsonNode args = fc.path("args");
                ObjectNode argsObj = args.isObject() ? (ObjectNode) args : MAPPER.createObjectNode();
                // Gemini 原生 functionCall 无 id → 稳定合成（持久化后跨重启不变）
                String id = fc.hasNonNull("id") ? fc.get("id").asText()
                    : "gcall_" + name + "_" + (toolIndex++) + "_"
                        + UUID.randomUUID().toString().substring(0, 8);
                ToolUseBlock block = new ToolUseBlock(id, name, argsObj);
                toolCalls.add(block);
                if (onToolCallComplete != null) {
                    onToolCallComplete.accept(block);
                }
                blocks.add(new ContentBlockDto("tool_use", null, null, wrapped,
                    id, name, argsObj.toString(), blocks.size()));
                return;
            }
            if (part.has("text")) {
                String text = part.get("text").asText("");
                if (!text.isEmpty()) {
                    content.append(text);
                    anyOutputShown = true;
                    if (onChunk != null) {
                        onChunk.accept(text);
                    }
                }
                blocks.add(new ContentBlockDto("text", text, null, wrapped, null, null, null, blocks.size()));
                return;
            }
            if (part.has("inlineData")) {
                // 聊天模型不产出图像 part；受控残留：显式告警，⛔ 不静默（登记限制）
                log.warn("[GoogleGeminiProvider] 响应含 inlineData part（聊天协议不支持模型图像输出），已跳过");
                return;
            }
            // 未知 part 类型：透传忽略（debug 级，协议前向兼容）
            if (log.isDebugEnabled()) {
                log.debug("[GoogleGeminiProvider] 未知 part 类型: {}", part.fieldNames().hasNext()
                    ? part.fieldNames().next() : "(empty)");
            }
        }

        void updateUsage(JsonNode usage) {
            // Gemini 流式 usageMetadata 为累计值，取最新（converter.ts:315-351 口径）
            if (usage.hasNonNull("promptTokenCount")) {
                promptTokens = usage.get("promptTokenCount").asLong();
            }
            // [USAGE-MAP] toolUsePromptTokenCount 是官方总量分解的独立加项（converter.ts:332-337
            //   computedTotal = prompt + candidates + toolUsePrompt + thoughts），非 prompt 子集；
            //   缺失/null/0 → 0（字段默认 0 + hasNonNull 守卫，现有归一规则）。
            if (usage.hasNonNull("toolUsePromptTokenCount")) {
                toolUsePromptTokens = usage.get("toolUsePromptTokenCount").asLong();
            }
            if (usage.hasNonNull("candidatesTokenCount")) {
                candidatesTokens = usage.get("candidatesTokenCount").asLong();
            }
            if (usage.hasNonNull("thoughtsTokenCount")) {
                thoughtsTokens = usage.get("thoughtsTokenCount").asLong();
            }
            if (usage.hasNonNull("cachedContentTokenCount")) {
                cachedTokens = usage.get("cachedContentTokenCount").asLong();
            }
        }

        AgentUsage usage() {
            // [USAGE-MAP] input = prompt + toolUsePrompt（官方独立加项）；output = candidates + thoughts
            //   （思考计入输出，与 OpenAI completion_tokens 含 reasoning 同口径）；
            //   cacheRead 仍 cachedContentTokenCount（prompt 已含 cached，⛔ 不双计；消费点走 totalTokens(false)）。
            return new AgentUsage(promptTokens + toolUsePromptTokens, candidatesTokens + thoughtsTokens,
                null, cachedTokens, null, null, null);
        }

        AssistantMessage toAssistantMessage() {
            String finish;
            String apiError = null;
            String raw = finishReasonRaw == null ? "STOP" : finishReasonRaw;
            switch (raw) {
                case "MAX_TOKENS":
                    finish = "max_tokens";
                    apiError = "max_output_tokens"; // AssistantMessage.isMaxOutputTokensError 判据
                    break;
                case "SAFETY":
                case "RECITATION":
                case "BLOCKLIST":
                case "SPII":
                case "PROHIBITED_CONTENT":
                    finish = "content_filter";
                    break;
                case "MALFORMED_FUNCTION_CALL":
                    finish = "error";
                    break;
                case "STOP":
                case "OTHER":
                default:
                    finish = "stop";
                    break;
            }
            if (!toolCalls.isEmpty()) {
                finish = "tool_calls"; // 工具轮权威信号仍是 hasToolCalls()；finishReason 对齐 OpenAI 约定
            }
            return new AssistantMessage(
                content.toString(), finish, List.copyOf(toolCalls), reasoning.toString(),
                apiError, usage(), requestId,
                null, // thinkingSignature 字段恒 null：Gemini 签名专属 blocks[].signature（防跨 provider 经共享字段误回放）
                blocks.isEmpty() ? null : List.copyOf(blocks));
        }
    }

    // ===================== 错误分类 / 401 / 终态 =====================

    /** 配额分类结果（googleQuotaErrors.ts:229-483 规则子集，源码级取证）。 */
    record QuotaClassification(boolean terminal, long retryDelayMs, String reason) {}

    /** 解析 Google API 错误体并分类；非配额状态返回 null。 */
    static QuotaClassification classifyQuota(int status, String body) {
        if (status != 429 && status != 499 && status != 503 && status != 400) {
            return null;
        }
        String message = "";
        JsonNode details = null;
        String errorInfoReason = null;
        String errorInfoDomain = null;
        JsonNode quotaFailure = null;
        long retryDelayMs = -1;
        try {
            JsonNode node = MAPPER.readTree(body == null ? "" : body);
            JsonNode err = node.path("error");
            message = err.path("message").asText("");
            details = err.path("details");
            if (details.isArray()) {
                for (JsonNode d : details) {
                    String type = d.path("@type").asText("");
                    if (type.endsWith("google.rpc.ErrorInfo")) {
                        errorInfoReason = d.path("reason").asText(null);
                        errorInfoDomain = d.path("domain").asText(null);
                    } else if (type.endsWith("google.rpc.QuotaFailure")) {
                        quotaFailure = d;
                    } else if (type.endsWith("google.rpc.RetryInfo")) {
                        retryDelayMs = parseDurationMs(d.path("retryDelay").asText(""));
                    }
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体 → 走消息文本兜底分类
        }
        String lower = message.toLowerCase(Locale.ROOT);
        // limit: 0 → 终态（googleQuotaErrors.ts:261-273）
        if ((status == 429 || status == 499 || status == 503) && lower.matches("(?s).*limit:\\s*0(?!\\d|\\.\\d).*")) {
            return new QuotaClassification(true, 0, "LIMIT_ZERO");
        }
        // 花费上限 → 终态（:277-287）
        if ((status == 400 || status == 429 || status == 499) && lower.matches("(?s).*\\b(spending|spend)\\s+cap\\b.*")) {
            return new QuotaClassification(true, 0, "SPENDING_CAP");
        }
        // 日限 → 终态（:347-356）
        if (quotaFailure != null) {
            for (JsonNode v : quotaFailure.path("violations")) {
                String quotaId = v.path("quotaId").asText("");
                if (quotaId.contains("PerDay") || quotaId.contains("Daily")) {
                    return new QuotaClassification(true, 0, "DAILY_QUOTA");
                }
            }
        }
        boolean cloudCodeDomain = errorInfoDomain != null
            && errorInfoDomain.replaceAll("[^a-zA-Z0-9.-]", "").endsWith("cloudcode-pa.googleapis.com");
        if (errorInfoReason != null) {
            // 容量耗尽 / 积分不足 → 终态（:369-389）
            if ("MODEL_CAPACITY_EXHAUSTED".equals(errorInfoReason) || "MODEL_CAPACITY_EXCEEDED".equals(errorInfoReason)
                || "INSUFFICIENT_G1_CREDITS_BALANCE".equals(errorInfoReason)) {
                return new QuotaClassification(true, Math.max(retryDelayMs, 0), errorInfoReason);
            }
            if (cloudCodeDomain) {
                if ("RATE_LIMIT_EXCEEDED".equals(errorInfoReason)) {
                    // 无延迟或 >300s → 终态；否则可重试（:394-416）
                    if (retryDelayMs < 0 || retryDelayMs > MAX_RETRYABLE_DELAY_MS) {
                        return new QuotaClassification(true, Math.max(retryDelayMs, 0), errorInfoReason);
                    }
                    return new QuotaClassification(false, retryDelayMs, errorInfoReason);
                }
                if ("QUOTA_EXHAUSTED".equals(errorInfoReason)) {
                    return new QuotaClassification(true, Math.max(retryDelayMs, 0), errorInfoReason);
                }
            }
        }
        // RetryInfo 延迟（:431-444）
        if (retryDelayMs >= 0) {
            return new QuotaClassification(retryDelayMs > MAX_RETRYABLE_DELAY_MS, retryDelayMs, "RETRY_INFO");
        }
        // 分钟限 → 可重试 60s（:447-469）
        if (quotaFailure != null) {
            for (JsonNode v : quotaFailure.path("violations")) {
                if (v.path("quotaId").asText("").contains("PerMinute")) {
                    return new QuotaClassification(false, 60_000L, "PER_MINUTE");
                }
            }
        }
        // 容量文案 → 终态（:472-478）
        if (lower.contains("exhausted your capacity") || lower.contains("capacity exceeded")
            || lower.contains("model_capacity_exhausted")) {
            return new QuotaClassification(true, 0, "CAPACITY_TEXT");
        }
        // "Please retry in Xs" 兜底（:297-310）
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("Please retry in ([0-9.]+(?:ms|s))").matcher(message);
        if (m.find()) {
            long delay = parseDurationMs(m.group(1));
            if (delay >= 0) {
                return new QuotaClassification(delay > MAX_RETRYABLE_DELAY_MS, delay, "RETRY_TEXT");
            }
        }
        if (status == 429 || status == 499 || status == 503) {
            return new QuotaClassification(false, 0, "GENERIC_RETRYABLE");
        }
        return null;
    }

    private static long parseDurationMs(String duration) {
        if (duration == null || duration.isEmpty()) {
            return -1;
        }
        try {
            if (duration.endsWith("ms")) {
                return (long) Double.parseDouble(duration.substring(0, duration.length() - 2));
            }
            if (duration.endsWith("s")) {
                return (long) (Double.parseDouble(duration.substring(0, duration.length() - 1)) * 1000);
            }
        } catch (NumberFormatException ignored) {
            // 解析失败 → 无延迟信息
        }
        return -1;
    }

    /** 错误翻译：配额终态附中文可读原因；403 VALIDATION_REQUIRED 附验证链接；其余保留 LlmApiException。 */
    private RuntimeException classifyAndTranslate(LlmApiException api) {
        int status = api.status();
        String body = api.body();
        if (status == 403 && body != null && body.contains("VALIDATION_REQUIRED")) {
            String link = extractValidationLink(body);
            return new LlmApiException(status, api.headers(),
                (body == null ? "" : body) + "\n（Google 订阅需要账号验证"
                    + (link != null ? "，请打开: " + link : "") + "）", api.kind());
        }
        if (status == 404) {
            return new LlmApiException(status, api.headers(),
                (body == null ? "" : body) + "\n（模型不存在或账号无该模型权限）", api.kind());
        }
        QuotaClassification qc = classifyQuota(status, body);
        if (qc != null && qc.terminal) {
            String hint = switch (qc.reason()) {
                case "DAILY_QUOTA" -> "今日配额已用完，请明天再试或切换模型";
                case "QUOTA_EXHAUSTED", "LIMIT_ZERO" -> "订阅配额已耗尽";
                case "MODEL_CAPACITY_EXHAUSTED", "MODEL_CAPACITY_EXCEEDED", "CAPACITY_TEXT" -> "模型容量暂时耗尽，请稍后再试或切换模型";
                case "INSUFFICIENT_G1_CREDITS_BALANCE" -> "AI Credits 余额不足";
                case "SPENDING_CAP" -> "项目花费上限已达";
                default -> "配额限制（等待时间过长或不可重试）";
            };
            return new LlmApiException(status, api.headers(),
                (body == null ? "" : body) + "\n（" + hint + "）", api.kind());
        }
        return api;
    }

    private static String extractValidationLink(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            for (JsonNode d : node.path("error").path("details")) {
                if (d.path("@type").asText("").endsWith("google.rpc.Help")) {
                    JsonNode links = d.path("links");
                    if (links.isArray() && !links.isEmpty()) {
                        return links.get(0).path("url").asText(null);
                    }
                }
                if (d.path("@type").asText("").endsWith("google.rpc.ErrorInfo")) {
                    JsonNode meta = d.path("metadata");
                    if (meta.hasNonNull("validation_link")) {
                        return meta.get("validation_link").asText();
                    }
                    if (meta.hasNonNull("validation_url")) {
                        return meta.get("validation_url").asText();
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败 → 无链接
        }
        return null;
    }

    /** 401 刷新（ResponsesSdkProvider:1474-1485 同款；失败 → null，⛔ 不抛）。 */
    private String forceRefreshAccess(String currentAccess) {
        com.nexusai.domain.provider.ProviderService service = this.providerService;
        if (service == null || currentAccess == null || currentAccess.isBlank()) {
            return null;
        }
        try {
            return service.forceRefreshByAccessToken(currentAccess);
        } catch (Exception e) {
            log.warn("[GoogleGeminiProvider] 401 后强制刷新 OAuth 凭据失败: {}", e.getMessage());
            return null;
        }
    }

    /** 认证失效可读错误（保留 status，body 追加中文提示）。 */
    static RuntimeException withReloginHint(RuntimeException translated) {
        String hint = "（登录已失效，请重新登录 Google 订阅）";
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

    private static String readErrorBody(TransportResponse response) {
        try {
            return readFullBody(response);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readFullBody(TransportResponse response) throws IOException {
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(64 * 1024);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean isIdleReadTimeout(Exception e, StreamIdleControl control) {
        if (control == null || control.idleTimeoutMs() <= 0) {
            return false;
        }
        // java.net.http 读阻塞经 close 解阻塞 → IOException（SocketException/Stream closed）
        return e instanceof IOException && !(e instanceof LlmApiException);
    }

    private StreamIdleTimeoutError buildStreamIdleError(Accumulator acc) {
        StreamIdleTimeoutError.Progress progress = acc.anyBlockFinished
            ? (acc.anyOutputShown ? StreamIdleTimeoutError.Progress.OUTPUT
                : StreamIdleTimeoutError.Progress.THINKING_ONLY)
            : (acc.anyOutputShown ? StreamIdleTimeoutError.Progress.PARTIAL_OUTPUT
                : (acc.anyEvent ? StreamIdleTimeoutError.Progress.STARTED
                    : StreamIdleTimeoutError.Progress.NOTHING));
        return new StreamIdleTimeoutError(progress, acc.finishReasonRaw != null,
            "Google Gemini stream idle timeout");
    }

    private static void failOnce(AtomicBoolean terminated, Consumer<Throwable> onError, Throwable t) {
        if (terminated.compareAndSet(false, true) && onError != null) {
            onError.accept(t);
        }
    }

    /**
     * [IND-D] 可取消退避：abort → 立即结束等待并以 CancellationException 收口，⛔ 不发下一 HTTP；
     * 用 latch 事件等待，⛔ 不 interrupt 共享执行线程。无 abort 载体时回落 Thread.sleep。
     */
    private static void awaitBackoff(long delayMs, CountDownLatch abortSignal) throws InterruptedException {
        if (abortSignal != null) {
            if (abortSignal.await(delayMs, TimeUnit.MILLISECONDS)) {
                throw new CancellationException("aborted during retry backoff");
            }
        } else {
            Thread.sleep(delayMs);
        }
    }

    private static void recordAttempt(LlmCallScope scope, AgentUsage usage, LlmUsagePhase phase) {
        boolean hasTokens = usage != null && usage.totalTokens() > 0;
        LlmCallScope.record(scope, hasTokens ? usage : AgentUsage.EMPTY,
            hasTokens ? UsageTokenSource.UPSTREAM : UsageTokenSource.UNKNOWN, phase);
    }
}
