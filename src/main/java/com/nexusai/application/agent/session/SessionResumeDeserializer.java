package com.nexusai.application.agent.session;

import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 会话恢复消息反序列化 · 对齐 CC conversationRecovery.ts:167-255 {@code deserializeMessagesWithInterruptDetection}。
 *
 * <p><b>WHY（S1）</b>：Java 从 DB messages 原始行恢复（created_at ASC），无 CC 的中断检测 /
 * tool_use 配对过滤 / "Continue" sentinel 注入 —— 中断 turn 恢复后"有问无答"。本类在恢复/续聊
 * 加载历史的通道（ChatController background / PartialCompactService / AwaySummaryController）
 * 对 DB 消息流应用 CC 同款反序列化语义。
 *
 * <p><b>六步映射（CC conversationRecovery.ts:167-255）</b>：
 * <ol>
 *   <li><b>filterUnresolvedToolUses</b>（CC messages.ts:3149-3199）：assistant 消息的 tool_use 全部
 *       未配对（无对应 tool_result）→ 删该 assistant。DTO 级：assistant.toolCalls() 全 id 无
 *       user/tool 消息 toolCallId 匹配。</li>
 *   <li><b>filterOrphanedThinkingOnlyMessages</b>（CC messages.ts:5452-5519）：assistant 仅 thinking
 *       （reasoning）且无同 assistantMessageId 的非 thinking 消息 → 删（流式 block 分离残留，
 *       否则"thinking blocks cannot be modified" API 400）。</li>
 *   <li><b>filterWhitespaceOnlyAssistantMessages</b>（CC messages.ts:5328-5379）：assistant 内容仅空白
 *       （model 输出 "\n\n" 后用户取消）→ 删。Java 无 mergeUserMessages，删后相邻 user 不合并
 *       （登记 Java-idiom 偏离：DB 权威不可丢行）。</li>
 *   <li><b>detectTurnInterruption</b>（CC conversationRecovery.ts:275-339）：末条 turn-relevant
 *       （跳过 system/api-error assistant）判别 → none / interrupted_turn / interrupted_prompt。</li>
 *   <li><b>interrupted_turn → interrupted_prompt</b>（CC :213-224）：追加 meta user
 *       「Continue from where you left off.」，统一两种中断为 interrupted_prompt。</li>
 *   <li><b>末条相关是 user → splice assistant sentinel</b>（CC :234-248）：插入
 *       「No response requested.」（NO_RESPONSE_REQUESTED, messages.ts:241），保证 API 消息
 *       交替合法（末条为 user 时无 assistant 响应）。</li>
 * </ol>
 *
 * <p><b>DTO 级判断映射</b>：
 * <ul>
 *   <li>tool_use → assistant.toolCalls()（ToolCallDto.id）；tool_result → user/tool 消息 toolCallId</li>
 *   <li>thinking-only → assistant.reasoning() 非空且 content() 空白且 toolCalls() 空</li>
 *   <li>isMeta / isCompactSummary → ChatMessageDto 既有字段</li>
 *   <li>isToolUseResultMessage → role==tool（Java tool_result 即 Role.tool）或 user.toolCallId()!=null</li>
 *   <li>isTerminalToolResult → 回走找对应 tool_use 的 tool 名命中终端工具白名单
 *       （SendUserMessage / Brief / SendUserFile，CC BRIEF_TOOL_NAME / LEGACY_BRIEF_TOOL_NAME /
 *       SEND_USER_FILE_TOOL_NAME，conversationRecovery.ts:354-387）</li>
 * </ul>
 *
 * <p><b>等价确认（fix-loop-resume-history 复核，原登记为"语义差异"系误判）</b>：interrupted_turn
 * 恢复时 deserializer 在历史末条注入 meta user「Continue from where you left off.」，loop 主路径随后
 * 又 append 新用户消息（buildUserMessageWithImages，LlmAgentLoop.doRun:2522）→ 模型同时见到 Continue
 * 与全新 prompt，序列为 {@code [历史, Continue, sentinel, 当前消息]}。CC 真源复核（conversationRecovery.ts:
 * 211-224 + cli/print.ts:1172-1186/:4875 removeInterruptedMessage）：{@code deserializeWithInterruptDetection}
 * 在 interrupted_turn 时<b>无条件</b>注入 Continue（:211-224），交互式 resume 后续新 prompt 直接 append
 * 在 Continue/sentinel 之后——与 Java 序列<b>完全一致</b>；{@code removeInterruptedMessage} 仅
 * {@code CLAUDE_CODE_RESUME_INTERRUPTED_TURN} env 门控的 SDK 自动恢复路径调用，非交互式 resume。
 * 故本条为等价实现，非语义差异；"排除当前用户消息后历史末条为非终端 tool_result"场景已被
 * MessageServiceResumeExcludingTest #excludeCurrentStillDetectsInterruptedTurn 固化。
 *
 * @see <a href="https://github.com/.../Open-Claude-code/blob/main/src/utils/conversationRecovery.ts">CC conversationRecovery.ts</a>
 */
