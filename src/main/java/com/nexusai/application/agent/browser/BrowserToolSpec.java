package com.nexusai.application.agent.browser;

/**
 * 单个 nexusai-in-chrome 浏览器工具定义 · 对齐 CCB {@code browserTools.ts} BROWSER_TOOLS 数组元素。
 *
 * <p>工具全名 = {@link BrowserToolRegistry#TOOL_PREFIX} + {@link #toolName()}（如
 * {@code mcp__nexusai-in-chrome__read_page}）。description 与 inputSchema 逐字对齐 CCB，
 * 供 {@link BrowserMcpTool} 直接消费。
 *
 * @param toolName          工具原名（无前缀，如 {@code "read_page"}；对齐 CCB browserTools.ts name）
 * @param description       工具描述（逐字对齐 CCB browserTools.ts description）
 * @param inputSchemaJson   inputSchema JSON Schema 文本（逐字对齐 CCB browserTools.ts inputSchema，
 *                          required/enum/description/type 全保留，不增删）
 * @param ccRef             CCB 行号引用（如 {@code "browserTools.ts:2-26"}，审计复验用）
 * @param readOnly          是否只读（读类工具 read_page/find/get_page_text/read_console/read_network
 *                          为 true；写类 computer/form_input/navigate/upload 等为 false）
 * @param concurrencySafe   是否并发安全（读类工具 true；写类 false）
 */
public record BrowserToolSpec(
        String toolName,
        String description,
        String inputSchemaJson,
        String ccRef,
        boolean readOnly,
        boolean concurrencySafe) {

    /**
     * compact constructor：name/description/schema 均必填非空（防注册定义漂移，fail fast）。
     */
    public BrowserToolSpec {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("BrowserToolSpec.toolName is blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("BrowserToolSpec.description is blank: " + toolName);
        }
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            throw new IllegalArgumentException("BrowserToolSpec.inputSchemaJson is blank: " + toolName);
        }
        if (ccRef == null || ccRef.isBlank()) {
            throw new IllegalArgumentException("BrowserToolSpec.ccRef is blank: " + toolName);
        }
    }
}
