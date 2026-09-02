package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.compact.SnipCompactor;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
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

/**
 * SnipTool · 对齐 CC 真源 {@code Open-ClaudeCode/src/tools/SnipTool/SnipTool.ts}。
 *
 * <p><b>CC 语义（SnipTool.ts:81-91）</b>：模型输入 {@code message_ids}（要裁剪的历史消息 ID 数组）
 * + {@code reason}（可选，裁剪原因）。执行后把这些消息从会话历史<b>替换为简短摘要</b>，
 * 释放上下文窗口空间（CC description: "Snip messages from conversation history to free up context"，
 * SnipTool.ts:38）。输出契约 {@code {snipped_count, summary}}（SnipTool.ts:25）。
 *
 * <p><b>门控</b>（tools.ts:123-124/243）：{@code SnipTool = feature('HISTORY_SNIP') ?
 * require(...).SnipTool : null}，flag 关时工具为 {@code null}、不进入 getAllBaseTools 数组。
 * Java 端 {@link #isEnabled()} = {@code featureFlags.historySnip()}（{@code nexusai.feature.history-snip}
 * 配置，FeatureFlags.java:213-214，默认 false）。门控保留默认关，但真逻辑已实现。
 *
 * <p><b>真行为（2026-08-23 · 用户拍板「实现真行为，cc 默认关我们配置文件门控」）</b>：
 * CC 的 SnipTool.call 仅返回 {@code {snipped_count, summary}}（SnipTool.ts:85-90），真正的
 * 消息剔除由「query engine 的 projection system」承接（SnipTool.ts:82-84 注释）。CC 真源机制
 * = <b>snip_boundary + removedUuids</b>（services/compact/snipCompact.ts:83-147 +
 * snipProjection.ts:35-60）：工具注入一条 {@code subtype='snip_boundary'} 的 system 消息，
 * 携带 {@code snipMetadata.removedUuids} 记录被裁剪的消息 UUID；随后 {@code snipCompactIfNeeded}
 * （snipCompact.ts:83-147）在 query 前把 removedUuids 消息剔除，释放上下文。
 *
 * <p>Java 端对应链路（已接线）：
 * <ol>
 *   <li><b>本工具 execute()</b>：读 {@code ctx.messages()}（per-turn TUC 承载的
 *       {@code state.messages()} 快照，AgentLoopContext.toolExecContext），把
 *       {@code message_ids} 中<b>实际存在</b>的消息 id 收进 {@code removedUuids}，构造
 *       snip_boundary system 消息（content = 摘要），经 {@link ToolResult#successWithNewMessages}
 *       {@code newMessages} 通道注入会话历史（ToolResultApplier.apply → state.messages().addAll，
 *       ToolResultApplier.java:69-71）。</li>
 *   <li><b>LlmAgentLoop snip 步骤</b>（LlmAgentLoop.java:3761-3787，对齐 CC query.ts:401-410）：
 *       下轮 do-while 迭代开头，{@code new SnipCompactor().snipCompactIfNeeded(state.messages())}
 *       反向扫到 boundary，按 {@code removedUuids} 剔除对应消息 → {@code state.replaceMessages(...)}
 *       把 removedUuids 消息从 {@code AgentState} 历史中物理移除，boundary（含摘要）保留，
 *       释放 token。模型面消息链 = 剔除后的消息 + 摘要边界（CC snipCompact.ts:118-139 等价）。</li>
 * </ol>
 *
 * <p><b>数据流</b>：message_ids+reason（LLM）→ execute 匹配会话历史 → removedUuids+summary
 * （snip_boundary 注入）→ 下轮 snip 投影移除被裁剪消息（释放上下文）→ 模型看到
 * {@code {snipped_count, summary}}。
 *
 * <p><b>输出契约对齐</b>：{@code data} = JSON {@code {"snipped_count": N, "summary": S}}
 * （CC SnipOutput，SnipTool.ts:25）；模型侧 tool_result content 经 {@link #mapToToolResultBlockParam}
 * 渲染为 {@code "Snipped N messages. Summary: S"}（CC SnipTool.ts:77）。
 *
 * <p><b>已知偏离（真行为强化）</b>：CC stub 的 {@code snipped_count = input.message_ids.length}
 * （SnipTool.ts:87，因 CC 从不真正剔除）；本真实现 {@code snipped_count} = 实际匹配到并标记
 * 移除的消息数（{@code message_ids} 中存在、且出现在会话历史中的 id 数量），语义更真实。
 */
