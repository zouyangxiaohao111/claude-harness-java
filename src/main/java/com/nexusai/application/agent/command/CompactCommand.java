package com.nexusai.application.agent.command;

import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.BoundaryReader;
import com.nexusai.application.agent.compact.CompactConstants;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactConversationContext;
import com.nexusai.application.agent.compact.CompactHooks;
import com.nexusai.application.agent.compact.CompactProgressEvent;
import com.nexusai.application.agent.compact.CompactWarningState;
import com.nexusai.application.agent.compact.CompactionResult;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.PostCompactCleanup;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.compact.fork.CacheSharingParamsBuilder;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

/**
 * /compact slash command · 对齐 CC {@code Open-ClaudeCode/src/commands/compact/compact.ts:40-137}。
 *
 * <p><b>WHY 存在（IMP-10，D-06 重建）</b>: CC 中 /compact 是 <b>slash command</b>（非 Tool，
 * INV-14 / OD-09）。Java 旧 {@code CompactCommand} 是 List&lt;String&gt; stub + 自创错误常量
 * （D-06），与 CC 语义偏移。本类重建为 CC {@code call(args, context)} 等价：
 * <ol>
 *   <li>boundary 剥离（getMessagesAfterCompactBoundary，compact.ts:46）</li>
 *   <li>空校验 throw 'No messages to compact'（compact.ts:48-50）</li>
 *   <li>customInstructions = args.trim()（compact.ts:52）</li>
 *   <li>无指令 SM 优先 trySessionMemoryCompaction（compact.ts:58-82，REQ-12）——
 *       成功收尾链：cache.clear + runPostCompactCleanup + notifyCompaction +
 *       markPostCompaction + suppressCompactWarning</li>
 *   <li>reactive-only 路由 compactViaReactive（compact.ts:87，OD-01 内部算法 ?）</li>
 *   <li>microcompactMessages → getCacheSharingParams 等价构建 → CacheSafeParamsHolder.save →
 *       compactConversation（compact.ts:98-110，suppressFollowUpQuestions=false、
 *       customInstructions、isAutoCompact=false）→ finally clear 槽位（[RES-R1] manual 与
 *       auto 共用 Holder save→summarize→clear 契约，对齐 compact.ts:101-108 + :250-287）</li>
 *   <li>成功收尾链：setLastSummarizedMessageId(undefined) + suppressCompactWarning +
 *       cache.clear + runPostCompactCleanup（compact.ts:112-118）</li>
 *   <li>错误翻译四分支（compact.ts:125-135）：aborted→'Compaction canceled.' /
 *       NOT_ENOUGH→原样 / INCOMPLETE→原样 / 其他→'Error during compaction: …'</li>
 *   <li>buildDisplayText（compact.ts:230-248）</li>
 * </ol>
 *
 * <p><b>接口</b>: Java 端无 CC ToolUseContext 单对象，本类用 {@link CompactCommandContext}
 * 携带压缩所需的会话状态 + 协作器（SM/microcompact/reactive/compactConversation 上下文工厂/
 * notifyCompaction/cache.clear），由 UserInputDispatcher /compact handler 构造。
 *
 * <p><b>错误</b>: 压缩链路（compactConversation / SM）抛 {@link IllegalArgumentException}，
 * 本命令 catch 做 CC 四分支翻译后重抛（消息即用户可见错误）。
 */
public final class CompactCommand {

    private static final Logger log = LoggerFactory.getLogger(CompactCommand.class);

    /** 空消息错误 · CC commands/compact/compact.ts:48-50 'No messages to compact' */
    public static final String ERROR_NO_MESSAGES_TO_COMPACT = "No messages to compact";

    /** 取消错误 · CC compact.ts:127 'Compaction canceled.' */
    public static final String ERROR_COMPACTION_CANCELED = "Compaction canceled.";

    /** 其他错误前缀 · CC compact.ts:134 'Error during compaction: ' */
    public static final String ERROR_PREFIX = "Error during compaction: ";

    private CompactCommand() { /* 静态工具类 */ }

