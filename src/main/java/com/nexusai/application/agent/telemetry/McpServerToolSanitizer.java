package com.nexusai.application.agent.telemetry;

/**
 * [P-AL-05 D-1] MCP server tool 名称脱敏器 · 对齐 CC sanitizeToolNameForAnalytics
 * (Open-ClaudeCode/src/services/analytics/metadata.ts:70-77).
 *
 * <p>CC 真源行为（当次 read 自验 metadata.ts:59-77）:
 * <pre>{@code
 * export function sanitizeToolNameForAnalytics(toolName: string) {
 *   if (toolName.startsWith('mcp__')) {
 *     return 'mcp_tool'
 *   }
 *   return toolName
 * }
 * }</pre>
 *
 * <p><b>WHY（PII 防护）</b>: MCP 工具名形如 {@code mcp__<server>__<tool>}，server alias
 * 可能暴露用户特定配置（IP / 路径 / 凭据），CC 视为 PII-medium（metadata.ts:59-68
 * javadoc 原文）——telemetry 侧把整个 {@code mcp__*} 名称遮蔽为字面量 {@code mcp_tool}；
 * 内置工具名（Bash / Read / Write 等）无 PII 风险，原样返回。
 *
 * <p><b>值语义历史修正（P-CC-03 D-1 拍板 · P-AL-05 执行）</b>: R32-b12 Fix-v3 曾按
 * "片段脱敏"实现（IP/路径/凭据/邮箱 pattern 局部替换、保留 {@code mcp__} 主体），
 * javadoc 声称对齐 CC sanitizeMcpServerTool.ts —— 该文件在 CC 基线 74923950 下
 * 不存在（git ls-tree + FS 双重自验：src/utils 无 sanitize 文件），且与 CC 实际
 * analytics 脱敏器（metadata.ts:70-77 整体遮蔽）值语义相悖。旧 pattern 实现已整段
 * 删除，不留兼容壳；测试断言同步修正为 CC 值语义
 * （R32B12_TelemetryTest / StreamingToolExecutorDeferredSchemaTelemetryTest）。
 *
 * <p><b>注意</b>: {@code toolUseID} / {@code use_id} 等字段不应用本方法（CC 不脱敏
 * use id，toolExecution.ts:1676 {@code use_id: toolUseID} 原样透传）；对 UUID 形态的
 * use id 本方法恒原样返回，无副作用。
 *
 * @since R32-b12 Fix-v3 / P-AL-05 值语义修正 (CC metadata.ts:70-77)
 */
public final class McpServerToolSanitizer {

    /** CC 遮蔽字面量 (metadata.ts:74 {@code return 'mcp_tool'})。 */
    public static final String MCP_TOOL_PLACEHOLDER = "mcp_tool";

    /**
     * 私有构造: 静态工具类, 不允许实例化.
     */
    private McpServerToolSanitizer() {
        throw new AssertionError("McpServerToolSanitizer 是静态工具类, 不允许实例化");
    }

    /**
     * 脱敏 MCP server tool 名称 · CC sanitizeToolNameForAnalytics 值语义。
     *
     * <p>逻辑:
     * <ol>
     *   <li>null / blank → 原样返回（防御；CC toolName 恒为字符串，无此场景）</li>
     *   <li>{@code mcp__} 前缀 → 字面量 {@code mcp_tool}（CC metadata.ts:73-75 整体遮蔽）</li>
     *   <li>其他 → 原样返回（内置工具名保留，CC metadata.ts:76）</li>
     * </ol>
     *
     * @param raw 原始工具名 (e.g. {@code mcp__github__create_issue} / {@code Bash})
     * @return 脱敏后的名称 ({@code mcp__*} → {@code mcp_tool}, 其他原样)
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (raw.startsWith("mcp__")) {
            return MCP_TOOL_PLACEHOLDER;
        }
        return raw;
    }
}
