package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Away session recap · 对齐 CC services/awaySummary.ts generateAwaySummary.
 *
 * <p><b>IMP-M-P2-3 重建</b>（DEL-M-29 处置 a：改接，不删除）: 旧静态 4 参
 * {@code generate(messages, aborted, memory, llmInvoker)}（BiFunction 注入 seam + 自建
 * message 表示）已删除 —— CC 两参契约 {@code generateAwaySummary(messages, signal)}
 * 用真实 llmInvoker（{@code queryModelWithoutStreaming}），Java 端改接
 * {@link LlmProvider#chatWithOptions} + 构造注入真实依赖。
 *
 * <p>L1 语义: "用户离开后回来" 场景生成 1-3 句 session recap。空 transcript / API error /
 * abort / 非 abort 异常 → null；成功路径 trim + 空 → null。
 *
 * <p>L2 llmInvoker 契约（CC awaySummary.ts:41-57 queryModelWithoutStreaming options）：
 * <ul>
 *   <li>{@code querySource: 'away_summary'}（:54）</li>
 *   <li>{@code skipCacheWrite: true}（:56）</li>
 *   <li>{@code model: getSmallFastModel()}（:49）→ 经 {@code smallFastModelSupplier} 注入</li>
 *   <li>{@code thinkingConfig: {type: 'disabled'}}（:44）</li>
 *   <li>{@code signal}（:46）→ provider abort 预检（claude.ts:744-745 等价）</li>
 *   <li>{@code systemPrompt: asSystemPrompt([])}（:43 空数组）→ Java systemPrompt=null 等价</li>
 *   <li>{@code tools: []}（:45 空数组 = 不调工具）→ Java tools=null 等价</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CompletableFuture&lt;String&gt; 两参异步契约 + 显式 sessionId 参数（AS-05 rev2：
 * REST 载体 resolveSessionId 解析一次后注入，memory 读与消息加载同源单轨；CC
 * sessionMemoryUtils.ts:110-126 无参读当前会话）；abort 经 {@link AbortController}（CC AbortSignal）。
 *
 * <p><b>触发层 N/A（OPD-M-39 / FIX-CL concern）</b>: CC 调用点在前端 REPL blur（useAwaySummary.ts
 * BLUR_DELAY_MS=5*60_000 + feature('AWAY_SUMMARY') + flag 'tengu_sedge_lantern' 默认 false），
 * Web 后端无 blur/focus —— FIX-CL 注册 {@code @Bean}（ToolRegistrationConfig）使服务生产可达，
 * 但触发层待前端接线（建议后续加 {@code POST /api/agent/away-summary} 供前端调）。不硬接
 * LlmAgentLoop（INV-14）。
 */
public class AwaySummaryService {

    private static final Logger log = LoggerFactory.getLogger(AwaySummaryService.class);

    /** CC awaySummary.ts:16 — RECENT_MESSAGE_WINDOW = 30（30 条 ≈ 15 轮，防止 prompt too long）。 */
    public static final int RECENT_MESSAGE_WINDOW = 30;

    /** llmInvoker 载体（CC queryModelWithoutStreaming → Java chatWithOptions）· FIX-CL 改惰性解析（生产 @Bean）。 */
    private final Supplier<LlmProvider> llmProviderSupplier;

    /** provider 运行时配置（baseUrl + apiKey）· FIX-CL 改惰性解析（生产 @Bean）。 */
    private final Supplier<ProviderConfig> providerConfigSupplier;

    /** session memory 读取（CC getSessionMemoryContent，sessionMemoryUtils.ts:110-126）。 */
    private final SessionMemoryService sessionMemoryService;


    /** small-fast 模型供应器 · CC original: getSmallFastModel()（model.ts:36-38）。 */
    private final Supplier<String> smallFastModelSupplier;

    /** 具体 provider/config 构造（测试/直构）· 包装为常量 supplier。 */
    public AwaySummaryService(
            LlmProvider llmProvider,
            ProviderConfig providerConfig,
            SessionMemoryService sessionMemoryService,
            Supplier<String> smallFastModelSupplier) {
        this(() -> Objects.requireNonNull(llmProvider, "llmProvider"),
            () -> Objects.requireNonNull(providerConfig, "providerConfig"),
            sessionMemoryService, smallFastModelSupplier);
    }

    /**
     * 惰性 provider/config 构造（FIX-CL 生产 @Bean 接线）· 对齐 ProductionForkedQuery 运行时
     * LLM 解析模式：provider/config 在 {@link #generate} 每次调用时解析，避免 bean 构造期
     * （Spring 启动，settings/DB 尚未就绪）锁定 mock provider。
     */
    public AwaySummaryService(
            Supplier<LlmProvider> llmProviderSupplier,
            Supplier<ProviderConfig> providerConfigSupplier,
            SessionMemoryService sessionMemoryService,
            Supplier<String> smallFastModelSupplier) {
        this.llmProviderSupplier = Objects.requireNonNull(llmProviderSupplier, "llmProviderSupplier");
        this.providerConfigSupplier = Objects.requireNonNull(providerConfigSupplier, "providerConfigSupplier");
        this.sessionMemoryService = Objects.requireNonNull(sessionMemoryService, "sessionMemoryService");
        this.smallFastModelSupplier = Objects.requireNonNull(smallFastModelSupplier, "smallFastModelSupplier");
    }

    /**
     * 生成 away session 摘要 · 对齐 CC {@code generateAwaySummary(messages, signal)}
     * （awaySummary.ts:29-74）。
     *
     * <p>失败/空语义（CC :33-35 空→null；:60-73 abort/API error/非 abort 异常→null；
     * messages.ts:2855 成功 trim+空→null）。
     *
     * <p><b>AS-05（rev2）</b>: sessionId 由调用方显式注入（REST 载体 resolveSessionId
     * body→query→MDC 解析一次），memory 读与消息加载同源单轨 —— CC
     * {@code getSessionMemoryContent()} 无参读当前会话（sessionMemoryUtils.ts:110）经
     * 调用方注入表达；旧的注入 supplier（MDC）双轨已删除。
     *
     * @param messages 当前 session 全部 messages（CC transcript；readonly Message[] 恒非空，
     *                 传 null 为契约违规 → NPE）
     * @param signal   取消信号（CC AbortSignal；provider 预检 claude.ts:744-745 + 本方法
     *                 catch CancellationException → null 无日志）
     * @param sessionId 当前会话 ID（CC 无参读当前会话的调用方注入；memory 读取用）
     * @return 1-3 句 recap 文本；空/失败 → null
     */
    public CompletableFuture<String> generate(List<ChatMessageDto> messages, AbortController signal,
                                              String sessionId) {
        // Q2 完成消息去重（OPD-CM3-24 · CC useAwaySummary.ts:16-23/:71 hasSummarySinceLastUserTurn）：
        //   自末条真实 user 消息（非 meta / 非 compactSummary）之后若已存在 subtype='away_summary'
        //   系统消息则跳过生成（避免重复 recap）。CC 在触发层 generate() 检查
        //   `if (hasSummarySinceLastUserTurn(messages)) return`（useAwaySummary.ts:71），
        //   后端 REST 载体（AwaySummaryController）亦应实现（F15 去重后端侧）。
        //   [F-11 登记 · IMP-MV2-40] △-11 去重位置（拍板 F15）：触发层（CC useAwaySummary）→
        //   服务层后移（本方法）；判定逐行同构 —— 登记声明。
        if (hasSummarySinceLastUserTurn(messages)) {
            if (log.isDebugEnabled()) {
                log.debug("[AwaySummary] 末条真实 user 消息后已存在 away_summary → 跳过生成（去重）");
            }
            return CompletableFuture.completedFuture(null);
        }
        // CC awaySummary.ts:33-35 — messages.length === 0 → null（D-19：==null 半支删除，
        // CC readonly Message[] 恒非空，仅保留 isEmpty 判定）
        if (messages.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[AwaySummary] 空 transcript → null（CC awaySummary.ts:33-35）");
            }
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // CC :38 getSessionMemoryContent() 无参读当前会话（fs-inaccessible→null 否则 rethrow，
                //   异常流进外层 catch → null，sessionMemoryUtils.ts:122-125）
                // [F-8 登记 · IMP-MV2-40] △-8 显式 sessionId 单轨（拍板 AS-05 rev2）：CC :38
                //   getSessionMemoryContent() 无参读当前会话；Java 经 resolveSessionId 显式参数
                //   单轨 —— 登记声明。
                String memory = sessionMemoryService.getSessionMemoryContent(sessionId);
                if (log.isDebugEnabled()) {
                    log.debug("[AwaySummary] 读取 session memory: session={} memoryChars={}",
                        sessionId, memory == null ? 0 : memory.length());
                }

                // CC :39-40 — slice(-RECENT_MESSAGE_WINDOW) + push(createUserMessage(prompt))
                int from = Math.max(0, messages.size() - RECENT_MESSAGE_WINDOW);
                List<ChatMessageDto> recent = new ArrayList<>(messages.subList(from, messages.size()));
                recent.add(userMessage(buildAwaySummaryPrompt(memory)));

                // CC :41-57 — queryModelWithoutStreaming options（querySource/skipCacheWrite/small-fast/thinking-disabled）
                LlmProvider.ChatRequestOptions options = buildAwaySummaryOptions(recent, signal);
                // FIX-CL：provider/config 惰性解析（每次调用取最新，对齐 ProductionForkedQuery 模式）
                LlmProvider provider = llmProviderSupplier.get();
                ProviderConfig config = providerConfigSupplier.get();
                String content = provider.chatWithOptions(
                    config, smallFastModelSupplier.get(), null, null, options);
                if (log.isDebugEnabled()) {
                    log.debug("[AwaySummary] LLM 调用完成: querySource=away_summary skipCacheWrite=true "
                            + "model={} contentLen={}",
                        smallFastModelSupplier.get(), content == null ? 0 : content.length());
                }

                // CC messages.ts:2855 getAssistantMessageText — join('\n').trim() || null（成功 trim+空→null）
                if (content == null) {
                    return null;
                }
                String trimmed = content.trim();
                return trimmed.isEmpty() ? null : trimmed;
            } catch (CancellationException e) {
                // CC awaySummary.ts:68 — APIUserAbortError（Java CancellationException 等价）
                // → null，静默无日志
                return null;
            } catch (LlmApiException e) {
                // CC :60-65 — isApiErrorMessage → logForDebugging('[awaySummary] API error') + null；
                // CC :68 abort 双条件优先：signal 已 abort 时任何错误静默（:68 在 :71 之前，
                // API error 亦落入 catch → abort 静默）
                if (signal.isCancelled()) {
                    return null;
                }
                if (log.isDebugEnabled()) {
                    log.debug("[AwaySummary] API error: {}", e.toString());
                }
                return null;
            } catch (Exception e) {
                // CC :71 — logForDebugging('[awaySummary] generation failed') + null；
                // CC :68 — abort 状态下（signal.aborted）→ 静默 null（无日志）
                if (signal.isCancelled()) {
                    return null;
                }
                if (log.isDebugEnabled()) {
                    log.debug("[AwaySummary] generation failed: {}", e.toString());
                }
                return null;
            }
        });
    }

    /**
     * 组装 queryModelWithoutStreaming options 契约 · 对齐 CC awaySummary.ts:41-57。
     *
     * <p>{@code systemPrompt} 与 {@code tools} 分别以 chatWithOptions 第 3 参 null 与
     * options.tools null 表达（CC :43 asSystemPrompt([]) + :46 tools:[] 空数组 = 不调工具）。
     *
     * @param recent CC messages 参数（slice(-30) + 追加 prompt user message）
     * @param signal CC signal 参数
     * @return 组装后的 ChatRequestOptions
     */
    private LlmProvider.ChatRequestOptions buildAwaySummaryOptions(
            List<ChatMessageDto> recent, AbortController signal) {
        return new LlmProvider.ChatRequestOptions(
            recent,                                    // history — CC messages（:41）
            null,                                      // tools — CC :46 tools: []（空数组 = 不调工具）
            null,                                      // outputFormat — CC 未设
            LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),  // CC :44 thinkingConfig: {type:'disabled'}
            null,                                      // temperature — CC 未设
            "away_summary",                            // CC :54 querySource: 'away_summary'
            signal,                                    // CC :45 signal → provider abort 预检（claude.ts:744-745）
            null,                                      // maxTokens — CC 未设
            Boolean.TRUE);                             // CC :56 skipCacheWrite: true
    }

    /** CC buildAwaySummaryPrompt（awaySummary.ts:18-23）· 逐字对齐，含 memory 前缀。 */
    static String buildAwaySummaryPrompt(String memory) {
        String memoryBlock = memory != null && !memory.isEmpty()
            ? "Session memory (broader context):\n" + memory + "\n\n"
            : "";
        return memoryBlock + "The user stepped away and is coming back. " +
            "Write exactly 1-3 short sentences. Start by stating the high-level task — " +
            "what they are building or debugging, not implementation details. " +
            "Next: the concrete next step. Skip status reports and commit recaps.";
    }

    /**
     * Q2 完成消息去重判定 · CC original: {@code hasSummarySinceLastUserTurn}
     * （useAwaySummary.ts:16-23，generate() :71 触发层调用）。
     *
     * <p>自消息列表<b>末尾向前</b>扫描（CC :17 for 循环 i=messages.length-1→0）：
     * <ul>
     *   <li>遇到真实 user 消息（type==='user' && !isMeta && !isCompactSummary，CC :19）
     *       → 返回 false（末条 user 之后无 away_summary，应生成）</li>
     *   <li>遇到 subtype='away_summary' 的 system 消息（CC :20）→ 返回 true（已有摘要，跳过）</li>
     *   <li>扫到开头仍未命中 → 返回 false（CC :22 兜底）</li>
     * </ul>
     *
     * <p>WHY（OPD-CM3-24 Q2 / F15）: 前端 blur 触发层用该方法避免重复 recap；后端
     * REST 载体（AwaySummaryController 经 {@link #generate} 检查）亦应实现去重，防止
     * 前端连续两次 POST 生成重复摘要消息。
     *
     * @param messages 会话消息列表（CC readonly Message[]；null/空 → false）
     * @return true = 末条真实 user 消息之后已存在 away_summary，应跳过生成
     */
    static boolean hasSummarySinceLastUserTurn(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m == null) {
                continue;
            }
            if (m.role() == Role.user && !m.isMeta() && !m.isCompactSummary()) {
                return false;
            }
            if (m.role() == Role.system && "away_summary".equals(m.subtype())) {
                return true;
            }
        }
        return false;
    }

    /** 创建 prompt user 消息 · 对齐 CC createUserMessage({content})（messages.ts:460-523，isMeta 缺省 false）。 */
    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }
}
