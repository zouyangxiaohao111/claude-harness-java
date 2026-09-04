package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.application.agent.compact.CompactProgressEvent.HooksStart;
import com.nexusai.application.agent.compact.CompactProgressEvent.HooksStart.HookType;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 全量压缩单函数 · 对齐 CC {@code compactConversation}
 * （Open-ClaudeCode/src/services/compact/compact.ts:387-763）。
 *
 * <p><b>WHY 存在（IMP-04 主流程重建）</b>: CC 压缩为<b>单函数</b>（compact.ts:387），
 * Java 旧架构的桩服务/四层管线/编排器（D-01/D-02/D-03）均与 CC 语义偏移，已删除。
 * 本类重建为 CC 单函数等价实现：
 * <ol>
 *   <li>空校验（compact.ts:398 throw NOT_ENOUGH_MESSAGES，REQ-01）</li>
 *   <li>preCompactTokenCount（tokenCountWithEstimation，compact.ts:401）</li>
 *   <li>pre_compact 事件 + SDK 状态（compact.ts:406-412）+ PreCompact hooks + mergeHookInstructions（:413-424）</li>
 *   <li>compact_start（:429）+ getCompactPrompt（:440）</li>
 *   <li>PTL 重试循环（MAX_PTL_RETRIES=3 + truncateHeadForPTLRetry，:450-491，REQ-03）</li>
 *   <li>无摘要 / API 前缀抛错（:493-515）</li>
 *   <li>readFileState 缓存（:518-521，REQ-04）→ 附件恢复（file 5/50K/5K + async-agent + plan +
 *       plan_mode + skill 5K/25K + 3×delta，:531-585）</li>
 *   <li>session_start hooks（:587-594，REQ-06）→ boundary（:598-611）→ summaryMessages（:613-624）</li>
 *   <li>度量三口径（:629-645）+ notifyCompaction/markPostCompaction/reAppendSessionMetadata（:698-711）</li>
 *   <li>post_compact hooks（:719-729）→ CompactionResult 10 字段（:738-748）</li>
 *   <li>错误通知（addErrorNotificationIfNeeded，:1108-1123）+ finally compact_end（:757-762）</li>
 * </ol>
 *
 * <p><b>类归属</b>: 本类为 compact 域 CC 契约宿主（IMP-04 新增）。生产接线（IMP-07/10/12）
 * 把摘要生产适配到 {@link StreamCompactSummary}（IMP-01 产物），并把事件/通知消费接到
 * LlmAgentLoop per-session 流。
 *
 * @see CompactConversationContext
 * @see CompactionResult
 * @see CompactHooks
 * @see PostCompactAttachmentRestorer
 */
public final class CompactConversation {

    private static final Logger log = LoggerFactory.getLogger(CompactConversation.class);

    private CompactConversation() { /* 静态工具类 */ }

    // ════════════════════════════════════════════════════════════════════
    // [MF2-3] invoked_skills 重注入数据源 holder（CC STATE 读侧语义）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 会话 AgentState 注册表（invoked_skills 数据源）· 对齐 CC 全局 {@code STATE}
     * （bootstrap/state.ts:1530 getInvokedSkillsForAgent 读侧）。
     *
     * <p><b>WHY 静态 holder</b>: CC 压缩单函数直接读进程内全局 STATE；Java 端按会话分散
     * （SessionAgentStateRegistry 每 session 主 AgentState），compactConversation 为静态
     * 单函数无法注入实例，故以 volatile 静态 holder 承接注册表，由
     * {@link AutoCompactor}（auto 路径）注入、{@link CompactCommand}（manual 前瞻面）透传、
     * 测试直接注入。未注入 → invoked_skills 重注入安全跳过（不中断压缩成功路径）。
     */
    private static volatile SessionAgentStateRegistry sessionAgentStateRegistry;

    /**
     * 注入会话 AgentState 注册表（幂等）· AutoCompactor 经 @Autowired 在 autoCompactIfNeeded
     * 调用压缩前写入；测试可经此注入显式验证。null 注入 = 复位（跳过 skill 重注入）。
     *
     * @param registry 会话 AgentState 注册表（null → skill 重注入关闭）
     */
    public static void setSessionAgentStateRegistry(SessionAgentStateRegistry registry) {
        sessionAgentStateRegistry = registry;
        log.info("[CompactConversation] SessionAgentStateRegistry 注入: {}",
            registry != null ? "已注入" : "null（skill 重注入关闭）");
    }

    // ════════════════════════════════════════════════════════════════════
    // [V54 token-compact-fix B1-2] 压缩配置 DB 实时读源静态槽位（PTL 重试上限）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 压缩配置 DB 实时读源 · [V54 token-compact-fix B1-2] 静态 volatile 槽位
     * （同 {@link com.nexusai.application.agent.compact.BoundaryReader} /
     * {@link com.nexusai.application.agent.compact.MicroCompactor} 先例：compactConversation
     * 为静态单函数无法实例注入）。PTL 重试上限 {@code settings.max_ptl_retries} 实时读；
     * 未注入 → 回落常量默认 3。
     *
     * <p>注入：ToolRegistrationConfig.autoCompactor bean（settingsResolver 单例）。
     */
    private static volatile CompactSettingsResolver settingsResolver;

