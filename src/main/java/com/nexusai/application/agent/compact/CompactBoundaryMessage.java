package com.nexusai.application.agent.compact;

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
 * 压缩边界标记消息 · 对齐 CC createCompactBoundaryMessage() / createMicrocompactBoundaryMessage()
 * （utils/messages.ts:4530-4575）。
 *
 * <p><b>WHY 存在（IMP-05 契约对齐）</b>: OD-18 裁决边界统一为 CC 结构化 subtype 单一表示，
 * 删除文本前缀 {@code [Compact boundary: X]} 双轨（D-23）。boundary 在消息流中以
 * ChatMessageDto 呈现，判别依据是 {@code subtype == 'compact_boundary'}'（读侧
 * {@link BoundaryReader#isCompactBoundaryMessage}，messages.ts:4608），而非文本前缀。
 *
 * <h2>CC 对齐（CC 原名 + 行号，grep -n 自验 2026-08-04）</h2>
 * <table>
 *   <tr><th>本字段</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>subtype</td><td>subtype: 'compact_boundary' / 'microcompact_boundary'</td><td>messages.ts:4539 / 4569</td></tr>
 *   <tr><td>content</td><td>content: 'Conversation compacted' / 'Context microcompacted'</td><td>messages.ts:4537 / 4563</td></tr>
 *   <tr><td>isMeta</td><td>isMeta: false</td><td>messages.ts:4536</td></tr>
 *   <tr><td>timestamp</td><td>timestamp: ISO string</td><td>messages.ts:4535</td></tr>
 *   <tr><td>uuid</td><td>uuid: randomUUID()</td><td>messages.ts:4538</td></tr>
 *   <tr><td>level</td><td>level: 'info'</td><td>messages.ts:4547</td></tr>
 *   <tr><td>compactMetadata</td><td>compactMetadata{trigger, preTokens, userContext, messagesSummarized}</td><td>messages.ts:4540-4546</td></tr>
 *   <tr><td>microcompactMetadata</td><td>microcompactMetadata{trigger, preTokens, tokensSaved, compactedToolIds, clearedAttachmentUUIDs}</td><td>messages.ts:4567-4574</td></tr>
 *   <tr><td>logicalParentUuid</td><td>logicalParentUuid（仅 compact_boundary，有值才存在）</td><td>messages.ts:4551-4553</td></tr>
 * </table>
 *
 * <p><b>MICRO 边界产出归属</b>: microcompact_boundary 的<b>产出</b>经 IMP2-11 接线
 * （MicroCompactor.maybeCreateMicrocompactBoundaryMessage 流结束 yield）；本类定义其结构化
 * <b>表示</b>（createMicrocompactBoundaryMessage）。
 */
public record CompactBoundaryMessage(
    /** CC original: subtype (messages.ts:4539 / 4569) · 'compact_boundary' | 'microcompact_boundary' */
    String subtype,
    /** CC original: content (messages.ts:4537 / 4563) · 'Conversation compacted' | 'Context microcompacted' */
    String content,
    /** CC original: isMeta (messages.ts:4536) · 边界恒为 false */
    boolean isMeta,
    /** CC original: timestamp (messages.ts:4535) · 创建时间（ISO，Java 侧 OffsetDateTime） */
    OffsetDateTime timestamp,
    /** CC original: uuid (messages.ts:4538) · randomUUID() */
    String uuid,
    /** CC original: level (messages.ts:4547) · 'info' */
    String level,
    /** CC original: compactMetadata (messages.ts:4540-4546) · compact_boundary 元数据；microcompact_boundary 为 null */
    CompactMetadata compactMetadata,
    /** CC original: microcompactMetadata (messages.ts:4567-4574) · microcompact_boundary 元数据；compact_boundary 为 null */
    MicrocompactMetadata microcompactMetadata,
    /** CC original: logicalParentUuid (messages.ts:4551-4553) · 上一个压缩前最后消息 uuid（可选） */
    String logicalParentUuid
) {

    private static final Logger log = LoggerFactory.getLogger(CompactBoundaryMessage.class);

    /** CC original: subtype 'compact_boundary' (messages.ts:4539) */
    public static final String SUBTYPE_COMPACT_BOUNDARY = "compact_boundary";
    /** CC original: subtype 'microcompact_boundary' (messages.ts:4569) */
    public static final String SUBTYPE_MICROCOMPACT_BOUNDARY = "microcompact_boundary";
    /** CC original: content 'Conversation compacted' (messages.ts:4537) */
    public static final String CONTENT_COMPACTED = "Conversation compacted";
    /** CC original: content 'Context microcompacted' (messages.ts:4563) */
    public static final String CONTENT_MICROCOMPACTED = "Context microcompacted";
    /** CC original: level 'info' (messages.ts:4547) */
    public static final String LEVEL_INFO = "info";

    /** 边界消息的角色固定为 system（CC type: 'system'，messages.ts:4534） */
    private static final Role BOUNDARY_ROLE = Role.system;

    /**
     * compact_boundary 元数据 · 对齐 CC {@code compactMetadata{trigger, preTokens, userContext,
     * messagesSummarized, preCompactDiscoveredTools, preservedSegment}}（utils/messages.ts:4540-4546）。
     *
     * <p><b>字段演进（IMP-11）</b>: CC compactMetadata 除 base 4 字段外，partial/reactive 路径还会携带
     * {@code preservedSegment}（compact.ts:349-367 annotateBoundaryWithPreservedSegment）与
     * {@code preCompactDiscoveredTools}（compact.ts:606-611 / :1023-1028 extractDiscoveredToolNames）。
     * 本 record 一并建模（可选字段，缺省空值），避免契约错位。
     *
     * @param trigger                  CC original: trigger (messages.ts:4541) · 'auto' | 'manual'
     * @param preTokens                CC original: preTokens (messages.ts:4542) · 压缩前 token 估算
     * @param userContext              CC original: userContext (messages.ts:4543) · 用户补充上下文（可选）
     * @param messagesSummarized       CC original: messagesSummarized (messages.ts:4544) · 被摘要的消息数（可选）
     * @param preCompactDiscoveredTools CC original: preCompactDiscoveredTools (compact.ts:606-611/1023-1028) ·
     *                                 已发现的 deferred 工具名（按名称排序；无 → 空列表）
     * @param preservedSegment         CC original: preservedSegment (compact.ts:349-367) · 压缩后保留段注解
     *                                 {headUuid, anchorUuid, tailUuid}（可选）
     */
    public record CompactMetadata(
            String trigger,
            int preTokens,
            String userContext,
            Integer messagesSummarized,
            List<String> preCompactDiscoveredTools,
            PreservedSegment preservedSegment) {

        /** 4 参便捷构造器 · 保留既有 base 4 字段调用方（createCompactBoundaryMessage 等）。 */
        public CompactMetadata(String trigger, int preTokens, String userContext, Integer messagesSummarized) {
            this(trigger, preTokens, userContext, messagesSummarized, List.of(), null);
        }

        /**
         * 压缩后保留段注解 · CC original: preservedSegment (compact.ts:349-367)。
         *
         * @param headUuid  保留段首条消息 uuid（CC keep[0]!.uuid）
         * @param anchorUuid 锚点消息 uuid（compact.ts:1077-1080：up_to→最后 summary / from→boundary）
         * @param tailUuid  保留段末条消息 uuid（CC keep.at(-1)!.uuid）
         */
        public record PreservedSegment(String headUuid, String anchorUuid, String tailUuid) {}
    }

    /**
     * microcompact_boundary 元数据 · 对齐 CC {@code microcompactMetadata{trigger, preTokens, tokensSaved,
     * compactedToolIds, clearedAttachmentUUIDs}}（utils/messages.ts:4567-4574）。
     *
     * @param trigger               CC original: trigger (messages.ts:4568) · 'auto'
     * @param preTokens             CC original: preTokens (messages.ts:4569) · 压缩前 token
     * @param tokensSaved           CC original: tokensSaved (messages.ts:4570) · 释放 token 数
     * @param compactedToolIds      CC original: compactedToolIds (messages.ts:4571) · 被压缩的工具 id 列表
     * @param clearedAttachmentUUIDs CC original: clearedAttachmentUUIDs (messages.ts:4572) · 被清除的附件 uuid 列表
     */
    public record MicrocompactMetadata(String trigger, int preTokens, int tokensSaved,
                                       List<String> compactedToolIds, List<String> clearedAttachmentUUIDs) {}

    /**
     * 工厂：compact_boundary · 对齐 CC {@code createCompactBoundaryMessage(trigger, preTokens,
     * lastPreCompactMessageUuid?, userContext?, messagesSummarized?)}（utils/messages.ts:4530-4555）。
     *
     * @param trigger                 CC original: trigger (messages.ts:4530) · 'auto' | 'manual'
     * @param preTokens               CC original: preTokens (messages.ts:4530) · 压缩前 token 估算
     * @param lastPreCompactMessageUuid CC original: lastPreCompactMessageUuid (messages.ts:4531) · 压缩前最后消息 uuid（可选）
     * @param userContext             CC original: userContext (messages.ts:4531) · 用户补充上下文（可选）
     * @param messagesSummarized      CC original: messagesSummarized (messages.ts:4531) · 被摘要消息数（可选）
     * @return subtype='compact_boundary' 的结构化边界消息
     */
    public static CompactBoundaryMessage createCompactBoundaryMessage(
            String trigger,
            int preTokens,
            String lastPreCompactMessageUuid,
            String userContext,
            Integer messagesSummarized) {
        CompactBoundaryMessage boundary = new CompactBoundaryMessage(
            SUBTYPE_COMPACT_BOUNDARY,
            CONTENT_COMPACTED,
            false,
            OffsetDateTime.now(),
            UUID.randomUUID().toString(),
            LEVEL_INFO,
            new CompactMetadata(trigger, preTokens, userContext, messagesSummarized),
            null,
            lastPreCompactMessageUuid
        );
        if (log.isDebugEnabled()) {
            log.debug("创建压缩边界消息: subtype={} trigger={} preTokens={} logicalParentUuid={} messagesSummarized={}",
                boundary.subtype(), trigger, preTokens, lastPreCompactMessageUuid, messagesSummarized);
        }
        return boundary;
    }

    /**
     * 工厂：microcompact_boundary 表示 · 对齐 CC {@code createMicrocompactBoundaryMessage(trigger,
     * preTokens, tokensSaved, compactedToolIds, clearedAttachmentUUIDs)}（utils/messages.ts:4557-4575）。
     *
     * <p><b>产出归属</b>: 本方法只定义表示；实际产出由 IMP-09（MicroCompactor 契约对齐）实现。
     */
    public static CompactBoundaryMessage createMicrocompactBoundaryMessage(
            String trigger,
            int preTokens,
            int tokensSaved,
            List<String> compactedToolIds,
            List<String> clearedAttachmentUUIDs) {
        List<String> toolIds = compactedToolIds == null ? List.of() : compactedToolIds;
        List<String> attUuids = clearedAttachmentUUIDs == null ? List.of() : clearedAttachmentUUIDs;
        CompactBoundaryMessage boundary = new CompactBoundaryMessage(
            SUBTYPE_MICROCOMPACT_BOUNDARY,
            CONTENT_MICROCOMPACTED,
            false,
            OffsetDateTime.now(),
            UUID.randomUUID().toString(),
            LEVEL_INFO,
            null,
            new MicrocompactMetadata(trigger, preTokens, tokensSaved, toolIds, attUuids),
            null
        );
        if (log.isDebugEnabled()) {
            log.debug("创建 microcompact 边界消息: trigger={} preTokens={} tokensSaved={} compactedToolIds={}",
                trigger, preTokens, tokensSaved, toolIds);
        }
        return boundary;
    }

    /**
     * 保留段注解 · 对齐 CC {@code annotateBoundaryWithPreservedSegment}
     * （compact.ts:349-367）。
     *
     * <p>CC 语义：{@code messagesToKeep} 非空时，向 boundary.compactMetadata 追加
     * {@code preservedSegment{headUuid, anchorUuid, tailUuid}}；空保留段返回原 boundary 不变。
     * {@code anchorUuid} = 保留段在消息链中紧邻的前一条消息 uuid：
     * <ul>
     *   <li>up_to（后缀保留）→ 最后一条 summary 消息（compact.ts:1079-1080）</li>
     *   <li>from（前缀保留）→ boundary 自身（compact.ts:1081）</li>
     * </ul>
     *
     * @param boundary     待注解边界（compact_boundary）
     * @param anchorUuid   锚点消息 uuid（compact.ts:1077-1081）
     * @param messagesToKeep 压缩后保留消息（空 → 返回原 boundary）
     * @return 注解后的新 boundary（record 不可变 → 新实例）
     */
    public static CompactBoundaryMessage annotateBoundaryWithPreservedSegment(
            CompactBoundaryMessage boundary,
            String anchorUuid,
            List<ChatMessageDto> messagesToKeep) {
        if (boundary == null || messagesToKeep == null || messagesToKeep.isEmpty()) {
            return boundary;
        }
        CompactMetadata oldMeta = boundary.compactMetadata();
        if (oldMeta == null) {
            return boundary;
        }
        CompactMetadata.PreservedSegment segment = new CompactMetadata.PreservedSegment(
            messagesToKeep.get(0).id(),
            anchorUuid,
            messagesToKeep.get(messagesToKeep.size() - 1).id());
        CompactMetadata newMeta = new CompactMetadata(
            oldMeta.trigger(), oldMeta.preTokens(), oldMeta.userContext(), oldMeta.messagesSummarized(),
            oldMeta.preCompactDiscoveredTools(), segment);
        if (log.isDebugEnabled()) {
            log.debug("boundary 保留段注解: headUuid={} anchorUuid={} tailUuid={} keep={}",
                segment.headUuid(), segment.anchorUuid(), segment.tailUuid(), messagesToKeep.size());
        }
        return boundary.withCompactMetadata(newMeta);
    }

    /**
     * 复制边界并替换 compactMetadata（record 不可变 → 新实例）。
     *
     * @param newMeta 新 compactMetadata（compact_boundary 场景）
     * @return 替换元数据后的新边界
     */
    public CompactBoundaryMessage withCompactMetadata(CompactMetadata newMeta) {
        return new CompactBoundaryMessage(
            subtype, content, isMeta, timestamp, uuid, level,
            newMeta, microcompactMetadata, logicalParentUuid);
    }


    /**
     * 转换为 ChatMessageDto，用于插入消息流。
     *
     * <p>对齐 CC: boundary 作为 SystemMessage（type:'system'）插入到 buildPostCompactMessages
     * 最前面（compact.ts:330-338）；判别依据是 {@code subtype}（messages.ts:4608），
     * 而非文本前缀（OD-18 / D-23 已删除文本前缀双轨）。
     */
    public ChatMessageDto toChatMessageDto() {
        ChatMessageDto dto = new ChatMessageDto(
            "compact-boundary-" + subtype,
            null,                         // sessionId（由调用方设置）
            BOUNDARY_ROLE,
            "system",                     // author
            content,                      // content：CC 常量文本（非文本前缀）
            null,                         // reasoning
            List.of(),                    // toolCalls
            FinishReason.stop,
            null,                         // inputTokens
            null,                         // outputTokens
            "刚刚",
            timestamp,
            null,                         // toolCallId
            null,                         // assistantMessageId
            null,                         // R32-b9 acceptFeedback
            List.of(),                    // R32-b9 contentBlocks
            List.of(),                    // R32-b9 imagePasteIds
            null,                         // structuredOutput
            isMeta,                       // R32-c-1 isMeta（边界恒 false）
            false,                        // H13-GAP isError
            null,                         // sourceToolUseID
            subtype,                      // IMP-05 subtype（读侧判别依据）
            false,                        // isApiErrorMessage
            null,                         // apiError
            null,                         // error
            null,                         // errorDetails
            toCompactMetadataMap(compactMetadata),      // IMP2-14 · CC original: compactMetadata (messages.ts:4540-4546)
            toMicrocompactMetadataMap(microcompactMetadata), // IMP2-14 · CC original: microcompactMetadata (messages.ts:4567-4574)
            logicalParentUuid,            // IMP2-14 · CC original: logicalParentUuid (messages.ts:4551-4553)
            false,                        // IMP2-14 isCompactSummary（boundary 非摘要消息）
            false                         // IMP2-14 isVisibleInTranscriptOnly
        );
        if (log.isDebugEnabled()) {
            log.debug("boundary 转 ChatMessageDto: id={} role={} subtype={} content={} compactMetadata={} logicalParentUuid={}",
                dto.id(), dto.role(), dto.subtype(), dto.content(),
                dto.compactMetadata() != null ? dto.compactMetadata().keySet() : null,
                logicalParentUuid);
        }
        return dto;
    }

    /**
     * compactMetadata → JSON 形状 Map · CC original: {@code compactMetadata}（messages.ts:4540-4546）。
     *
     * <p>CC 语义：{@code compactMetadata} 对象恒存在（trigger/preTokens 必有值）；
     * 可选字段（userContext/messagesSummarized/preCompactDiscoveredTools/preservedSegment）
     * 为 undefined 时不输出（JS JSON 序列化丢 undefined 字段，Java 侧等价 = 不 put）。
     * preCompactDiscoveredTools 仅非空时存在（compact.ts:607-611 if size>0）。
     *
     * @param meta boundary 元数据（microcompact_boundary → null）
     * @return JSON 形状 Map（null → null）
     */
    public static Map<String, Object> toCompactMetadataMap(CompactMetadata meta) {
        if (meta == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trigger", meta.trigger());
        m.put("preTokens", meta.preTokens());
        if (meta.userContext() != null) {
            m.put("userContext", meta.userContext());
        }
        if (meta.messagesSummarized() != null) {
            m.put("messagesSummarized", meta.messagesSummarized());
        }
        if (meta.preCompactDiscoveredTools() != null && !meta.preCompactDiscoveredTools().isEmpty()) {
            m.put("preCompactDiscoveredTools", new ArrayList<>(meta.preCompactDiscoveredTools()));
        }
        if (meta.preservedSegment() != null) {
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("headUuid", meta.preservedSegment().headUuid());
            seg.put("anchorUuid", meta.preservedSegment().anchorUuid());
            seg.put("tailUuid", meta.preservedSegment().tailUuid());
            m.put("preservedSegment", seg);
        }
        return m;
    }

    /**
     * microcompactMetadata → JSON 形状 Map · CC original: {@code microcompactMetadata}
     * （messages.ts:4567-4574）。
     *
     * <p>CC 语义：五字段恒存在（数组可为空数组，JS 序列化保留空数组）。
     *
     * @param meta microcompact 元数据（compact_boundary → null）
     * @return JSON 形状 Map（null → null）
     */
    public static Map<String, Object> toMicrocompactMetadataMap(MicrocompactMetadata meta) {
        if (meta == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trigger", meta.trigger());
        m.put("preTokens", meta.preTokens());
        m.put("tokensSaved", meta.tokensSaved());
        m.put("compactedToolIds", new ArrayList<>(meta.compactedToolIds()));
        m.put("clearedAttachmentUUIDs", new ArrayList<>(meta.clearedAttachmentUUIDs()));
        return m;
    }
}
