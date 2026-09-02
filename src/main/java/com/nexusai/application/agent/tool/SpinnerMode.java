package com.nexusai.application.agent.tool;

/**
 * Spinner 模式枚举 · 对齐 CC {@code Open-ClaudeCode/src/components/Spinner/types.ts} {@code SpinnerMode}.
 *
 * <p><b>CC 真源</b> (逐字面量 grep 自验, 见探查 EV-H3-022):
 * <ul>
 *   <li>{@code 'requesting'} — 发送 LLM 请求中 ({@code services/compact/compact.ts:427} 等)</li>
 *   <li>{@code 'responding'} — 接收 LLM 响应中 / 初始态 ({@code services/compact/compact.ts:1340}、
 *       {@code screens/REPL.tsx:838} {@code useState<SpinnerMode>('responding')})</li>
 * </ul>
 *
 * <p><b>已收敛项 (IMP-H4)</b>: {@code 'compacting'} 经逐字面量 grep 自验属 {@link SDKStatus}
 * (CC {@code services/compact/compact.ts:412/817} {@code setSDKStatus('compacting')}),
 * 不属 SpinnerMode, 已移除; {@code 'idle'} 在 CC spinner/streamMode 语境全仓无证据, 已移除.
 * Java 端仅保留 CC 已证字面量 REQUESTING/RESPONDING (CLAUDE.md 规则 7 显式择一, 不保留猜测值).
 *
 * <p><b>序列化约定</b>: {@code name().toLowerCase()} → CC 字面量 1:1 对齐.
 *
 * @see SDKStatus
 * @see AppState
 * @see ToolUseContext#setStreamMode()
 */
public enum SpinnerMode {
    /** CC {@code 'requesting'} · 发送 LLM 请求中. */
    REQUESTING,
    /** CC {@code 'responding'} · 接收 LLM 响应中 (REPL.tsx:838 初始态). */
    RESPONDING
}