public final class SessionResumeDeserializer {

    private static final Logger log = LoggerFactory.getLogger(SessionResumeDeserializer.class);

    /** CC messages.ts:241 NO_RESPONSE_REQUESTED = 'No response requested.' */
    public static final String NO_RESPONSE_REQUESTED = "No response requested.";

    /** CC conversationRecovery.ts:216 Continue from where you left off. */
    public static final String CONTINUE_FROM_LEFT_OFF = "Continue from where you left off.";

    /** 中断状态 · 对齐 CC TurnInterruptionState（conversationRecovery.ts:142-149）。 */
    public enum InterruptionKind {
        /** 无中断（完成 turn / 空列表）。 */
        NONE,
        /** 中断 turn：末条为非终端 tool_result（工具中途被 kill）。 */
        INTERRUPTED_TURN,
        /** 中断 prompt：末条为纯文本 user（模型未开始回应）。 */
        INTERRUPTED_PROMPT
    }

    /** 反序列化结果 · 对齐 CC DeserializeResult（conversationRecovery.ts:146-149）。 */
    public record ResumeResult(List<ChatMessageDto> messages, InterruptionKind interruption) {}

    private SessionResumeDeserializer() {}

    /**
     * 反序列化 + 中断检测 · 对齐 CC conversationRecovery.ts:167-255。
     *
     * <p>输入为 DB 原始消息流（created_at ASC），输出为过滤 + 中断语义注入后的消息列表
     * （不修改 DB；本方法为读侧漏斗，写入仍走 DB 权威通道）。
     *
     * @param raw DB 原始消息列表（可 null/空 → 恒返回空列表 + NONE）
     * @return 过滤后消息列表 + 中断状态（interrupted_turn 已统一为 interrupted_prompt）
     */
    public static ResumeResult deserializeWithInterruptDetection(List<ChatMessageDto> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ResumeResult(List.of(), InterruptionKind.NONE);
        }
        // 1. 未配对 tool_use 剥离（CC :190-192 filterUnresolvedToolUses）
        List<ChatMessageDto> filtered = filterUnresolvedToolUses(raw);
        // 1.5 [fix-toolcalls-400 C] 部分配对 assistant 补 synthetic error tool_result
        //   WHY: filterUnresolvedToolUses 只删"全部未配对"的 assistant；部分配对（N 个 call 只有
        //   S<N 个 result，来自旧版 A/B 缺口落库的失衡链）保留的 assistant 仍有未配对 tool_use，
        //   resume 注入 OpenAI 即 400。本步为每个未配对 id 补一条 error tool_result（对齐 CC
        //   yieldMissingToolResultBlocks is_error:true + sourceToolAssistantUUID 语义），使注入历史合法。
        filtered = appendMissingToolResults(filtered);
        // 2. 孤立 thinking-only 剥离（CC :197-199 filterOrphanedThinkingOnlyMessages）
        filtered = filterOrphanedThinkingOnly(filtered);
        // 3. 纯空白 assistant 剥离（CC :203-205 filterWhitespaceOnlyAssistantMessages）
        filtered = filterWhitespaceOnlyAssistant(filtered);

        // 4. 中断检测（CC :207 detectTurnInterruption）
        InterruptionKind kind = detectTurnInterruption(filtered);
        if (log.isDebugEnabled()) {
            log.debug("[SessionResumeDeserializer] 中断检测完成: raw={} filtered={} kind={}",
                raw.size(), filtered.size(), kind);
        }

