package com.nexusai.infra.llm;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [P1] 发送边界 tool_use / tool_result 配对修复 · CC original: {@code ensureToolResultPairing}
 * （{@code claude-code-best/src/utils/messages.ts:5594-5951}，唯一调用点 {@code services/api/claude.ts:1324}）。
 *
 * <p><b>WHY（2026-09-11 实证根因）</b>：fork 链路（SM 提取 / extract_memories / auto_dream / partial compact）
 * 反复 400 —— {@code An assistant message with 'tool_calls' must be followed by tool messages
 * responding to each 'tool_call_id'}。断头有两类：
 * <ol>
 *   <li>上下文尾部是「刚采样的 assistant(tool_calls)」，其 tool 结果尚未产生（工具在
 *       {@code handleToolCallsTurn} 才跑）→ 直发必 400；</li>
 *   <li>fork 自执行工具的结果消息 {@code toolCallId} 恒 null → provider 序列化侧丢弃
 *       （{@code OpenAiSdkProvider.toSdkMessage} 的 tool 分支 warn 后 yield null）→ 下一轮失配。</li>
 * </ol>
 * CC 侧对此的裁决（{@code utils/forkedAgent.ts:538-541}）：<b>不要</b>在 fork 前置过滤悬挂 tool_use，
 * 而是在「发往 API 前」由 {@code ensureToolResultPairing} 统一修复，且<b>主线程与 fork 共用同一处</b>
 * （"same as the main thread"）。本类即该实现。
 *
 * <p><b>落点论证（为什么在 {@code infra.llm}）</b>：CC 把函数放在共享消息工具层
 * （{@code utils/messages.ts}）但只在 API 发送边界调用。Java 侧「共享发送边界」= 两个 provider 的
 * DTO→SDK 消息转换点（{@code OpenAiSdkProvider.buildSdkMessages}/{@code buildRequestParams} ·
 * {@code AnthropicSdkProvider.buildSdkMessages}）。放在 {@code infra.llm} 而非 application 层：
 * <ul>
 *   <li>被测对象是「provider 出站 DTO 列表」，属传输层关注点，与上下文装配/压缩策略（application）解耦；</li>
 *   <li>两个 provider 同包直接调用，避免 provider 之间互相依赖，并使「两通道共用一个实现」成为结构事实；</li>
 *   <li>主线程 / fork / 子 agent / compact 侧链 / cron 全部经 {@code provider.stream|chat} 收敛到上述转换点
 *       → 一处接线即全员受益（对齐 CC "same as the main thread"）。</li>
 * </ul>
 *
 * <p><b>与 CC 的表示差异映射</b>：CC 的 tool_result 嵌在 user message 的 content 块数组里，Java 侧
 * tool_result 是独立的 {@code Role.tool} 消息（{@code ChatMessageDto.toolCallId} 承载
 * {@code tool_use_id}）。因此：
 * <ul>
 *   <li>CC「tool_use 无结果 → 在下一条 user 消息前<b>前置</b>合成 tool_result 块」（:5795-5836）
 *       ⇒ Java「为缺失 id 生成合成 {@code Role.tool} 消息，置于该 assistant 的结果区段之前」；</li>
 *   <li>CC「剥离孤儿 tool_result 块」（:5817-5834 / :5622-5661）⇒ Java「丢弃孤儿 {@code Role.tool} 消息」；</li>
 *   <li>CC 的 {@code Role.system} 消息在 {@code normalizeMessagesForAPI} 已被过滤（pairing 只见 user/assistant）
 *       ⇒ Java 两个 provider 在更晚的序列化点才过滤 system，故本实现对 {@code Role.system}
 *       <b>透明</b>（原样透传、不参与邻接判定），语义等价（{@code AnthropicSdkProvider.buildSdkMessages}
 *       system 分支 continue / {@code OpenAiSdkProvider.toSdkMessage} case system → null）。</li>
 * </ul>
 *
 * <p><b>幂等</b>：已完整配对的消息<b>对象同一</b>（不重建、不改字段），且无任何修复时直接返回入参
 * list 本体 —— 出站字节不变，不破坏前缀缓存（CC 同款：未改动分支 push 原 msg 引用）。
 *
 * <p><b>绝不删上下文</b>：正向用「补合成结果」而非过滤整条 assistant。注意本类与
 * {@code LlmAgentLoop.filterIncompleteAssistantToolCalls}（<b>整条删除</b>含未完成 tool_calls 的
 * assistant，仅主循环 resume 分支）是两套语义，后者在 fork 上会删掉真实上下文 —— 不共用、不搬运。
 */
public final class ToolResultPairingRepair {

