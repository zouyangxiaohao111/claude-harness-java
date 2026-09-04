package com.nexusai.application.agent.compact;

/**
 * 压缩进度事件 · 对齐 CC {@code Open-ClaudeCode/src/Tool.ts:150-156} CompactProgressEvent.
 *
 * <p><b>CC schema</b>: 3 类 discriminated union 事件.
 * <ul>
 *   <li>{@code { type: 'hooks_start', hookType: 'pre_compact' | 'post_compact' | 'session_start' }}
 *       — PreCompact / PostCompact / SessionStart hook 前触发</li>
 *   <li>{@code { type: 'compact_start' }} — summarization 摘要请求前触发</li>
 *   <li>{@code { type: 'compact_end' }} — summarization 结束 (finally 块, 无论成功失败) 触发</li>
 * </ul>
 *
 * <p>Java L3 镜像: TS union type → {@code sealed interface} + 嵌套 {@code record} + 嵌套 {@code HookType} enum
 * (保证类型安全, 优于 string type).
 *
 * <h2>单流程恰 5 事件 (D-04 / OD-07 裁决, INV-1)</h2>
 * <p>压缩单流程 (compactConversation 等价) 恰 5 事件, 跨方法不重复 emit
 * (对齐 CC services/compact/compact.ts 单流程 compactConversation):
 * <ol>
 *   <li>{@code hooks_start: pre_compact} — CC compact.ts:406 (executePreCompactHooks 前)</li>
 *   <li>{@code compact_start} — CC compact.ts:429 (摘要请求前)</li>
 *   <li>{@code hooks_start: session_start} — CC compact.ts:587 (processSessionStartHooks 前)</li>
 *   <li>{@code hooks_start: post_compact} — CC compact.ts:719 (executePostCompactHooks 前)</li>
 *   <li>{@code compact_end} — CC compact.ts:760 (finally 块, 无论成功失败)</li>
 * </ol>
 *
 * <p>Java 端事件分布 (见 {@link com.nexusai.application.agent.compact.CompactConversation}
 * compactConversation 单流程 emit 注释):
 * <ul>
 *   <li>compactConversation 入口: {@code pre_compact} (1/5)</li>
 *   <li>compactConversation 摘要请求前: {@code compact_start} (2/5)</li>
 *   <li>compactConversation 成功路径: {@code session_start} (3/5) → {@code post_compact} (4/5)</li>
 *   <li>compactConversation finally: {@code compact_end} (5/5)</li>
 * </ul>
 *
 * <p><b>事件与 hook 一一对应 (INV-1)</b>: pre_compact↔PreCompact hook, compact_start↔摘要请求,
 * session_start↔SessionStart hook, post_compact↔PostCompact hook, compact_end↔finally 复位;
 * hook 失败不产生额外事件. 应急 (reactive) / drain 路径不产生压缩进度事件 (归 IMP-02/IMP-04).
 *
 * @see com.nexusai.application.agent.compact.CompactConversation
 * @see AutoCompactor
 * @see com.nexusai.application.agent.tool.ToolUseContext#onCompactProgress()
 */
public sealed interface CompactProgressEvent {

    /**
     * Hook 启动事件 · 对齐 CC {@code hooks_start} + hookType 字段.
     *
     * <p>3 个 hook 类型: PreCompact (压缩前), PostCompact (压缩后),
     * SessionStart (新 session 启动).
     *
     * @param hookType hook 类型枚举 (PRE_COMPACT / POST_COMPACT / SESSION_START)
     */
    record HooksStart(HookType hookType) implements CompactProgressEvent {
        /**
         * Hook 类型枚举 · 对齐 CC {@code hookType: 'pre_compact' | 'post_compact' | 'session_start'}.
         */
        public enum HookType {
            /** PreCompact hook 前 (CC services/compact/compact.ts:406-409 / 812-815, reactive commands/compact/compact.ts:149-152). */
            PRE_COMPACT,
            /** PostCompact hook 前 (CC services/compact/compact.ts:719-722 / 1065-1068). */
            POST_COMPACT,
            /** SessionStart hook 前 (CC services/compact/compact.ts:587-590 / 977-980). */
            SESSION_START
        }
    }

    /**
     * 压缩开始事件 · 对齐 CC {@code compact_start}.
     *
     * <p>在 LLM 摘要请求前触发 (CC services/compact/compact.ts:429 / 838,
     * reactive commands/compact/compact.ts:171).
     */
    record CompactStart() implements CompactProgressEvent {
    }

    /**
     * 压缩结束事件 · 对齐 CC {@code compact_end}.
     *
     * <p>在 summarization 结束 finally 块触发, 无论成功失败
     * (CC services/compact/compact.ts:760 / 1101-1104, reactive commands/compact/compact.ts:225).
     */
    record CompactEnd() implements CompactProgressEvent {
    }

    /**
     * 摘要流式进度（Java 扩展 2026-09-04 · CC 无此事件——CC CompactProgressEvent 仅
     * {@code hooks_start/compact_start/compact_end} 3 点事件；Java 为前端「真进度条」补充，
     * 非 CC 对齐项）。摘要 LLM 流式期间按已收字符累计推送（对齐 CC {@code setResponseLength
     * (length => length + delta)} 累加语义，compact.ts:1345-1347，CC 用它驱动 spinner 无 percent）。
     *
     * @param chars 已流式收到的摘要字符数（单调增；前端可驱动进度条在摘要段实时蠕动，
     *              到 {@code compact_end} 归 100%）
     */
    record SummaryProgress(int chars) implements CompactProgressEvent {
    }
}
