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
 *                        extract-memories/auto-dream 后台 fork = true（不污染主 transcript）
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
        FileStateCache readFileState) {

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
            onMessage, null);
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
