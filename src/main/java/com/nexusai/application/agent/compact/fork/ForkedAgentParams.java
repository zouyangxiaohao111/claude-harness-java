package com.nexusai.application.agent.compact.fork;

import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.function.Consumer;

/**
 * fork 查询参数 · 对齐 CC {@code ForkedAgentParams}
 * (Open-ClaudeCode/src/utils/forkedAgent.ts:83-113)。
 *
 * <p><b>WHY 存在（REQ-27）</b>: fork 缓存共享（compact.ts:1155-1248）通过
 * {@code runForkedAgent({querySource:'compact', maxTurns:1, skipCacheWrite:true,
 * overrides:{abortController}})} 复用主线程 prompt cache。本 record 承载 fork 参数契约：
 * <ul>
 *   <li><b>fork 路径不设 maxOutputTokens</b>（{@link #maxOutputTokens()} 恒 null）——
 *       设了会改 budget_tokens 破坏 cache key（compact.ts:1181-1187，INV-7）。</li>
 *   <li><b>abortController 透传</b>（{@link #abortController()}）——用户 Esc 中止 fork，
 *       与流式 fallback 同信号（compact.ts:1196-1199）。</li>
 *   <li><b>继承权限</b>——通过 {@link CacheSafeParams#toolUseContext()} 的
 *       {@code ToolUseContext.with(overrides)} 派生隔离上下文，权限 context 透传。</li>
 * </ul>
 *
 * @param promptMessages  fork 查询起始消息 · CC original: {@code promptMessages: Message[]}
 *                        (forkedAgent.ts:85)；compact 场景 = {@code [summaryRequest]} (compact.ts:1189)
 * @param cacheSafeParams 与父查询一致的 cache-safe 参数 · CC original:
 *                        {@code cacheSafeParams: CacheSafeParams} (forkedAgent.ts:87)
 * @param canUseTool      权限检查函数 · CC original: {@code canUseTool: CanUseToolFn}
 *                        (forkedAgent.ts:89)；compact 场景 = createCompactCanUseTool() deny 语义
 * @param querySource     来源标识 · CC original: {@code querySource: QuerySource}
 *                        (forkedAgent.ts:91)；compact 场景 = 'compact'
 * @param forkLabel       analytics 标签（如 'compact'）· CC original:
 *                        {@code forkLabel: string} (forkedAgent.ts:93)
 * @param maxOutputTokens 输出 token 上限（可选）· CC original:
 *                        {@code maxOutputTokens?: number} (forkedAgent.ts:103)；
 *                        <b>fork 缓存共享路径必须为 null</b>（防 cache key 破坏）
 * @param maxTurns        轮次上限（API round-trips）· CC original:
 *                        {@code maxTurns?: number} (forkedAgent.ts:105)；compact 场景 = 1
 * @param skipTranscript  跳过 sidechain transcript 记录 · CC original:
 *                        {@code skipTranscript?: boolean} (forkedAgent.ts:109)；
 *                        extract-memories/auto-dream 后台 fork = true（不污染主 transcript）。
 *                        <b>[E-1a 有意偏离 · 零消费者 · 见登记处 D-E1a-01]</b> CC 在
 *                        {@code skipTranscript=false}（或未传）时会记 sidechain transcript
 *                        （{@code recordSidechainTranscript}）；<b>本仓 fork 一律不记 sidechain
 *                        transcript</b>（无论本值为 true/false —— Java fork 链
 *                        {@link RunForkedAgent} / {@code ProductionForkedQuery} 无任何 transcript
 *                        I/O）。此为<b>用户裁定的有意偏离</b>（2026-09-12 E-1a），故字段保留但
 *                        不接线（CC 有对应物，符合本仓「死代码不一定要删」）。登记处：
 *                        {@code docs/zjkycode/plans/2026-09-12-fork-converge-E-registry.md}
 *                        （条目 D-E1a-01）。
 * @param skipCacheWrite  最后一条消息不写新 prompt cache 条目 · CC original:
 *                        {@code skipCacheWrite?: boolean} (forkedAgent.ts:112)；
 *                        compact 场景 = true（fork 不写缓存）
 * @param abortController abortController 透传（CC overrides.abortController）·
 *                        compact.ts:1196-1199；null → 隔离上下文共享/新链
 * @param onMessage       每条消息到达的回调（流式 UI）· CC original:
 *                        {@code onMessage?: (message: Message) => void} (forkedAgent.ts:107)
 * @param readFileState   共享的 readFileState 缓存（CC overrides.readFileState ·
 *                        sessionMemory.ts:324）：fork 与 setup 上下文共享同一缓存，
 *                        Edit read-before-write 门禁放行；compact/extract 老调用不传=null
 *                        （with() 内部从父 clone，行为不变）
 * @param projectRoot     [TL-W1 P1] 会话绑定 projectRoot（= 会话线程 {@code ctx.sessionState()
 *                        .workspaceDir()} / boundProject · CC {@code getOriginalCwd()} 语义）·
 *                        由**会话线程**解析后随 fork 参数透传；fork 内走
 *                        {@code contextFactory.shared(param.projectRoot())} —— 已删除 fork 内的
 *                        {@code AutoMemPaths.currentSessionProjectRoot()} 现算（runAsync/ForkJoinPool
 *                        worker 不继承 ThreadLocal → 回落 config home → fork loop ctx.workspaceDir 错）。
 *                        null = 调用方未提供（compact/session-memory 链未接线）→ fork 端
 *                        {@code shared(null)} 走 CwdResolution originalCwd 回落（非 config home）。
 */
