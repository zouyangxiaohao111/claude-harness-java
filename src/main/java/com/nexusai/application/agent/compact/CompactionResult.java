package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩结果契约 · 对齐 CC {@code CompactionResult}（compact.ts:299-310）·
 * <b>10 字段</b>（compact.ts:738-748 返回形态）。
 *
 * <p><b>WHY 存在（IMP-04 契约对齐）</b>: CC {@code compactConversation} 返回
 * {@code CompactionResult} 10 字段；Java 旧 CompactResult 宽形状（管线聚合 7 字段）属
 * 管线架构产物，不承载 CC 契约——[IMP2-23 D-19] 已删除。本 record 为 CC 契约的
 * Java 表达（CC snake_case → Java camelCase，各字段 JavaDoc 标注 CC 原名 + 行号）。
 *
 * <h2>CC 对齐（grep -n 自验 2026-08-04，compact.ts:738-748）</h2>
 * <table>
 *   <tr><th>本字段</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>boundaryMarker</td><td>boundaryMarker (SystemMessage)</td><td>compact.ts:739</td></tr>
 *   <tr><td>summaryMessages</td><td>summaryMessages (UserMessage[])</td><td>compact.ts:740</td></tr>
 *   <tr><td>attachments</td><td>attachments (AttachmentMessage[])</td><td>compact.ts:741</td></tr>
 *   <tr><td>hookResults</td><td>hookResults (HookResultMessage[])</td><td>compact.ts:742</td></tr>
 *   <tr><td>messagesToKeep</td><td>messagesToKeep?（全量压缩 undefined）</td><td>compact.ts:743</td></tr>
 *   <tr><td>userDisplayMessage</td><td>userDisplayMessage?（pre+post hook 合并）</td><td>compact.ts:744</td></tr>
 *   <tr><td>preCompactTokenCount</td><td>preCompactTokenCount</td><td>compact.ts:745</td></tr>
 *   <tr><td>postCompactTokenCount</td><td>postCompactTokenCount（compact API 调用总用量）</td><td>compact.ts:746</td></tr>
 *   <tr><td>truePostCompactTokenCount</td><td>truePostCompactTokenCount（结果上下文消息载荷估算）</td><td>compact.ts:747</td></tr>
 *   <tr><td>compactionUsage</td><td>compactionUsage（getTokenUsage(summaryResponse)）</td><td>compact.ts:748</td></tr>
 * </table>
 *
 * @see CompactConversation
 * @see CompactBoundaryMessage
 */
