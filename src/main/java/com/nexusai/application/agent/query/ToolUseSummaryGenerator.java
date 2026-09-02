package com.nexusai.application.agent.query;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * [H7-arch Phase 5 P4 C7] Tool-use summary 生成器 · 对齐 CC
 * {@code generateToolUseSummary} (services/toolUseSummary/toolUseSummaryGenerator.ts:45)
 * + {@code createToolUseSummaryMessage} (utils/messages.ts:5105)。
 *
 * <p><b>CC 契约</b>:
 * <pre>
 * // 生产点 (query.ts:1412-1482, 工具批后 fire-and-forget)
 * if (config.gates.emitToolUseSummaries && toolUseBlocks.length > 0 && !aborted && !agentId) {
 *   nextPendingToolUseSummary = generateToolUseSummary({tools, signal, isNonInteractiveSession, lastAssistantText})
 *     .then(summary => summary ? createToolUseSummaryMessage(summary, toolUseIds) : null)
 *     .catch(() => null)
 * }
 * // 消费点 (query.ts:1055-1060, 下轮顶部 await + yield)
 * if (pendingToolUseSummary) { const summary = await pendingToolUseSummary; if (summary) yield summary }
 * </pre>
 *
 * <p><b>WHY 独立接口</b>: 快模型调用（Haiku）在测试中可注入 mock；默认实现
 * {@link HaikuToolUseSummaryGenerator}（{@code @Component} 生产注册，[W9-01 OPD-TS-29]）
 * 复用 {@code LlmProviderFactory#chatWithOptions} 承载 CC queryHaiku options。
 * 未注入时 loop 空值保护跳过（行为与既有版本一致）。
 */
public interface ToolUseSummaryGenerator {

    /**
     * 异步生成工具使用摘要（fire-and-forget，不阻塞主链）。
     *
     * @param state                   AgentState（turnCount 等）
     * @param toolUseBlocks           当前工具批的 tool_use blocks（CC {@code toolUseBlocks}；
     *                                由 id/name/input 三字段派生 precedingToolUseIds 与 prompt 摘要）
     * @param messagesWithToolResults 含 tool_result 的消息列表（CC {@code toolResults} 定位）
     * @param lastAssistantText       最后 assistant 文本（CC {@code lastAssistantText}）
     * @param isNonInteractiveSession CC {@code isNonInteractiveSession} 透传
     *                                （toolUseSummaryGenerator.ts:77；Java 直呼路径仅携带对齐）
     * @return 摘要 attachment（type='tool_use_summary'，含 precedingToolUseIds）；
     *         生成失败/空工具返回 completedFuture(null)
     */
    CompletableFuture<AttachmentMessageDto> generateToolUseSummaryAsync(
        AgentState state,
        List<ToolUseBlock> toolUseBlocks,
        List<ChatMessageDto> messagesWithToolResults,
        String lastAssistantText,
        boolean isNonInteractiveSession);
}
