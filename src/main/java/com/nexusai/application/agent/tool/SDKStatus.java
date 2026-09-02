package com.nexusai.application.agent.tool;

/**
 * SDK 状态枚举 · 对齐 CC {@code Open-ClaudeCode/src/entrypoints/sdk/coreSchemas.ts:1268-1270}
 * {@code SDKStatusSchema = z.union([z.literal('compacting'), z.null()])}.
 *
 * <p><b>CC 真源</b> ({@code coreSchemas.ts:1268-1270}):
 * <pre>
 * export const SDKStatusSchema = lazySchema(() =&gt;
 *   z.union([z.literal('compacting'), z.null()]),
 * )
 * </pre>
 *
 * <p><b>Java L3 镜像</b> (严格 2 值, 1:1 对齐 CC schema):
 * <ul>
 *   <li>{@link #COMPACTING} — 对应 CC 字面量 {@code 'compacting'} (压缩中)</li>
 *   <li>{@link #NULL} — 对应 CC {@code null} (非压缩状态, 序列化时输出 JSON {@code null})</li>
 * </ul>
 *
 * <p><b>WHY 严格 2 值</b> (CLAUDE.md 规则 7 显式暴露冲突): CC schema 用 zod
 * {@code z.union} 严格限制 2 值, Java 端 enum 不能扩展更多值, 否则会破坏 SDK consumer
 * 的 schema 校验. 不要增加 {@code READY} / {@code PROCESSING} 等值.
 *
 * <p><b>序列化约定</b> (Stage 3.3 react 对接时实施): {@code COMPACTING} → JSON
 * {@code "compacting"}, {@code NULL} → JSON {@code null} (与 CC z.null() 对齐).
 * Stage 3.2 阶段不写 {@code @JsonValue} 注解, 仅完成 setter 透传.
 *
 * <p><b>CC 调用方严格 1:1</b>:
 * <ol>
 *   <li>{@code services/compact/compact.ts:412} {@code setSDKStatus?.('compacting')}
 *       — {@code compactConversation} 入口</li>
 *   <li>{@code services/compact/compact.ts:761} {@code setSDKStatus?.(null)}
 *       — {@code compactConversation} finally</li>
 *   <li>{@code services/compact/compact.ts:817} {@code setSDKStatus?.('compacting')}
 *       — {@code partialCompactConversation} 入口</li>
 *   <li>{@code cli/print.ts:2201} {@code setSDKStatus: status => { output.enqueue({type: 'system', subtype: 'status', status, ...}) }}
 *       — SDK 模式发 status 事件</li>
 * </ol>
 *
 * <p><b>UI Integration</b> (用户 2026-07-28 决策): 本枚举供 Java 后端 SDK 模式使用,
 * 序列化由 web 前端 React 侧按 zod schema 解析.
 * Stage 3.3 阶段按需添加序列化方法.
 *
 * @see SpinnerMode
 * @see AppState
 * @see ToolUseContext#setSDKStatus()
 */
public enum SDKStatus {
    /** CC {@code 'compacting'} · 当前在压缩 (z.literal('compacting')). */
    COMPACTING,
    /** CC {@code null} · 非压缩状态 (z.null()). 序列化时输出 JSON null. */
    NULL
}