    // ════════════════════════════════════════════════════════════════════
    // [MF2-3] invoked_skills 重注入数据源注入面（manual 路径接线面，最小改动）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 注入会话 AgentState 注册表（manual 路径接线面）· 透传
     * {@link CompactConversation#setSessionAgentStateRegistry}。
     *
     * <p><b>WHY 存在（前瞻接线面）</b>: /compact 成功路径委托共用单函数
     * {@link CompactConversation#compactConversation}（本类 {@link #call} 委托，行 192），
     * invoked_skills 重注入（CC compact.ts:558 createSkillAttachmentIfNeeded 读全局 STATE）已
     * 在该共用成功路径统一接线 —— 故本类<b>不重复 populate 逻辑，不保留兼容壳</b>。本静态
     * setter 仅为未来 /compact handler（UserInputDispatcher 构造 {@link CompactCommandContext}）
     * 提供与 auto 路径（AutoCompactor）对称的 registry 注入面，对齐 CC 全局 STATE 读侧语义。
     *
     * @param registry 会话 AgentState 注册表（null → skill 重注入关闭）
     */
    public static void setSessionAgentStateRegistry(SessionAgentStateRegistry registry) {
        CompactConversation.setSessionAgentStateRegistry(registry);
    }

    // ════════════════════════════════════════════════════════════════════
    // 上下文 / 结果
    // ════════════════════════════════════════════════════════════════════

    /**
     * 命令执行上下文 · CC {@code call(args, context)} 的 context 依赖面。
     *
     * @param messages                        当前消息列表（boundary 剥离在命令内做）
     * @param sessionId                       会话 ID（SM 优先 / markPostCompaction）
     * @param agentId                         agent ID（SM 优先 / notifyCompaction）
     * @param querySource                     CC context.options.querySource（默认 "compact"）
     * @param verbose                         是否 verbose（buildDisplayText 省略 shortcut 提示）
     * @param abortController                 CC context.abortController.signal（aborted 翻译）
     * @param sessionMemoryService            SM 优先路径（null = 跳过 SM）
     * @param microCompactor                  microcompactMessages 参考实现
     * @param reactiveCompactor               reactive-only 参考实现（null = 不路由 reactive）
     * @param compactConversationContextSupplier compactConversation 上下文工厂
     * @param notifyCompaction                CC notifyCompaction（feature 门控由调用方接线）
     * @param clearUserContextCache           getUserContext.cache.clear（Java 等价：
     *                                        SystemPromptInjection.clearAllProviderCaches；
     *                                        IMP2-02 起由 ToolRegistrationConfig 注入真实实现）
     * @param toolUseContext                  会话工具使用上下文（fork 缓存共享 · CC original:
     *                                        {@code context}（compact.ts:285）——manual 路径复用
     *                                        主线程一致的 TUC（AgentState.currentToolUseContext），
     *                                        null → 跳过 fork 缓存共享不阻断压缩）
     * @param sysPromptCtxProvider            会话级 system/user 上下文提供者（getCacheSharingParams
     *                                        · CC original: {@code getUserContext()}/{@code getSystemContext()}
     *                                        （compact.ts:277）；null → 跳过 fork 缓存共享）
     * @param defaultSysPromptAssemble        default system prompt 惰性组装（CC original:
     *                                        {@code getSystemPrompt(tools, model, dirs, mcpClients)}
     *                                        （compact.ts:261-263）；custom 非空时不被调用）
     * @param customSystemPrompt              自定义 system prompt（CC original:
     *                                        {@code context.options.customSystemPrompt}（compact.ts:269）；
     *                                        Java 取 state.systemPrompt()）
     * @param appendSystemPrompt              用户追加指令（CC original:
     *                                        {@code context.options.appendSystemPrompt}（compact.ts:274）；
     *                                        Java 取 state.appendSystemPrompt()，OPD-SP-31 接线）
     * @param useGlobalCacheScope             firstParty fork 缓存共享 gate · CC original:
     *                                        {@code shouldUseGlobalCacheScope()}（utils/betas.ts:227-233，
     *                                        {@code getAPIProvider()==='firstParty' && !isEnvTruthy(
     *                                        CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS)}）；Java 由
     *                                        ToolRegistrationConfig.handleCompactCommand 从
     *                                        ForkSuppliers.configSupplier 取 ProviderConfig 经
     *                                        {@code GlobalCacheScope.shouldUseGlobalCacheScope} 求值注入，
     *                                        manual 与主线程（LlmAgentLoop）同一判定（REQ-R4-3）
     * @param promptCacheBreakDetectionGate [SM-10] PROMPT_CACHE_BREAK_DETECTION feature 门控
     *                                        · CC original: {@code feature('PROMPT_CACHE_BREAK_DETECTION')}
     *                                        （compact.ts:67-72）——SM 成功链 notifyCompaction
     *                                        仅在 feature 开启时调用（默认 false，对齐 CC）
     */
    public record CompactCommandContext(
            List<ChatMessageDto> messages,
            String sessionId,
            String agentId,
            String querySource,
            boolean verbose,
            AbortController abortController,
            SessionMemoryService sessionMemoryService,
            MicroCompactor microCompactor,
            ReactiveCompactor reactiveCompactor,
            Supplier<CompactConversationContext> compactConversationContextSupplier,
            Runnable notifyCompaction,
            Runnable clearUserContextCache,
            ToolUseContext toolUseContext,
            SystemPromptContextProvider sysPromptCtxProvider,
            Supplier<SystemPrompt> defaultSysPromptAssemble,
            String customSystemPrompt,
            String appendSystemPrompt,
            boolean useGlobalCacheScope,
            java.util.function.BooleanSupplier promptCacheBreakDetectionGate) {

        public CompactCommandContext {
            if (messages == null) {
                throw new IllegalArgumentException("CompactCommandContext.messages is null");
            }
        }

        /** CC context.options.querySource ?? 'compact'（compact.ts:69）。 */
        public String effectiveQuerySource() {
            return querySource == null || querySource.isBlank() ? "compact" : querySource;
        }
    }

    /**
     * 命令执行结果 · CC {@code call} 返回 {@code {type:'compact', compactionResult, displayText}}。
     *
     * @param compactionResult CC compactionResult
     * @param displayText      CC displayText（buildDisplayText）
     */
    public record CompactCommandResult(CompactionResult compactionResult, String displayText) {
        public CompactCommandResult {
            if (compactionResult == null) {
                throw new IllegalArgumentException("CompactCommandResult.compactionResult is null");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // call · CC commands/compact/compact.ts:40-137
    // ════════════════════════════════════════════════════════════════════

    /**
     * /compact 命令执行 · 对齐 CC {@code call(args, context)}（compact.ts:40-137）。
     *
     * @param args 命令参数（/compact 后文本；null → 空）
     * @param ctx  执行上下文（消息 + 协作器）
     * @return 压缩结果 + displayText
     */
    public static CompactCommandResult call(String args, CompactCommandContext ctx) {
        // ── 1. boundary 剥离（compact.ts:46）──
        List<ChatMessageDto> messages = BoundaryReader.getMessagesAfterCompactBoundary(ctx.messages());

        // ── 2. 空校验（compact.ts:48-50）──
        if (messages.isEmpty()) {
            throw new IllegalArgumentException(ERROR_NO_MESSAGES_TO_COMPACT);
        }

        // ── 3. customInstructions（compact.ts:52）──
        String customInstructions = args == null ? "" : args.trim();

        try {
            // ── 4. SM 优先（compact.ts:58-82，REQ-12）──
            if (customInstructions.isEmpty() && ctx.sessionMemoryService() != null) {
                CompactionResult smResult = ctx.sessionMemoryService().trySessionMemoryCompaction(
                    messages, ctx.sessionId(), ctx.agentId(), null);
                if (smResult != null) {
                    // SM 成功收尾链（compact.ts:63-75）：
                    // getUserContext.cache.clear + runPostCompactCleanup + notifyCompaction +
                    // markPostCompaction + suppressCompactWarning
                    // [IMP-A2-5 · OPD-CM5-A-15] 收尾顺序对齐 CC：runPostCompactCleanup 先于
                    // notifyCompaction（旧实现 notify 在前、cleanup 在后——A5 探查 △-1 曾登记不修，
                    //   本次拍板对齐 CC；auto 路径 AutoCompactor:631-643 同序，手动 /compact 归一）。
                    ctx.clearUserContextCache().run();
                    // [merge 回归修复 2026-08-14] SM 成功链无参门：runPostCompactCleanup()
                    // （compact.ts:64 无参调用）→ querySource=undefined → isMainThreadCompact=TRUE
                    // → resetContextCollapse + clearUserOnlyProviderCaches + resetGetMemoryFilesCache('compact')
                    // 全执行（merge 冲突解决时误改为 effectiveQuerySource()="compact" → gate=false
                    // → P0 缓存残留回归；IMP2-02 修复被覆盖）。
                    PostCompactCleanup.runPostCompactCleanup();
                    // [SM-10] notifyCompaction 按 PROMPT_CACHE_BREAK_DETECTION 门控（DRIFT-9）·
                    //   CC compact.ts:67-72 `if (feature('PROMPT_CACHE_BREAK_DETECTION'))`
                    //   —— feature 关闭时不动 cache-read 基线（旧实现无条件调用）。
                    if (ctx.promptCacheBreakDetectionGate().getAsBoolean()) {
                        ctx.notifyCompaction().run();
                    }
                    PostCompactionState.markPostCompaction(ctx.sessionId());
                    CompactWarningState.suppressCompactWarning();
                    log.info("[CompactCommand] SM 优先压缩成功: session={} agent={} preTokens={}",
                        ctx.sessionId(), ctx.agentId(), smResult.preCompactTokenCount());
                    return new CompactCommandResult(smResult, buildDisplayText(ctx, null));
                }
            }

            // ── 5. reactive-only（compact.ts:87，OD-01 内部算法 ?）──
            if (isReactiveOnlyMode(ctx)) {
                return compactViaReactive(messages, ctx, customInstructions);
            }

            // ── 6. microcompactMessages → compactConversation（compact.ts:98-110）──
            CompactConversationContext ccCtx = ctx.compactConversationContextSupplier().get();
            // [IMP-A2-1] 传统路径 compactConversation 内部 notifyCompaction 接线（CC compact.ts:698-699）
            wireManualNotifyCompaction(ctx, ccCtx);
            List<ChatMessageDto> messagesForCompact = microcompactMessages(messages, ctx);

            // [RES-R1] CC: compactConversation(messagesForCompact, context,
            //     await getCacheSharingParams(context, messagesForCompact), false,
            //     customInstructions, false)（compact.ts:101-108）。
            // Java 端 compactConversation 无 cacheSafeParams 形参，fork 缓存共享经
            // CacheSafeParamsHolder 槽位（StreamCompactSummary cacheSafeParamsSupplier=Holder.get()）。
            // 故传统路径压缩前构建 CacheSafeParams（对齐 CC getCacheSharingParams compact.ts:250-287）
            // 并 save → compactConversation 内 summaryProducer 触发时 Holder 非 null → fork 路径可达
            // （manual 与 auto 同一线程内 save→summarize→finally clear 契约，LlmAgentLoop:2535/2577）。
            CacheSafeParams cacheSafeParams = buildCacheSafeParamsForCompact(ctx, messagesForCompact);
            CacheSafeParamsHolder.save(cacheSafeParams);
            try {
                CompactionResult result = CompactConversation.compactConversation(
                    messagesForCompact, ccCtx, false, customInstructions, false, null);

                // ── 7. 成功收尾链（compact.ts:112-118）──
                // setLastSummarizedMessageId(undefined) + suppressCompactWarning +
                // getUserContext.cache.clear + runPostCompactCleanup。
                // [sm-cursor-sessionize P0-2] 只清本会话游标（旧 static volatile 语义跨会话清空）
                SessionMemoryService.setLastSummarizedMessageId(ctx.sessionId(), null);
                CompactWarningState.suppressCompactWarning();
                ctx.clearUserContextCache().run();
                // [IMP2-02] 无参门：runPostCompactCleanup()（compact.ts:118 无参调用）
                // → gate=TRUE 全执行（旧实现传 "compact" → gate=false → 缓存残留）。
                PostCompactCleanup.runPostCompactCleanup();
                log.info("[CompactCommand] 传统压缩成功: preTokens={} postTokens={} summary={}",
                    result.preCompactTokenCount(), result.postCompactTokenCount(),
                    result.summaryMessages() != null ? result.summaryMessages().size() : 0);

                return new CompactCommandResult(result, buildDisplayText(ctx, result.userDisplayMessage()));
            } finally {
                // finally 清槽防串台/泄漏（对齐 LlmAgentLoop auto 路径 finally clear :2577）。
                CacheSafeParamsHolder.clear();
            }
        } catch (Exception error) {
            // ── 8. 错误翻译四分支（compact.ts:125-135）──
            if (ctx.abortController() != null && ctx.abortController().isCancelled()) {
                // aborted → 'Compaction canceled.'（compact.ts:126-127）
                log.warn("[CompactCommand] 压缩被用户取消: {}", error.toString());
                throw new IllegalArgumentException(ERROR_COMPACTION_CANCELED);
            } else if (hasExactErrorMessage(error, CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES)) {
                // NOT_ENOUGH → 原样（compact.ts:128-129）
                throw new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES);
            } else if (hasExactErrorMessage(error, CompactConstants.ERROR_MESSAGE_INCOMPLETE_RESPONSE)) {
                // INCOMPLETE → 原样（compact.ts:130-131）
                throw new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_INCOMPLETE_RESPONSE);
            } else {
                // 其他 → 'Error during compaction: …'（compact.ts:132-134）
                log.error("[CompactCommand] 压缩失败: {}", error.toString());
                throw new IllegalArgumentException(ERROR_PREFIX + error.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // reactive-only · CC compact.ts:139-228 compactViaReactive
    // ════════════════════════════════════════════════════════════════════

    /**
     * reactive-only 是否生效 · 对齐 CC {@code reactiveCompact?.isReactiveOnlyMode()}
     * （compact.ts:87）。<b>2026-08-18 可达性裁决</b>: CC 真源 {@code isReactiveOnlyMode}
     * （reactiveCompact.ts:12）<b>硬编码恒 false</b> → 本判定恒 false → {@code call()} 的
     * reactive-only 路由（compact.ts:87）<b>永不触发</b>，{@link #compactViaReactive} 为
     * CC 对齐死代码（保留实现，路由不可达；compactViaReactive 已改调
     * {@code reactiveCompactOnPromptTooLong}，保持 CC 契约面 compact.ts:139-228 完整）。
     *
     * @param ctx 命令上下文
     * @return 恒 false（CC reactiveCompact.ts:12 硬编码）
     */
    public static boolean isReactiveOnlyMode(CompactCommandContext ctx) {
        // [reactive-align 2026-08-18] ReactiveCompactor.isReactiveOnlyMode() 恒 false（CC 真源），
        // 本判定恒 false，/compact reactive-only 路由死代码（对齐 CC compact.ts:87 永不触发）。
        return ctx.reactiveCompactor() != null && ctx.reactiveCompactor().isReactiveOnlyMode();
    }

    /**
     * reactive-only 压缩 · 对齐 CC {@code compactViaReactive}（compact.ts:139-228）。
     *
     * <p><b>2026-08-18 CC 真源对齐</b>: {@code reactiveCompactOnPromptTooLong}
     * （reactiveCompact.ts:14-41）委托 {@link CompactConversation#compactConversation}
     * （compact.ts:175-179 调用面）——替代旧实现委托 {@link ReactiveCompactor#tryReactiveCompact}
     * 的用户算法（OD-01 #3）。失败 reason 翻译五值（compact.ts:181-194）不变，仅判定源从
     * 「outcome==null + isTooFewGroupsToCompact 定制」改为「{@code !outcome.ok()} + 信封 reason」。
     * 本方法为 CC 对齐死代码（{@link #isReactiveOnlyMode} 恒 false，compact.ts:87 路由不触发）。
     *
     * @param messages           待压缩消息（boundary 剥离后）
     * @param ctx                命令上下文
     * @param customInstructions 用户自定义指令
     * @return 压缩结果 + displayText
     */
    private static CompactCommandResult compactViaReactive(List<ChatMessageDto> messages,
                                                           CompactCommandContext ctx,
                                                           String customInstructions) {
        CompactConversationContext ccCtx = ctx.compactConversationContextSupplier().get();
        // [IMP-A2-1] reactive 路径 compactConversation 内部 notifyCompaction 接线（CC compact.ts:698-699）
        wireManualNotifyCompaction(ctx, ccCtx);

        // hooks_start pre_compact + SDK compacting（compact.ts:149-152）
        ccCtx.getOnCompactProgress().accept(new CompactProgressEvent.HooksStart(
            CompactProgressEvent.HooksStart.HookType.PRE_COMPACT));
        ccCtx.getSdkStatusSetter().accept(SDKStatus.COMPACTING);

        try {
            // [IMP2-17 △-5] Promise.all 等价并发（compact.ts:159-165）：executePreCompactHooks ∥
            // getCacheSharingParams（Java = buildCacheSafeParamsForCompact）——hooks 起子进程、
            // cacheParams 走全部工具组装 system prompt，两者互不依赖（CC 注释明示）。
            CompletableFuture<CompactHooks.PreCompactHookResult> hooksFuture =
                CompletableFuture.supplyAsync(() -> CompactHooks.executePreCompactHooks(
                    ccCtx, "manual",
                    customInstructions == null || customInstructions.isBlank() ? null : customInstructions));
            CompletableFuture<CacheSafeParams> paramsFuture =
                CompletableFuture.supplyAsync(() -> buildCacheSafeParamsForCompact(ctx, messages));
            CompactHooks.PreCompactHookResult hookResult = hooksFuture.join();
            CacheSafeParams cacheSafeParams = paramsFuture.join();
            if (log.isDebugEnabled()) {
                log.debug("[CompactCommand] reactive 并发构建完成: hooksResult={} cacheSafeParams={} "
                    + "· CC compact.ts:159-165 Promise.all", hookResult, cacheSafeParams != null);
            }
            String mergedInstructions = CompactConversation.mergeHookInstructions(
                customInstructions, hookResult.newCustomInstructions());

            // setStreamMode requesting + setResponseLength 0 + compact_start（compact.ts:171-173）
            ccCtx.getStreamModeSetter().accept(SpinnerMode.REQUESTING);
            ccCtx.getResponseLengthSetter().accept(0);
            ccCtx.getOnCompactProgress().accept(new CompactProgressEvent.CompactStart());

            // reactiveCompactOnPromptTooLong（compact.ts:175-179 + reactiveCompact.ts:14-41）·
            // 委托 CompactConversation.compactConversation(messages, ccCtx, true, mergedInstructions,
            // true, RecompactionInfo(false,0,null,0,'compact'))。
            // [reactive-align 2026-08-18] fork 缓存共享经 CacheSafeParamsHolder 槽位
            // （CC compact.ts:175-179 cacheSafeParams 直传语义 → Java compactConversation 读 Holder）：
            // 并发构建产物 save → compactConversation 内 StreamCompactSummary 读取 → finally clear。
            CacheSafeParamsHolder.save(cacheSafeParams);
            ReactiveCompactor.ReactiveCompactOutcome outcome;
            try {
                outcome = ctx.reactiveCompactor().reactiveCompactOnPromptTooLong(
                    messages, ccCtx, mergedInstructions);
            } finally {
                CacheSafeParamsHolder.clear();
            }
            if (!outcome.ok()) {
                // [IMP2-17 △-4] !ok → 四 reason 翻译（compact.ts:181-194）：too_few_groups→
                // NOT_ENOUGH / aborted→USER_ABORT（外层 call catch 在 signal.aborted 时改写
                // 'Compaction canceled.'，CC 同序 compact.ts:126-127）/ exhausted|error|
                // media_unstrippable→INCOMPLETE。
                ReactiveFailureReason reason = classifyReactiveFailure(ctx, outcome.reason());
                if (log.isDebugEnabled()) {
                    log.debug("[CompactCommand] reactive 压缩失败: reason={} · CC compact.ts:181-194",
                        reason);
                }
                throw new IllegalArgumentException(translateReactiveFailureReason(reason));
            }
            CompactionResult reactiveResult = outcome.result();

            // 成功收尾链（compact.ts:200-203）：setLastSummarizedMessageId(undefined) +
            // runPostCompactCleanup + suppressCompactWarning + getUserContext.cache.clear。
            // [sm-cursor-sessionize P0-2] 只清本会话游标（旧 static volatile 语义跨会话清空）
            SessionMemoryService.setLastSummarizedMessageId(ctx.sessionId(), null);
            // [IMP2-02] 无参门：runPostCompactCleanup()（compact.ts:201 无参调用）
            // → gate=TRUE 全执行（旧实现传 "compact" → gate=false → 缓存残留）。
            PostCompactCleanup.runPostCompactCleanup();
            CompactWarningState.suppressCompactWarning();
            ctx.clearUserContextCache().run();

            // combinedMessage（compact.ts:209-212）
            String combinedMessage = combineDisplayMessages(
                hookResult.userDisplayMessage(), reactiveResult.userDisplayMessage());
            CompactionResult result = new CompactionResult(
                reactiveResult.boundaryMarker(),
                reactiveResult.summaryMessages(),
                reactiveResult.attachments(),
                reactiveResult.hookResults(),
                reactiveResult.messagesToKeep(),
                combinedMessage,
                reactiveResult.preCompactTokenCount(),
                reactiveResult.postCompactTokenCount(),
                reactiveResult.truePostCompactTokenCount(),
                reactiveResult.compactionUsage());
            log.info("[CompactCommand] reactive-only 压缩成功: preTokens={} boundary={}",
                result.preCompactTokenCount(),
                result.boundaryMarker() != null ? result.boundaryMarker().subtype() : null);

            return new CompactCommandResult(result, buildDisplayText(ctx, combinedMessage));
        } finally {
            // finally 收尾（compact.ts:222-227）：setStreamMode requesting + setResponseLength 0 +
            // compact_end + setSDKStatus null
            ccCtx.getStreamModeSetter().accept(SpinnerMode.REQUESTING);
            ccCtx.getResponseLengthSetter().accept(0);
            ccCtx.getOnCompactProgress().accept(new CompactProgressEvent.CompactEnd());
            ccCtx.getSdkStatusSetter().accept(SDKStatus.NULL);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // reactive 失败 reason 翻译 · CC compact.ts:181-194
    // ════════════════════════════════════════════════════════════════════

    /** reactiveCompactOnPromptTooLong 失败 reason · CC compact.ts:185-193 outcome.reason 五值。 */
    enum ReactiveFailureReason {
        TOO_FEW_GROUPS, ABORTED, EXHAUSTED, ERROR, MEDIA_UNSTRIPPABLE
    }

    /**
     * 失败 reason 翻译 · 对齐 CC compact.ts:185-193 switch：too_few_groups→NOT_ENOUGH /
     * aborted→USER_ABORT / exhausted|error|media_unstrippable→INCOMPLETE。
     *
     * @param reason 失败原因
     * @return CC ERROR_MESSAGE_* 常量
     */
    static String translateReactiveFailureReason(ReactiveFailureReason reason) {
        return switch (reason) {
            case TOO_FEW_GROUPS -> CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES;
            case ABORTED -> CompactConstants.ERROR_MESSAGE_USER_ABORT;
            case EXHAUSTED, ERROR, MEDIA_UNSTRIPPABLE -> CompactConstants.ERROR_MESSAGE_INCOMPLETE_RESPONSE;
        };
    }

    /**
     * {@code !outcome.ok()} 时的 reason 判定 · 对齐 CC compact.ts:181-194 引用面语义：
     * 信封 {@code reason} = CC {@code String(error)}（reactiveCompact.ts:39，异常 toString），
     * 按消息内容分类：
     * <ol>
     *   <li>abort 信号置位 → {@code 'aborted'}（外层 call catch 再按 signal.aborted 改写
     *       'Compaction canceled.'，CC 同序 compact.ts:126-127）</li>
     *   <li>{@code reason} 含 {@link CompactConstants#ERROR_MESSAGE_NOT_ENOUGH_MESSAGES}
     *       （compactConversation 空输入抛错，compact.ts:397-399）→ {@code 'too_few_groups'}</li>
     *   <li>其余（摘要失败 / API 错误等）→ {@code 'error'}（exhausted|error|media_unstrippable
     *       三值同映射 INCOMPLETE，compact.ts:190-193）</li>
     * </ol>
     *
     * @param ctx    命令上下文（abort 信号）
     * @param reason 信封 reason（CC {@code String(error)}；null → ERROR）
     * @return 分类后的 reason
     */
    static ReactiveFailureReason classifyReactiveFailure(CompactCommandContext ctx, String reason) {
        if (ctx.abortController() != null && ctx.abortController().isCancelled()) {
            return ReactiveFailureReason.ABORTED;
        }
        if (reason != null && reason.contains(CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES)) {
            return ReactiveFailureReason.TOO_FEW_GROUPS;
        }
        return ReactiveFailureReason.ERROR;
    }

    // ════════════════════════════════════════════════════════════════════
    // 支撑方法
    // ════════════════════════════════════════════════════════════════════

    /**
     * microcompactMessages 参考调用 · 对齐 CC {@code microcompactMessages(messages, context)}
     * （compact.ts:98，microCompact.ts:253 链式入口，IMP-09 重建）。
     *
     * <p><b>IMP-09（D-12）</b>: 旧实现委托 {@link MicroCompactor#compact(List)}（L3 内容清除主路径，
     * 已删）；新实现调用链式入口 {@code microcompactMessages(messages, querySource)}。
     *
     * <p><b>[IMP2-11 V2-S4] /compact 入口 source 语义</b>: CC compact.ts:98 调用不传 querySource
     * （undefined）→ {@code isMainThreadSource(undefined)=true}（microCompact.ts:249-251 向后兼容）
     * → cached 门控<b>可进</b>（若 feature/module/model 三条件满足）；time-based 仍不触发
     * （microCompact.ts:427-433 {@code !querySource} 短路）。旧实现传 {@code effectiveQuerySource()}
     * ="compact" 字符串 → cached 门控不可进（语义偏移，V2-S4）。对齐后传 null（undefined 等价）。
     *
     * @param messages 消息列表
     * @param ctx      命令上下文（提供 microCompactor）
     * @return microcompact 后的消息列表（compact.ts:99 messagesForCompact）
     */
    private static List<ChatMessageDto> microcompactMessages(List<ChatMessageDto> messages,
                                                            CompactCommandContext ctx) {
        if (ctx.microCompactor() == null) {
            log.debug("[CompactCommand] microCompactor 未注入，跳过 microcompact");
            return messages;
        }
        // [IMP2-11 V2-S4] 对齐 CC compact.ts:98（无 querySource 传参 → undefined）：
        // 传 null 而非 effectiveQuerySource()（"compact" 字符串）——isMainThreadSource(null)=true，
        // cached 门控可进；time-based 不触发（microCompact.ts:427-433）。
        return ctx.microCompactor()
            .microcompactMessages(messages, null)
            .messages();
    }

    /**
     * [IMP-A2-1 · OPD-CM5-A-06] 手动 /compact 传统（及 reactive）路径 compactConversation
     * 上下文 notifyCompaction 接线 · 对齐 CC compact.ts:698-699
     * {@code if (feature('PROMPT_CACHE_BREAK_DETECTION')) { notifyCompaction(
     * context.options.querySource ?? 'compact', context.agentId) }}。
     *
     * <p><b>WHY（OPD-CM5-A-06 · 全局报告 §5 #1/#2 同类缺口）</b>: SM 成功链（:242-244）已接线
     * notifyCompaction；但传统路径的 {@link CompactConversationContext} 由
     * {@code ToolRegistrationConfig.buildCompactConversationContext} 构造，未
     * {@code setNotifyCompaction} → 默认 no-op（CompactConversationContext:92）→
     * {@link CompactConversation#compactConversation} step 15（:388）调用后 cache-read 基线
     * 不复位 → 下轮 LLM turn 消息数下降被误报为 cache break（或命中陈旧指令/记忆）。
     * 与 auto 路径 {@code AutoCompactor.wireAutoNotifyCompaction} 同模式：外层按
     * {@code promptCacheBreakDetectionGate} 门控短路（与 SM 成功链同门，读同一
     * FeatureFlags.promptCacheBreakDetection()）→ feature 关 → no-op 等价；feature 开 →
     * {@code ctx.notifyCompaction()}（ToolRegistrationConfig 单点注入
     * gatedBy(featureFlags)，真实重置 prevCacheReadTokens，promptCacheBreakDetection.ts:689-698）。
     *
     * @param ctx    命令上下文（notifyCompaction + promptCacheBreakDetectionGate）
     * @param ccCtx  待接线的压缩上下文（compactConversation step 15 消费）
     */
    private static void wireManualNotifyCompaction(CompactCommandContext ctx,
                                                   CompactConversationContext ccCtx) {
        if (ccCtx == null || ctx == null || ctx.notifyCompaction() == null
                || ctx.promptCacheBreakDetectionGate() == null) {
            return;
        }
        ccCtx.setNotifyCompaction(() -> {
            if (ctx.promptCacheBreakDetectionGate().getAsBoolean()) {
                ctx.notifyCompaction().run();
            }
        });
    }

    /**
     * 构建 manual 压缩 fork 缓存共享参数 · 对齐 CC {@code getCacheSharingParams(context,
     * messagesForCompact)}（compact.ts:250-287）。
     *
     * <p><b>[RES-R1]</b>: manual /compact 缺口实锤（OPD-SP-24）= CompactCommandContext 无
     * ToolUseContext 通道 → Holder 槽位恒空 → fork 缓存共享跳过 → 落 streamingFallback
     * （tools=null）。本方法复用会话 ToolUseContext（ctx.toolUseContext()，主线程 per-turn
     * TUC）与会话级 system prompt 组装链（ctx.sysPromptCtxProvider() + ctx.defaultSysPromptAssemble()
     * + ctx.customSystemPrompt()）构建 6 字段 CacheSafeParams（forkedAgent.ts:57-68 +
     * betas.ts:227-233），产物交给 {@link CacheSafeParamsHolder#save}（call 传统路径 → finally clear）。
     *
     * <p><b>[RES-R4-1]</b>: gate 传 ctx.useGlobalCacheScope()（ToolRegistrationConfig 从
     * configSupplier 经 {@link com.nexusai.application.agent.compact.fork.GlobalCacheScope}
     * 求值注入）——manual fork 缓存共享与主线程同一 firstParty 判定（REQ-R4-3），不再恒 false。
     *
     * <p><b>fail-safe</b>: toolUseContext / sysPromptCtxProvider 缺失 → 返回 null →
     * 调用方 save(null) → Holder.get()=null → StreamCompactSummary 跳过 fork 路径走流式
     * fallback（缓存共享为优化项，不阻断压缩）。defaultSysPromptAssemble 缺省时
     * CacheSharingParamsBuilder 走 I-13 custom 短路或空 default（不 NPE）。
     *
     * @param ctx               命令上下文（toolUseContext / sysPromptCtxProvider /
     *                          defaultSysPromptAssemble / customSystemPrompt / useGlobalCacheScope）
     * @param messagesForCompact 压缩前消息快照（CC {@code messagesForCompact} compact.ts:104）
     * @return 6 字段 CacheSafeParams；构建输入缺失 → null
     */
    private static CacheSafeParams buildCacheSafeParamsForCompact(CompactCommandContext ctx,
                                                                  List<ChatMessageDto> messagesForCompact) {
        if (ctx.toolUseContext() == null || ctx.sysPromptCtxProvider() == null) {
            log.debug("[CompactCommand] 会话 ToolUseContext/sysPromptCtxProvider 未注入，"
                + "跳过 fork 缓存共享（不阻断压缩）");
            return null;
        }
        return CacheSharingParamsBuilder.build(
            ctx.sysPromptCtxProvider(),
            ctx.defaultSysPromptAssemble(),
            ctx.customSystemPrompt(),
            ctx.appendSystemPrompt(),                  // [RES-SP31] 接线：append 恒末尾（CC compact.ts:274）
            ctx.toolUseContext(),
            new ArrayList<>(messagesForCompact),
            ctx.useGlobalCacheScope());                // [RES-R4-1] firstParty gate 传 7 参 build（REQ-R4-3）
    }

    /**
     * displayText 构建 · 对齐 CC {@code buildDisplayText(context, userDisplayMessage?)}
     * （compact.ts:230-248）。
     *
     * <p>[IMP-A3-4 · OPD-CM5-A-17] upgradeMessage 已对齐实施：getUpgradeMessage('tip')
     * （compact.ts:234 + contextWindowUpgradeCheck.ts:35-47）→ {@link #getUpgradeMessageTip}，
     * 当前模型设置读取自 {@link MicroCompactor#getMainLoopModel()}（getUserSpecifiedModelSetting
     * 等价，LlmAgentLoop 每轮 turn 注入）+ 1M 访问判定（CLAUDE_CODE_DISABLE_1M_CONTEXT 门，
     * check1mAccess 等价；Java 后端恒 API/PAYG → 非订阅者恒有访问）。expandShortcut
     * （compact.ts:235-239）= {@link #getShortcutDisplay} 动态解析（'app:toggleTranscript' /
     * 'Global' / fallback 'ctrl+o'）；verbose 省略 shortcut 提示。
     *
     * @param ctx               命令上下文（verbose）
     * @param userDisplayMessage CC userDisplayMessage（可选）
     * @return "Compacted " + dimmed.join("\n")（compact.ts:247）
     */
    public static String buildDisplayText(CompactCommandContext ctx, String userDisplayMessage) {
        return buildDisplayText(ctx.verbose(), userDisplayMessage);
    }

    /**
     * displayText 构建（生产默认绑定源 + 生产模型源）· 对齐 CC buildDisplayText + getShortcutDisplay。
     *
     * <p>绑定源：{@link #DEFAULT_BINDINGS}（CC loadKeybindingsSync = 默认绑定 + 用户 keybindings.json
     * 覆盖；Java 后端无用户键位读取链路，KeybindingsCommand 仅生成模板 → 生产源仅默认绑定）。
     * 模型源：[IMP-A3-4] {@link MicroCompactor#getMainLoopModel()}（当前 turn 有效模型 =
     * getUserSpecifiedModelSetting 等价）+ env CLAUDE_CODE_DISABLE_1M_CONTEXT（1M 访问禁用门）。
     *
     * @param verbose           是否 verbose
     * @param userDisplayMessage CC userDisplayMessage（可选）
     * @return "Compacted " + dimmed.join("\n")
     */
    public static String buildDisplayText(boolean verbose, String userDisplayMessage) {
        return buildDisplayText(verbose, userDisplayMessage, DEFAULT_BINDINGS,
            MicroCompactor.getMainLoopModel(), is1mContextDisabled());
    }

    /**
     * displayText 构建（纯参数版，供测试注入绑定源；无模型 → 不产生 upgradeMessage 段）
     * · 对齐 CC buildDisplayText。历史测试缝：绑定注入 + 无升级提示（null 模型 = CC 无 1M
     * 访问时亦为 null → 默认场景等价）。
     *
     * @param verbose           是否 verbose
     * @param userDisplayMessage CC userDisplayMessage（可选）
     * @param bindings          键位绑定源（CC loadKeybindingsSync() 返回值等价）
     * @return "Compacted " + dimmed.join("\n")（compact.ts:247）
     */
    public static String buildDisplayText(boolean verbose, String userDisplayMessage,
                                          List<ShortcutBinding> bindings) {
        return buildDisplayText(verbose, userDisplayMessage, bindings, null, false);
    }

    /**
     * displayText 构建（全参数版，upgradeMessage 输入可注入）· 对齐 CC buildDisplayText。
     *
     * <p>[IMP-A3-4 · OPD-CM5-A-17] dimmed 第 3 段 = {@link #getUpgradeMessageTip}
     * （compact.ts:234-245 + contextWindowUpgradeCheck.ts:35-43）——当前模型设置恰为
     * 'opus'/'sonnet' 别名且 1M 访问可用时产生升级提示段；否则 null 不加入。
     *
     * @param verbose               是否 verbose
     * @param userDisplayMessage    CC userDisplayMessage（可选）
     * @param bindings              键位绑定源（CC loadKeybindingsSync() 返回值等价）
     * @param currentModelSetting   当前模型设置（CC getUserSpecifiedModelSetting 等价；
     *                              null/非 opus·sonnet → 无升级提示段）
     * @param is1mContextDisabled   CLAUDE_CODE_DISABLE_1M_CONTEXT 禁用门（check1mAccess 等价前置）
     * @return "Compacted " + dimmed.join("\n")（compact.ts:247）
     */
    public static String buildDisplayText(boolean verbose, String userDisplayMessage,
                                          List<ShortcutBinding> bindings,
                                          String currentModelSetting, boolean is1mContextDisabled) {
        // expandShortcut = getShortcutDisplay('app:toggleTranscript', 'Global', 'ctrl+o')（compact.ts:235-239）
        String expandShortcut = getShortcutDisplay("app:toggleTranscript", "Global", "ctrl+o", bindings);
        List<String> dimmed = new ArrayList<>();
        if (!verbose) {
            dimmed.add("(" + expandShortcut + " to see full summary)");
        }
        if (userDisplayMessage != null && !userDisplayMessage.isBlank()) {
            dimmed.add(userDisplayMessage);
        }
        // upgradeMessage = getUpgradeMessage('tip')（compact.ts:234-245）
        String upgradeMessage = getUpgradeMessageTip(currentModelSetting, is1mContextDisabled);
        if (upgradeMessage != null) {
            dimmed.add(upgradeMessage);
        }
        return "Compacted " + String.join("\n", dimmed);
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-A3-4 · OPD-CM5-A-17] upgradeMessage · 对齐 CC contextWindowUpgradeCheck.ts:9-47
    //   + check1mAccess.ts:46-72
    // ════════════════════════════════════════════════════════════════════

    /**
     * 可用模型升级档 · CC {@code getAvailableUpgrade()} 返回
     * {@code {alias, name, multiplier}}（contextWindowUpgradeCheck.ts:9-30）。
     *
     * @param alias      CC alias（'opus[1m]' / 'sonnet[1m]'）
     * @param name       CC name（'Opus 1M' / 'Sonnet 1M'）
     * @param multiplier CC multiplier（5）
     */
    public record ModelUpgrade(String alias, String name, int multiplier) {}

    /**
     * 可用模型升级判定 · 对齐 CC {@code getAvailableUpgrade()}
     * （contextWindowUpgradeCheck.ts:9-30）。
     *
     * <p>CC 仅当当前模型设置恰为 'opus'/'sonnet' 别名且 checkOpus1mAccess/checkSonnet1mAccess
     * 为真（用户有 1M 访问）时返回升级档；否则 null。
     *
     * @param currentModelSetting 当前模型设置（CC getUserSpecifiedModelSetting 等价；null → null）
     * @param is1mContextDisabled CLAUDE_CODE_DISABLE_1M_CONTEXT 禁用门（check1mAccess 等价前置）
     * @return 升级档；无可用升级 → null
     */
    public static ModelUpgrade getAvailableUpgrade(String currentModelSetting, boolean is1mContextDisabled) {
        if ("opus".equals(currentModelSetting) && check1mAccess(is1mContextDisabled)) {
            return new ModelUpgrade("opus[1m]", "Opus 1M", 5);
        }
        if ("sonnet".equals(currentModelSetting) && check1mAccess(is1mContextDisabled)) {
            return new ModelUpgrade("sonnet[1m]", "Sonnet 1M", 5);
        }
        return null;
    }

    /**
     * getUpgradeMessage('tip') · 对齐 CC contextWindowUpgradeCheck.ts:42-43
     * {@code `Tip: You have access to ${upgrade.name} with ${upgrade.multiplier}x more context`}。
     *
     * @param currentModelSetting 当前模型设置（CC getUserSpecifiedModelSetting 等价）
     * @param is1mContextDisabled CLAUDE_CODE_DISABLE_1M_CONTEXT 禁用门
     * @return 升级提示串；无可用升级 → null
     */
    public static String getUpgradeMessageTip(String currentModelSetting, boolean is1mContextDisabled) {
        ModelUpgrade upgrade = getAvailableUpgrade(currentModelSetting, is1mContextDisabled);
        return upgrade == null ? null
            : "Tip: You have access to " + upgrade.name() + " with " + upgrade.multiplier() + "x more context";
    }

    /**
     * getUpgradeMessage('warning') · 对齐 CC contextWindowUpgradeCheck.ts:40-41
     * {@code `/model ${upgrade.alias}`}（TokenWarning.tsx:121 上下文接近满场景）。
     *
     * <p>Java 端消费点：AgentEvent.TokenWarning（BACK 组共享文件，装配由 BACK 接线）；本方法
     * 提供共享升级判定供该路径复用。
     *
     * @param currentModelSetting 当前模型设置（CC getUserSpecifiedModelSetting 等价）
     * @param is1mContextDisabled CLAUDE_CODE_DISABLE_1M_CONTEXT 禁用门
     * @return 升级指令串（如 "/model opus[1m]"）；无可用升级 → null
     */
    public static String getUpgradeMessageWarning(String currentModelSetting, boolean is1mContextDisabled) {
        ModelUpgrade upgrade = getAvailableUpgrade(currentModelSetting, is1mContextDisabled);
        return upgrade == null ? null : "/model " + upgrade.alias();
    }

    /**
     * checkOpus1mAccess/checkSonnet1mAccess 等价 · 对齐 CC check1mAccess.ts:46-72。
     *
     * <p>CC 逻辑：is1mContextDisabled → false；Claude AI 订阅者 → isExtraUsageEnabled；
     * 非订阅者（API/PAYG）→ true。Java 后端恒 API/PAYG（用户自配 provider key，无 Claude AI
     * 订阅概念）→ 非订阅者分支恒成立，仅 {@code CLAUDE_CODE_DISABLE_1M_CONTEXT} 禁用门参与。
     *
     * @param is1mContextDisabled CLAUDE_CODE_DISABLE_1M_CONTEXT 真值
     * @return true = 用户拥有 1M 访问
     */
    private static boolean check1mAccess(boolean is1mContextDisabled) {
        return !is1mContextDisabled;
    }

    /**
     * 1M 上下文禁用判定（非 Spring 静态兜底）· 对齐 CC context.ts:31-33 is1mContextDisabled
     * （isEnvTruthy(process.env.CLAUDE_CODE_DISABLE_1M_CONTEXT)）。
     *
     * <p>同 {@code LlmAgentLoop.is1mContextDisabled}（LlmAgentLoop.java:6894-6908）——静态场景
     * 直读 env；Spring 生产绑定等价（CompactEnvProperties.disable1MContext 同源 env）。
     */
    private static boolean is1mContextDisabled() {
        return isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_1M_CONTEXT"));
    }

    /**
     * env truthy 判定 · 对齐 CC isEnvTruthy（envUtils.ts:32-37）：
     * 值（lowercase+trim）∈ {1, true, yes, on} 为真，其余（含 null/空）为假。
     */
    private static boolean isEnvTruthy(String envVar) {
        if (envVar == null) {
            return false;
        }
        String normalized = envVar.toLowerCase().trim();
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }

    /**
     * 键位绑定 · 对齐 CC ParsedBinding（loadUserBindings.ts ParsedBinding + parser.ts parseBindings）。
     *
     * @param context CC binding.context（键位上下文名，如 "Global"）
     * @param action  CC binding.action（动作名，如 "app:toggleTranscript"）
     * @param chord   CC chordToString(binding.chord) 展示串（parser.ts:143-145，如 "ctrl+o"）
     */
    public record ShortcutBinding(String context, String action, String chord) {}

    /**
     * 生产默认绑定源 · 对齐 CC DEFAULT_BINDINGS Global 块 'ctrl+o' → 'app:toggleTranscript'
     * （defaultBindings.ts:44）。
     *
     * <p>CC loadKeybindingsSync()（loadUserBindings.ts:243-250）= 默认绑定 + 用户 keybindings.json
     * 覆盖（findLast 用户优先，resolver.ts:73-76）；Java 后端无用户键位读取链路 → 生产源仅默认绑定，
     * 命中恒 "ctrl+o"；fallback 仅在 action 未绑定时可达（测试覆盖）。
     */
    public static final List<ShortcutBinding> DEFAULT_BINDINGS = List.of(
        new ShortcutBinding("Global", "app:toggleTranscript", "ctrl+o"));

    /** 已记录 fallback 的 action:context 对 · 对齐 CC LOGGED_FALLBACKS（shortcutFormat.ts:19）。 */
    private static final Set<String> LOGGED_FALLBACKS = new HashSet<>();

    /**
     * 动态键位显示解析 · 对齐 CC getShortcutDisplay（shortcutFormat.ts:38-63）。
     *
     * <p>查找语义：getBindingDisplayText（resolver.ts:67-77）倒序 findLast —— 用户覆盖优先；命中返回
     * chord 展示串（chordToString，parser.ts:143-145）；未命中返回 fallback 并一次性记录（CC
     * tengu_keybinding_fallback_used 事件 shortcutFormat.ts:45-60 → Java debug 日志）。
     *
     * @param action   CC action（如 'app:toggleTranscript'）
     * @param context  CC context（如 'Global'）
     * @param fallback CC fallback（未命中兜底展示串）
     * @param bindings 绑定源（默认 + 用户覆盖合并，loadUserBindings.ts:196-197）
     * @return 命中的展示串或 fallback
     */
    public static String getShortcutDisplay(String action, String context, String fallback,
                                            List<ShortcutBinding> bindings) {
        for (int i = bindings.size() - 1; i >= 0; i--) {
            ShortcutBinding binding = bindings.get(i);
            if (binding.action().equals(action) && binding.context().equals(context)) {
                return binding.chord();
            }
        }
        String key = action + ":" + context;
        if (LOGGED_FALLBACKS.add(key)) {
            if (log.isDebugEnabled()) {
                log.debug("键位解析未命中，回退 fallback 展示键位：action={}, context={}, fallback={}（对齐 CC tengu_keybinding_fallback_used 一次性事件）",
                    action, context, fallback);
            }
        }
        return fallback;
    }

    /** pre+post hook 显示消息合并 · 对齐 CC compact.ts:209-212 filter(Boolean).join('\n')。 */
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

    /** hasExactErrorMessage · CC errors.ts:103-105 error instanceof Error && message === message。 */
    private static boolean hasExactErrorMessage(Throwable error, String message) {
        return error != null && error.getMessage() != null && error.getMessage().equals(message);
    }
}
