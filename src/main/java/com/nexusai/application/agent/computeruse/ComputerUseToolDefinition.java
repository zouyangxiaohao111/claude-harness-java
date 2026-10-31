package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * computer-use 的一个 MCP 工具定义 · 对齐 CCB {@code packages/@ant/computer-use-mcp/src/types.ts}
 * 的 MCP {@code Tool}（只有 name / description / inputSchema 三键）。
 *
 * @param name        工具名（如 {@code left_click}）—— 完整 MCP 名由
 *                    {@code McpStringUtils.buildMcpToolName(server, tool)} 生成
 * @param description 面向模型的描述（CCB 原文）
 * @param inputSchema 输入 JSON Schema（{@code inputSchema.type} 恒 {@code "object"}）
 */
public record ComputerUseToolDefinition(String name, String description, JsonNode inputSchema) {
}
