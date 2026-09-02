package com.nexusai.application.agent.tool;

/**
 * 工具决策归因记录 · 对齐 CC {@code toolUseContext.toolDecisions} map value.
 *
 * <p><b>[R32-b12 D-4 P0 必修]</b> CC 真源:
 * <pre>{@code
 * // Open-ClaudeCode/src/services/tools/toolExecution.ts:1741-1743
 * toolUseContext.toolDecisions = new Map()
 * toolUseContext.toolDecisions.set(toolUseID, { source, decision })
 * }</pre>
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@link #source} —— 决策来源（{@code "user_temporary"} / {@code "user_permanent"} /
 *       {@code "user_reject"} / {@code "rule"} / {@code "hook"} / {@code "config"} 等），
 *       由 {@code decisionReasonToOTelSource} 映射. 对齐 CC toolExecution.ts:207-250.</li>
 *   <li>{@link #decision} —— 决策行为 ({@code "accept"} / {@code "reject"}),
 *       由 {@code permissionDecision.behavior === 'allow' ? 'accept' : 'reject'} 映射.
 *       对齐 CC toolExecution.ts:299-300.</li>
 *   <li>{@link #timestamp} —— [Session H9] 决策时间戳. 对齐 CC
 *       {@code toolDecisions.set(toolUseID, {source, decision, timestamp})}
 *       (Open-ClaudeCode/src/hooks/toolPermission/permissionLogging.ts:224-228),
 *       由 {@link com.nexusai.application.agent.permission.PermissionDecisionLogger} 注入.</li>
 * </ul>
 *
 * <p>注入链路：
 * <ol>
 *   <li>LlmAgentLoop.applyPermissionFilter 在 Allow/Deny 分支调
 *       {@code Telemetry.recordToolDecision(callId, source, decision)}</li>
 *   <li>Telemetry 写入 {@code ToolUseContext.toolDecisions} (per-call map)</li>
 *   <li>StreamingToolExecutor 工具执行完成时，从 ctx.toolDecisions() 读 decisionInfo
 *       注入 {@code logOTelEvent('tool_result')} 的 decision_source / decision_type 字段</li>
 * </ol>
 *
 * <h2>不可变性</h2>
 * <p>compact constructor 校验非 null + 非 blank；record 默认不可变 ——
 * 工具执行期间 map value 不变 (匹配 CC toolUseContext.toolDecisions Map 行为).
 *
 * @see ToolUseContext#toolDecisions()
 * @since R32-b12
 */
public record ToolDecisionInfo(String source, String decision, long timestamp) {

    /**
     * 2 参便捷构造器 · 时间戳取当前时刻.
     *
     * <p>既有调用方 (emitCancelledTelemetry / injectDecisionInfo) 不关心时间戳,
     * 但 CC {@code toolDecisions.set(toolUseID, {source, decision, timestamp})}
     * (permissionLogging.ts:224-228) 要求 timestamp — 便捷构造器让旧调用自动补齐.
     */
    public ToolDecisionInfo(String source, String decision) {
        this(source, decision, System.currentTimeMillis());
    }

    public ToolDecisionInfo {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("ToolDecisionInfo.source is blank");
        }
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("ToolDecisionInfo.decision is blank");
        }
        if (!"accept".equals(decision) && !"reject".equals(decision)) {
            throw new IllegalArgumentException(
                "ToolDecisionInfo.decision must be 'accept' or 'reject', got: " + decision);
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("ToolDecisionInfo.timestamp must be positive, got: " + timestamp);
        }
    }
}