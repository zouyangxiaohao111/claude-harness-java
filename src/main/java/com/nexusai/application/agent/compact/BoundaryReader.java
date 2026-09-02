package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 边界读侧（BoundaryReader）· 对齐 CC utils/messages.ts:4608-4656 + services/compact/snipProjection.ts。
 *
 * <p><b>WHY 存在（IMP-05）</b>: D-22 删除 {@code CompactBoundaryMessage.isCompactBoundary/extractSource}
 * 文本前缀读侧死代码后，由本组件按 CC 结构化 subtype 判别边界。boundary 在消息流中为
 * ChatMessageDto（role=system + subtype='compact_boundary'），读侧以 subtype 判别（INV-4 单一表示）。
 *
 * <h2>CC 对齐（CC 原名 + 行号，grep -n 自验 2026-08-04 / 2026-08-18）</h2>
 * <table>
 *   <tr><th>本方法</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>isCompactBoundaryMessage</td><td>isCompactBoundaryMessage(message)</td><td>messages.ts:4608-4612</td></tr>
 *   <tr><td>findLastCompactBoundaryIndex</td><td>findLastCompactBoundaryIndex(messages)</td><td>messages.ts:4618-4628</td></tr>
 *   <tr><td>getMessagesAfterCompactBoundary(messages, includeSnipped)</td><td>getMessagesAfterCompactBoundary(messages, options?)</td><td>messages.ts:4643-4656</td></tr>
 *   <tr><td>isSnipBoundaryMessage</td><td>isSnipBoundaryMessage(message)</td><td>snipProjection.ts:15-18</td></tr>
 *   <tr><td>projectSnippedView</td><td>projectSnippedView(messages)</td><td>snipProjection.ts:35-60</td></tr>
 * </table>
 *
 * <p><b>snip 投影（2026-08-18 真源对齐）</b>: CC getMessagesAfterCompactBoundary 在 HISTORY_SNIP
 * 开启且 {@code !options?.includeSnipped} 时会对切片应用 {@code projectSnippedView}
 * （messages.ts:4648-4653），剔除被 snip 删除的消息（removedUuids），使模型面数组不含陈旧历史。
 * 本组件按已入库真源 {@code Open-ClaudeCode/src/services/compact/snipProjection.ts} 完整实现
 * isSnipBoundaryMessage + projectSnippedView（此前 TODO[OD-01] 悬空，vendored snapshot 缺
 * snipProjection.js —— 2026-08-18 真源已取回，投影从「引用方语义」升级为「真源实现」）。
 * HISTORY_SNIP 门经 {@link #setFeatureFlags} 静态槽位注入（MicroCompactor/StreamCompactSummary
 * 先例），默认 ALL_DISABLED → flag 关时投影恒 no-op，既有单参调用方零行为变化。
 */
public final class BoundaryReader {

    private static final Logger log = LoggerFactory.getLogger(BoundaryReader.class);

    /** CC original: subtype 'snip_boundary'（snipProjection.ts:17 / snipCompact.ts:99）· snip 边界消息 subtype */
    private static final String SUBTYPE_SNIP_BOUNDARY = "snip_boundary";

    /** CC original: snipMetadata.removedUuids（snipProjection.ts:31 / snipCompact.ts:99-103）· snip 删除消息 uuid 数组 */
    private static final String SNIP_METADATA_REMOVED_UUIDS = "removedUuids";

    /**
     * [2026-08-18] HISTORY_SNIP 门（CC {@code feature('HISTORY_SNIP')}，query.ts:115/401）· 默认全关。
     *
     * <p>与 {@link MicroCompactor} / {@link StreamCompactSummary} 同模式（static volatile + 测试
     * setter，IMP2-01 先例）：getMessagesAfterCompactBoundary 为静态纯函数，生产 bean 无
     * FeatureFlags 注入面，以静态槽位承载门控；默认 {@code ALL_DISABLED}（historySnip=false）
     * → snip 投影恒 no-op（CC flag-off 等价）。
     */
    private static volatile com.nexusai.application.agent.loop.FeatureFlags featureFlags =
        com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;

    /**
     * [V52 X1-3] 压缩配置 DB 实时读源静态槽位（null = 未接线 → 回落 {@link #featureFlags}）。
     *
     * <p>同 {@link #setFeatureFlags} 静态槽位模式（BoundaryReader 为纯静态工具类，无实例注入面）；
     * 生产在 {@code ToolRegistrationConfig.microCompactor} @Bean 接线（与 MicroCompactor 静态槽位
     * 同点）。
     */
    private static volatile com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver;

    /**
     * [2026-08-18] 测试注入 feature 门（对齐 {@link MicroCompactor#setFeatureFlags} 先例）。
     *
     * @param flags feature 门（null → 回退 ALL_DISABLED，对齐 flag-off）
     */
    public static void setFeatureFlags(com.nexusai.application.agent.loop.FeatureFlags flags) {
        featureFlags = flags != null
            ? flags
            : com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;
        if (log.isDebugEnabled()) {
            log.debug("BoundaryReader setFeatureFlags: historySnip={}（getMessagesAfterCompactBoundary snip 投影门控，CC HISTORY_SNIP）",
                featureFlags.historySnip());
        }
    }

    /**
     * [V52 X1-3] 压缩配置 DB 实时读源静态注入（可 null，null = 未接线回落 FeatureFlags）。
     *
     * @param resolver 压缩配置实时读源（可 null）
     */
    public static void setSettingsResolver(com.nexusai.application.agent.compact.CompactSettingsResolver resolver) {
        settingsResolver = resolver;
        if (log.isDebugEnabled()) {
            log.debug("BoundaryReader setSettingsResolver: 注入={}（snip 投影 DB 覆盖，null 回落 FeatureFlags）",
                resolver != null);
        }
    }

    /**
     * HISTORY_SNIP 门 DB-aware 解析 · [V52 X1-3] 供读侧 snip 投影门控。
     *
     * <p>DB {@code settings.history_snip_enabled} 有值覆盖 {@link #featureFlags}.historySnip()
     * （null 回落 FeatureFlags，零行为变化）。
     *
     * @return true = HISTORY_SNIP 开启（含 DB 覆盖）
     */
    private static boolean isHistorySnipEnabled() {
        Boolean dbSnip = settingsResolver != null ? settingsResolver.historySnipEnabled() : null;
        if (dbSnip != null) {
            return dbSnip;
        }
        return featureFlags.historySnip();
    }

    private BoundaryReader() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 判别一个消息是否为 compact boundary · 对齐 CC {@code isCompactBoundaryMessage}
     * （messages.ts:4608-4612）：{@code type==='system' && subtype==='compact_boundary'}。
     *
     * <p>Java 侧等价：role==system && subtype=='compact_boundary'。microcompact_boundary
     * 不在判别范围（CC 仅匹配 compact_boundary，microCompact 返回 {messages, compactionInfo?}
     * 不产 boundary，D-19）。
     *
     * @param message 待检查消息
     * @return true 表示是 compact boundary
     */
    public static boolean isCompactBoundaryMessage(ChatMessageDto message) {
        return message != null
            && message.role() == Role.system
            && CompactBoundaryMessage.SUBTYPE_COMPACT_BOUNDARY.equals(message.subtype());
    }

    /**
     * 找出消息数组中最后一个 compact boundary 的下标 · 对齐 CC {@code findLastCompactBoundaryIndex}
     * （messages.ts:4618-4628）：向后扫描，返回最后一个边界下标；无边界返回 -1。
     *
     * @param messages 消息列表
     * @return 最后一个 compact boundary 下标，无边界时 -1
     */
    public static int findLastCompactBoundaryIndex(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return -1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isCompactBoundaryMessage(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从最后一个 compact boundary（含）向后切片 · 对齐 CC {@code getMessagesAfterCompactBoundary}
     * （messages.ts:4643-4656）：无边界返回全量；有边界返回从最后一个边界（含）到末尾的新列表。
     * 默认 {@code includeSnipped=false}（CC 缺省）：HISTORY_SNIP 开启时对切片应用
     * {@link #projectSnippedView}（messages.ts:4648-4653）；flag 关时行为与旧单参版完全一致。
     *
     * <p><b>WHY 返回新列表</b>: CC {@code messages.slice(boundaryIndex)} 生成新数组；下游
     * （budget/snip/autocompact）可能改写切片，返回新列表避免 subList 视图把改动回灌原消息链
     * （05 DRIFT-17 边界剥离语义）。
     *
     * @param messages 消息列表
     * @return 从最后一个 boundary（含）向后的切片；无边界时全量（不可变快照）
     */
    public static List<ChatMessageDto> getMessagesAfterCompactBoundary(List<ChatMessageDto> messages) {
        return getMessagesAfterCompactBoundary(messages, false);
    }

    /**
     * 从最后一个 compact boundary（含）向后切片 + 可选 snip 投影 · 对齐 CC
     * {@code getMessagesAfterCompactBoundary(messages, options?)}（messages.ts:4643-4656）：
     * 切片语义与单参版相同；随后按 CC 门控 {@code !options?.includeSnipped && feature('HISTORY_SNIP')}
     * （messages.ts:4648）对切片应用 {@link #projectSnippedView}，剔除被 snip 删除的陈旧消息。
     *
     * <p><b>includeSnipped 语义</b>: true = 保留被 snip 删除的消息（CC REPL.tsx 全屏 compact
     * 处理器传 {@code { includeSnipped: true }} 保留滚动回看）；false = 默认，flag 开时应用投影。
     * {@link #setFeatureFlags} 注入 historySnip 门（默认全关 → 投影恒跳过）。
     *
     * @param messages      消息列表
     * @param includeSnipped 是否保留被 snip 删除的消息（CC options.includeSnipped，messages.ts:4648）
     * @return 切片 + 可选 snip 投影后的列表；无边界时全量
     */
    public static List<ChatMessageDto> getMessagesAfterCompactBoundary(
            List<ChatMessageDto> messages, boolean includeSnipped) {
        if (messages == null || messages.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("读侧切片: 消息列表为空，返回空列表");
            }
            return messages == null ? List.of() : messages;
        }
        int boundaryIndex = findLastCompactBoundaryIndex(messages);
        List<ChatMessageDto> sliced;
        if (boundaryIndex == -1) {
            if (log.isDebugEnabled()) {
                log.debug("读侧切片: 无 compact boundary，返回全量 {} 条消息", messages.size());
            }
            sliced = List.copyOf(messages);
        } else {
            sliced = new ArrayList<>(messages.subList(boundaryIndex, messages.size()));
            if (log.isDebugEnabled()) {
                log.debug("读侧切片: 最后一个 compact boundary 下标={}，切片 {} 条消息（含 boundary）",
                    boundaryIndex, sliced.size());
            }
        }
        if (!includeSnipped && isHistorySnipEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("读侧切片: HISTORY_SNIP 开启且 includeSnipped=false，对切片应用 snip 投影（messages.ts:4648-4653）");
            }
            return projectSnippedView(sliced);
        }
        if (log.isDebugEnabled()) {
            log.debug("读侧切片: 跳过 snip 投影（includeSnipped={} historySnip={}，messages.ts:4648 门控）",
                includeSnipped, isHistorySnipEnabled());
        }
        return sliced;
    }

    /**
     * 判别一个消息是否为 snip boundary 标记 · 对齐 CC {@code isSnipBoundaryMessage}
     * （snipProjection.ts:15-18）：{@code message.type==='system' && subtype==='snip_boundary'}。
     *
     * <p>Java 侧等价：role==system && subtype=='snip_boundary'（含 null 防护）。
     * snip boundary 是 subtype='snip_boundary' 的 system 消息，可携带
     * {@code snipMetadata.removedUuids} 记录 snip 操作删除的消息 uuid（snipProjection.ts:5-9）。
     *
     * @param message 待检查消息
     * @return true 表示是 snip boundary 标记
     */
    public static boolean isSnipBoundaryMessage(ChatMessageDto message) {
        return message != null
            && message.role() == Role.system
            && SUBTYPE_SNIP_BOUNDARY.equals(message.subtype());
    }

    /**
     * 投影「snipped 视图」· 对齐 CC {@code projectSnippedView}（snipProjection.ts:35-60）：
     * 遍历收集所有 snip boundary 的 {@code snipMetadata.removedUuids} → removedSet；set 非空时
     * 过滤掉 uuid 在 removedSet 中的消息（boundary 自身保留），set 为空返回原数组。
     *
     * <p><b>模型面语义</b>: getMessagesAfterCompactBoundary 在 compact boundary 切片后经本方法
     * 进一步过滤被 snip 删除的消息，使模型面数组不含陈旧历史（snipProjection.ts:20-30）；
     * REPL 保留全量历史用于 UI 滚动回看，故模型面路径需同时应用 compact 切片 + snip 过滤。
     *
     * <p><b>泛型擦除防护</b>: {@code snipMetadata} 为 {@code Map<String,Object>}，
     * {@code removedUuids} 值经 {@code instanceof List<?>} 判定，元素经 {@code instanceof String}
     * 判定（值可为 List&lt;String&gt; 或 List&lt;?&gt;，需 null/类型防护）。
     *
     * @param messages 消息数组（可能含 0..N 个 snip boundary）
     * @return 剔除被删除消息后的新数组；无 removedUuids 时原数组（同引用）
     */
    public static List<ChatMessageDto> projectSnippedView(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        // 收集所有 snip boundary 删除的 uuid（snipProjection.ts:36-53）
        Set<String> removedSet = new HashSet<>();
        for (ChatMessageDto msg : messages) {
            if (isSnipBoundaryMessage(msg)) {
                Map<String, Object> meta = msg.snipMetadata();
                if (meta != null) {
                    Object removedUuidsObj = meta.get(SNIP_METADATA_REMOVED_UUIDS);
                    if (removedUuidsObj instanceof List<?> removedUuids) {
                        for (Object uuidObj : removedUuids) {
                            if (uuidObj instanceof String uuid) {
                                removedSet.add(uuid);
                            }
                        }
                    }
                }
            }
        }
        if (removedSet.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("snip 投影: 无 removedUuids，返回原数组 {} 条消息（snipProjection.ts:55-57）",
                    messages.size());
            }
            return messages;
        }
        // 过滤 uuid 在 removedSet 中的消息（boundary 自身保留，snipProjection.ts:59）
        List<ChatMessageDto> projected = messages.stream()
            .filter(m -> !removedSet.contains(m.id()))
            .toList();
        if (log.isDebugEnabled()) {
            log.debug("snip 投影: removedSet={} 条 uuid，过滤 {} → {} 条消息（snipProjection.ts:59）",
                removedSet.size(), messages.size(), projected.size());
        }
        return projected;
    }
}