        // 5. interrupted_turn → 追加 meta user Continue（统一为 interrupted_prompt，CC :213-224）
        if (kind == InterruptionKind.INTERRUPTED_TURN) {
            String sessionId = lastNonNullSessionId(filtered);
            filtered.add(metaUser(sessionId, CONTINUE_FROM_LEFT_OFF, true));
            kind = InterruptionKind.INTERRUPTED_PROMPT;
            if (log.isInfoEnabled()) {
                log.info("[SessionResumeDeserializer] 检测到中断 turn，追加 meta user「{}」（对齐 CC :216）",
                    CONTINUE_FROM_LEFT_OFF);
            }
        }

        // 6. 末条相关是 user → splice assistant sentinel「No response requested.」（CC :234-248）
        spliceNoResponseRequested(filtered);

        return new ResumeResult(filtered, kind);
    }

    // ─────────────────────────── 步骤 1: filterUnresolvedToolUses ───────────────────────────

    /**
     * 剥离 tool_use 全部未配对的 assistant 消息 · 对齐 CC messages.ts:3149-3199。
     *
     * <p>收集全部 tool_use id（assistant.toolCalls）与 tool_result id（user/tool.toolCallId），
     * 未配对 = toolUseIds - toolResultIds；仅当 assistant 的全部 tool_use 均未配对才删该消息
     * （CC :3196-3198 every(id =&gt; unresolvedIds.has(id))）。
     */
    static List<ChatMessageDto> filterUnresolvedToolUses(List<ChatMessageDto> messages) {
        Set<String> toolUseIds = new HashSet<>();
        Set<String> toolResultIds = new HashSet<>();
        for (ChatMessageDto m : messages) {
            if (m == null) {
                continue;
            }
            if (m.role() == Role.assistant && m.toolCalls() != null) {
                for (ToolCallDto tc : m.toolCalls()) {
                    if (tc.id() != null) {
                        toolUseIds.add(tc.id());
                    }
                }
            } else if (m.toolCallId() != null) {
                // Java tool_result 消息（Role.tool / user 带 toolCallId）→ CC content block tool_use_id
                toolResultIds.add(m.toolCallId());
            }
        }
        Set<String> unresolvedIds = new HashSet<>(toolUseIds);
        unresolvedIds.removeAll(toolResultIds);
        if (unresolvedIds.isEmpty()) {
            return messages;
        }
        List<ChatMessageDto> out = new ArrayList<>(messages.size());
        for (ChatMessageDto m : messages) {
            if (m == null || m.role() != Role.assistant || m.toolCalls() == null || m.toolCalls().isEmpty()) {
                out.add(m);
                continue;
            }
            boolean allUnresolved = true;
            for (ToolCallDto tc : m.toolCalls()) {
                if (tc.id() == null || !unresolvedIds.contains(tc.id())) {
                    allUnresolved = false;
                    break;
                }
            }
            if (allUnresolved) {
                if (log.isDebugEnabled()) {
                    log.debug("[SessionResumeDeserializer] 剥离全部 tool_use 未配对 assistant: msg={} toolUseIds={}",
                        m.id(), m.toolCalls().stream().map(ToolCallDto::id).toList());
                }
            } else {
                out.add(m);
            }
        }
        return out;
    }

    // ─────────────────────────── 步骤 1.5: appendMissingToolResults ───────────────────────────

    /**
     * 对部分配对 assistant 的未配对 tool_use 补 synthetic error tool_result（fix-toolcalls-400 C）。
     *
     * <p>WHY: {@link #filterUnresolvedToolUses} 只删"全部未配对"的 assistant；部分配对 assistant
     * （N 个 call 只有 S&lt;N 个 result）保留的未配对 tool_use 在 resume 注入 OpenAI 时触发
     * 400 "insufficient tool messages following tool_calls message"。本步为每个未配对 id 追加
     * 一条 Role.tool error 消息（对齐 CC yieldMissingToolResultBlocks is_error:true +
     * sourceToolAssistantUUID 语义，query.ts:123-149；assistantMessageId = CC sourceToolAssistantUUID
     * 等价位）。未配对判定与 filterUnresolvedToolUses 同源：全局 toolResultIds（user/tool 消息
     * toolCallId）。
     *
     * @param messages 步骤 1 过滤后的消息列表（可能含部分配对 assistant）
     * @return 每个 tool_call 都有 tool 响应的合法注入历史（顺序 = 原序 + assistant 后紧跟其 synthetic）
     */
    static List<ChatMessageDto> appendMissingToolResults(List<ChatMessageDto> messages) {
        Set<String> toolResultIds = new HashSet<>();
        for (ChatMessageDto m : messages) {
            if (m != null && m.toolCallId() != null) {
                toolResultIds.add(m.toolCallId());
            }
        }
        List<ChatMessageDto> out = new ArrayList<>(messages.size());
        for (ChatMessageDto m : messages) {
            out.add(m);
            if (m != null && m.role() == Role.assistant && m.toolCalls() != null) {
                for (ToolCallDto tc : m.toolCalls()) {
                    if (tc.id() != null && !toolResultIds.contains(tc.id())) {
                        out.add(syntheticMissingToolResult(m, tc.id()));
                        if (log.isInfoEnabled()) {
                            log.info("[SessionResumeDeserializer] 为部分配对 assistant 补 synthetic error tool_result: assistant={} toolUseId={} · 对齐 CC yieldMissingToolResultBlocks",
                                m.id(), tc.id());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 构造未配对 tool_use 的 synthetic error tool_result（20 参兼容构造器 · Role.tool / isError=true）。 */
    private static ChatMessageDto syntheticMissingToolResult(ChatMessageDto assistant, String toolUseId) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), assistant.sessionId(), Role.tool, null,
            "Tool result missing", null, null, null, null, null, "刚刚", null,
            toolUseId, assistant.assistantMessageId(), null,
            List.of(), List.of(), null, false, true);
    }

    // ─────────────────────────── 步骤 2: filterOrphanedThinkingOnly ───────────────────────────

    /**
     * 剥离孤立 thinking-only assistant 消息 · 对齐 CC messages.ts:5452-5519。
     *
     * <p>DTO 判定：assistant 且 reasoning() 非空 && content() 空白 && toolCalls() 空 = thinking-only；
     * 若存在同 assistantMessageId 的其它 assistant 消息含非 thinking 内容（content 非空 /
     * toolCalls 非空）→ 不孤立即保留（后续可合并）；否则删（流式 block 分离残留，API 400）。
     */
    static List<ChatMessageDto> filterOrphanedThinkingOnly(List<ChatMessageDto> messages) {
        // 第一遍：收集含非 thinking 内容的 assistantMessageId（CC :5463-5476）
        Set<String> idsWithNonThinking = new HashSet<>();
        for (ChatMessageDto m : messages) {
            if (m == null || m.role() != Role.assistant) {
                continue;
            }
            if (hasNonThinkingContent(m) && m.assistantMessageId() != null) {
                idsWithNonThinking.add(m.assistantMessageId());
            }
        }
        // 第二遍：thinking-only 且无同 id 非 thinking → 删（CC :5478-5516）
        List<ChatMessageDto> out = new ArrayList<>(messages.size());
        for (ChatMessageDto m : messages) {
            if (m == null || m.role() != Role.assistant || !isThinkingOnly(m)) {
                out.add(m);
                continue;
            }
            if (m.assistantMessageId() != null && idsWithNonThinking.contains(m.assistantMessageId())) {
                out.add(m); // 有同 id 非 thinking 内容，后续合并（CC :5500-5505）
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("[SessionResumeDeserializer] 剥离孤立 thinking-only assistant: msg={} assistantMsgId={}",
                        m.id(), m.assistantMessageId());
                }
            }
        }
        return out;
    }

    /** DTO 级 hasNonThinkingContent：content 非空 || toolCalls 非空（CC messages.ts:5470-5472）。 */
    private static boolean hasNonThinkingContent(ChatMessageDto m) {
        return (m.content() != null && !m.content().isBlank())
            || (m.toolCalls() != null && !m.toolCalls().isEmpty());
    }

    /** DTO 级 thinking-only：reasoning 非空 && content 空白 && toolCalls 空（CC messages.ts:5489-5492）。 */
    private static boolean isThinkingOnly(ChatMessageDto m) {
        return (m.reasoning() != null && !m.reasoning().isBlank())
            && (m.content() == null || m.content().isBlank())
            && (m.toolCalls() == null || m.toolCalls().isEmpty());
    }

    // ─────────────────────────── 步骤 3: filterWhitespaceOnlyAssistant ───────────────────────────

    /**
     * 剥离纯空白 assistant 消息 · 对齐 CC messages.ts:5328-5379。
     *
     * <p>DTO 判定：assistant && content 空白 && reasoning 空白 && toolCalls 空 = 仅空白文本
     * （CC hasOnlyWhitespaceTextContent，:5294-5314）。Java 无 mergeUserMessages —— 删后相邻
     * user 不合并（Java-idiom 偏离：DB 权威不可丢行，登记 conversationRecovery 计划 §4）。
     */
    static List<ChatMessageDto> filterWhitespaceOnlyAssistant(List<ChatMessageDto> messages) {
        List<ChatMessageDto> out = new ArrayList<>(messages.size());
        for (ChatMessageDto m : messages) {
            if (m == null || m.role() != Role.assistant) {
                out.add(m);
                continue;
            }
            boolean contentBlank = m.content() == null || m.content().isBlank();
            boolean reasoningBlank = m.reasoning() == null || m.reasoning().isBlank();
            boolean noToolCalls = m.toolCalls() == null || m.toolCalls().isEmpty();
            if (contentBlank && reasoningBlank && noToolCalls) {
                if (log.isDebugEnabled()) {
                    log.debug("[SessionResumeDeserializer] 剥离纯空白 assistant: msg={}", m.id());
                }
            } else {
                out.add(m);
            }
        }
        return out;
    }

    // ─────────────────────────── 步骤 4: detectTurnInterruption ───────────────────────────

    /**
     * 中断检测 · 对齐 CC conversationRecovery.ts:275-339 {@code detectTurnInterruption}。
     *
     * <p>末条 turn-relevant 判别（跳过 system / api-error assistant）：assistant → none（流式
     * 持久化 stop_reason 恒 null，过滤后 assistant 末条视为完成）；user → isMeta/isCompactSummary
     * → none，tool_result（终端工具 → none，非终端 → interrupted_turn），纯文本 → interrupted_prompt；
     * 无相关消息 → none。
     */
    static InterruptionKind detectTurnInterruption(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return InterruptionKind.NONE;
        }
        // 找末条 turn-relevant：跳过 system / progress / api-error assistant（CC :287-292）
        int lastIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m == null) {
                continue;
            }
            if (m.role() == Role.system) {
                continue;
            }
            if (m.role() == Role.assistant && m.isApiErrorMessage()) {
                continue;
            }
            lastIdx = i;
            break;
        }
        if (lastIdx < 0) {
            return InterruptionKind.NONE;
        }
        ChatMessageDto last = messages.get(lastIdx);
        if (last.role() == Role.assistant) {
            // 过滤后 assistant 末条 → 完成（CC :300-307）
            return InterruptionKind.NONE;
        }
        if (last.role() == Role.user || last.role() == Role.tool) {
            if (last.isMeta() || last.isCompactSummary()) {
                // isMeta / compact summary 非真实 prompt（CC :310-312）
                return InterruptionKind.NONE;
            }
            if (isToolUseResultMessage(last)) {
                // Brief 模式（#20467）SendUserMessage 后无文本 → 终端工具结果 = 完成（CC :313-324）
                if (isTerminalToolResult(last, messages, lastIdx)) {
                    return InterruptionKind.NONE;
                }
                return InterruptionKind.INTERRUPTED_TURN;
            }
            // 纯文本 user prompt —— CC 未开始回应
            return InterruptionKind.INTERRUPTED_PROMPT;
        }
        // 其余 role（未知）→ 保守 none
        return InterruptionKind.NONE;
    }

    /** DTO 级 isToolUseResultMessage · CC messages.ts:867-877（Java tool_result = Role.tool）。 */
    private static boolean isToolUseResultMessage(ChatMessageDto m) {
        return m.role() == Role.tool
            || (m.role() == Role.user && m.toolCallId() != null);
    }

    /**
     * 是否终端工具结果 · 对齐 CC conversationRecovery.ts:354-387 {@code isTerminalToolResult}。
     *
     * <p>回走找末条 tool_result 对应的 assistant tool_use（toolCallId 匹配 toolCalls().id），
     * tool 名命中终端白名单（SendUserMessage / Brief / SendUserFile）→ true（Brief 模式完成）。
     */
    private static boolean isTerminalToolResult(ChatMessageDto result, List<ChatMessageDto> messages, int resultIdx) {
        String toolUseId = result.toolCallId();
        if (toolUseId == null) {
            return false;
        }
        for (int i = resultIdx - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m == null || m.role() != Role.assistant || m.toolCalls() == null) {
                continue;
            }
            for (ToolCallDto tc : m.toolCalls()) {
                if (toolUseId.equals(tc.id()) && isTerminalToolName(tc.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 终端工具名白名单 · CC BRIEF_TOOL_NAME / LEGACY_BRIEF_TOOL_NAME / SEND_USER_FILE_TOOL_NAME。 */
    private static boolean isTerminalToolName(String name) {
        return ToolNameConstants.BRIEF_TOOL_NAME.equals(name)          // SendUserMessage
            || "Brief".equals(name)                                    // LEGACY_BRIEF_TOOL_NAME
            || ToolNameConstants.SEND_USER_FILE_TOOL_NAME.equals(name); // SendUserFile
    }

    // ─────────────────────────── 步骤 5/6: sentinel 注入 ───────────────────────────

    /**
     * 末条相关是 user → 在末条 user 后 splice 插入 assistant sentinel「No response requested.」
     * · 对齐 CC conversationRecovery.ts:234-248。
     *
     * <p>WHY：跳过末尾 system/progress 找末条 turn-relevant；若为 user（或 Java Role.tool 即
     * tool_result，CC user message），插入 assistant sentinel 使 API 消息交替合法（末条为 user
     * 且无 assistant 响应时，removeInterruptedMessage 的 splice(idx,2) 配对语义依赖此）。
     */
    static void spliceNoResponseRequested(List<ChatMessageDto> messages) {
        int lastIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m == null || m.role() == Role.system) {
                continue;
            }
            lastIdx = i;
            break;
        }
        if (lastIdx < 0) {
            return;
        }
        ChatMessageDto last = messages.get(lastIdx);
        if (last.role() == Role.user || last.role() == Role.tool) {
            // [Fix-P1 MODERATE] isMeta / compact summary 末条不注入 sentinel · 对齐
            //   detectTurnInterruption isMeta→NONE 语义（conversationRecovery.ts:310-312）。
            //   WHY：prompt 型技能内容 isMeta 落库（ChatService slash isMeta 通道）后，resume
            //   排除当前 user 气泡 → 历史末条 = user(isMeta 技能内容) → 旧实现注入幽灵 assistant
            //   'No response requested.' 混入模型上下文（报告声称序列不实）。跳过注入 →
            //   模型上下文 = [历史..., user(isMeta), user(当前 prompt)]，与 CC
            //   [metadata, user(isMeta)] 无幽灵 sentinel 等价（连续 user 消息 API 允许，CC 同构）。
            if (last.isMeta() || last.isCompactSummary()) {
                if (log.isDebugEnabled()) {
                    log.debug("[SessionResumeDeserializer] 末条相关为 isMeta/compact summary user，跳过 sentinel 注入: role={} isMeta={} isCompact={}",
                        last.role(), last.isMeta(), last.isCompactSummary());
                }
                return;
            }
            messages.add(lastIdx + 1, metaUser(last.sessionId(), NO_RESPONSE_REQUESTED, false));
            if (log.isInfoEnabled()) {
                log.info("[SessionResumeDeserializer] 末条相关为 user，splice 注入 assistant sentinel「{}」（对齐 CC messages.ts:241）",
                    NO_RESPONSE_REQUESTED);
            }
        }
    }

    /**
     * 构造 synthetic 消息（meta user / sentinel assistant）· 复用 20 参兼容构造器
     * （R32-b14 形状：acceptFeedback/contentBlocks/imagePasteIds/structuredOutput 空 + isMeta/isError）。
     *
     * @param sessionId 会话 ID（沿用源消息；null 允许 —— CC createUserMessage 无 sessionId）
     * @param content   消息内容
     * @param isMeta    元消息标志（Continue = true；sentinel = false）
     */
    private static ChatMessageDto metaUser(String sessionId, String content, boolean isMeta) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(),
            sessionId,
            isMeta ? Role.user : Role.assistant,
            null,
            content,
            null,
            null,
            null,
            null,
            null,
            "刚刚",
            null,
            null,
            UUID.randomUUID().toString(),
            null,
            java.util.List.of(),
            java.util.List.of(),
            null,
            isMeta,
            false);
    }

    /** 末条非 null sessionId（synthetic 消息沿用；全 null → null）。 */
    private static String lastNonNullSessionId(List<ChatMessageDto> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.sessionId() != null) {
                return m.sessionId();
            }
        }
        return null;
    }
}