    /**
     * 合成 tool_result 的占位文本 · CC original: {@code SYNTHETIC_TOOL_RESULT_PLACEHOLDER}
     * （messages.ts:247-248，导出供 HFI 拒绝含占位符的训练数据载荷）。
     */
    public static final String SYNTHETIC_TOOL_RESULT_PLACEHOLDER =
        "[Tool result missing due to internal error]";

    /** assistant 内容被剥空后的占位文本 · CC original: {@code '[Tool use interrupted]'}（messages.ts:5725-5731）。 */
    public static final String TOOL_USE_INTERRUPTED_PLACEHOLDER = "[Tool use interrupted]";

    /** 首条消息即孤立 tool 结果时的兜底 user 文本 · CC original（messages.ts:5649）。 */
    public static final String ORPHANED_TOOL_RESULT_REMOVED_PLACEHOLDER =
        "[Orphaned tool result removed due to conversation resume]";

    private static final Logger log = LoggerFactory.getLogger(ToolResultPairingRepair.class);

    private ToolResultPairingRepair() {
    }

    /**
     * 修复 tool_use / tool_result 配对（双向）· 发送边界调用。
     *
     * <p>逐条语义见类注释；边界行为与 CC 对位：
     * <ol>
     *   <li><b>tool_use 无结果</b> → 补合成 tool 结果（{@link #SYNTHETIC_TOOL_RESULT_PLACEHOLDER}，
     *       {@code isError=true}，{@code isMeta=true}），置于该 assistant 结果区段之前；</li>
     *   <li><b>tool 结果无前置 tool_use</b> → 丢弃该 tool 消息（CC 剥块等价）；若它是首条对 API 可见
     *       消息，则替换为 user 占位文本以保住「载荷以 user 开头」（CC :5642-5657）；</li>
     *   <li><b>tool 结果无 id / id 对不上</b> → <b>丢弃</b>（不认领）：CC 的 tool_result 恒带
     *       {@code tool_use_id}（{@code ToolResultBlockParam}），不对应的结果统一走 orphanedIds 剥离
     *       （CC :5817-5834）；其对应的悬挂 tool_use 则收到合成占位（前述第 1 条）；</li>
     *   <li><b>一次多个悬挂</b> → 逐个补齐（<b>顺序 = assistant tool_calls 顺序</b>）；</li>
     *   <li><b>连续多条 assistant</b> → 前一条的结果区段为空即补齐，不合并、不删消息；tool_use id 重复
     *       （跨消息/消息内）→ 保留首次出现，后续剥离（CC :5698-5707，API "tool_use ids must be unique"）；</li>
     *   <li><b>空内容 / 已配对</b> → 已配对原样返回（含重复 tool_result 只留首条）；assistant 的 toolCalls
     *       被剥空且 content 为空白 → 补 {@link #TOOL_USE_INTERRUPTED_PLACEHOLDER}（CC :5725-5731）。</li>
     * </ol>
     *
     * @param messages 出站消息列表（provider 入参，未被本方法修改）
     * @return 修复后的新列表；无需修复时返回入参本体（同一引用 · 出站字节不变）
     */
    public static List<ChatMessageDto> ensureToolResultPairing(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        List<ChatMessageDto> result = new ArrayList<>(messages.size());
        // 跨消息 tool_use id 追踪（CC :5608 allSeenToolUseIds）——重复 id 触发 API "tool_use ids must be unique"
        Set<String> allSeenToolUseIds = new HashSet<>();
        // result 中最后一条「对 API 可见」消息的角色（system 透明 ⇒ 不参与邻接判定）
        Role lastVisibleRole = null;
        boolean repaired = false;

        int i = 0;
        while (i < messages.size()) {
            ChatMessageDto msg = messages.get(i);

            // system / null 对 provider 不可见（两通道序列化侧均跳过）→ 原样透传，不参与邻接判定
            if (msg == null || msg.role() == null || msg.role() == Role.system) {
                result.add(msg);
                i++;
                continue;
            }

            // ── 非 assistant：user / tool ──────────────────────────────────────────────
            if (msg.role() != Role.assistant) {
                // 反向方向（孤立 tool 结果）：能走到这里 = 其前置可见消息不是 assistant
                // （相邻者已被 assistant 分支的结果区段消费，见下）⇒ 必为孤儿 → 丢弃。
                // CC original: messages.ts:5817-5834（剥孤儿 tool_result 块）/ :5622-5661（首条孤立块整条剥离）
                if (msg.role() == Role.tool) {
                    repaired = true;
                    if (lastVisibleRole == null) {
                        // 载荷必须仍以 user 开头（Anthropic "first message must use the user role"）
                        result.add(syntheticUserPlaceholder(msg, ORPHANED_TOOL_RESULT_REMOVED_PLACEHOLDER));
                        lastVisibleRole = Role.user;
                    } else {
                        log.warn("[ToolResultPairingRepair] 丢弃孤立 tool 结果（无前置 assistant tool_use）"
                            + " toolCallId={} 内容={}", msg.toolCallId(), truncate(msg.content()));
                    }
                    i++;
                    continue;
                }
                result.add(msg);
                lastVisibleRole = msg.role();
                i++;
                continue;
            }

            // ── assistant ─────────────────────────────────────────────────────────────
            // 1) tool_use 去重 + 无效 tool_call 剥离（CC :5694-5717 的 tool_use 去重对位）
            //    有效性判据与 OpenAiSdkProvider.toSdkMessage 的 tool_call 序列化判据一致
            //    （id/name 任一空即无法被任何 tool 结果应答 → 必须剥离，否则该 assistant 的
            //     tool_call 与后续 tool 消息永不配对）。
            List<ToolCallDto> originalCalls = msg.toolCalls();
            List<ToolCallDto> keptCalls = new ArrayList<>();
            boolean callsChanged = false;
            if (originalCalls != null) {
                for (ToolCallDto tc : originalCalls) {
                    if (tc == null || tc.id() == null || tc.id().isBlank()
                        || tc.name() == null || tc.name().isBlank()) {
                        callsChanged = true;
                        continue;
                    }
                    if (!allSeenToolUseIds.add(tc.id())) {
                        callsChanged = true; // 重复 tool_use id → 保留首次（CC :5701-5706）
                        continue;
                    }
                    keptCalls.add(tc);
                }
            }
            String content = msg.content();
            boolean contentChanged = false;
            if (callsChanged && keptCalls.isEmpty() && originalCalls != null && !originalCalls.isEmpty()
                && (content == null || content.isBlank())) {
                // tool_calls 被剥空 + content 空白 → 占位，避免空 assistant 内容被 API 拒（CC :5725-5731）
                content = TOOL_USE_INTERRUPTED_PLACEHOLDER;
                contentChanged = true;
            }
            ChatMessageDto assistantMsg = (callsChanged || contentChanged)
                ? copyAssistant(msg, content, keptCalls)
                : msg; // 未改动 → 引用同一（出站字节不变）
            if (callsChanged || contentChanged) {
                repaired = true;
                log.warn("[ToolResultPairingRepair] assistant tool_calls 修复: 原 {} → 保留 {}（去重/无效剥离）id={}",
                    originalCalls == null ? 0 : originalCalls.size(), keptCalls.size(), msg.id());
            }
            result.add(assistantMsg);
            lastVisibleRole = Role.assistant;

            // 2) 前瞻该 assistant 的结果区段（紧随的 tool 消息；system 透明跳过）
            int j = i + 1;
            List<ChatMessageDto> region = new ArrayList<>();
            Set<String> presentIdSet = new LinkedHashSet<>();
            int nullIdCount = 0;
            boolean duplicateResults = false;
            while (j < messages.size()) {
                ChatMessageDto t = messages.get(j);
                if (t == null || t.role() == null) {
                    break;
                }
                if (t.role() == Role.system) {
                    region.add(t);
                    j++;
                    continue;
                }
                if (t.role() != Role.tool) {
                    break;
                }
                region.add(t);
                String id = t.toolCallId();
                if (id == null || id.isBlank()) {
                    nullIdCount++;
                } else if (!presentIdSet.add(id)) {
                    duplicateResults = true; // 同一 tool_call_id 出现两次（CC :5754-5773）
                }
                j++;
            }

            List<String> toolUseIds = new ArrayList<>(keptCalls.size());
            for (ToolCallDto tc : keptCalls) {
                toolUseIds.add(tc.id());
            }
            Set<String> toolUseIdSet = new LinkedHashSet<>(toolUseIds);
            List<String> missingIds = new ArrayList<>();
            for (String id : toolUseIds) {
                if (!presentIdSet.contains(id)) {
                    missingIds.add(id);
                }
            }
            List<String> orphanedIds = new ArrayList<>();
            for (String id : presentIdSet) {
                if (!toolUseIdSet.contains(id)) {
                    orphanedIds.add(id);
                }
            }

            if (missingIds.isEmpty() && orphanedIds.isEmpty() && !duplicateResults && nullIdCount == 0) {
                // 已配对：区段原样（对象同一 · 位置不变 · 出站字节不变）
                result.addAll(region);
                i = j;
                continue;
            }

            repaired = true;

            log.warn("[ToolResultPairingRepair] 配对修复: 悬挂 tool_use={} 孤儿 tool_result={} 重复结果={} "
                    + "无 id 结果={} · session={} assistantMsgId={}",
                missingIds, orphanedIds, duplicateResults, nullIdCount,
                assistantMsg.sessionId(), assistantMsg.id());

            // 3) 合成结果前置（CC :5836 `[...syntheticBlocks, ...content]`）
            for (String id : missingIds) {
                result.add(syntheticToolResult(assistantMsg, id));
            }
            // 4) 区段按原序输出：system 透传 / 真实结果保留（丢弃孤儿 + 重复 + 无 id 结果，
            //    后者 provider 序列化侧本就丢弃：OpenAiSdkProvider toolCallId==null → yield null）
            Set<String> emitted = new HashSet<>();
            for (ChatMessageDto t : region) {
                if (t.role() != Role.tool) {
                    result.add(t);
                    continue;
                }
                String id = t.toolCallId();
                if (id == null || id.isBlank()) {
                    // 丢弃而非认领（对齐 CC）：CC 的 tool_result 恒带 tool_use_id（ToolResultBlockParam），
                    // 无「无 id 结果」输入形态；不对应的结果一律走 orphanedIds 剥离。
                    // CC original: messages.ts:5817-5834 `if (orphanedSet.has(trId)) return false`。
                    // 保留 warn 以便发现残留坏数据（写入侧根因已修，正常新数据走不到此处）。
                    log.warn("[ToolResultPairingRepair] 丢弃无 id tool 结果（CC 无认领语义 · 无法配对）"
                        + " content={}", truncate(t.content()));
                    continue;
                }
                if (!toolUseIdSet.contains(id)) {
                    continue;
                }
                if (!emitted.add(id)) {
                    continue;
                }
                result.add(t);
            }
            i = j;
        }

        if (!repaired) {
            // 零改动 → 返回入参本体：调用侧可据引用同一性跳过后续处理，出站字节 100% 不变
            return messages;
        }
        if (log.isDebugEnabled()) {
            log.debug("[ToolResultPairingRepair] 修复完成: {} → {} 条", messages.size(), result.size());
        }
        return result;
    }

