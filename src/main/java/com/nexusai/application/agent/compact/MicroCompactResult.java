package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;

/**
 * Micro 压缩结果 · 对齐 CC {@code MicrocompactResult}（microCompact.ts:215-220）。
 *
 * <p><b>WHY 存在（IMP-09，D-19 契约对齐）</b>: CC microcompact 返回 {@code {messages, compactionInfo?}}，
 * <b>无 boundary / summary / source</b>（microCompact.ts:493-528 两分支均不产出边界）。Java 旧
 * MicroCompactor 曾以 CompactResult 宽形状承载 Micro 结果，与 CC 契约错位（D-19）——[IMP2-23] 过渡面已删除。
 * 本 record 对齐 CC {@code MicrocompactResult} 形状。
 *
 * <h2>CC 对齐（grep -n 自验 2026-08-04，microCompact.ts）</h2>
 * <table>
 *   <tr><th>本字段</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>messages</td><td>messages: Message[]</td><td>microCompact.ts:216</td></tr>
 *   <tr><td>compactionInfo</td><td>compactionInfo?: { pendingCacheEdits? }</td><td>microCompact.ts:217-220</td></tr>
 * </table>
 *
 * <p><b>分支产出</b>:
 * <ul>
 *   <li>默认 no-op（INV-10）：{@code {messages}}，compactionInfo=null（microCompact.ts:292）</li>
 *   <li>time-based MC：{@code {messages: result}}，compactionInfo=null（microCompact.ts:529）</li>
 *   <li>cached MC（状态机已实现）：删除触发时
 *       {@code {messages, compactionInfo:{pendingCacheEdits}}}
 *       （microCompact.ts:385-394）——pendingCacheEdits 为
 *       {@code {trigger:'auto', deletedToolIds, baselineCacheDeletedTokens}}</li>
 * </ul>
 *
 * <p>cached-MC 状态机类型（cachedMicrocompact.ts 真源）亦居本文件：
 * {@link CachedMCConfig}（:37-42 配置）、{@link CacheEditsBlock}（:9-12 块）、
 * {@link PinnedCacheEdits}（:14-17 已钉住块）。
 *
 * @param messages      压缩后的消息列表（no-op 时与原列表同引用；time-based 为新列表）
 * @param compactionInfo CC compactionInfo（仅 cached-MC 产出 pendingCacheEdits 时非空）
 */
public record MicroCompactResult(
    List<ChatMessageDto> messages,
    MicroCompactCompactionInfo compactionInfo
) {

    /**
     * 紧凑构造器：不变量保护（messages 非空；compactionInfo 可空 = 无压缩元数据）。
     */
    public MicroCompactResult {
        if (messages == null) {
            throw new IllegalArgumentException("MicroCompactResult.messages is null");
        }
    }

    /**
     * CC compactionInfo · 对齐 {@code MicrocompactResult['compactionInfo']}
     * （microCompact.ts:217-220）。
     *
     * @param pendingCacheEdits CC pendingCacheEdits?（microCompact.ts:218）
     */
    public record MicroCompactCompactionInfo(MicroCompactResult.PendingCacheEdits pendingCacheEdits) {}

    /**
     * 待下发 cache_edits · 对齐 CC {@code PendingCacheEdits}（microCompact.ts:207-213）。
     *
     * <p>由 {@code cachedMicrocompactPath} 删除触发时产出（microCompact.ts:385-394），供
     * {@link MicroCompactor#maybeCreateMicrocompactBoundaryMessage(long)} 流结束 yield 消费；
     * provider 注入请求用的 {@code CacheEditsBlock} 经
     * {@link MicroCompactor#consumePendingCacheEditsBlock()} 独立通道。
     *
     * @param trigger                    CC original: trigger (microCompact.ts:209) · 恒 'auto'
     * @param deletedToolIds             CC original: deletedToolIds (microCompact.ts:210) · 待删除工具 id
     * @param baselineCacheDeletedTokens CC original: baselineCacheDeletedTokens (microCompact.ts:212) ·
     *                                   上一次 API 响应的累计 cache_deleted_input_tokens 基线
     */
    public record PendingCacheEdits(
        String trigger,
        List<String> deletedToolIds,
        long baselineCacheDeletedTokens
    ) {

        public PendingCacheEdits {
            if (deletedToolIds == null) {
                deletedToolIds = List.of();
            }
        }
    }

    /**
     * cached-MC 配置 · 对齐 CC {@code getCachedMCConfig()} 返回值
     * {@code {triggerThreshold, keepRecent}}（cachedMicrocompact.ts:37-42）。
     *
     * @param triggerThreshold CC original: triggerThreshold (cachedMicrocompact.ts:41) ·
     *                         active 工具数超过该值触发删除（TRIGGER_THRESHOLD，:19）
     * @param keepRecent       CC original: keepRecent (:42) · 保留最近 N 个工具结果不删（KEEP_RECENT，:20）
     */
    public record CachedMCConfig(int triggerThreshold, int keepRecent) {

        /** CC 默认 · CC original: TRIGGER_THRESHOLD / KEEP_RECENT（cachedMicrocompact.ts:19-20）。 */
        public static final CachedMCConfig DEFAULTS = new CachedMCConfig(10, 5);
    }

    /**
     * cache_edits 块 · 对齐 CC {@code CacheEditsBlock}（cachedMicrocompact.ts:9-12）。
     *
     * <p>由 {@code createCacheEditsBlock} 构建（:100-112），删除触发时入队模块态供 provider 注入
     * API 请求（microCompact.ts:336-339）。块类型恒 {@code 'cache_edits'}。
     *
     * @param type  CC original: type (cachedMicrocompact.ts:10) · 恒 {@link #TYPE_CACHE_EDITS}
     * @param edits CC original: edits (:11) · 待删除工具结果编辑项
     */
    public record CacheEditsBlock(String type, List<CacheEdit> edits) {

        /** cache_edits 块类型字面量 · CC original: type (cachedMicrocompact.ts:10)。 */
        public static final String TYPE_CACHE_EDITS = "cache_edits";

        /**
         * 单条删除编辑 · 对齐 CC edits 元素 {@code {type: string; tool_use_id: string}}
         * （cachedMicrocompact.ts:11）。
         *
         * @param type      CC original: type (:11) · 恒 {@link #TYPE_DELETE_TOOL_RESULT}
         * @param toolUseId CC original: tool_use_id (:11) · 待删除工具结果 id（snake_case 转 camelCase）
         */
        public record CacheEdit(String type, String toolUseId) {

            /** 删除工具结果编辑类型 · CC original: type (cachedMicrocompact.ts:11)。 */
            public static final String TYPE_DELETE_TOOL_RESULT = "delete_tool_result";
        }
    }

    /**
     * 已钉住 cache_edits · 对齐 CC {@code PinnedCacheEdits}（cachedMicrocompact.ts:14-17）。
     *
     * <p>API 层在消息中插入新 cache_edits 块后经 {@code pinCacheEdits} 钉住（microCompact.ts:111-118 /
     * claude.ts:3153），后续请求在原始用户消息位置重发（claude.ts:1532 getPinnedCacheEdits）。
     *
     * @param userMessageIndex CC original: userMessageIndex (cachedMicrocompact.ts:15) · 块插入的用户消息下标
     * @param block            CC original: block (:16) · 已钉住块
     */
    public record PinnedCacheEdits(int userMessageIndex, CacheEditsBlock block) {}
}
