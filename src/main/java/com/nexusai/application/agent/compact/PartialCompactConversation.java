package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.application.agent.compact.CompactProgressEvent.HooksStart;
import com.nexusai.application.agent.compact.CompactProgressEvent.HooksStart.HookType;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.compact.fork.CacheSharingParamsBuilder;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 部分压缩单函数 · 对齐 CC {@code partialCompactConversation}
 * （Open-ClaudeCode/src/services/compact/compact.ts:772-1106）。
 *
 * <p><b>WHY 存在（IMP-11 完整实现，OD-14 裁决）</b>: CC 压缩除全量 {@code compactConversation}
 * 外，还有 partial 变体——按 pivot 把对话切为「被摘要段 + 保留段」，方向为
 * {@code 'up_to'}（摘要较早消息，保留尾段）或 {@code 'from'}（摘要较晚消息，保留头段）。
 * OD-14 裁决<b>不因无调用方砍语义</b>：UI/API 入口由 owner 后续决策，本类按 CC 完整实现
 * 全流程。接口用 Spring/Java 惯用法，行为对齐 CC。
 *
 * <h2>CC 对齐（compact.ts:772-1106，grep -n 自验 2026-08-04）</h2>
 * <ol>
 *   <li>方向切分（:781-800）：up_to summarize={@code slice(0,pivot)} / keep={@code slice(pivot)}
 *       过滤 progress/boundary/compactSummary；from summarize={@code slice(pivot)} / keep=
 *       {@code slice(0,pivot)} 过滤 progress（保留旧 boundary）</li>
 *   <li>空 summarize 抛错（:802-808）：'Nothing to summarize before/after the selected message.'</li>
 *   <li>preCompactTokenCount（:810 tokenCountWithEstimation(allMessages)）</li>
 *   <li>pre_compact 事件 + SDK 状态（:812-817）+ PreCompact hooks（:818-824，trigger='manual'）</li>
 *   <li>指令合并（:827-834）：hook 指令在前 + {@code `\n\nUser context: ${userFeedback}`}</li>
 *   <li>compact_start（:836-838）+ getPartialCompactPrompt（:840-843）</li>
 *   <li>API 缓存选择（:852-858）：up_to 前缀命中直发 messagesToSummarize；from 发 allMessages</li>
 *   <li>PTL retry 循环（:862-899，tengu_partial_compact_failed prompt_too_long /
 *       tengu_compact_ptl_retry，MAX_PTL_RETRIES）</li>
 *   <li>无摘要 / API 前缀抛错（:900-916，tengu_partial_compact_failed no_summary/api_error）</li>
 *   <li>readFileState 缓存（:918-921）→ 附件恢复（:925-953，preserved=messagesToKeep Read 去重）</li>
 *   <li>session_start hooks（:977-983）→ postCompactTokenCount + compactionUsage（:985-988）</li>
 *   <li>lastPreCompactUuid（:1009-1013：up_to 前缀最后非 progress；from 取 messagesToKeep.at(-1)）</li>
 *   <li>boundary = createCompactBoundaryMessage('manual', preTokens, lastUuid, userFeedback,
 *       messagesSummarized)（:1014-1020）+ preCompactDiscoveredTools（:1023-1028）</li>
 *   <li>summaryMessages（:1031-1045）：isCompactSummary + summarizeMetadata{messagesSummarized,
 *       userContext, direction}（messagesToKeep 非空时）</li>
 *   <li>notifyCompaction + markPostCompaction + reAppendSessionMetadata（:1047-1057）</li>
 *   <li>post_compact hooks（:1065-1075）→ anchorUuid + annotateBoundaryWithPreservedSegment
 *       （:1077-1087：up_to anchor=最后 summary / from anchor=boundary）</li>
 *   <li>错误通知（:1097-1099 addErrorNotificationIfNeeded）+ finally compact_end（:1100-1105）</li>
 * </ol>
 *
 * <p><b>Java 映射注记</b>:
 * <ul>
 *   <li>CC {@code Message.type} 判别字段 → Java {@code ChatMessageDto.subtype}（boundary 已对齐，
 *       OD-18）；progress 消息以 {@link #PROGRESS_SUBTYPE} 判别；compactSummary 以
 *       {@link CompactConversation#SUMMARY_SUBTYPE} 判别。</li>
 *   <li>CC {@code cacheSafeParams.forkContextMessages}（up_to = messagesToSummarize 前缀，
 *       compact.ts:855-858）为 StreamCompactSummary 的 fork 缓存接线面（CacheSafeParams supplier），
 *       本类把前缀语义落实为「up_to 直发 messagesToSummarize 给摘要生产者」（可观察的缓存直发行为）。</li>
 *   <li>[RES-OPD-SP33] partial 压缩触发摘要前接入 fork 缓存共享通道（同 R1 manual /compact 方案）：
 *       压缩前 {@link #buildCacheSafeParamsForPartial} 构建 CacheSafeParams（forkContextMessages =
 *       apiMessages）→ {@code CacheSafeParamsHolder.save} → summarize → finally clear，消除 partial
 *       启用时槽位空（无缓存共享）问题。best-effort：toolUseContext/sysPromptCtxProvider 缺失 →
 *       build null → save(null) → fork 跳过（不阻断压缩）。</li>
 *   <li>CC partial 返回<b>不含</b> truePostCompactTokenCount（compact.ts:1082-1096）→
 *       {@link CompactionResult} 该字段传 0（CC 不计算）。</li>
 *   <li>CC {@code summarizeMetadata} 在 Java 侧以 summary 消息的 {@code structuredOutput}
 *       携带（ChatMessageDto 无独立字段，避免改模型；summary 为 user 消息，structuredOutput
 *       不序列化到 API——AnthropicSdkProvider 仅 role=tool 序列化）。</li>
 * </ul>
 *
 * @see CompactConversation
 * @see CompactConversationContext
 * @see CompactBoundaryMessage
 * @see CompactionResult
 */
public final class PartialCompactConversation {

    private static final Logger log = LoggerFactory.getLogger(PartialCompactConversation.class);

    private PartialCompactConversation() { /* 静态工具类 */ }

    // ════════════════════════════════════════════════════════════════════
    // [ALIGN-COMP-1] invoked_skills 重注入数据源 holder（CS-2）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 会话 AgentState 注册表（invoked_skills 重注入数据源）· 对齐 CC 全局 {@code STATE}
     * 读侧语义，镜像 {@link CompactConversation#setSessionAgentStateRegistry}（同文件修改面，
     * 同 holder 模式，无双轨）。
     *
     * <p><b>WHY（CS-2 / M-33）</b>: CC partial 压缩成功路径在 plan/plan_mode 之后注入
     * {@code createSkillAttachmentIfNeeded(context.agentId)}（compact.ts:950-953）；
     * Java partial 路径（本类 step 13）此前仅 restore() 不 populate（探查 ✗ M-33），
     * 压缩后 invoked_skills 内容丢失。holder 经
     * {@link PostCompactAttachmentRestorer#populateInvokedSkillsAttachment} 按
     * {@code ctx.sessionId} 解析主 AgentState → 复用 per-skill 5K + 总预算 25K
     * most-recent-first 装配（compact.ts:1494-1534）。
     *
     * <p><b>注入方式</b>: 调用方（PartialCompactService，构造注入 registry；测试直接
     * setter）在调用 {@link #partialCompactConversation} 前写入。未注入 → populate 安全
     * 跳过（不中断压缩成功路径，对齐 CompactConversation holder 语义）。
     */
    private static volatile com.nexusai.application.agent.SessionAgentStateRegistry sessionAgentStateRegistry;

    /**
     * 注入会话 AgentState 注册表（幂等）· 透传 {@link CompactConversation} 同款静态 holder 模式。
     *
     * @param registry 会话 AgentState 注册表（null → skill 重注入关闭）
     */
    public static void setSessionAgentStateRegistry(
            com.nexusai.application.agent.SessionAgentStateRegistry registry) {
        sessionAgentStateRegistry = registry;
        log.info("[PartialCompactConversation] SessionAgentStateRegistry 注入: {}",
            registry != null ? "已注入" : "未注入");
    }

    // ════════════════════════════════════════════════════════════════════
    // [V54 token-compact-fix B1-2] 压缩配置 DB 实时读源静态槽位（PTL 重试上限）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 压缩配置 DB 实时读源 · [V54 token-compact-fix B1-2] 静态 volatile 槽位
     * （同 {@link CompactConversation#setSettingsResolver} 同款 holder 模式，无双轨）。
     * partial 路径 PTL 重试上限 {@code settings.max_ptl_retries} 实时读；未注入 → 回落
     * 常量默认 3。
     *
     * <p>注入：PartialCompactService（@Service，partialCompact 调用前写入）。
     */
    private static volatile CompactSettingsResolver settingsResolver;

    /**
     * 注入压缩配置 DB 实时读源（幂等）· 同 {@link CompactConversation#setSettingsResolver}
     * 回落语义（null → 复位回落常量默认）。
     *
     * @param resolver 压缩配置实时读源（null → 复位回落常量默认）
     */
    public static void setSettingsResolver(CompactSettingsResolver resolver) {
        settingsResolver = resolver;
        if (log.isDebugEnabled()) {
            log.debug("[PartialCompactConversation] setSettingsResolver: 注入={}（PTL 重试上限"
                + " DB 实时覆盖，null 回落常量）", resolver != null ? "已注入" : "复位");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 常量（CC compact.ts:802-808/900-916）
    // ════════════════════════════════════════════════════════════════════

    /** CC original: 'Nothing to summarize before the selected message.' (compact.ts:805) · up_to 空摘要 */
    public static final String ERROR_MESSAGE_NOTHING_TO_SUMMARIZE_BEFORE =
        "Nothing to summarize before the selected message.";

    /** CC original: 'Nothing to summarize after the selected message.' (compact.ts:806) · from 空摘要 */
    public static final String ERROR_MESSAGE_NOTHING_TO_SUMMARIZE_AFTER =
        "Nothing to summarize after the selected message.";

    /** CC original: 无摘要抛错文本（compact.ts:907）。 */
    public static final String ERROR_MESSAGE_NO_SUMMARY =
        "Failed to generate conversation summary - response did not contain valid text content";

    /** CC original: type 'progress'（messages.ts:613 createProgressMessage）→ Java subtype 判别值。 */
    public static final String PROGRESS_SUBTYPE = "progress";

    /** CC original: summarizeMetadata{ messagesSummarized, userContext, direction }（compact.ts:1037-1042）。 */
    public record SummarizeMetadata(int messagesSummarized, String userContext, String direction) {}

    /**
     * PTL 重试上限 DB 实时解析 · CC original: MAX_PTL_RETRIES（compact.ts:227，默认 3）。
     *
     * <p>[V54 token-compact-fix B1-2] DB {@code settings.max_ptl_retries} 有值（&gt; 0）覆盖
     * 常量（前端 PUT settings 后下一轮生效），null 回落常量 3。与全量路径
     * {@link CompactConversation#resolveMaxPtlRetries()} 语义一致（同源 DB 列）。
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

    // ════════════════════════════════════════════════════════════════════
    // 部分压缩单函数 · 对齐 CC compact.ts:772-1106
    // ════════════════════════════════════════════════════════════════════

    /**
     * 部分压缩单函数（默认方向 from）· 对齐 CC {@code partialCompactConversation(allMessages,
     * pivotIndex, context, cacheSafeParams, userFeedback, direction='from')}（compact.ts:772-779）。
     *
     * @param allMessages  全量消息（CC allMessages）
     * @param pivotIndex   切分点（CC pivotIndex）
     * @param ctx          压缩上下文（CC ToolUseContext 依赖面）
     * @param userFeedback 用户补充上下文（CC userFeedback，可选）
     * @return CompactionResult（CC partial 返回，truePostCompactTokenCount 不计算传 0）
     */
    public static CompactionResult partialCompactConversation(
            List<ChatMessageDto> allMessages,
            int pivotIndex,
            CompactConversationContext ctx,
            String userFeedback) {
        return partialCompactConversation(allMessages, pivotIndex, ctx, userFeedback, CompactPrompt.Direction.FROM);
    }

    /**
     * 部分压缩单函数 · 对齐 CC {@code partialCompactConversation}（compact.ts:772-1106）。
     *
     * <p><b>WHY（IMP-11）</b>: OD-14 裁决完整实现不砍语义。本方法覆盖 REQ-18 全部
     * partial 能力：方向切分 / strip 旧 boundary / userFeedback 指令合并 / up_to 缓存直发 /
     * PTL retry / preservedSegment / summarizeMetadata / boundary 元数据。
     *
     * @param allMessages  全量消息（CC allMessages，完整对话）
     * @param pivotIndex   切分点：up_to 摘要 {@code [0,pivot)}、from 摘要 {@code [pivot,size)}
     * @param ctx          压缩上下文（CC ToolUseContext 依赖面；summaryProducer 必填）
     * @param userFeedback 用户补充上下文（CC userFeedback，可选）
     * @param direction    FROM（摘要较晚消息，保留头段）| UP_TO（摘要较早消息，保留尾段）
     * @return CompactionResult（CC partial 返回 9 字段，Java 10 字段 record 中
     *         truePostCompactTokenCount 传 0 —— CC partial 不计算）
     */
    public static CompactionResult partialCompactConversation(
            List<ChatMessageDto> allMessages,
            int pivotIndex,
            CompactConversationContext ctx,
            String userFeedback,
            CompactPrompt.Direction direction) {
        if (ctx == null) {
            throw new IllegalArgumentException("CompactConversationContext is required");
        }
        CompactPrompt.Direction dir = direction != null ? direction : CompactPrompt.Direction.FROM;
        boolean upTo = dir == CompactPrompt.Direction.UP_TO;
        int pivot = Math.max(0, Math.min(pivotIndex, allMessages == null ? 0 : allMessages.size()));
        try {
            // ── 1. 方向切分（compact.ts:781-800）──
            // up_to: summarize=[0,pivot) / keep=[pivot,size) 过滤 progress+boundary+compactSummary
            // from : summarize=[pivot,size) / keep=[0,pivot) 过滤 progress（保留旧 boundary）
            List<ChatMessageDto> messagesToSummarize = upTo
                ? slice(allMessages, 0, pivot)
                : slice(allMessages, pivot, allMessages.size());
            List<ChatMessageDto> messagesToKeep;
            if (upTo) {
                messagesToKeep = new ArrayList<>();
                for (ChatMessageDto m : slice(allMessages, pivot, allMessages.size())) {
                    if (isProgressMessage(m)
                        || BoundaryReader.isCompactBoundaryMessage(m)
                        || isCompactSummaryMessage(m)) {
                        continue;
                    }
                    messagesToKeep.add(m);
                }
            } else {
                messagesToKeep = new ArrayList<>();
                for (ChatMessageDto m : slice(allMessages, 0, pivot)) {
                    if (!isProgressMessage(m)) {
                        messagesToKeep.add(m);
                    }
                }
            }
            log.info("[PartialCompactConversation] 方向切分: direction={} pivot={} total={} summarize={} keep={}",
                dir, pivot, allMessages == null ? 0 : allMessages.size(),
                messagesToSummarize.size(), messagesToKeep.size());

            // ── 2. 空 summarize 抛错（compact.ts:802-808）──
            if (messagesToSummarize.isEmpty()) {
                log.warn("[PartialCompactConversation] 空 summarize: direction={}", dir);
                throw new IllegalArgumentException(upTo
                    ? ERROR_MESSAGE_NOTHING_TO_SUMMARIZE_BEFORE
                    : ERROR_MESSAGE_NOTHING_TO_SUMMARIZE_AFTER);
            }

            // ── 3. preCompactTokenCount（compact.ts:810）──
            // [A5-2] 求和 provider 分派：deepseek input 已含 cache hit → 按 ctx.model 判 anthropic
            final int preCompactTokenCount = CompactConversation.tokenCountWithEstimation(
                allMessages, CompactConversation.resolveAnthropic(ctx.getModel()));

            // ── 4. hooks_start: pre_compact + SDK 状态（compact.ts:812-817）──
            ctx.getOnCompactProgress().accept(new HooksStart(HookType.PRE_COMPACT));
            ctx.getSdkStatusSetter().accept(SDKStatus.COMPACTING);

            // ── 5. PreCompact hooks（compact.ts:818-824，trigger='manual'，customInstructions=null）──
            CompactHooks.PreCompactHookResult hookResult = CompactHooks.executePreCompactHooks(ctx, "manual", null);

            // ── 6. 指令合并（compact.ts:827-834）：hook 指令在前 + User context ──
            String customInstructions = mergeHookWithUserContext(hookResult.newCustomInstructions(), userFeedback);

            // ── 7. streamMode + responseLength + compact_start（compact.ts:836-838）──
            ctx.getStreamModeSetter().accept(SpinnerMode.REQUESTING);
            ctx.getResponseLengthSetter().accept(0);
            ctx.getOnCompactProgress().accept(new CompactProgressEvent.CompactStart());

            // ── 8. getPartialCompactPrompt → summaryRequest（compact.ts:840-843）──
            String compactPrompt = CompactPrompt.buildPartialCompactPrompt(customInstructions, dir);
            ChatMessageDto summaryRequest = CompactConversation.buildSummaryRequestMessage(compactPrompt);

            // ── 9. API 缓存选择（compact.ts:852-858）：up_to 前缀命中直发 messagesToSummarize ──
            List<ChatMessageDto> apiMessages = upTo
                ? new ArrayList<>(messagesToSummarize)
                : new ArrayList<>(allMessages);
            log.info("[PartialCompactConversation] 缓存选择: up_to={} apiMessages={}（up_to 直发前缀命中缓存）",
                upTo, apiMessages.size());

            // ── 9.5 [RES-OPD-SP33] fork 缓存共享通道（CC getCacheSharingParams compact.ts:250-287 +
            //     partial 缓存选择 compact.ts:852-858）：压缩前 build+save → summarize → finally clear ──
            // CC partialCompactConversation 由调用方传入 cacheSafeParams（REPL.tsx:4943），
            // up_to 的 forkContextMessages = messagesToSummarize（:855-858）。Java partial 无
            // cacheSafeParams 形参 → 经 CacheSharingParamsBuilder.build（同 R1 CompactCommand 共享
            // 构建器，非双轨）从 ctx 原料构建，forkContextMessages = apiMessages（up_to=前缀 / from=全量），
            // CacheSafeParamsHolder.save（StreamCompactSummary cacheSafeParamsSupplier=Holder.get() 读侧）。
            // best-effort：toolUseContext/sysPromptCtxProvider 缺失 → build 返回 null → save(null) →
            // StreamCompactSummary 跳过 fork 路径走流式 fallback（缓存共享为优化项，不阻断压缩）。
            CacheSafeParams cacheSafeParams = buildCacheSafeParamsForPartial(ctx, apiMessages);
            CacheSafeParamsHolder.save(cacheSafeParams);
            log.info("[PartialCompactConversation] fork 缓存共享通道: saved={}（up_to={}）",
                cacheSafeParams != null, upTo);

            // ── 10. PTL retry 循环（compact.ts:862-899，tengu_partial_compact_failed）──
            CompactConversation.SummaryResult summaryResult = null;
            int ptlAttempts = 0;
            for (;;) {
                summaryResult = ctx.getSummaryProducer().summarize(apiMessages, compactPrompt, preCompactTokenCount);
                String summary = summaryResult != null ? summaryResult.text() : null;
                if (summary == null || !summary.startsWith(ApiErrors.PROMPT_TOO_LONG_ERROR_MESSAGE)) {
                    break;
                }
                ptlAttempts++;
                // [V54 token-compact-fix B1-2] PTL 重试上限 DB 实时读（settings.max_ptl_retries
                //   有值覆盖常量 3，null 回落；与全量路径 CompactConversation 同源读）
                List<ChatMessageDto> truncated = ptlAttempts <= resolveMaxPtlRetries()
                    ? CompactConversation.truncateHeadForPTLRetry(apiMessages, summary)
                    : null;
                if (truncated == null) {
                    log.warn("[PartialCompactConversation] tengu_partial_compact_failed: reason=prompt_too_long "
                            + "attempts={} preTokens={} messagesSummarized={} direction={}",
                        ptlAttempts, preCompactTokenCount, messagesToSummarize.size(), dir);
                    // [IMP-CM-17] CC compact.ts:880-885 logEvent('tengu_partial_compact_failed',
                    //   {reason:'prompt_too_long', ...failureMetadata, ptlAttempts})；
                    //   failureMetadata = {preCompactTokenCount, direction, messagesSummarized}（compact.ts:845-849）
                    emitPartialCompactEvent(ctx, "tengu_partial_compact_failed", Map.of(
                        "reason", "prompt_too_long",
                        "preCompactTokenCount", preCompactTokenCount,
                        "direction", upTo ? "up_to" : "from",
                        "messagesSummarized", messagesToSummarize.size(),
                        "ptlAttempts", ptlAttempts));
                    throw new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_PROMPT_TOO_LONG);
                }
                log.info("[PartialCompactConversation] tengu_compact_ptl_retry: attempt={} dropped={} "
                        + "remaining={} path=partial",
                    ptlAttempts, apiMessages.size() - truncated.size(), truncated.size());
                // [IMP-CM-17] CC compact.ts:888-893 logEvent('tengu_compact_ptl_retry',
                //   {attempt, droppedMessages, remainingMessages, path:'partial'})
                emitPartialCompactEvent(ctx, "tengu_compact_ptl_retry", Map.of(
                    "attempt", ptlAttempts,
                    "droppedMessages", apiMessages.size() - truncated.size(),
                    "remainingMessages", truncated.size(),
                    "path", "partial"));
                apiMessages = truncated;
                // [RES-C4] OPD-SP-33 PTL retry 前缀偏移（§十二 用户拍板）· 对齐 CC
                //   compact.ts:895-898：每 retry 更新 retryCacheSafeParams.forkContextMessages = truncated，
                //   使 retry 摘要请求的 fork 缓存前缀与实际发送消息一致（缓存命中不偏移）。
                //   CacheSafeParams 为不可变 record → 以 truncated 构造新实例并 re-save
                //   （CacheSafeParamsHolder.save 同线程覆盖槽位，StreamCompactSummary fork 读侧下次读取即新前缀）；
                //   仅当首轮已 save 非 null 时更新（无 PTL 触发 → 无更新开销；首轮 save(null) → 跳过）。
                CacheSafeParams saved = CacheSafeParamsHolder.get();
                if (saved != null) {
                    CacheSafeParamsHolder.save(new CacheSafeParams(
                        saved.systemPrompt(), saved.userContext(), saved.systemContext(),
                        saved.toolUseContext(), new ArrayList<>(truncated), saved.useGlobalCacheScope()));
                    log.info("[PartialCompactConversation] PTL retry 更新 fork 前缀: forkMsgs={} "
                            + "（对齐 CC compact.ts:895-898）",
                        truncated.size());
                }
            }

            // ── 11. 无摘要 / API 前缀抛错（compact.ts:900-916）──
            String summaryText = summaryResult != null ? summaryResult.text() : null;
            // △-18 移交（IMP2-15 反思）：CC `!summary`（compact.ts:900）仅拒 null/undefined/''
            // —— 纯空白串 CC 放行（isBlank 判据为 Java 独有边缘偏差），对齐为 isEmpty。
            if (summaryText == null || summaryText.isEmpty()) {
                log.warn("[PartialCompactConversation] tengu_partial_compact_failed: reason=no_summary preTokens={}",
                    preCompactTokenCount);
                // [IMP-CM-17] CC compact.ts:901-905 logEvent('tengu_partial_compact_failed',
                //   {reason:'no_summary', ...failureMetadata}）
                emitPartialCompactEvent(ctx, "tengu_partial_compact_failed", Map.of(
                    "reason", "no_summary",
                    "preCompactTokenCount", preCompactTokenCount,
                    "direction", upTo ? "up_to" : "from",
                    "messagesSummarized", messagesToSummarize.size()));
                throw new IllegalArgumentException(ERROR_MESSAGE_NO_SUMMARY);
            }
            if (ApiErrors.startsWithApiErrorPrefix(summaryText)) {
                log.warn("[PartialCompactConversation] tengu_partial_compact_failed: reason=api_error preTokens={} err={}",
                    preCompactTokenCount, summaryText);
                // [IMP-CM-17] CC compact.ts:910-914 logEvent('tengu_partial_compact_failed',
                //   {reason:'api_error', ...failureMetadata}）
                emitPartialCompactEvent(ctx, "tengu_partial_compact_failed", Map.of(
                    "reason", "api_error",
                    "preCompactTokenCount", preCompactTokenCount,
                    "direction", upTo ? "up_to" : "from",
                    "messagesSummarized", messagesToSummarize.size()));
                throw new IllegalArgumentException(summaryText);
            }

            // ── 12. readFileState 缓存（compact.ts:918-921）──
            Map<String, CompactConversation.ReadFileState> preCompactReadFileState = snapshotReadFileState(ctx);
            ctx.clearReadFileState();

            // ── 12.1 loadedNestedMemoryPaths 清空（compact.ts:921，OPD-CM5-A-08 REWORK）──
            // CC partialCompactConversation 在 readFileState.clear() 后紧跟
            // context.loadedNestedMemoryPaths?.clear()（compact.ts:921）——与全量路径（
            // CompactConversation step 9.1 :316-324）同因：该 Set 是 memory 文件重注入去重双源之一
            // （loadedNestedMemoryPaths + readFileState.has，跨域 CM-F1），压缩后不复位 → 下轮 memory
            // 重注入命中陈旧 Set 跳过。PartialCompactService:373 已注入主循环会话级 Set 同一实例
            // （LlmAgentLoop:814）→ 补清空必需（A2 组 GLOBAL_REFLECTOR REWORK 裁决）。
            // tuc 未接线 → 空安全跳过（对齐 CC ?.）。
            ToolUseContext partialTuc = ctx.getToolUseContext();
            if (partialTuc != null && partialTuc.loadedNestedMemoryPaths() != null) {
                partialTuc.loadedNestedMemoryPaths().clear();
                log.debug("[PartialCompactConversation] loadedNestedMemoryPaths 已清空（压缩后，compact.ts:921）");
            }

            // ── 13. 附件恢复（compact.ts:925-953）· partial 传 messagesToKeep（Read 去重，:925-931）──
            // [WF6] plan_file_reference + plan_mode 重注入（CC compact.ts:939-947 plan 在 skill 之前）：
            //   与全量路径（CompactConversation step 10）同一 populate 函数，经 PlanProvider（ctx 注入 /
            //   按 sessionId 回落 PlanProviderImpl 读磁盘）读 plan 文件 → 注入。无 plan 文件 / 非 plan
            //   模式 → populate 安全跳过（不中断压缩成功路径）。顺序对齐 CC：plan → plan_mode → skill。
            PostCompactAttachmentRestorer.populatePlanAttachment(sessionAgentStateRegistry, ctx);
            PostCompactAttachmentRestorer.populatePlanModeAttachment(ctx);
            // [ALIGN-COMP-1 CS-2] invoked_skills 重注入（CC compact.ts:950-953
            //   createSkillAttachmentIfNeeded→push，位于 plan/plan_mode 之后、3×delta 之前）：
            //   与全量路径（CompactConversation step 10 :274）同一 populate 单函数、同一 holder
            //   模式。经 SessionAgentStateRegistry 按 sessionId 解析主 AgentState →
            //   getInvokedSkillsForAgent(agentId) → skillAttachment → setAdditionalPostCompactAttachments，
            //   使本步 restore() 输出含 subtype='invoked_skills'（per-skill 5K + 总预算 25K，
            //   most-recent-first）。未注入 holder / 无 skill → populate 安全跳过（不中断压缩成功路径）。
            PostCompactAttachmentRestorer.populateInvokedSkillsAttachment(sessionAgentStateRegistry, ctx);
            List<ChatMessageDto> postCompactAttachments =
                PostCompactAttachmentRestorer.restore(ctx, preCompactReadFileState, messagesToKeep);

            // ── 14. hooks_start: session_start（compact.ts:977-983）──
            ctx.getOnCompactProgress().accept(new HooksStart(HookType.SESSION_START));
            List<ChatMessageDto> hookMessages = CompactHooks.processSessionStartHooks(ctx);

            // ── 15. postCompactTokenCount + compactionUsage（compact.ts:985-988）──
            // [A5-2] 求和 provider 分派：摘要 API usage 按 ctx.model 判 anthropic（deepseek 仅 input+output）
            int postCompactTokenCount = CompactConversation.tokenCountFromLastAPIResponse(
                summaryResult, CompactConversation.resolveAnthropic(ctx.getModel()));
            CompactConversation.TokenUsage compactionUsage = summaryResult != null ? summaryResult.usage() : null;

            // ── 16. lastPreCompactUuid（compact.ts:1009-1013）──
            String lastPreCompactUuid;
            if (upTo) {
                // allMessages.slice(0, pivot).findLast(m => m.type !== 'progress')?.uuid
                lastPreCompactUuid = null;
                for (ChatMessageDto m : slice(allMessages, 0, pivot)) {
                    if (!isProgressMessage(m)) {
                        lastPreCompactUuid = m.id();
                    }
                }
            } else {
                lastPreCompactUuid = messagesToKeep.isEmpty()
                    ? null
                    : messagesToKeep.get(messagesToKeep.size() - 1).id();
            }

            // ── 17. boundary（compact.ts:1014-1020）──
            CompactBoundaryMessage boundaryMarker = CompactBoundaryMessage.createCompactBoundaryMessage(
                "manual", preCompactTokenCount, lastPreCompactUuid, userFeedback, messagesToSummarize.size());

            // ── 18. preCompactDiscoveredTools（compact.ts:1023-1028）──
            Set<String> preCompactDiscovered = extractDiscoveredToolNames(allMessages);
            if (!preCompactDiscovered.isEmpty()) {
                List<String> sorted = new ArrayList<>(preCompactDiscovered);
                Collections.sort(sorted);
                CompactBoundaryMessage.CompactMetadata meta = boundaryMarker.compactMetadata();
                boundaryMarker = boundaryMarker.withCompactMetadata(new CompactBoundaryMessage.CompactMetadata(
                    meta.trigger(), meta.preTokens(), meta.userContext(), meta.messagesSummarized(),
                    sorted, meta.preservedSegment()));
                log.info("[PartialCompactConversation] boundary 记录 preCompactDiscoveredTools: {}",
                    sorted);
            }

            // ── 19. summaryMessages（compact.ts:1031-1045，isCompactSummary + summarizeMetadata）──
            String transcriptPath = transcriptPathFor(ctx);
            String summaryContent = CompactSummary.buildUserMessage(summaryText, transcriptPath, false, false);
            SummarizeMetadata summarizeMetadata = messagesToKeep.isEmpty()
                ? null
                : new SummarizeMetadata(messagesToSummarize.size(), userFeedback,
                    upTo ? "up_to" : "from");
            List<ChatMessageDto> summaryMessages = List.of(buildSummaryMessage(summaryContent, summarizeMetadata));
            log.info("[PartialCompactConversation] 摘要消息: summarizeChars={} keep={} summarizeMetadata={}",
                summaryText.length(), messagesToKeep.size(), summarizeMetadata != null);

            // ── 20. notifyCompaction + markPostCompaction + reAppendSessionMetadata（compact.ts:1047-1057）──
            ctx.getNotifyCompaction().run();
            PostCompactionState.markPostCompaction(ctx.getSessionId());
            CompactConversation.reAppendSessionMetadata(ctx);

            // ── 21. hooks_start: post_compact（compact.ts:1065-1075）──
            ctx.getOnCompactProgress().accept(new HooksStart(HookType.POST_COMPACT));
            CompactHooks.PostCompactHookResult postHookResult =
                CompactHooks.executePostCompactHooks(ctx, "manual", summaryText);

            // ── 22. anchorUuid + preservedSegment 注解（compact.ts:1077-1087）──
            String anchorUuid;
            if (upTo) {
                anchorUuid = summaryMessages.isEmpty()
                    ? boundaryMarker.uuid()
                    : summaryMessages.get(summaryMessages.size() - 1).id();
            } else {
                anchorUuid = boundaryMarker.uuid();
            }
            CompactBoundaryMessage annotatedBoundary =
                CompactBoundaryMessage.annotateBoundaryWithPreservedSegment(boundaryMarker, anchorUuid, messagesToKeep);

            // ── 23. CompactionResult 返回（compact.ts:1082-1096）──
            return new CompactionResult(
                annotatedBoundary,
                summaryMessages,
                postCompactAttachments,
                hookMessages,
                messagesToKeep,
                postHookResult.userDisplayMessage(),
                preCompactTokenCount,
                postCompactTokenCount,
                0,                                  // truePostCompactTokenCount · CC partial 不计算（:1082-1096 无该字段）
                compactionUsage);
        } catch (Exception error) {
            // ── 24. 错误通知（compact.ts:1097-1099 addErrorNotificationIfNeeded）──
            CompactConversation.addErrorNotificationIfNeeded(error, ctx);
            throw error;
        } finally {
            // ── 25. finally 收尾（compact.ts:1100-1105）──
            // [RES-OPD-SP33] 清空 fork 缓存共享槽位（对齐 R1 CompactCommand finally clear +
            //     LlmAgentLoop:2577），防槽位串台/泄漏到下一流程。
            CacheSafeParamsHolder.clear();
            ctx.getStreamModeSetter().accept(SpinnerMode.REQUESTING);
            ctx.getResponseLengthSetter().accept(0);
            ctx.getOnCompactProgress().accept(new CompactProgressEvent.CompactEnd());
            ctx.getSdkStatusSetter().accept(SDKStatus.NULL);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 指令合并（compact.ts:827-834）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 合并 PreCompact hook 指令与用户反馈 · 对齐 CC compact.ts:827-834。
     *
     * <p><b>顺序</b>: hook 指令在前，{@code `\n\nUser context: ${userFeedback}`} 在后；
     * 只有 hook 指令 → 原样；只有 userFeedback → {@code `User context: ${userFeedback}`}。
     *
     * @param hookInstructions PreCompact hook 返回指令（可选）
     * @param userFeedback     用户补充上下文（可选）
     * @return 合并后指令（两者皆空 → null）
     */
    static String mergeHookWithUserContext(String hookInstructions, String userFeedback) {
        if (hasText(hookInstructions) && hasText(userFeedback)) {
            return hookInstructions + "\n\nUser context: " + userFeedback;
        }
        if (hasText(hookInstructions)) {
            return hookInstructions;
        }
        if (hasText(userFeedback)) {
            return "User context: " + userFeedback;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    // 消息判别（CC Message.type → Java subtype）
    // ════════════════════════════════════════════════════════════════════

    /** progress 消息判别 · CC original: m.type !== 'progress'（compact.ts:795/800）。 */
    static boolean isProgressMessage(ChatMessageDto m) {
        return m != null && PROGRESS_SUBTYPE.equals(m.subtype());
    }

    /** compactSummary 判别 · CC original: m.type === 'user' && m.isCompactSummary（compact.ts:798）。 */
    static boolean isCompactSummaryMessage(ChatMessageDto m) {
        return m != null && m.role() == Role.user
            && CompactConversation.SUMMARY_SUBTYPE.equals(m.subtype());
    }

    // ════════════════════════════════════════════════════════════════════
    // extractDiscoveredToolNames（compact.ts:1023-1028 / toolSearch.ts:545-577）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 提取已发现的 deferred 工具名 · 对齐 CC {@code extractDiscoveredToolNames}
     * （toolSearch.ts:545-592）。
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
     * 序列化闭环后 DTO 层携带，与 {@link CompactConversation#extractDiscoveredToolNames}
     * legacy 路径同构）；tool_reference 扫描 user 消息 contentBlocks 中
     * type='tool_result' 的 JsonNode 块。返回集为空时 boundary 不写该字段
     * （CC :1024 if size>0）。
     *
     * @param messages 全量消息
     * @return 已发现工具名集合（无 → 空集）
     */
    public static Set<String> extractDiscoveredToolNames(List<ChatMessageDto> messages) {
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

    // ════════════════════════════════════════════════════════════════════
    // 小工具
    // ════════════════════════════════════════════════════════════════════

    /** 安全切片（CC Array.prototype.slice，越界钳制；返回新列表）。 */
    private static List<ChatMessageDto> slice(List<ChatMessageDto> messages, int from, int to) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int f = Math.max(0, Math.min(from, messages.size()));
        int t = Math.max(f, Math.min(to, messages.size()));
        return new ArrayList<>(messages.subList(f, t));
    }

    /** CC 真值语义：非 null 且非空串。 */
    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * [IMP-CM-17] 发射 partial 压缩失败/重试结构化遥测事件（双发射 recordEvent + logOTelEvent）。
     * <p>对齐 CC {@code logEvent}（compact.ts:880/:888/:901/:910 tengu_partial_compact_failed /
     * tengu_compact_ptl_retry(path='partial')）——失败/重试事件在 CC 中<b>同样走结构化 logEvent
     * 通道</b>，Java log.warn/info 文本仅为本地 console 冗余，结构化事件才是 CC logEvent 等价物。
     * telemetry 未注入（ctx.getTelemetry()==null）→ 静默跳过（测试/未接线零行为变化）。
     *
     * @param ctx   partial 压缩上下文（承载 telemetry 发射器）
     * @param event CC 事件名（tengu_partial_compact_failed / tengu_compact_ptl_retry）
     * @param attrs 事件属性（与 CC logEvent 字段逐项一致）
     */
    private static void emitPartialCompactEvent(CompactConversationContext ctx, String event, Map<String, Object> attrs) {
        Telemetry t = ctx.getTelemetry();
        if (t == null) {
            return;
        }
        t.recordEvent(event, attrs);
        t.logOTelEvent(event, attrs);
    }

    /** snapshot readFileState（CC cacheToObject(context.readFileState) 前的复制）。 */
    private static Map<String, CompactConversation.ReadFileState> snapshotReadFileState(CompactConversationContext ctx) {
        Map<String, CompactConversation.ReadFileState> src = ctx.getReadFileState();
        if (src == null || src.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(src);
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
     * 构建 partial 压缩 fork 缓存共享参数 · 对齐 CC {@code getCacheSharingParams(context,
     * messagesForCompact)}（compact.ts:250-287）+ partial 缓存选择（compact.ts:852-858）。
     *
     * <p><b>[RES-OPD-SP33]</b>: partial 压缩启用时无缓存共享通道（仅测试调用，无生产接线），
     * 命中与 manual /compact 相同的 CacheSafeParamsHolder 槽位空 → fork 缓存共享跳过 → 落流式
     * fallback。本方法复用会话 ToolUseContext（ctx.getToolUseContext()）与会话级 system prompt
     * 组装链（ctx.getSysPromptCtxProvider() + ctx.getDefaultSysPromptAssemble() +
     * ctx.getCustomSystemPrompt() + ctx.getAppendSystemPrompt()）构建 6 字段 CacheSafeParams
     * （forkedAgent.ts:57-68 + betas.ts:227-233），产物交给 {@link CacheSafeParamsHolder#save}
     * （summarize 前 save → finally clear，与 R1 CompactCommand / LlmAgentLoop 同一契约）。
     *
     * <p><b>forkContextMessages</b>: CC up_to 直发前缀命中缓存（:855-858）→
     * {@code forkContextMessages = apiMessages}（up_to = messagesToSummarize / from = allMessages），
     * 与 CompactCommand 传统路径压缩前消息快照同语义。
     *
     * <p><b>fail-safe</b>: toolUseContext / sysPromptCtxProvider 缺失 → 返回 null → 调用方
     * save(null) → Holder.get()=null → StreamCompactSummary 跳过 fork 路径走流式 fallback
     * （缓存共享为优化项，不阻断压缩）。
     *
     * @param ctx         压缩上下文（toolUseContext / sysPromptCtxProvider / defaultSysPromptAssemble /
     *                    customSystemPrompt / appendSystemPrompt / useGlobalCacheScope）
     * @param apiMessages 压缩缓存前缀（CC apiMessages，compact.ts:852-858：up_to=前缀 / from=全量）
     * @return 6 字段 CacheSafeParams；构建输入缺失 → null
     */
    private static CacheSafeParams buildCacheSafeParamsForPartial(
            CompactConversationContext ctx, List<ChatMessageDto> apiMessages) {
        if (ctx.getToolUseContext() == null || ctx.getSysPromptCtxProvider() == null) {
            log.debug("[PartialCompactConversation] 会话 ToolUseContext/sysPromptCtxProvider 未注入，"
                + "跳过 fork 缓存共享（不阻断压缩）");
            return null;
        }
        return CacheSharingParamsBuilder.build(
            ctx.getSysPromptCtxProvider(),
            ctx.getDefaultSysPromptAssemble(),
            ctx.getCustomSystemPrompt(),
            ctx.getAppendSystemPrompt(),
            ctx.getToolUseContext(),
            new ArrayList<>(apiMessages),
            ctx.isUseGlobalCacheScope());
    }

    /**
     * 构建摘要 user 消息 · CC original: createUserMessage({content, isCompactSummary: true,
     * ...(keep>0 ? {summarizeMetadata} : {isVisibleInTranscriptOnly: true})})（compact.ts:1031-1045）。
     *
     * <p>Java 映射：subtype={@link CompactConversation#SUMMARY_SUBTYPE}（isCompactSummary 判别）；
     * summarizeMetadata 以 {@code structuredOutput} 携带（user 消息不序列化到 API，
     * AnthropicSdkProvider 仅 role=tool 序列化 structuredOutput）；messagesToKeep 为空时
     * CC 走 isVisibleInTranscriptOnly（transcript-only 展示标记），Java ChatMessageDto
     * 无该字段，以 subtype 判别 + structuredOutput 为 null 表达。
     */
    static ChatMessageDto buildSummaryMessage(String content, SummarizeMetadata summarizeMetadata) {
        Map<String, Object> structuredOutput = null;
        if (summarizeMetadata != null) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("messagesSummarized", summarizeMetadata.messagesSummarized());
            meta.put("userContext", summarizeMetadata.userContext());
            meta.put("direction", summarizeMetadata.direction());
            structuredOutput = new LinkedHashMap<>();
            structuredOutput.put("summarizeMetadata", meta);
        }
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "system",
            content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), structuredOutput, false, false,
            CompactConversation.SUMMARY_SUBTYPE);
    }
}