    /** 合成 tool 结果（CC {@code syntheticBlocks} · messages.ts:5796-5801：is_error=true + 占位文本 + isMeta）。 */
    private static ChatMessageDto syntheticToolResult(ChatMessageDto owner, String toolUseId) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(),
            owner.sessionId(),
            Role.tool,
            "tool",
            SYNTHETIC_TOOL_RESULT_PLACEHOLDER,
            null, null, null, null, null, null,
            OffsetDateTime.now(),
            toolUseId,
            owner.assistantMessageId() != null ? owner.assistantMessageId() : owner.id(),
            null, null, null, null,
            true,   // isMeta · CC createUserMessage({content: syntheticBlocks, isMeta: true})
            true    // isError · CC is_error: true（工具失败语义，非成功）
        );
    }

    /** 首条孤立 tool 结果的 user 占位（CC {@code createUserMessage({content: NO_CONTENT_MESSAGE...})} 的文本变体）。 */
    private static ChatMessageDto syntheticUserPlaceholder(ChatMessageDto owner, String text) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(),
            owner.sessionId(),
            Role.user,
            "user",
            text,
            null, null, null, null, null, null,
            OffsetDateTime.now(),
            null,
            owner.assistantMessageId(),
            null, null, null, null,
            true,   // isMeta
            false
        );
    }

    /**
     * assistant 全字段透传 + content/toolCalls 覆盖（record 无 withToolCalls，只能走 canonical 全参重建；
     * 字段顺序与 {@code ChatMessageDto.withContent} 完全一致 —— 少一个字段就会静默丢数据）。
     */
    private static ChatMessageDto copyAssistant(ChatMessageDto m, String content, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(
            m.id(), m.sessionId(), m.role(), m.author(), content, m.reasoning(), toolCalls, m.finishReason(),
            m.inputTokens(), m.outputTokens(), m.time(), m.createdAt(), m.toolCallId(), m.assistantMessageId(),
            m.acceptFeedback(), m.contentBlocks(), m.imagePasteIds(), m.structuredOutput(), m.isMeta(), m.isError(),
            m.sourceToolUseID(), m.subtype(),
            m.isApiErrorMessage(), m.apiError(), m.error(), m.errorDetails(),
            m.inputCacheReadTokens(), m.inputCacheCreationTokens(),
            m.compactMetadata(), m.microcompactMetadata(), m.logicalParentUuid(),
            m.isCompactSummary(), m.isVisibleInTranscriptOnly(), m.usage(), m.level(), m.matchedRule(), m.snipMetadata(),
            m.cwd(), m.reasoningDurationMs(), m.userMessageId(), m.decodeMs(), m.contextTokensUsed(), m.percentLeft(),
            m.contextWindow(), m.userAttachments(), m.queuedOrigin());
    }

    private static String truncate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
