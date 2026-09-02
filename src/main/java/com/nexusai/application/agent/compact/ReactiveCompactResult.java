package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;

/**
 * 应急压缩结果 · 对齐 CC {@code tryReactiveCompact} 返回契约（query.ts:1134 {@code if (compacted)}）。
 *
 * <p><b>WHY 存在（IMP-14 契约对齐）</b>: CC {@code reactiveCompact.tryReactiveCompact(...)}
 * 返回 {@code compacted}（真值 = 恢复成功）或 {@code false}（无法恢复 → surface 错误）。调用方
 * 对真值结果执行 {@code buildPostCompactMessages(compacted)}（query.ts:1148）组装输出。
 * Java 旧 {@code ReactiveCompactResult}（嵌套 record）为 {@code (messages, tokensFreed, needsAutoCompact)}
 * 裸列表结构（R4/R6 偏移：返回裸列表、无 boundary/summary 组装）；本 record 对齐 CC
 * {@code CompactionResult} 结构：真值对象携带 {@link CompactionResult}，由调用方经
 * {@link #buildPostCompactMessages()} 组装（boundary → summary → messagesToKeep → attachments → hookResults）。
 *
 * <p><b>needsAutoCompact 已删除（D-24）</b>: CC 无该字段，Java 旧实现中亦被忽略
 * （tryReactiveCompact 只查 tokensFreed/size，不消费该标志）——Java 独有推测，OD-02 裁决删除。
 *
 * <h2>CC 对齐（grep -n 自验 2026-08-04，query.ts:1120-1166）</h2>
 * <table>
 *   <tr><th>本记录</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>tryReactiveCompact 返回</td><td>{@code compacted}（真值）或 {@code false}</td><td>query.ts:1134</td></tr>
 *   <tr><td>buildPostCompactMessages()</td><td>{@code buildPostCompactMessages(compacted)}</td><td>query.ts:1148</td></tr>
 *   <tr><td>compactionResult</td><td>{@code compacted}（CompactionResult 结构）</td><td>query.ts:1134</td></tr>
 * </table>
 *
 * @param compactionResult 应急压缩结果（boundary/summary/messagesToKeep 结构，CC compacted）
 */
public record ReactiveCompactResult(
    /** CC original: compacted (query.ts:1134) · 应急压缩结果（非 null = 真值） */
    CompactionResult compactionResult
) {

    /**
     * 紧凑构造器：不变量保护。
     *
     * <p>WHY: 真值结果必须携带有效压缩结果，否则调用方 {@code buildPostCompactMessages} 无组装来源。
     */
    public ReactiveCompactResult {
        if (compactionResult == null) {
            throw new IllegalArgumentException("ReactiveCompactResult.compactionResult is null");
        }
    }

    /**
     * 组装应急压缩后的消息列表 · 对齐 CC {@code buildPostCompactMessages(compacted)}
     * （query.ts:1148 + compact.ts:330-338）。
     *
     * <p><b>固定顺序（INV-2）</b>：{@code boundary → summaryMessages → messagesToKeep →
     * attachments → hookResults}。应急路径（snip-first）通常只有 boundary + messagesToKeep。
     *
     * @return 压缩后消息列表（顺序不变）
     */
    public List<ChatMessageDto> buildPostCompactMessages() {
        return CompactionResult.buildPostCompactMessages(compactionResult);
    }
}