public record CompactionResult(
    /** CC original: boundaryMarker (compact.ts:739) · 压缩边界标记（结构化 subtype） */
    CompactBoundaryMessage boundaryMarker,
    /** CC original: summaryMessages (compact.ts:740) · 摘要用户消息列表 */
    List<ChatMessageDto> summaryMessages,
    /** CC original: attachments (compact.ts:741) · 压缩后恢复附件消息列表 */
    List<ChatMessageDto> attachments,
    /** CC original: hookResults (compact.ts:742) · SessionStart hook 结果消息列表 */
    List<ChatMessageDto> hookResults,
    /** CC original: messagesToKeep (compact.ts:743) · 压缩后保留消息（全量压缩 undefined） */
    List<ChatMessageDto> messagesToKeep,
    /** CC original: userDisplayMessage (compact.ts:744) · pre+post hook 合并显示消息（可选） */
    String userDisplayMessage,
    /** CC original: preCompactTokenCount (compact.ts:745) · 压缩前 token（tokenCountWithEstimation） */
    int preCompactTokenCount,
    /** CC original: postCompactTokenCount (compact.ts:746) · compact API 调用总用量（tokenCountFromLastAPIResponse） */
    int postCompactTokenCount,
    /** CC original: truePostCompactTokenCount (compact.ts:747) · 结果上下文消息载荷估算（roughTokenCountEstimationForMessages） */
    int truePostCompactTokenCount,
    /** CC original: compactionUsage (compact.ts:748) · 压缩 API usage 明细 */
    CompactConversation.TokenUsage compactionUsage
) {

    private static final Logger log = LoggerFactory.getLogger(CompactionResult.class);

    /**
     * 构建压缩后基础消息数组 · 对齐 CC {@code buildPostCompactMessages}
     * （compact.ts:330-338，OD-04 RESOLVED）。
     *
     * <p><b>固定顺序（INV-2）</b>：{@code boundary → summaryMessages → messagesToKeep →
     * attachments → hookResults}。L4 尾段（messagesToKeep）不得丢弃（OD-04）。
     *
     * @param result 压缩结果
     * @return 压缩后消息列表（顺序不变）
     */
    public static List<ChatMessageDto> buildPostCompactMessages(CompactionResult result) {
        List<ChatMessageDto> out = new ArrayList<>();
        if (result == null) {
            return out;
        }
        // 1. boundaryMarker
        if (result.boundaryMarker() != null) {
            out.add(result.boundaryMarker().toChatMessageDto());
        }
        // 2. summaryMessages
        if (result.summaryMessages() != null) {
            out.addAll(result.summaryMessages());
        }
        // 3. messagesToKeep ?? []（CC compact.ts:334）
        if (result.messagesToKeep() != null) {
            out.addAll(result.messagesToKeep());
        }
        // 4. attachments
        if (result.attachments() != null) {
            out.addAll(result.attachments());
        }
        // 5. hookResults
        if (result.hookResults() != null) {
            out.addAll(result.hookResults());
        }
        if (log.isDebugEnabled()) {
            log.debug("[CompactionResult] buildPostCompactMessages: total={} boundary={} summary={} keep={} att={} hooks={}",
                out.size(),
                result.boundaryMarker() != null ? 1 : 0,
                result.summaryMessages() != null ? result.summaryMessages().size() : 0,
                result.messagesToKeep() != null ? result.messagesToKeep().size() : 0,
                result.attachments() != null ? result.attachments().size() : 0,
                result.hookResults() != null ? result.hookResults().size() : 0);
        }
        return out;
    }

    /**
     * 构建 partial 压缩后消息数组 · 对齐 CC REPL.tsx:4950-4952（direction-aware 重组）。
     *
     * <p><b>WHY 区别于 {@link #buildPostCompactMessages}</b>: CC partial 返回的
     * {@code messagesToKeep} 为「被保留段」（partial 独有，全量压缩 undefined），重组顺序
     * 按方向切分（REPL.tsx:4950-4952）：
     * <pre>
     *   kept    = result.messagesToKeep ?? []
     *   ordered = direction === 'up_to' ? [...summaryMessages, ...kept]
     *                                   : [...kept, ...summaryMessages]
     *   postCompact = [boundaryMarker, ...ordered, ...attachments, ...hookResults]
     * </pre>
     * {@code from} 时 keep 在 summary <b>之前</b>（前缀保留段先于摘要）；
     * {@code up_to} 时 summary 在 keep 之前（摘要先于后缀保留段）。现有
     * {@code buildPostCompactMessages}（[boundary, summary, keep, ...]）只匹配 up_to 顺序，
     * from 顺序错位 —— 本方法为 direction-aware helper（⚠ 接线时不可复用全量 helper）。
     *
     * @param result     partial 压缩结果（PartialCompactConversation 返回）
     * @param direction  压缩方向（CC PartialCompactDirection）
     * @return 压缩后消息列表（顺序 = boundary → ordered → attachments → hookResults）
     */
    public static List<ChatMessageDto> buildPartialPostCompactMessages(
            CompactionResult result, CompactPrompt.Direction direction) {
        List<ChatMessageDto> out = new ArrayList<>();
        if (result == null) {
            return out;
        }
        // 1. boundaryMarker（REPL.tsx:4952 postCompact[0]）
        if (result.boundaryMarker() != null) {
            out.add(result.boundaryMarker().toChatMessageDto());
        }
        // 2. ordered = up_to ? [summary, ...keep] : [...keep, summary]（REPL.tsx:4950-4951）
        List<ChatMessageDto> summary = result.summaryMessages() == null
            ? List.of() : result.summaryMessages();
        List<ChatMessageDto> keep = result.messagesToKeep() == null
            ? List.of() : result.messagesToKeep();
        boolean upTo = direction == CompactPrompt.Direction.UP_TO;
        if (upTo) {
            out.addAll(summary);
            out.addAll(keep);
        } else {
            out.addAll(keep);
            out.addAll(summary);
        }
        // 3. attachments + hookResults（REPL.tsx:4952）
        if (result.attachments() != null) {
            out.addAll(result.attachments());
        }
        if (result.hookResults() != null) {
            out.addAll(result.hookResults());
        }
        if (log.isDebugEnabled()) {
            log.debug("[CompactionResult] buildPartialPostCompactMessages: direction={} total={} boundary={} keep={} summary={} att={} hooks={}",
                upTo ? "up_to" : "from", out.size(),
                result.boundaryMarker() != null ? 1 : 0, keep.size(), summary.size(),
                result.attachments() != null ? result.attachments().size() : 0,
                result.hookResults() != null ? result.hookResults().size() : 0);
        }
        return out;
    }
}