public class SnipTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SnipTool.class);

    /** CC 工具名 · {@code SnipTool/prompt.ts:1} SNIP_TOOL_NAME='Snip'。 */
    public static final String NAME = ToolNameConstants.SNIP_TOOL_NAME;

    /** CC original: searchHint (SnipTool.ts:29) 'snip trim history remove old messages compact context'。 */
    private static final String SEARCH_HINT =
        "snip trim history remove old messages compact context";

    /** CC original: maxResultSizeChars (SnipTool.ts:30) = 5_000。 */
    private static final long MAX_RESULT_SIZE_CHARS = 5_000;

    /** snipMetadata 内 removedUuids 键名 · 对齐 CC snipCompact.ts:99-103 + SnipCompactor.KEY_REMOVED_UUIDS。 */
    private static final String KEY_REMOVED_UUIDS = "removedUuids";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FeatureFlags featureFlags;

    /** [V52 X1-3] 压缩配置 DB 实时读源（可 null = 未接线 → 回落 FeatureFlags）。 */
    private com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver;

    public SnipTool() {
        this(FeatureFlags.ALL_DISABLED);
    }

    public SnipTool(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
    }

    /**
     * [V52 X1-3] 压缩配置 DB 实时读源注入（可 null）：DB settings.history_snip_enabled 覆盖
     * FeatureFlags，null 回落。
     *
     * @param settingsResolver 压缩配置实时读源（可 null）
     */
    public void setSettingsResolver(com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        this.settingsResolver = settingsResolver;
    }

    @Override
    public String name() {
        if (log.isDebugEnabled()) {
            log.debug("SnipTool.name(): 返回 CC 工具名 SNIP_TOOL_NAME='Snip'（对齐 tools.ts:123-124）");
        }
        return NAME;
    }

    @Override
    public String description() {
        // CC original: SnipTool.ts:38 description() = 'Snip messages from conversation history to free up context'
        return "Snip messages from conversation history to free up context";
    }

    /**
     * 工具提示词 · CC original: prompt() (SnipTool.ts:41-51)。
     *
     * <p>CC 真源全文：模型上下文快满 / 早期消息含不再需要完整保留的大工具输出 / 想压缩
     * 长探索序列为摘要时使用；只裁剪确信不再需要逐字保留的消息；摘要替换保留关键事实
     * （文件路径、决策、发现的错误）；不可撤销——原文从上下文消失。
     */
    @Override
    public String prompt() {
        return "Snip messages from your conversation history to free up context window space. "
            + "Snipped messages are replaced with a compact summary so you retain awareness "
            + "of what happened without the full content.\n"
            + "\n"
            + "Use this when:\n"
            + "- Your context is getting full and you need to make room\n"
            + "- Earlier messages contain large tool outputs you no longer need in full\n"
            + "- You want to compact a long exploration sequence into a summary\n"
            + "\n"
            + "Guidelines:\n"
            + "- message_ids must contain the [id:xxx] short IDs shown at the end of each user message "
            + "(NOT message content — content text never matches)\n"
            + "- Snip deletes by user-message boundary: snipping a user message also removes its following "
            + "assistant reply and tool results together (up to the next user message), so tool call/result "
            + "pairs stay intact\n"
            + "- Only snip messages you're confident you won't need verbatim again\n"
            + "- The summary replacement preserves key facts (file paths, decisions, errors found)\n"
            + "- You cannot un-snip — the original content is gone from context";
    }

    @Override
    public String searchHint() {
        return SEARCH_HINT;
    }

    /**
     * 输入 schema · CC original: inputSchema (SnipTool.ts:7-21) z.strictObject({message_ids, reason?})。
     *
     * <p>CC: {@code message_ids} 必填字符串数组（描述「IDs of the messages to snip from history.
     * Snipped messages are replaced with a short summary.」）；{@code reason} 可选字符串
     * （描述「Why these messages are being snipped. Used in the summary replacement.」）。
     * {@code strict: true}（SnipTool.ts:31）+ {@code z.strictObject} → {@code additionalProperties:false}。
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode props = schema.putObject("properties");

        ObjectNode messageIds = props.putObject("message_ids");
        messageIds.put("type", "array");
        ArrayNode items = messageIds.putArray("items");
        items.addObject().put("type", "string");
        messageIds.put("description",
            "IDs of the messages to snip from history. Use the [id:xxx] short IDs shown at the end of each "
                + "user message (NOT message content — content text never matches). Snipped messages are "
                + "replaced with a short summary. Deletes by user-message boundary: snipping a user message "
                + "also removes its following assistant reply and tool results up to the next user message.");

        ObjectNode reason = props.putObject("reason");
        reason.put("type", "string");
        reason.put("description",
            "Why these messages are being snipped. Used in the summary replacement.");

        schema.set("required", JsonNodeFactory.instance.arrayNode().add("message_ids"));
        return schema;
    }

    @Override
    public boolean isEnabled() {
        // [V52 X1-3] DB settings.history_snip_enabled 有值覆盖 FeatureFlags（null 回落，零行为变化）
        Boolean dbSnip = settingsResolver != null ? settingsResolver.historySnipEnabled() : null;
        boolean enabled = dbSnip != null ? dbSnip : this.featureFlags.historySnip();
        if (log.isDebugEnabled()) {
            log.debug("SnipTool.isEnabled() = {}（HISTORY_SNIP 门控 · nexusai.feature.history-snip"
                    + " · CC tools.ts:123；DB history_snip_enabled={}）",
                enabled, dbSnip != null ? dbSnip : "null→FeatureFlags");
        }
        return enabled;
    }

    /** CC original: isConcurrencySafe() → false (SnipTool.ts:54-56)。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /** CC original: isReadOnly() → false (SnipTool.ts:57-59) —— 修改会话历史。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    /** CC original: strict → true (SnipTool.ts:31)。 */
    @Override
    public boolean strict() {
        return true;
    }

    /** CC original: userFacingName() → 'Snip' (SnipTool.ts:61-63)。 */
    @Override
    public String userFacingName() {
        return "Snip";
    }

    /** CC original: maxResultSizeChars → 5_000 (SnipTool.ts:30)。 */
    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();

        // ── 解析 message_ids（CC SnipTool.ts:7-21，必填）──
        List<String> requestedIds = parseMessageIds(input);
        if (requestedIds.isEmpty()) {
            log.warn("[SnipTool] execute 被调用但 message_ids 为空: id={}（CC SnipTool.ts:7-21 必填数组）", call.id());
            return ToolResult.error(call.id(),
                "Snip 工具需要非空 message_ids（要裁剪的历史消息 ID 数组）。");
        }
        String reason = input.hasNonNull("reason") ? input.get("reason").asText() : null;

        // ── 访问会话历史（ctx.messages() = per-turn TUC 承载的 state.messages() 快照）──
        if (ctx == null) {
            log.warn("[SnipTool] execute 无 ToolUseContext，无法访问会话历史（fail loud）: id={}", call.id());
            return ToolResult.error(call.id(),
                "Snip 工具无法访问会话历史：ToolUseContext 缺失（fail loud）。");
        }

        // ── [snip-ccb-align] 匹配 message_ids → 定位目标 user 消息（对齐 CCB [id:xxx] 短 id tag）──
        //   模型传的 message_ids 是 user 消息末尾 [id:<6位短id>] tag（AgentLoopContext.maybeAppendSnipIdTags
        //   注入，对齐 CCB messages.ts:2667-2686）；也兼容完整 id。匹配后按"区间删除"
        //   （user → 下一 user 前，含中间 assistant+tool 配对整段删）计算 removedUuids ——
        //   tool_use/tool_result 配对完整保留或整段剔除，杜绝 API 序列断裂（用户联调关切）。
        List<Integer> targetUserIndices = resolveTargetUserIndices(ctx.messages(), requestedIds);
        if (targetUserIndices.isEmpty()) {
            log.warn("[SnipTool] message_ids 未匹配到任何 user 消息: requested={}（需 [id:xxx] 短 id，见 user 消息末尾 · CCB messages.ts:2667-2686）",
                requestedIds.size());
            return ToolResult.error(call.id(),
                "Snip message_ids 未匹配到会话中的任何 user 消息。message_ids 应为 user 消息末尾的 [id:xxx] 短 id（每个 user 消息带 [id:xxx] tag）。");
        }

        // ── 区间删除 → removedUuids（对齐 CCB /force-snip removedUuids 语义，但只删模型指定区间）──
        List<String> removedUuids = computeRemovedUuids(ctx.messages(), targetUserIndices);
        int snippedCount = removedUuids.size();

        // ── 摘要（CC SnipTool.ts:88: input.reason ?? `Snipped ${N} messages`）──
        String summary = (reason != null && !reason.isBlank())
            ? reason
            : "Snipped " + snippedCount + " messages";

        // ── 构造 snip_boundary system 消息（CC snipCompact.ts:99-106: subtype='snip_boundary' +
        //    snipMetadata.removedUuids；content = 摘要）──
        ChatMessageDto boundary = buildSnipBoundaryMessage(ctx, removedUuids, summary);

        // ── 输出契约 {snipped_count, summary}（CC SnipOutput，SnipTool.ts:25）──
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("snipped_count", snippedCount);
        data.put("summary", summary);
        String dataJson = data.toString();

        if (log.isDebugEnabled()) {
            log.debug("[SnipTool] execute 完成: id={} requested={} matchedUsers={} snippedCount={} summary={} · CCB SnipTool.ts:81-91 + 区间删除",
                call.id(), requestedIds.size(), targetUserIndices.size(), snippedCount, summary);
        }
        log.info("[SnipTool] 裁剪 {} 条历史消息（requested={}, 目标 user={}）: boundary={} · CCB SnipTool.ts:81-91",
            snippedCount, requestedIds.size(), targetUserIndices.size(), boundary.id());

        // boundary 经 newMessages 通道注入 state.messages()（ToolResultApplier.apply，
        // ToolResultApplier.java:69-71）；下轮 LlmAgentLoop snip 步骤按 removedUuids 物理剔除。
        return ToolResult.successWithNewMessages(call.id(), dataJson, List.of(boundary));
    }

    /**
     * 渲染模型侧 tool_result content · CC original: mapToolResultToToolResultBlockParam
     * (SnipTool.ts:70-79)。
     *
     * <p>CC 真源：{@code content: `Snipped ${content.snipped_count} messages. Summary: ${content.summary}`}。
     * 从 {@code data}（JSON {@code {"snipped_count":N,"summary":S}}）解析渲染；解析失败回退原串。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(
            AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (result == null) {
            return null;
        }
        int count = 0;
        String summary = "";
        if (result instanceof ToolResult<?> tr && tr.data() instanceof String s) {
            try {
                JsonNode node = MAPPER.readTree(s);
                count = node.path("snipped_count").asInt(0);
                summary = node.path("summary").asText("");
            } catch (Exception e) {
                summary = s;
            }
        }
        String content = "Snipped " + count + " messages. Summary: " + summary;
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    /**
     * 解析 {@code message_ids} 输入（CC SnipTool.ts:7-21）。非数组 / 缺失 → 空列表。
     */
    private static List<String> parseMessageIds(JsonNode input) {
        List<String> ids = new ArrayList<>();
        if (input != null) {
            JsonNode idsNode = input.get("message_ids");
            if (idsNode != null && idsNode.isArray()) {
                for (JsonNode n : idsNode) {
                    if (n != null && n.isTextual() && !n.asText().isBlank()) {
                        ids.add(n.asText());
                    }
                }
            }
        }
        return ids;
    }

    /**
     * [snip-ccb-align] 把模型传的 message_ids（[id:xxx] 短 id 或完整 id）解析为会话历史中
     * 目标 user 消息的 index 列表（去重保序）。
     *
     * <p>匹配维度：① 短 id（{@code SnipCompactor.deriveShortMessageId}，与
     * {@code AgentLoopContext.maybeAppendSnipIdTags} 注入的 [id:] tag 对称——模型看到的就是
     * 这个短 id）；② 完整 id（兼容旧调用/显式 UUID）。仅匹配 user 消息（区间删除的锚点，
     * 对齐 CCB 只给 user 消息注入 [id:] tag 的语义）。
     *
     * @param messages     ctx.messages()（state.messages() 快照）
     * @param requestedIds 模型传入的 message_ids（short id / 完整 id）
     * @return 目标 user 消息 index（升序去重）
     */
    private static List<Integer> resolveTargetUserIndices(List<?> messages, List<String> requestedIds) {
        List<Integer> result = new ArrayList<>();
        if (messages == null || requestedIds == null || requestedIds.isEmpty()) {
            return result;
        }
        for (int i = 0; i < messages.size(); i++) {
            Object o = messages.get(i);
            if (!(o instanceof ChatMessageDto m) || m.role() != Role.user || m.id() == null) {
                continue;
            }
            String shortId = SnipCompactor.deriveShortMessageId(m.id());
            for (String rid : requestedIds) {
                if (rid == null) {
                    continue;
                }
                if (rid.equals(shortId) || rid.equals(m.id())) {
                    if (!result.contains(i)) {
                        result.add(i);
                    }
                    break;
                }
            }
        }
        return result;
    }

    /**
     * [snip-ccb-align] 区间删除：对每个目标 user index，计算删除区间 [userIdx, nextUserIdx)
     * （含中间 assistant + tool 消息，tool_use/tool_result 配对整段删，杜绝 API 序列断裂），
     * 合并重叠区间，返回区间内全部消息的完整 id（removedUuids）。
     *
     * @param messages          ctx.messages()
     * @param targetUserIndices resolveTargetUserIndices 结果（升序）
     * @return 区间内全部消息完整 id（removedUuids 顺序 = 消息序）
     */
    private static List<String> computeRemovedUuids(List<?> messages, List<Integer> targetUserIndices) {
        List<String> removed = new ArrayList<>();
        if (messages == null || targetUserIndices == null || targetUserIndices.isEmpty()) {
            return removed;
        }
        List<Integer> sorted = new ArrayList<>(targetUserIndices);
        java.util.Collections.sort(sorted);
        int rangeStart = -1;
        int rangeEnd = -1;
        for (int idx : sorted) {
            int end = nextUserIndex(messages, idx);
            if (rangeStart == -1) {
                rangeStart = idx;
                rangeEnd = end;
            } else if (idx <= rangeEnd) {
                // 重叠/相邻 → 合并（扩展右边界）
                rangeEnd = Math.max(rangeEnd, end);
            } else {
                collectRangeIds(messages, rangeStart, rangeEnd, removed);
                rangeStart = idx;
                rangeEnd = end;
            }
        }
        if (rangeStart != -1) {
            collectRangeIds(messages, rangeStart, rangeEnd, removed);
        }
        return removed;
    }

    /** 找 {@code from} 之后下一个 Role.user 消息的 index；无 → messages.size()（删除到末尾）。 */
    private static int nextUserIndex(List<?> messages, int from) {
        for (int i = from + 1; i < messages.size(); i++) {
            Object o = messages.get(i);
            if (o instanceof ChatMessageDto m && m.role() == Role.user) {
                return i;
            }
        }
        return messages.size();
    }

    /** 收集区间 [start, end) 内全部消息的完整 id 到 out。 */
    private static void collectRangeIds(List<?> messages, int start, int end, List<String> out) {
        for (int i = start; i < end && i < messages.size(); i++) {
            Object o = messages.get(i);
            if (o instanceof ChatMessageDto m && m.id() != null) {
                out.add(m.id());
            }
        }
    }

    /**
     * 构造 snip_boundary system 消息 · 对齐 CC snipProjection.ts:15-18
     * （type='system' && subtype='snip_boundary'）+ snipCompact.ts:99-106
     * （snipMetadata.removedUuids）。
     *
     * <p>content = 摘要（boundary 保留在投影后消息链中，模型看到摘要替换）；isMeta=true
     * （对齐 CC isSnipMarkerMessage 内部消息语义，snipCompact.ts:25-28，非用户面向历史条目标记）。
     *
     * @param ctx          ToolUseContext（sessionId 归因）
     * @param removedUuids 被裁剪消息 id 列表（写入 snipMetadata.removedUuids）
     * @param summary      摘要文本（boundary content）
     * @return snip_boundary system 消息
     */
    private static ChatMessageDto buildSnipBoundaryMessage(
            ToolUseContext ctx, List<String> removedUuids, String summary) {
        Map<String, Object> snipMetadata = new LinkedHashMap<>();
        snipMetadata.put(KEY_REMOVED_UUIDS, new ArrayList<>(removedUuids));
        String sessionId = ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null;
        return new ChatMessageDto(
            UUID.randomUUID().toString(), sessionId, Role.system, "system", summary, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(),
            null, false, false, null, SnipCompactor.SUBTYPE_SNIP_BOUNDARY,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, snipMetadata);
    }
}