    /**
     * 注入压缩配置 DB 实时读源（幂等）· 同 {@link AutoCompactor#setSettingsResolver} 回落语义
     * （null → 复位回落常量默认）。
     *
     * @param resolver 压缩配置实时读源（null → 复位回落常量默认）
     */
    public static void setSettingsResolver(CompactSettingsResolver resolver) {
        settingsResolver = resolver;
        if (log.isDebugEnabled()) {
            log.debug("[CompactConversation] setSettingsResolver: 注入={}（PTL 重试上限 DB 实时覆盖，"
                + "null 回落常量）", resolver != null ? "已注入" : "复位");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [A5-2] 协议分派 mapper 静态槽位（求和 provider 分派 · deepseek 双计修复）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 模型/provider mapper 静态槽位 · [A5-2 求和 provider 分派] 协议判定（isAnthropic）原料。
     *
     * <p>同 {@link #settingsResolver} 先例：compactConversation 为静态单函数无法实例注入，
     * 以 volatile 静态槽位承接，由 {@link com.nexusai.application.agent.config.ToolRegistrationConfig}
     * autoCompactor bean 启动期注入一次（auto/reactive/manual/partial 全路径共用同一全局槽位）。
     * 未注入 → {@link #resolveAnthropic} 回落 anthropic 语义（既有 4 项和，向后兼容测试）。
     */
    private static volatile ModelMapper modelMapper;
    private static volatile ProviderMapper providerMapper;

    /**
     * 注入协议分派 mapper（幂等）· null 注入 = 复位（回落 anthropic 语义）。
     *
     * @param mm  模型 mapper（null → 回落）
     * @param pm  提供商 mapper（null → 回落）
     */
    public static void setMappers(ModelMapper mm, ProviderMapper pm) {
        modelMapper = mm;
        providerMapper = pm;
        if (log.isDebugEnabled()) {
            log.debug("[CompactConversation] setMappers: 注入 modelMapper={} providerMapper={}（A5-2 求和分派）",
                mm != null, pm != null);
        }
    }

    /**
     * 压缩路径协议判定 · [A5-2] 由模型名解析 isAnthropic（经静态 mapper 槽位）。
     *
     * <p>回落语义：mapper 或模型不可得 → <b>true（anthropic 语义，既有 4 项和）</b>——
     * 与 1 参方法默认一致，避免未接线（测试/手动直构）改变既有行为；生产（mappers 注入 +
     * ctx.model 已设）→ 真实分派（deepseek 走 input+output）。
     *
     * @param model 生效模型名（ctx.getModel()；null/blank → 回落 anthropic）
     * @return true=Anthropic 4 项和；false=OpenAI/DeepSeek 仅 input+output
     */
    static boolean resolveAnthropic(String model) {
        if (modelMapper == null || providerMapper == null || model == null || model.isBlank()) {
            return true; // mapper/模型不可得 → 保持 anthropic 语义（既有 4 项和，向后兼容）
        }
        return ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, model);
    }

    // ════════════════════════════════════════════════════════════════════
    // 嵌套类型
    // ════════════════════════════════════════════════════════════════════

    /**
     * 摘要生产 · 对齐 CC {@code streamCompactSummary}（compact.ts:451，摘要 API 调用）。
     *
     * <p>生产接线由调用方注入（默认经 {@link StreamCompactSummary#streamCompactSummary}
     * 适配，IMP-01 产物）；测试用 fake。PTL 时返回文本以
     * {@link com.nexusai.application.agent.api.ApiErrors#PROMPT_TOO_LONG_ERROR_MESSAGE} 前缀开始，
     * 触发 truncateHeadForPTLRetry 重试循环（compact.ts:450-491）。
     */
    @FunctionalInterface
    public interface SummaryProducer {
        /**
         * 生产压缩摘要。
         *
         * @param messages            待摘要消息（PTL 重试时逐轮截断）
         * @param compactPrompt       压缩提示词（CC summaryRequest content）
         * @param preCompactTokenCount 压缩前 token（日志/遥测）
         * @return 摘要结果（text + usage）
         */
        SummaryResult summarize(List<ChatMessageDto> messages, String compactPrompt, int preCompactTokenCount);
    }

    /**
     * 摘要生产结果 · 对齐 CC {@code streamCompactSummary} 返回值 +
     * {@code getAssistantMessageText(summaryResponse)}（compact.ts:451-459）。
     *
     * @param text  摘要文本（PTL 时以 PROMPT_TOO_LONG_ERROR_MESSAGE 前缀开始）
     * @param usage 压缩 API usage（compact.ts:645 getTokenUsage；可 null）
     */
    public record SummaryResult(String text, TokenUsage usage) {}

    /**
     * 压缩 API token 用量 · 对齐 CC {@code getTokenUsage}（utils/tokens.ts:7-21）的
     * input/cache_read/cache_creation/output 四元组。
     */
    public record TokenUsage(int inputTokens, int outputTokens, int cacheReadInputTokens, int cacheCreationInputTokens) {
        /** 对齐 CC getTokenCountFromUsage（tokens.ts:29-37）：input + cache_creation + cache_read + output（anthropic 语义，A5-2 默认） */
        public int total() {
            return total(true);
        }

        /**
         * 协议分派 total · [A5-2] anthropic → 4 项和；非 anthropic（deepseek input 已含 cache hit）→
         * input+output（展示/预算口径，输入侧不重复计命中）。
         *
         * @param anthropic 协议判定：true=4 项和；false=仅 input+output
         * @return 压缩 API token 总数（≥ 0）
         */
        public int total(boolean anthropic) {
            if (anthropic) {
                return inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens;
            }
            return inputTokens + outputTokens;
        }
    }

    /**
     * readFileState 文件项 · 对齐 CC {@code cacheToObject(context.readFileState)}
     * （compact.ts:518，{content, timestamp}）。
     */
    public record ReadFileState(String content, long timestamp) {}

    /**
     * 压缩通知 · 对齐 CC {@code context.addNotification?.(...)}（compact.ts:1116-1121）。
     */
    public record CompactionNotification(String key, String text, String priority, String color) {}

    /**
     * 重压缩诊断上下文 · 对齐 CC {@code RecompactionInfo}（compact.ts:317-323）。
     */
    public record RecompactionInfo(
        boolean isRecompactionInChain,
        int turnsSincePreviousCompact,
        String previousCompactTurnId,
        int autoCompactThreshold,
        String querySource
    ) {}

    /** 摘要用户消息 subtype 标记 · CC original: isCompactSummary (messages.ts:465/480)。 */
    public static final String SUMMARY_SUBTYPE = "compact_summary";

    // ════════════════════════════════════════════════════════════════════
    // 全量压缩单函数 · 对齐 CC compact.ts:387-763
    // ════════════════════════════════════════════════════════════════════

    /**
     * 全量压缩单函数 · 对齐 CC {@code compactConversation}（compact.ts:387-763）。
     *
     * <p>单流程恰 5 事件（INV-1）：pre_compact → compact_start → session_start →
     * post_compact → compact_end（finally）。压缩成功路径调用
     * notifyCompaction + markPostCompaction + reAppendSessionMetadata（INV-8）。
     *
     * @param messages               待压缩消息（空 → throw ERROR_MESSAGE_NOT_ENOUGH_MESSAGES）
     * @param ctx                    压缩上下文（CC ToolUseContext 依赖面）
     * @param suppressFollowUpQuestions 抑制后续提问（CC suppressFollowUpQuestions）
     * @param customInstructions     用户自定义压缩指令（可选）
     * @param isAutoCompact          是否自动压缩（错误通知仅 manual 触发，compact.ts:752-756）
     * @param recompactionInfo       重压缩诊断上下文（可选）
     * @return CompactionResult 10 字段契约
     */
    public static CompactionResult compactConversation(
            List<ChatMessageDto> messages,
            CompactConversationContext ctx,
            boolean suppressFollowUpQuestions,
            String customInstructions,
            boolean isAutoCompact,
            RecompactionInfo recompactionInfo) {
        if (ctx == null) {
            throw new IllegalArgumentException("CompactConversationContext is required");
        }
        try {
            // ── 1. 空校验（compact.ts:397-399，REQ-01）──
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES);
            }

            // ── 2. preCompactTokenCount（compact.ts:401 tokenCountWithEstimation）──
            // [A5-2] 求和 provider 分派：deepseek input 已含 cache hit → 按 ctx.model 判 anthropic
            //   （mapper/模型不可得 → resolveAnthropic 回落 anthropic 语义，既有 4 项和）
            final int preCompactTokenCount = tokenCountWithEstimation(messages, resolveAnthropic(ctx.getModel()));

            // ── 3. hooks_start: pre_compact + SDK 状态（compact.ts:406-412）──
            ctx.getOnCompactProgress().accept(new HooksStart(HookType.PRE_COMPACT));
            ctx.getSdkStatusSetter().accept(SDKStatus.COMPACTING);
            log.info("[CompactConversation] pre_compact: messages={} preTokens={} isAuto={}",
                messages.size(), preCompactTokenCount, isAutoCompact);

            // ── 4. PreCompact hooks + mergeHookInstructions（compact.ts:413-424）──
            String trigger = isAutoCompact ? "auto" : "manual";
            CompactHooks.PreCompactHookResult hookResult =
                CompactHooks.executePreCompactHooks(ctx, trigger, customInstructions);
            String mergedInstructions = mergeHookInstructions(customInstructions, hookResult.newCustomInstructions());
            String userDisplayMessage = hookResult.userDisplayMessage();

            // ── 5. streamMode REQUESTING + responseLength 0 + compact_start（compact.ts:427-429）──
            ctx.getStreamModeSetter().accept(SpinnerMode.REQUESTING);
            ctx.getResponseLengthSetter().accept(0);
            ctx.getOnCompactProgress().accept(new CompactProgressEvent.CompactStart());

            // ── 6. getCompactPrompt（compact.ts:440-443）──
            // summaryRequest（createUserMessage）由 SummaryProducer 内部构建（CompactConversation
            // buildSummaryRequestMessage），本处直接传 compactPrompt（D-32：死局部已删）
            String compactPrompt = CompactPrompt.buildCompactPrompt(mergedInstructions);

            // ── 7. PTL 重试循环（compact.ts:450-491，REQ-03）──
            List<ChatMessageDto> messagesToSummarize = messages;
            SummaryResult summaryResult = null;
            int ptlAttempts = 0;
            for (;;) {
                summaryResult = ctx.getSummaryProducer().summarize(messagesToSummarize, compactPrompt, preCompactTokenCount);
                String summary = summaryResult != null ? summaryResult.text() : null;
                if (summary == null || !summary.startsWith(ApiErrors.PROMPT_TOO_LONG_ERROR_MESSAGE)) {
                    break;
                }
                ptlAttempts++;
                // [V54 token-compact-fix B1-2] PTL 重试上限 DB 实时读（settings.max_ptl_retries
                //   有值覆盖常量 3，null 回落；同步骤 2 熔断阈值读 DB 范式）
                List<ChatMessageDto> truncated = ptlAttempts <= resolveMaxPtlRetries()
                    ? truncateHeadForPTLRetry(messagesToSummarize, summary)
                    : null;
                if (truncated == null) {
                    log.warn("[CompactConversation] tengu_compact_failed: reason=prompt_too_long attempts={} preTokens={}",
                        ptlAttempts, preCompactTokenCount);
                    // [IMP-CM-17] CC compact.ts:470-476 logEvent('tengu_compact_failed',
                    //   {reason:'prompt_too_long', preCompactTokenCount, promptCacheSharingEnabled, ptlAttempts})
                    emitCompactEvent(ctx, "tengu_compact_failed", Map.of(
                        "reason", "prompt_too_long",
                        "preCompactTokenCount", preCompactTokenCount,
                        "promptCacheSharingEnabled", ctx.isPromptCacheSharingEnabled(),
                        "ptlAttempts", ptlAttempts));
                    throw new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_PROMPT_TOO_LONG);
                }
                log.info("[CompactConversation] tengu_compact_ptl_retry: attempt={} dropped={} remaining={}",
                    ptlAttempts, messagesToSummarize.size() - truncated.size(), truncated.size());
                // [IMP-CM-17] CC compact.ts:479-483 logEvent('tengu_compact_ptl_retry',
                //   {attempt, droppedMessages, remainingMessages})
                emitCompactEvent(ctx, "tengu_compact_ptl_retry", Map.of(
                    "attempt", ptlAttempts,
                    "droppedMessages", messagesToSummarize.size() - truncated.size(),
                    "remainingMessages", truncated.size()));
                messagesToSummarize = truncated;
                // [IMP2-13 △-3] 对齐 CC compact.ts:487-490：每轮 PTL 重试把截断后的消息集同步进
                //   fork 缓存共享槽位（forkContextMessages）——forked-agent 路径读
                //   cacheSafeParams.forkContextMessages 而非 messages 参数，不更新则重试轮
                //   fork 前缀仍为截断前消息（缓存命中率偏移；partial 路径 [RES-C4] 已对齐）。
                //   CacheSafeParams 为不可变 record → 以 truncated 重建并 re-save
                //   （CacheSafeParamsHolder.save 同线程覆盖槽位，StreamCompactSummary fork 读侧
                //   下次读取即新前缀）；仅当槽位非 null 时更新（无 fork 前缀 → 无缓存共享，跳过）。
                CacheSafeParams saved = CacheSafeParamsHolder.get();
                if (saved != null) {
                    CacheSafeParamsHolder.save(new CacheSafeParams(
                        saved.systemPrompt(), saved.userContext(), saved.systemContext(),
                        saved.toolUseContext(), new ArrayList<>(truncated), saved.useGlobalCacheScope()));
                    if (log.isDebugEnabled()) {
                        log.debug("[CompactConversation] PTL retry 更新 fork 前缀: forkMsgs={}"
                                + "（对齐 CC compact.ts:487-490 retryCacheSafeParams.forkContextMessages=truncated）",
                            truncated.size());
                    }
                }
            }

            // ── 8. 无摘要 / API 前缀抛错（compact.ts:493-515）──
            String summaryText = summaryResult != null ? summaryResult.text() : null;
            // [IMP2-15 △-18] CC 判据为 !summary（仅 null/undefined/'' 拒绝，compact.ts:493）——
            //   Java 旧实现 isBlank() 额外拒绝纯空白串（CC 放行：空白串继续走
            //   formatCompactSummary/摘要消息构建，无 <summary> 标签时内容被 trim 为空）。
            //   对齐后仅 null/'' 抛 no_summary。
            if (summaryText == null || summaryText.isEmpty()) {
                log.warn("[CompactConversation] tengu_compact_failed: reason=no_summary preTokens={}", preCompactTokenCount);
                // [IMP-CM-17] CC compact.ts:498-503 logEvent('tengu_compact_failed',
                //   {reason:'no_summary', preCompactTokenCount, promptCacheSharingEnabled})
                emitCompactEvent(ctx, "tengu_compact_failed", Map.of(
                    "reason", "no_summary",
                    "preCompactTokenCount", preCompactTokenCount,
                    "promptCacheSharingEnabled", ctx.isPromptCacheSharingEnabled()));
                throw new IllegalArgumentException(
                    "Failed to generate conversation summary - response did not contain valid text content");
            }
            if (ApiErrors.startsWithApiErrorPrefix(summaryText)) {
                log.warn("[CompactConversation] tengu_compact_failed: reason=api_error preTokens={} err={}",
                    preCompactTokenCount, summaryText);
                // [IMP-CM-17] CC compact.ts:508-514 logEvent('tengu_compact_failed',
                //   {reason:'api_error', preCompactTokenCount, promptCacheSharingEnabled})
                emitCompactEvent(ctx, "tengu_compact_failed", Map.of(
                    "reason", "api_error",
                    "preCompactTokenCount", preCompactTokenCount,
                    "promptCacheSharingEnabled", ctx.isPromptCacheSharingEnabled()));
                throw new IllegalArgumentException(summaryText);
            }

            // ── 9. readFileState 缓存（compact.ts:517-522，REQ-04）──
            Map<String, ReadFileState> preCompactReadFileState = snapshotReadFileState(ctx);
            ctx.clearReadFileState();

            // ── 9.1 loadedNestedMemoryPaths 清空（compact.ts:522，A13）──
            // CC context.loadedNestedMemoryPaths?.clear()（?: 空安全）：压缩完成后清空已加载
            // 嵌套记忆路径集合，防压缩后 memory 文件重注入依赖陈旧 Set（跨域 CM-F1，记忆去重
            // 双源 loadedNestedMemoryPaths + readFileState.has 之一；OPD-CM5-A-08 拍板）。
            // tuc 未接线（AutoCompactor / ToolRegistrationConfig 路径）→ 空安全跳过（对齐 CC ?.）。
            ToolUseContext tuc = ctx.getToolUseContext();
            if (tuc != null && tuc.loadedNestedMemoryPaths() != null) {
                tuc.loadedNestedMemoryPaths().clear();
                log.debug("[CompactConversation] loadedNestedMemoryPaths 已清空（压缩后，compact.ts:522）");
            }

            // ── 10. 附件恢复（compact.ts:531-585）──
            // CC 全量压缩 createPostCompactFileAttachments(preCompactReadFileState, context,
            // POST_COMPACT_MAX_FILES_TO_RESTORE) 只传 3 参，preservedMessages 走默认 []（compact.ts:533-537）——
            // 全量压缩不去重 Read 路径（collectReadToolFilePaths 仅服务于 partial/reactive 的
            // messagesToKeep 去重，compact.ts:1419 javadoc "Messages kept post-compact"）。故 preserved 传空集。
            //
            // [WF6] plan_file_reference + plan_mode 重注入（CC compact.ts:545-555
            // createPlanAttachmentIfNeeded → push，plan 之后 createPlanModeAttachmentIfNeeded → push，
            // 位于 async-agent 之后、skill 之前）：经 PlanProvider（ctx 注入 / 按 sessionId 回落
            // PlanProviderImpl 读磁盘）读 plan 文件。plan_file_reference 走 typed 工厂 →
            // state.attachments()（maybeInjectHookAttachments 渲染 system-reminder）；plan_mode 走
            // planModeAttachment → setAdditionalPostCompactAttachments。无 plan 文件 / 非 plan 模式 →
            // populate 安全跳过（不中断压缩成功路径）。顺序对齐 CC：plan_file_reference → plan_mode → invoked_skills。
            PostCompactAttachmentRestorer.populatePlanAttachment(sessionAgentStateRegistry, ctx);
            PostCompactAttachmentRestorer.populatePlanModeAttachment(ctx);
            // [MF2-3] invoked_skills 重注入（CC compact.ts:558-560 createSkillAttachmentIfNeeded→push，
            // 位于 plan/plan_mode 之后、3×delta 之前）：经 SessionAgentStateRegistry（静态 holder，
            // AutoCompactor/测试注入）按 sessionId 解析主 AgentState → getInvokedSkillsForAgent(agentId)
            // → skillAttachment → setAdditionalPostCompactAttachments，使 restore() 输出含
            // subtype='invoked_skills'（per-skill 5K + 总预算 25K，most-recent-first）。未注入/
            // 无 skill → populate 安全跳过（不中断压缩成功路径）。
            PostCompactAttachmentRestorer.populateInvokedSkillsAttachment(sessionAgentStateRegistry, ctx);
            List<ChatMessageDto> postCompactAttachments =
                PostCompactAttachmentRestorer.restore(ctx, preCompactReadFileState, List.of());

            // ── 11. hooks_start: session_start + processSessionStartHooks（compact.ts:587-594）──
            ctx.getOnCompactProgress().accept(new HooksStart(HookType.SESSION_START));
            List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

            // ── 12. boundary（compact.ts:598-611）──
            String lastUuid = lastMessageUuid(messages);
            CompactBoundaryMessage boundaryMarker = CompactBoundaryMessage.createCompactBoundaryMessage(
                trigger, preCompactTokenCount, lastUuid, null, null);
            // ✗-5 全量路径：提取 deferred 工具名写入 boundary compactMetadata（compact.ts:606-611）
            //   extractDiscoveredToolNames(messages) → 非空时按名排序（CC [...set].sort()）
            Set<String> preCompactDiscovered = extractDiscoveredToolNames(messages);
            if (!preCompactDiscovered.isEmpty()) {
                CompactBoundaryMessage.CompactMetadata oldMeta = boundaryMarker.compactMetadata();
                List<String> sorted = new ArrayList<>(preCompactDiscovered);
                sorted.sort(String::compareTo);
                boundaryMarker = boundaryMarker.withCompactMetadata(
                    new CompactBoundaryMessage.CompactMetadata(
                        oldMeta.trigger(), oldMeta.preTokens(), oldMeta.userContext(),
                        oldMeta.messagesSummarized(), sorted, oldMeta.preservedSegment()));
                if (log.isDebugEnabled()) {
                    log.debug("[CompactConversation] boundary 携带 preCompactDiscoveredTools: {} 个工具名（compact.ts:606-611）",
                        sorted.size());
                }
            }

            // ── 13. summaryMessages（compact.ts:613-624，isCompactSummary + isVisibleInTranscriptOnly）──
            String transcriptPath = transcriptPathFor(ctx);
            String summaryContent = CompactSummary.buildUserMessage(summaryText, transcriptPath, suppressFollowUpQuestions, false);
            List<ChatMessageDto> summaryMessages = List.of(buildCompactSummaryMessage(summaryContent));
            log.info("[CompactConversation] 摘要生成: preTokens={} summaryChars={} keepTail={}",
                preCompactTokenCount, summaryText.length(), messages.size() - messagesToSummarize.size());

            // ── 14. 度量三口径（compact.ts:626-645）──
            // [A5-2] 求和 provider 分派：摘要 API usage 按 ctx.model 判 anthropic（deepseek 仅 input+output）
            int compactionCallTotalTokens = tokenCountFromLastAPIResponse(summaryResult, resolveAnthropic(ctx.getModel()));
            int truePostCompactTokenCount = roughTokenCountEstimationForMessages(
                concatLists(
                    List.of(boundaryMarker.toChatMessageDto()),
                    summaryMessages,
                    postCompactAttachments,
                    hookMessages));
            TokenUsage compactionUsage = summaryResult != null ? summaryResult.usage() : null;

            // ── 14.5 [IMP-CM-17] tengu_compact 结构化遥测（compact.ts:650-695 logEvent）──
            emitTenguCompactTelemetry(ctx, messages, preCompactTokenCount,
                compactionCallTotalTokens, truePostCompactTokenCount,
                isAutoCompact, recompactionInfo, compactionUsage);

            // ── 15. notifyCompaction + markPostCompaction + reAppendSessionMetadata（compact.ts:698-711，INV-8）──
            ctx.getNotifyCompaction().run();
            PostCompactionState.markPostCompaction(ctx.getSessionId());
            reAppendSessionMetadata(ctx);

            // ── 16. hooks_start: post_compact + executePostCompactHooks（compact.ts:719-729）──
            ctx.getOnCompactProgress().accept(new HooksStart(HookType.POST_COMPACT));
            CompactHooks.PostCompactHookResult postHookResult =
                CompactHooks.executePostCompactHooks(ctx, trigger, summaryText);
            String combinedUserDisplayMessage = combineDisplayMessages(userDisplayMessage, postHookResult.userDisplayMessage());

            // ── 17. CompactionResult 10 字段（compact.ts:738-748）──
            return new CompactionResult(
                boundaryMarker,
                summaryMessages,
                postCompactAttachments,
                hookMessages,
                null,                                  // messagesToKeep · 全量压缩 undefined
                combinedUserDisplayMessage,
                preCompactTokenCount,
                compactionCallTotalTokens,
                truePostCompactTokenCount,
                compactionUsage);
        } catch (Exception error) {
            // ── 18. 错误通知（compact.ts:1108-1123；仅 !isAutoCompact，跳过 USER_ABORT/NOT_ENOUGH）──
            if (!isAutoCompact) {
                addErrorNotificationIfNeeded(error, ctx);
            }
            throw error;
        } finally {
            // ── 19. finally 收尾（compact.ts:757-762）──
            ctx.getStreamModeSetter().accept(SpinnerMode.REQUESTING);
            ctx.getResponseLengthSetter().accept(0);
            ctx.getOnCompactProgress().accept(new CompactProgressEvent.CompactEnd());
            ctx.getSdkStatusSetter().accept(SDKStatus.NULL);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [GR-1] auto 路径 per-session 上下文工厂 · 对齐 CC ToolUseContext 依赖面
    // ════════════════════════════════════════════════════════════════════

    /**
     * [GR-1] 构建 auto 自动压缩的 per-session compactConversation 上下文 ·
     * 从 {@link ToolUseContext} 依赖面映射（CC compact.ts:387-395 context 参数）。
     *
     * <p><b>WHY（GR-1 消除双轨）</b>: LlmAgentLoop 主自动压缩路径迁移到 CC 对齐单函数
     * {@link #compactConversation}（autoCompact.ts:313 autoCompactIfNeeded 调
     * compactConversation）后，需要与 /compact 相同的事件 / SDK / 流桥接。per-session
     * 桥接（onCompactProgress / setStreamMode / setSDKStatus / setResponseLength /
     * abortController / readFileState / effectiveCwd）均承载于主循环 ToolUseContext
     * （buildBaseToolUseContext 注入，对齐 CC Tool.ts:234-236），本工厂一次性映射。
     *
     * <p>readFileState 由 TUC 的 Caffeine 缓存适配为 compact 域 Map 快照
     * （CC cacheToObject(context.readFileState)，compact.ts:518）。摘要生产
     * （summaryProducer）由 AutoCompactor 在调用点经 prepareAutoContext 补齐
     * （缺省回落 CompactCallback 适配）；错误通知保持 no-op（auto 经
     * {@code isAutoCompact=true} 在 compactConversation 内跳过，compact.ts:752-756）。
     *
     * <p><b>[IMP-CM-12]</b> 本工厂<b>不</b>接线 notifyCompaction：auto 路径的
     * {@code ctx.getNotifyCompaction()}（compactConversation step 15，compact.ts:698-699）
     * 由 {@code AutoCompactor.prepareAutoContext} 统一接线（PROMPT_CACHE_BREAK_DETECTION
     * feature 门控，querySource/agentId 取自本 ctx）——门控 + notifyCompaction 源均在
     * AutoCompactor（ToolRegistrationConfig 单点注入），保持与 SM 成功链同源。
     * {@code compactConversation} 调用点（compact.ts:698-699 等价）在
     * {@link #compactConversation} step 15 无条件 {@code ctx.getNotifyCompaction().run()}，
     * feature 关时 Runnable 内部 no-op（对齐 CC 调用点 gating 语义）。
     *
     * @param tuc          主循环 per-session ToolUseContext（可 null → 默认无桥接上下文）
     * @param model        当前有效模型名（CC context.options.mainLoopModel，compact.ts:594；G-2 统一
     *                     吃 effectiveModel——可被 fallbackModel 改写，query.ts:922）——阈值体系经
     *                     ccContext.getModel() → AutoCompactor.model → getAutoCompactThreshold 消费）
     * @param querySource  查询来源（CC context.options.querySource，compact.ts:648）
     * @param hookRegistry PreCompact/SessionStart/PostCompact hooks 执行器
     *                     （CC context，compact.ts:413/592/723；null → hooks 跳过）
     * @return 已接线的 CompactConversationContext
     */
    public static CompactConversationContext buildAutoContext(
            ToolUseContext tuc, String model, String querySource,
            com.nexusai.application.agent.permission.hook.HookRegistry hookRegistry) {
        CompactConversationContext ctx = new CompactConversationContext()
            .setModel(model)
            .setQuerySource(querySource != null ? querySource : "compact")
            .setHookRegistry(hookRegistry);
        if (tuc != null) {
            // [session-id-short] tuc.sessionId() 已 String（short）恒等直传
            ctx.setSessionId(tuc.sessionId());
            ctx.setAgentId(tuc.agentId() != null ? tuc.agentId().toString() : null);
            // [RV-E-01 GAP-03] 把 ToolUseContext 接线进 ctx（对齐 CC compact.ts:285 context 持有
            //   toolUseContext），使 isInPlanMode() 读真实 plan mode → populatePlanModeAttachment
            //   （compactConversation step 10）生产可达（此前 auto 路径 ctx.toolUseContext 恒 null，
            //   isInPlanMode() 恒 false → plan_mode 附件压缩后从不重注入）。
            ctx.setToolUseContext(tuc);
            ctx.setOnCompactProgress(tuc.onCompactProgress());
            ctx.setStreamModeSetter(tuc.setStreamMode());
            ctx.setSdkStatusSetter(tuc.setSDKStatus());
            if (tuc.setResponseLength() != null) {
                ctx.setResponseLengthSetter(len ->
                    tuc.setResponseLength().accept(String.valueOf(len)));
            }
            ctx.setAbortController(tuc.abortController());
            if (tuc.effectiveCwd() != null) {
                ctx.setWorkspaceDir(tuc.effectiveCwd());
            }
            if (tuc.readFileState() != null) {
                // [P-CC-02] FileStateCache 方法面严格对齐 CC (fileStateCache.ts:41-84, 无 asMap) —
                //   遍历 entries() 快照, 等价 CC cacheToObject(context.readFileState)
                //   (compact.ts:518 Object.fromEntries(cache.entries())).
                Map<String, ReadFileState> snapshot = new LinkedHashMap<>();
                Iterator<Map.Entry<String, ToolUseContext.ReadState>> it = tuc.readFileState().entries();
                while (it.hasNext()) {
                    Map.Entry<String, ToolUseContext.ReadState> e = it.next();
                    snapshot.put(e.getKey(),
                        new ReadFileState(e.getValue().content(), e.getValue().mtimeMillis()));
                }
                ctx.setReadFileState(snapshot);
            }
        }
        return ctx;
    }

    // ════════════════════════════════════════════════════════════════════
    // mergeHookInstructions（compact.ts:374-381，REQ-01）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 合并用户指令与 hook 指令 · 对齐 CC {@code mergeHookInstructions}
     * （compact.ts:374-381）。
     *
     * <p><b>顺序</b>：user 在前，hook 在后（{@code `${userInstructions}\n\n${hookInstructions}`}）；
     * 空串归一为 undefined（CC {@code !hookInstructions} / {@code !userInstructions} 真值语义）。
     *
     * @param userInstructions 用户自定义指令（可选）
     * @param hookInstructions hook 返回指令（可选）
     * @return 合并后指令（两者皆空 → null）
     */
    public static String mergeHookInstructions(String userInstructions, String hookInstructions) {
        if (hookInstructions == null || hookInstructions.isBlank()) {
            return (userInstructions == null || userInstructions.isBlank()) ? null : userInstructions;
        }
        if (userInstructions == null || userInstructions.isBlank()) {
            return hookInstructions;
        }
        return userInstructions + "\n\n" + hookInstructions;
    }

    // ════════════════════════════════════════════════════════════════════
    // 错误通知（compact.ts:1108-1123）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 是否应加错误通知 · 对齐 CC {@code hasExactErrorMessage} 双条件
     * （compact.ts:1112-1114）：跳过 USER_ABORT / NOT_ENOUGH。
     *
     * @param error 压缩异常
     * @return true=加通知，false=跳过
     */
    public static boolean shouldAddErrorNotification(Throwable error) {
        String msg = error != null && error.getMessage() != null ? error.getMessage() : "";
        return !CompactConstants.ERROR_MESSAGE_USER_ABORT.equals(msg)
            && !CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES.equals(msg);
    }

    /**
     * 加错误通知 · 对齐 CC {@code addErrorNotificationIfNeeded}（compact.ts:1108-1123）。
     * 仅 manual /compact 调用方在 catch 中调用（auto 失败下轮重试，通知无意义）。
     */
    public static void addErrorNotificationIfNeeded(Throwable error, CompactConversationContext ctx) {
        if (shouldAddErrorNotification(error)) {
            ctx.getNotification().accept(new CompactionNotification(
                "error-compacting-conversation",
                "Error compacting conversation",
                "immediate",
                "error"));
            log.warn("[CompactConversation] 压缩失败，已加错误通知: {}", error == null ? "null" : error.toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PTL 重试循环支撑（compact.ts:227-291，REQ-03）
    // ════════════════════════════════════════════════════════════════════

    /**
     * PTL 重试上限 DB 实时解析 · CC original: MAX_PTL_RETRIES（compact.ts:227，默认 3）。
     *
     * <p>[V54 token-compact-fix B1-2] DB {@code settings.max_ptl_retries} 有值（&gt; 0）覆盖
     * 常量（前端 PUT settings 后下一轮生效），null 回落常量 3。
     *
     * @return 分段截断重试上限（DB 覆盖或常量默认）
     */
    private static int resolveMaxPtlRetries() {
        CompactSettingsResolver r = settingsResolver;
        if (r != null) {
            Integer db = r.maxPtlRetries();
            if (db != null && db > 0) {
                return db;
            }
        }
        return CompactConstants.MAX_PTL_RETRIES;
    }

    /**
     * 丢弃最旧 API-round 组直至覆盖 tokenGap · 对齐 CC {@code truncateHeadForPTLRetry}
     * （compact.ts:243-291）。CC-1180 兜底逃生舱：压缩请求自身 PTL 时丢弃最旧上下文重试。
     *
     * @param messages 待重试消息
     * @param ptlSummary 以 PROMPT_TOO_LONG_ERROR_MESSAGE 前缀开始的摘要文本
     * @return 截断后消息（无法截断 → null）
     */
    public static List<ChatMessageDto> truncateHeadForPTLRetry(List<ChatMessageDto> messages, String ptlSummary) {
        // 剥离上次重试的合成标记（否则它成为独立 group 0，20% fallback 停滞）
        List<ChatMessageDto> input = messages;
        if (!messages.isEmpty() && messages.get(0).isMeta()
            && CompactConstants.PTL_RETRY_MARKER.equals(messages.get(0).content())) {
            input = messages.subList(1, messages.size());
        }
        List<List<ChatMessageDto>> groups = groupMessagesByApiRound(input);
        if (groups.size() < 2) {
            return null;
        }
        Integer tokenGap = getPromptTooLongTokenGap(ptlSummary);
        int dropCount;
        if (tokenGap != null) {
            int acc = 0;
            dropCount = 0;
            for (List<ChatMessageDto> g : groups) {
                acc += roughTokenCountEstimationForMessages(g);
                dropCount++;
                if (acc >= tokenGap) {
                    break;
                }
            }
        } else {
            dropCount = Math.max(1, (int) Math.floor(groups.size() * 0.2));
        }
        // 至少保留一个组以便有内容可摘要
        dropCount = Math.min(dropCount, groups.size() - 1);
        if (dropCount < 1) {
            return null;
        }
        List<ChatMessageDto> sliced = new ArrayList<>();
        for (int i = dropCount; i < groups.size(); i++) {
            sliced.addAll(groups.get(i));
        }
        // group 0 是 preamble；丢弃 group 0 后首条为 assistant（API 要求首条 role=user）→ 前置合成标记
        if (!sliced.isEmpty() && sliced.get(0).role() == Role.assistant) {
            List<ChatMessageDto> withMarker = new ArrayList<>();
            withMarker.add(buildMetaMarkerMessage(CompactConstants.PTL_RETRY_MARKER));
            withMarker.addAll(sliced);
            return withMarker;
        }
        return sliced;
    }
    /**
     * API-round 分组 · 对齐 CC {@code groupMessagesByApiRound}（grouping.ts:22-63）。
     *
     * <p>分组边界：新 assistant 响应的 {@code message.id} 不同于上一 assistant 时触发
     * （同一 API 响应的 streaming 分块共享 id —— {@code [tu_A(id=X), result_A, tu_B(id=X)]}
     * 保持同组）。Java 端 assistant 消息的 API round id 落
     * {@code ChatMessageDto.assistantMessageId}（R28-3.4 对齐 CC seenAsstIds；assistant 消息
     * 填自身 envelope id），缺失时回落 {@code id()}。
     *
     * <p>[IMP2-15 △-17] 无 id 时不制造随机 id：CC 的 {@code msg.message.id} 为 undefined 时
     * {@code undefined !== undefined} 恒 false → 连续 assistant 不触发边界（同组）；
     * Java 旧实现回退 {@code UUID.randomUUID()}（每次不同）→ 同一轮分块被拆组。
     * 边界判定改用 {@link java.util.Objects#equals} 精确复刻 CC 的 {@code !==} 语义
     * （id 从有到无 / 从无到有均按 CC 触发或不触发）。
     *
     * @param messages 消息列表
     * @return API-round 分组
     */
    public static List<List<ChatMessageDto>> groupMessagesByApiRound(List<ChatMessageDto> messages) {
        List<List<ChatMessageDto>> groups = new ArrayList<>();
        List<ChatMessageDto> current = new ArrayList<>();
        String lastAssistantId = null;
        for (ChatMessageDto msg : messages) {
            if (msg.role() == Role.assistant) {
                String roundId = apiRoundId(msg);
                if (!java.util.Objects.equals(roundId, lastAssistantId) && !current.isEmpty()) {
                    groups.add(current);
                    current = new ArrayList<>();
                }
                lastAssistantId = roundId;
            }
            current.add(msg);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 提取 assistant 消息的 API-round id · 对齐 CC {@code msg.message.id}（grouping.ts:46）。
     *
     * <p>Java 映射：{@code assistantMessageId} 优先（assistant 消息自身 envelope uuid =
     * R28-3.4 seenAsstIds 追踪源），缺失回落 {@code id()}；两者皆无 → null（CC undefined
     * 语义，不制造随机 id —— △-17）。
     */
    private static String apiRoundId(ChatMessageDto msg) {
        if (msg.assistantMessageId() != null && !msg.assistantMessageId().isBlank()) {
            return msg.assistantMessageId();
        }
        return msg.id();
    }

    /** PTL 文本中解析 token 缺口 · 对齐 CC parsePromptTooLongTokenCounts（errors.ts:85-93）。 */
    static Integer getPromptTooLongTokenGap(String ptlMessage) {
        if (ptlMessage == null) {
            return null;
        }
        Matcher m = PTL_TOKEN_PATTERN.matcher(ptlMessage);
        if (m.find()) {
            int actual;
            int limit;
            try {
                actual = Integer.parseInt(m.group(1));
                limit = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
            int gap = actual - limit;
            return gap > 0 ? gap : null;
        }
        return null;
    }

    /** CC errors.ts:85-93 正则：/prompt is too long[^0-9]*(\d+)\s*tokens?\s*>\s*(\d+)/i */
    private static final Pattern PTL_TOKEN_PATTERN =
        Pattern.compile("prompt is too long[^0-9]*(\\d+)\\s*tokens?\\s*>\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    // ════════════════════════════════════════════════════════════════════
    // token 度量（utils/tokens.ts + tokenEstimation.ts）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 当前上下文窗口 token 估算 · 对齐 CC {@code tokenCountWithEstimation}
     * （utils/tokens.ts:226-261）：最后含 usage 的 assistant 响应总用量 + 同 id sibling 回溯 +
     * 其后消息的 rough 估算；无 usage 时全量 rough。
     *
     * <p><b>IMP2-19 △-1（S-4 双实现收敛）</b>: 本地简化版（无 sibling 回溯、无 cache 4 项，
     * 并行 tool call 交错场景欠估）已删除，本方法委托 canonical 宿主 {@link Tokens}——
     * 与 {@link TokenEstimator#tokenCountWithEstimation} 同源（usage-walk + sibling 回溯，
     * tokens.ts:232-252），消除双实现漂移（探查 R2/S-4）。
     *
     * <p><b>A5-2</b>: 1 参 = anthropic 语义；deepseek 调用点请用
     * {@link #tokenCountWithEstimation(List, boolean)} 传 isAnthropic。
     *
     * @param messages 消息列表
     * @return 上下文窗口 token 估算（≥ 0）
     */
    public static int tokenCountWithEstimation(List<ChatMessageDto> messages) {
        return Tokens.tokenCountWithEstimation(messages);
    }

    /**
     * 当前上下文窗口 token 估算 · 协议分派重载（A5-2 · deepseek 双计修复）。
     *
     * @param messages  消息列表
     * @param anthropic 协议判定：true=4 项和；false=仅 input+output
     * @return 上下文窗口 token 估算（≥ 0）
     */
    public static int tokenCountWithEstimation(List<ChatMessageDto> messages, boolean anthropic) {
        return Tokens.tokenCountWithEstimation(messages, anthropic);
    }

    /**
     * 压缩 API 调用总用量 · 对齐 CC {@code tokenCountFromLastAPIResponse}
     * （utils/tokens.ts:55，compact.ts:629-631）：usage 全量（input+cache+output）。
     *
     * <p><b>A5-2</b>: 1 参 = anthropic 语义；deepseek 调用点请用
     * {@link #tokenCountFromLastAPIResponse(SummaryResult, boolean)} 传 isAnthropic。
     */
    public static int tokenCountFromLastAPIResponse(SummaryResult summaryResult) {
        return tokenCountFromLastAPIResponse(summaryResult, true);
    }

    /**
     * 压缩 API 调用总用量 · 协议分派重载（A5-2 · deepseek 双计修复）。
     *
     * @param summaryResult 摘要生产结果（usage 可为 null）
     * @param anthropic     协议判定：true=4 项和；false=仅 input+output
     * @return 压缩 API token 数（无 usage → 0）
     */
    public static int tokenCountFromLastAPIResponse(SummaryResult summaryResult, boolean anthropic) {
        if (summaryResult == null || summaryResult.usage() == null) {
            return 0;
        }
        return summaryResult.usage().total(anthropic);
    }

    /** 对齐 CC roughTokenCountEstimation（tokenEstimation.ts:203）：Math.round(len/4)。 */
    public static int roughTokenCountEstimation(String content) {
        if (content == null) {
            return 0;
        }
        return (int) Math.round(content.length() / 4.0);
    }

    /** 对齐 CC roughTokenCountEstimationForMessages（tokenEstimation.ts:327-340）。 */
    public static int roughTokenCountEstimationForMessages(List<ChatMessageDto> messages) {
        int total = 0;
        for (ChatMessageDto m : messages) {
            if (m == null) {
                continue;
            }
            if (m.content() != null) {
                total += roughTokenCountEstimation(m.content());
            }
            if (m.contentBlocks() != null && !m.contentBlocks().isEmpty()) {
                for (Object block : m.contentBlocks()) {
                    if (block instanceof com.fasterxml.jackson.databind.JsonNode node && node.isObject()) {
                        total += roughTokenCountEstimation(node.path("text").asText(""));
                    }
                }
            }
        }
        return total;
    }

    /**
     * [IMP-CM-17] 发射 tengu_compact 结构化遥测 · 对齐 CC compact.ts:650-695
     * {@code logEvent('tengu_compact', {...})} 全字段 analytics。
     *
     * <p>CC 字段逐项映射（compact.ts:651-694）：
     * preCompactTokenCount / postCompactTokenCount=compactionCallTotalTokens /
     * truePostCompactTokenCount / autoCompactThreshold（recompactionInfo?.autoCompactThreshold ?? -1）/
     * willRetriggerNextTurn（recompactionInfo!==undefined && truePost>=threshold）/
     * isAutoCompact / querySource（recompactionInfo?.querySource ?? ctx.querySource ?? 'unknown'）/
     * queryChainId（ctx.queryChainId ?? ''）/ queryDepth（ctx.queryDepth ?? -1）/
     * isRecompactionInChain / turnsSincePreviousCompact / previousCompactTurnId /
     * compactionInput/Output/CacheRead/CacheCreation/TotalTokens（compactionUsage，无则 0）/
     * promptCacheSharingEnabled / ...tokenStatsToStatsigMetrics(analyzeContext(messages))（尾部 breakdown）。
     *
     * <p>双发射（recordEvent 1P 计数 + logOTelEvent OTel 转发 · HookRegistry:278-279 惯例）。
     * telemetry 未注入（ctx.getTelemetry()==null）→ 静默跳过（测试/未接线零行为变化）。
     *
     * @param messages 压缩前消息集（analyzeContext breakdown 原料）
     * @param compactionUsage 摘要 usage（IMP-CM-14 透传产物；当前未透传时 null → token 字段 0，同 CC 恒 0 语义）
     */
    private static void emitTenguCompactTelemetry(CompactConversationContext ctx,
                                                  List<ChatMessageDto> messages,
                                                  int preCompactTokenCount,
                                                  int compactionCallTotalTokens,
                                                  int truePostCompactTokenCount,
                                                  boolean isAutoCompact,
                                                  RecompactionInfo recompactionInfo,
                                                  TokenUsage compactionUsage) {
        Telemetry t = ctx.getTelemetry();
        if (t == null) {
            return;
        }
        int autoCompactThreshold = recompactionInfo != null
            ? recompactionInfo.autoCompactThreshold() : -1;
        boolean willRetriggerNextTurn = recompactionInfo != null
            && truePostCompactTokenCount >= recompactionInfo.autoCompactThreshold();
        String querySourceForEvent = recompactionInfo != null && recompactionInfo.querySource() != null
            ? recompactionInfo.querySource()
            : (ctx.getQuerySource() != null ? ctx.getQuerySource() : "unknown");
        boolean isRecompactionInChain = recompactionInfo != null
            && recompactionInfo.isRecompactionInChain();
        int turnsSincePreviousCompact = recompactionInfo != null
            ? recompactionInfo.turnsSincePreviousCompact() : -1;
        String previousCompactTurnId = recompactionInfo != null
            && recompactionInfo.previousCompactTurnId() != null
            ? recompactionInfo.previousCompactTurnId() : "";

        int compactionInputTokens = compactionUsage != null ? compactionUsage.inputTokens() : 0;
        int compactionOutputTokens = compactionUsage != null ? compactionUsage.outputTokens() : 0;
        int compactionCacheReadTokens = compactionUsage != null ? compactionUsage.cacheReadInputTokens() : 0;
        int compactionCacheCreationTokens = compactionUsage != null ? compactionUsage.cacheCreationInputTokens() : 0;
        int compactionTotalTokens = compactionUsage != null
            ? compactionInputTokens + compactionCacheCreationTokens + compactionCacheReadTokens + compactionOutputTokens
            : 0;

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("preCompactTokenCount", preCompactTokenCount);
        attrs.put("postCompactTokenCount", compactionCallTotalTokens);
        attrs.put("truePostCompactTokenCount", truePostCompactTokenCount);
        attrs.put("autoCompactThreshold", autoCompactThreshold);
        attrs.put("willRetriggerNextTurn", willRetriggerNextTurn);
        attrs.put("isAutoCompact", isAutoCompact);
        attrs.put("querySource", querySourceForEvent);
        attrs.put("queryChainId", ctx.getQueryChainId());
        attrs.put("queryDepth", ctx.getQueryDepth());
        attrs.put("isRecompactionInChain", isRecompactionInChain);
        attrs.put("turnsSincePreviousCompact", turnsSincePreviousCompact);
        attrs.put("previousCompactTurnId", previousCompactTurnId);
        attrs.put("compactionInputTokens", compactionInputTokens);
        attrs.put("compactionOutputTokens", compactionOutputTokens);
        attrs.put("compactionCacheReadTokens", compactionCacheReadTokens);
        attrs.put("compactionCacheCreationTokens", compactionCacheCreationTokens);
        attrs.put("compactionTotalTokens", compactionTotalTokens);
        attrs.put("promptCacheSharingEnabled", ctx.isPromptCacheSharingEnabled());
        // CC :683-695 ...tokenStatsToStatsigMetrics(analyzeContext(messages)) 尾部 breakdown
        // （CC try/catch 失败 → {} 兜底；Java 同式静默跳过）
        try {
            attrs.putAll(ContextTokenStats.analyze(messages));
        } catch (Exception ex) {
            log.debug("[CompactConversation] tengu_compact analyzeContext breakdown 失败跳过: {}",
                ex.toString());
        }
        t.recordEvent("tengu_compact", attrs);
        t.logOTelEvent("tengu_compact", attrs);
        if (log.isDebugEnabled()) {
            log.debug("[CompactConversation] tengu_compact 遥测已发射: pre={} post={} truePost={} isAuto={}",
                preCompactTokenCount, compactionCallTotalTokens, truePostCompactTokenCount, isAutoCompact);
        }
    }

    /**
     * [IMP-CM-17] 发射紧凑压缩失败/重试结构化遥测事件（双发射 recordEvent + logOTelEvent）。
     * <p>对齐 CC {@code logEvent}（compact.ts:470/:479/:498/:508 失败/重试事件）——
     * 失败/重试事件在 CC 中<b>同样走结构化 logEvent 通道</b>（3/4 事件仅 logEvent 无 console 文本），
     * Java log.warn/info 文本仅为本地 console 冗余，结构化事件才是 CC logEvent 等价物。
     * telemetry 未注入（ctx.getTelemetry()==null）→ 静默跳过（测试/未接线零行为变化）。
     *
     * @param ctx   压缩上下文（承载 telemetry 发射器）
     * @param event CC 事件名（tengu_compact_failed / tengu_compact_ptl_retry）
     * @param attrs 事件属性（与 CC logEvent 字段逐项一致）
     */
    private static void emitCompactEvent(CompactConversationContext ctx, String event, Map<String, Object> attrs) {
        Telemetry t = ctx.getTelemetry();
        if (t == null) {
            return;
        }
        t.recordEvent(event, attrs);
        t.logOTelEvent(event, attrs);
    }

    // ════════════════════════════════════════════════════════════════════
    // 小工具
    // ════════════════════════════════════════════════════════════════════

    /** snapshot readFileState（CC cacheToObject(context.readFileState) 前的复制）。 */
    private static Map<String, ReadFileState> snapshotReadFileState(CompactConversationContext ctx) {
        Map<String, ReadFileState> src = ctx.getReadFileState();
        if (src == null || src.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(src);
    }

    /** 最后一条非 null 消息的 uuid（CC messages.at(-1)?.uuid，compact.ts:601）。 */
    private static String lastMessageUuid(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1).id();
    }

    /**
     * 提取消息历史中已发现的 deferred 工具名 · 对齐 CC {@code extractDiscoveredToolNames}
     * （utils/toolSearch.ts:545-592）。
     *
     * <p>CC 语义：
     * <ol>
     *   <li>{@code compact_boundary} system 消息 → carry 其
     *       {@code compactMetadata.preCompactDiscoveredTools}（toolSearch.ts:553-560）——
     *       压缩会抹掉 tool_reference 块，boundary 快照把已发现集合带过压缩边界；</li>
     *   <li>user 消息 tool_result 内容内的 {@code tool_reference.tool_name}
     *       （toolSearch.ts:562-580，ToolSearch 命中输出，ToolSearchTool.ts:462-469）。</li>
     * </ol>
     *
     * <p>Java 映射：boundary 判别经 {@link BoundaryReader#isCompactBoundaryMessage}
     * （messages.ts:4608），carry 读 {@code ChatMessageDto.compactMetadata()}（IMP2-14
     * 序列化闭环后 DTO 层携带）；tool_reference 扫描 user 消息 contentBlocks 中
     * type='tool_result' 的 JsonNode 块（与 PartialCompactConversation 扫描同构）。
     * 与 {@link com.nexusai.application.agent.toolsearch.SchemaNotSentHint}
     * 第 4 道门共享同一 CC 真源语义。
     *
     * @param messages 压缩前消息链
     * @return 已发现工具名集合（无 → 空集）
     */
    static Set<String> extractDiscoveredToolNames(List<ChatMessageDto> messages) {
        Set<String> discovered = new HashSet<>();
        if (messages == null) {
            return discovered;
        }
        for (ChatMessageDto m : messages) {
            if (m == null) {
                continue;
            }
            // 1. compact_boundary → carry preCompactDiscoveredTools（toolSearch.ts:553-560）
            if (BoundaryReader.isCompactBoundaryMessage(m)) {
                Map<String, Object> meta = m.compactMetadata();
                if (meta != null) {
                    Object carried = meta.get("preCompactDiscoveredTools");
                    if (carried instanceof List<?> list) {
                        for (Object name : list) {
                            if (name instanceof String s && !s.isBlank()) {
                                discovered.add(s);
                            }
                        }
                    }
                }
                continue;
            }
            // 2. user 消息 tool_result 块内的 tool_reference.tool_name（toolSearch.ts:562-580）
            if (m.role() != Role.user || m.contentBlocks() == null) {
                continue;
            }
            for (Object blockObj : m.contentBlocks()) {
                if (!(blockObj instanceof JsonNode block) || !block.isObject()) {
                    continue;
                }
                if (!"tool_result".equals(block.path("type").asText(""))) {
                    continue;
                }
                JsonNode content = block.path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (JsonNode item : content) {
                    if (item.isObject()
                        && "tool_reference".equals(item.path("type").asText(""))
                        && item.path("tool_name").isTextual()) {
                        discovered.add(item.path("tool_name").asText());
                    }
                }
            }
        }
        return discovered;
    }

    /** transcript 路径（CC getTranscriptPath()，compact.ts:613）· D3 读兼容：读 nexusai 现有 transcript（无 claude 回落）。 */
    private static String transcriptPathFor(CompactConversationContext ctx) {
        if (ctx.getWorkspaceDir() == null || ctx.getSessionId() == null) {
            return null;
        }
        java.nio.file.Path p = com.nexusai.application.agent.tool.SessionStorage.resolveExistingTranscript(
            ctx.getWorkspaceDir(), ctx.getSessionId());
        return p != null ? p.toString() : null;
    }

    /**
     * 压缩成功路径重 append session 元数据 · 对齐 CC reAppendSessionMetadata
     * （compact.ts:711；sessionStorage.ts:721-829，REQ-30）。
     *
     * <p>IMP-08 登记的 Java 化差异：CC 元数据来自实例缓存，Java 端由调用方参数传入
     * （09-open-decisions IMP-08 登记）。workspaceDir/sessionId 缺失时 no-op。
     */
    static void reAppendSessionMetadata(CompactConversationContext ctx) {
        if (ctx.getWorkspaceDir() == null || ctx.getSessionId() == null) {
            return;
        }
        com.nexusai.application.agent.tool.SessionStorage.reAppendSessionMetadata(
            ctx.getWorkspaceDir(), ctx.getSessionId(), ctx.getSessionMetadata());
    }

    /** pre+post hook 显示消息合并 · 对齐 CC combinedUserDisplayMessage（compact.ts:731-736）。 */
    private static String combineDisplayMessages(String pre, String post) {
        StringBuilder sb = new StringBuilder();
        if (pre != null && !pre.isBlank()) {
            sb.append(pre);
        }
        if (post != null && !post.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(post);
        }
        String result = sb.toString();
        return result.isBlank() ? null : result;
    }

    /** 拼接多个消息列表（避免 List.of 参数为 null）。 */
    private static List<ChatMessageDto> concatLists(List<ChatMessageDto>... lists) {
        List<ChatMessageDto> out = new ArrayList<>();
        for (List<ChatMessageDto> l : lists) {
            if (l != null) {
                out.addAll(l);
            }
        }
        return out;
    }

    /** 构建 summaryRequest user 消息（CC createUserMessage({content: compactPrompt})）。 */
    static ChatMessageDto buildSummaryRequestMessage(String prompt) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user",
            prompt == null ? "" : prompt, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    /**
     * 构建压缩摘要 user 消息 · CC original: createUserMessage({content,
     * isCompactSummary: true, isVisibleInTranscriptOnly: true})（compact.ts:614-624）。
     *
     * <p>Java 映射（IMP2-14 ✗-8 后）：ChatMessageDto 现携带
     * {@code isCompactSummary}/{@code isVisibleInTranscriptOnly} 独立字段
     * （CC messages.ts:464-465/479-480 可观察性标志），不再依赖
     * {@link #SUMMARY_SUBTYPE} subtype 单一判别；subtype 标记保留（读侧
     * PartialCompactConversation.isCompactSummaryMessage :407-411 既有判别）。
     */
    public static ChatMessageDto buildCompactSummaryMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "system",
            content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false,
            null, SUMMARY_SUBTYPE, false, null, null, null,
            null, null, null, true, true);
        // IMP2-14: isCompactSummary=true + isVisibleInTranscriptOnly=true（compact.ts:621-622）
    }

    /** 构建 PTL 重试合成 user 标记（CC createUserMessage({content: marker, isMeta: true})）。 */
    private static ChatMessageDto buildMetaMarkerMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user",
            content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, true, false);
    }
}