public record ForkedAgentParams(
        List<ChatMessageDto> promptMessages,
        CacheSafeParams cacheSafeParams,
        HookPermissionResolver.CanUseTool canUseTool,
        QuerySource querySource,
        String forkLabel,
        Integer maxOutputTokens,
        Integer maxTurns,
        boolean skipTranscript,
        boolean skipCacheWrite,
        AbortController abortController,
        Consumer<ChatMessageDto> onMessage,
        FileStateCache readFileState,
        // [TL-W1 P1] 会话绑定 projectRoot（显式直传 · 会话线程解析后随 fork 参数透传）——
        //   见 {@link #projectRoot()}。null = 调用方未提供（fork 端不造字段，走 shared(null)）。
        String projectRoot) {

    /**
     * 11 参便利构造器 · 向后兼容（compact/extract-memories 老调用不传 readFileState=null，
     * 对齐 CC overrides.readFileState 缺省 = undefined 语义）。
     */
    public ForkedAgentParams(
            List<ChatMessageDto> promptMessages,
            CacheSafeParams cacheSafeParams,
            HookPermissionResolver.CanUseTool canUseTool,
            QuerySource querySource,
            String forkLabel,
            Integer maxOutputTokens,
            Integer maxTurns,
            boolean skipTranscript,
            boolean skipCacheWrite,
            AbortController abortController,
            Consumer<ChatMessageDto> onMessage) {
        this(promptMessages, cacheSafeParams, canUseTool, querySource, forkLabel,
            maxOutputTokens, maxTurns, skipTranscript, skipCacheWrite, abortController,
            onMessage, null, null);
    }

    /**
     * 12 参构造器（无 projectRoot）· 兼容既有调用方（compact/session-memory 不传 projectRoot →
     * null，行为不变）。
     */
    public ForkedAgentParams(
            List<ChatMessageDto> promptMessages,
            CacheSafeParams cacheSafeParams,
            HookPermissionResolver.CanUseTool canUseTool,
            QuerySource querySource,
            String forkLabel,
            Integer maxOutputTokens,
            Integer maxTurns,
            boolean skipTranscript,
            boolean skipCacheWrite,
            AbortController abortController,
            Consumer<ChatMessageDto> onMessage,
            FileStateCache readFileState) {
        this(promptMessages, cacheSafeParams, canUseTool, querySource, forkLabel,
            maxOutputTokens, maxTurns, skipTranscript, skipCacheWrite, abortController,
            onMessage, readFileState, null);
    }

    /**
     * [TL-W1 P1] 带显式会话 projectRoot 的副本 —— 会话线程解析后随 fork 参数透传
     * （禁止 fork 线程读 AutoMemPaths.currentSessionProjectRoot()：runAsync/ForkJoinPool
     * worker 不继承 ThreadLocal → 回落 config home → fork 的 loop ctx.workspaceDir 错）。
     *
     * @param projectRoot 会话绑定项目根（CC getOriginalCwd 语义）；null → 落回 null（不造字段）
     * @return 带 projectRoot 的新参数（其余字段逐字节不变）
     */
    public ForkedAgentParams withProjectRoot(String projectRoot) {
        return new ForkedAgentParams(promptMessages, cacheSafeParams, canUseTool, querySource, forkLabel,
            maxOutputTokens, maxTurns, skipTranscript, skipCacheWrite, abortController,
            onMessage, readFileState, projectRoot);
    }

    /** 紧凑构造器 · 校验必传 + null 兜底。 */
    public ForkedAgentParams {
        if (promptMessages == null) {
            promptMessages = List.of();
        }
        if (querySource == null) {
            throw new IllegalArgumentException("ForkedAgentParams.querySource is null");
        }
        if (forkLabel == null) {
            forkLabel = "";
        }
    }
}
