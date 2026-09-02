package com.nexusai.application.agent.tool;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内容替换状态 · 对齐 CC {@code src/utils/toolResultStorage.ts:390-393 ContentReplacementState}.
 *
 * <h2>L1 / L2 契约（保留）</h2>
 * <ul>
 *   <li><b>L1 行为</b>：Per-conversation-thread state, 一旦 tool result 决策过 (seen)
 *       后续所有 turn 复用同一 preview（保证 prompt cache 稳定）</li>
 *   <li><b>L2 契约</b>：
 *     <ul>
 *       <li>seenIds: 哪些 result 已经过 budget 检查（replaced 或 not）</li>
 *       <li>replacements: 已被持久化的 (toolUseId → preview 文本) 映射</li>
 *     </ul>
 *   </li>
 *   <li><b>L3 实现</b>：Java record + ConcurrentHashMap（CC 是 Set + Map lit object）</li>
 * </ul>
 *
 * <h2>CC 注释对齐</h2>
 * <pre>{@code
 * // toolResultStorage.ts:373-393
 * Per-conversation-thread state for the aggregate tool result budget.
 * State must be stable to preserve prompt cache:
 *   - seenIds: results that have passed through the budget check (replaced
 *     or not). Once seen, a result's fate is frozen for the conversation.
 *   - replacements: subset of seenIds that were persisted to disk and
 *     replaced with previews, mapped to the exact preview string shown to
 *     the model. Re-application is a Map lookup — no file I/O, guaranteed
 *     byte-identical, cannot fail.
 * }</pre>
 *
 * <h2>使用路径</h2>
 * <ul>
 *   <li>per-tool 立即处理（{@link LlmAgentLoop}）：markSeen + recordReplacement</li>
 *   <li>per-message 聚合（{@link LlmAgentLoop}）：扫描 seenIds 跳过已处理</li>
 *   <li>resume 时 (R28-3.6 (追))：{@link #reconstructForSubagentResume} —
 *       candidateIds 过滤 + inheritedReplacements gap fill</li>
 * </ul>
 */
public class ContentReplacementState {

    private final Set<String> seenIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> replacements = new ConcurrentHashMap<>();

    /**
     * 标记一个 toolUseId 已被 budget 处理（无论是否替换）。
     * <p>CC semantic: 一旦 seen, 后续所有 turn 不会再被替换（保证 prompt cache 稳定）。
     */
    public void markSeen(String toolUseId) {
        if (toolUseId != null) {
            seenIds.add(toolUseId);
        }
    }

    /** 是否曾经被 budget 处理过。 */
    public boolean isSeen(String toolUseId) {
        return toolUseId != null && seenIds.contains(toolUseId);
    }

    /**
     * 记录一个已被持久化的 toolUseId → preview 文本映射。
     * <p>preference 文本 = LLM 实际看到的（与 transcript 序列化保持完全一致）。
     */
    public void recordReplacement(String toolUseId, String preview) {
        if (toolUseId != null && preview != null) {
            seenIds.add(toolUseId);
            replacements.put(toolUseId, preview);
        }
    }

    /** 取已记录的 preview 文本（保证 byte-identical re-application）。 */
    public String getReplacement(String toolUseId) {
        return toolUseId == null ? null : replacements.get(toolUseId);
    }

    /** 只读 seenIds view. */
    public Set<String> seenIds() {
        return Collections.unmodifiableSet(seenIds);
    }

    /** 只读 replacements view. */
    public Map<String, String> replacements() {
        return Collections.unmodifiableMap(replacements);
    }

    /** 创建新 state。 */
    public static ContentReplacementState create() {
        return new ContentReplacementState();
    }

    /**
     * Clone 现有 state（fork subagent 场景）。
     * <p>对齐 CC cloneContentReplacementState (toolResultStorage.ts:404-411).
     */
    public static ContentReplacementState cloneOf(ContentReplacementState source) {
        ContentReplacementState copy = new ContentReplacementState();
        if (source != null) {
            copy.seenIds.addAll(source.seenIds);
            copy.replacements.putAll(source.replacements);
        }
        return copy;
    }

    /**
     * resume 时重建 ContentReplacementState · 对齐 CC reconstructForSubagentResume
     * (Open-ClaudeCode/src/utils/toolResultStorage.ts:1001-1012).
     *
     * <p>CC 语义 (toolResultStorage.ts:960-994 — 原 reconstructContentReplacementState 同族逻辑):
     * <ol>
     *   <li>candidateIds = resumedMessages 中所有 tool_use id (collectCandidatesByMessage)</li>
     *   <li>每个 candidate id 先 markSeen (冻结 fate)</li>
     *   <li>sidechainRecords 中 candidateIds 命中的 record 写入 replacements</li>
     *   <li>inheritedReplacements (parentState.replacements) gap-fill: candidateIds 命中且未
     *       有替换时才写入 (fork-inherited mustReapply 补缝)</li>
     * </ol>
     *
     * <p>{@code parentState == null} → 返 null (CC :1006 {@code if (!parentState) return undefined}).
     *
     * @param parentState      父 agent 的 live ContentReplacementState (可能为 null)
     * @param resumedMessages  resume 过滤后的消息 (candidateIds 来源)
     * @param sidechainRecords transcript.contentReplacements (getAgentTranscript 返回)
     * @return 重建的 state; parentState 为 null 时返回 null
     */
    public static ContentReplacementState reconstructForSubagentResume(
            ContentReplacementState parentState,
            java.util.List<? extends com.nexusai.application.agent.subagent.AgentMessage> resumedMessages,
            java.util.List<com.nexusai.application.agent.subagent.ContentReplacementRecord> sidechainRecords) {
        if (parentState == null) {
            return null;
        }
        ContentReplacementState state = new ContentReplacementState();
        Set<String> candidateIds = collectCandidateToolUseIds(resumedMessages);
        for (String id : candidateIds) {
            state.markSeen(id);
        }
        if (sidechainRecords != null) {
            for (com.nexusai.application.agent.subagent.ContentReplacementRecord r : sidechainRecords) {
                if (candidateIds.contains(r.toolUseId())) {
                    state.recordReplacement(r.toolUseId(), r.replacement());
                }
            }
        }
        if (parentState.replacements != null) {
            for (Map.Entry<String, String> e : parentState.replacements.entrySet()) {
                if (candidateIds.contains(e.getKey()) && state.getReplacement(e.getKey()) == null) {
                    state.recordReplacement(e.getKey(), e.getValue());
                }
            }
        }
        return state;
    }

    /** 收集 resumedMessages 中所有 tool_use id · 对齐 CC collectCandidatesByMessage (toolResultStorage.ts). */
    private static Set<String> collectCandidateToolUseIds(
            java.util.List<? extends com.nexusai.application.agent.subagent.AgentMessage> messages) {
        Set<String> ids = new java.util.HashSet<>();
        if (messages == null) {
            return ids;
        }
        for (com.nexusai.application.agent.subagent.AgentMessage m : messages) {
            if (m.toolCalls() != null) {
                for (com.nexusai.application.agent.subagent.AgentMessage.ToolCallInfo tc : m.toolCalls()) {
                    if (tc.id() != null) ids.add(tc.id());
                }
            }
        }
        return ids;
    }
}
