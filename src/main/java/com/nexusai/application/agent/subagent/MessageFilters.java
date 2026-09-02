package com.nexusai.application.agent.subagent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息过滤器工具类 · 对齐 CC {@code utils/messages.ts} + {@code runAgent.ts} 的过滤函数.
 *
 * <p><b>⚠ 语义区分 (S5 纪律):</b>
 * <ul>
 *   <li>{@link #filterUnresolvedToolUses} —— CC messages.ts:2795, <b>resumeAgent 用</b>:
 *       仅当 assistant 消息的 <b>ALL</b> tool_use 均 unresolved 才丢弃 (保留 partial)。</li>
 *   <li>{@link #filterIncompleteToolCalls} —— CC runAgent.ts:866, <b>summary 用</b>:
 *       丢弃 assistant 消息的 <b>ANY</b> tool_use 无对应 tool_result。</li>
 * </ul>
 * 两者语义不同, <b>不可混用</b> (resume 用 ALL, summary 用 ANY).
 *
 * <p>Java 消息模型映射: assistant 消息的 tool_use 块 → {@link AgentMessage#toolCalls()}
 * ({@link AgentMessage.ToolCallInfo#id()}), tool_result → {@code role=tool} 消息的
 * {@link AgentMessage#toolCallId()} (CC 中 tool_result 是 user 消息 content 内块, Java 端
 * 用独立 tool-role 消息承载, 语义等价).
 */
public final class MessageFilters {

    private static final Logger log = LoggerFactory.getLogger(MessageFilters.class);

    private MessageFilters() {}

    /**
     * 过滤 unresolved tool uses · 对齐 CC messages.ts:2795.
     *
     * <p>收集所有 tool_use id + tool_result id (直接扫消息), unresolvedIds = toolUse - toolResult;
     * 仅当 assistant 消息的 <b>ALL</b> tool_use 块均 unresolved 才丢弃 (保留 partial —
     * 与 filterIncompleteToolCalls 的 ANY 语义不同).
     *
     * @param messages 原始消息列表
     * @return 过滤后的消息列表 (非 assistant 消息恒保留)
     */
    public static List<AgentMessage> filterUnresolvedToolUses(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        Set<String> toolUseIds = new HashSet<>();
        Set<String> toolResultIds = new HashSet<>();
        for (AgentMessage msg : messages) {
            if (msg.toolCalls() != null) {
                for (AgentMessage.ToolCallInfo tc : msg.toolCalls()) {
                    if (tc.id() != null) toolUseIds.add(tc.id());
                }
            }
            if ("tool".equals(msg.role()) && msg.toolCallId() != null) {
                toolResultIds.add(msg.toolCallId());
            }
        }
        Set<String> unresolvedIds = new HashSet<>();
        for (String id : toolUseIds) {
            if (!toolResultIds.contains(id)) unresolvedIds.add(id);
        }
        if (unresolvedIds.isEmpty()) {
            return messages;
        }
        List<AgentMessage> filtered = new ArrayList<>(messages.size());
        for (AgentMessage msg : messages) {
            if (!"assistant".equals(msg.role())) {
                filtered.add(msg);
                continue;
            }
            List<AgentMessage.ToolCallInfo> toolUses = msg.toolCalls();
            if (toolUses == null || toolUses.isEmpty()) {
                filtered.add(msg);
                continue;
            }
            // 仅 ALL tool_use 均 unresolved 才丢弃 (CC messages.ts:2829 every → 丢弃)
            boolean allUnresolved = true;
            for (AgentMessage.ToolCallInfo tc : toolUses) {
                if (tc.id() != null && !unresolvedIds.contains(tc.id())) {
                    allUnresolved = false;
                    break;
                }
            }
            if (!allUnresolved) {
                filtered.add(msg);
            }
        }
        return filtered;
    }

    /**
     * 过滤含未配对 tool_use 的 assistant 消息 · 对齐 CC runAgent.ts:866-904.
     *
     * <p>收集有结果的 tool_use_id (role=tool 消息的 toolCallId), 丢弃 assistant 消息中
     * <b>ANY</b> tool_use 无对应结果者 (CC :898-901 hasIncompleteToolCall → exclude).
     * 作用于 AgentMessage (summary 用).
     *
     * <p><b>@SharedLogic</b>: 算法单源位于 {@link #filterIncompleteToolCallsImpl}, 本方法
     * 仅以 AgentMessage accessor 委托该单源; {@code SubagentExecutor.filterIncompleteToolCalls(List)}
     * (ChatMessageDto 版) 亦委托同一实现, 两路语义等价对齐 CC runAgent.ts:866-904.
     *
     * @param messages 原始消息列表
     * @return 过滤后的消息列表
     */
    public static List<AgentMessage> filterIncompleteToolCalls(List<AgentMessage> messages) {
        return filterIncompleteToolCallsImpl(
            messages,
            AgentMessage::role,
            AgentMessage::toolCallId,
            m -> m.toolCalls() == null ? null : m.toolCalls().stream().map(AgentMessage.ToolCallInfo::id).toList());
    }

    /**
     * filterIncompleteToolCalls 算法单源 · 对齐 CC runAgent.ts:866-904.
     *
     * <p><b>@SharedLogic 单源契约</b>: SubagentExecutor (ChatMessageDto 版) 与 MessageFilters
     * (AgentMessage 版) 两路均委托本方法, 消除双实现漂移. 算法: 收集 role='tool' 消息的
     * toolResultId (对应 CC tool_result 块的 tool_use_id) -> Set; 剔除 role='assistant' 且
     * toolUseIds 中 <b>ANY</b> id 不在 Set 内的消息; 其余消息一律保留 (CC :902 return true).
     *
     * <p>类型映射: CC 端 tool_result 在 user 消息 content 内 (BetaBlock[]), Java 端用独立
     * role=tool 消息承载 (toolCallId 即 tool_use_id); CC 端 tool_use 在 assistant content 内,
     * Java 端用 toolCalls 列表承载. 泛型 accessor 解耦消息类型, 字段零转换零丢失.
     *
     * <p>可见性说明: 计划原标 {@code private}, 但 SubagentExecutor 位于
     * {@code com.nexusai.application.agent.tool.impl} 跨包委托本方法, Java 可见性规则要求
     * {@code public} (非 CC 语义分歧, 纯 Java 跨包访问约束).
     *
     * @param messages            原始消息列表
     * @param roleGetter          role 取值 ("tool" / "assistant" / 其它); null -> 视为非 tool/assistant 保留
     * @param toolResultIdGetter  role=tool 消息的 tool_use_id (CC tool_result.tool_use_id)
     * @param toolUseIdGetter     assistant 消息的 tool_use id 列表 (CC content tool_use blocks 的 id)
     * @param <T>                 消息类型 (AgentMessage / ChatMessageDto)
     * @return 过滤后的消息列表 (null/空入参 -> List.of())
     */
    public static <T> List<T> filterIncompleteToolCallsImpl(
            List<T> messages,
            Function<T, String> roleGetter,
            Function<T, String> toolResultIdGetter,
            Function<T, List<String>> toolUseIdGetter) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        // CC runAgent.ts:868-882: 收集有结果的 tool_use_id (Java 端 role=tool 消息的 toolCallId
        // 对应 CC user 消息 content 内 tool_result 块的 tool_use_id)
        Set<String> toolUseIdsWithResults = new HashSet<>();
        for (T msg : messages) {
            if ("tool".equals(roleGetter.apply(msg))) {
                String toolResultId = toolResultIdGetter.apply(msg);
                if (toolResultId != null) {
                    toolUseIdsWithResults.add(toolResultId);
                }
            }
        }
        // CC runAgent.ts:884-903: 剔除含 ANY 未配对 tool_use 的 assistant 消息
        List<T> filtered = new ArrayList<>(messages.size());
        for (T msg : messages) {
            if ("assistant".equals(roleGetter.apply(msg))) {
                List<String> toolUseIds = toolUseIdGetter.apply(msg);
                if (toolUseIds != null && !toolUseIds.isEmpty()) {
                    // CC runAgent.ts:891-895: hasIncompleteToolCall = content.some(block =>
                    //   block.type==='tool_use' && block.id && !toolUseIdsWithResults.has(block.id))
                    boolean hasIncompleteToolCall = false;
                    for (String id : toolUseIds) {
                        if (id != null && !toolUseIdsWithResults.contains(id)) {
                            hasIncompleteToolCall = true;
                            break;
                        }
                    }
                    // CC runAgent.ts:898: return !hasIncompleteToolCall (含 ANY 未配对 -> 剔除整条)
                    if (hasIncompleteToolCall) {
                        continue;
                    }
                }
            }
            // CC runAgent.ts:902: 其余消息一律保留 (非 assistant / assistant 无 tool_use)
            filtered.add(msg);
        }
        if (log.isDebugEnabled()) {
            log.debug("[MessageFilters] filterIncompleteToolCalls 过滤完成, 入参 size={}, 过滤后 size={}",
                messages.size(), filtered.size());
        }
        return filtered;
    }

    /**
     * 过滤纯空白 content 的 assistant 消息 · 对齐 CC messages.ts:4869-4920.
     *
     * <p>resumeAgent.ts:72-74 三层过滤链第 1 层. 空白 assistant 消息 (如流式残留) 丢弃,
     * 若发生丢弃, 相邻 user 消息需合并 (API 要求 user/assistant 交替).
     *
     * @param messages 原始消息列表
     * @return 过滤后的消息列表
     */
    public static List<AgentMessage> filterWhitespaceOnlyAssistantMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        boolean hasChanges = false;
        List<AgentMessage> filtered = new ArrayList<>(messages.size());
        for (AgentMessage msg : messages) {
            if (!"assistant".equals(msg.role())) {
                filtered.add(msg);
                continue;
            }
            String content = msg.content();
            if (content == null || content.isBlank()) {
                hasChanges = true;
                continue; // 纯空白 assistant → 丢弃
            }
            filtered.add(msg);
        }
        if (!hasChanges) {
            return messages;
        }
        // 相邻 user 消息合并 (CC mergeUserMessages — Java 端拼接 content)
        List<AgentMessage> merged = new ArrayList<>(filtered.size());
        for (AgentMessage msg : filtered) {
            AgentMessage prev = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if ("user".equals(msg.role()) && prev != null && "user".equals(prev.role())) {
                merged.set(merged.size() - 1, new AgentMessage(
                    prev.role(), join(prev.content(), msg.content()), prev.isApiError(),
                    prev.agentId(), prev.isSidechain(), prev.uuid(), prev.parentUuid(),
                    prev.toolCalls(), prev.toolCallId()));
            } else {
                merged.add(msg);
            }
        }
        return merged;
    }

    /**
     * 过滤 orphaned thinking-only 消息 · 对齐 CC messages.ts:4991-5040.
     *
     * <p>resumeAgent.ts:72-74 三层过滤链第 2 层. 纯 thinking 块 (无 id 关联非-thinking 消息)
     * 的 assistant 消息丢弃 — Java 端 AgentMessage 无独立 thinking 字段, 简化实现:
     * content 为空 且无 toolCalls 的 assistant 消息视为 orphaned thinking-only 丢弃.
     *
     * @param messages 原始消息列表
     * @return 过滤后的消息列表
     */
    public static List<AgentMessage> filterOrphanedThinkingOnlyMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<AgentMessage> filtered = new ArrayList<>(messages.size());
        for (AgentMessage msg : messages) {
            if ("assistant".equals(msg.role())
                    && (msg.content() == null || msg.content().isBlank())
                    && (msg.toolCalls() == null || msg.toolCalls().isEmpty())) {
                continue; // orphaned thinking-only → 丢弃
            }
            filtered.add(msg);
        }
        return filtered;
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b == null ? "" : b;
        if (b == null || b.isBlank()) return a;
        return a + "\n" + b;
    }
}
