package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 工具引用块 · 对齐 Anthropic {@code ToolReferenceBlockParam}（CC
 * {@code ToolSearchTool.ts:465-468}：ToolSearch 命中后输出
 * {@code {type:'tool_reference', tool_name: name}} 块数组）。
 *
 * <p>CC 端 mapToolResultToToolResultBlockParam 在 {@code matches} 非空时构造
 * {@code content: content.matches.map(name => ({type:'tool_reference', tool_name: name}))}
 * （ToolSearchTool.ts:462-469）；Java 侧等价经 {@link ContentBlockParam} 块数组注入
 * {@link ToolResultBlockParam} 的 content。
 *
 * <p>用途说明（CC ToolSearchTool.ts:439-443）：该格式在 1P/Foundry 上可被客户端展开为
 * 完整工具 schema；Bedrock/Vertex 可能暂不支持客户端侧 tool_reference 展开。
 *
 * @param type     固定 {@code "tool_reference"}
 * @param toolName 被引用工具名 · CC original: {@code tool_name} (ToolSearchTool.ts:467)
 */
public record ToolReferenceBlockParam(
        @JsonProperty("type") String type,
        @JsonProperty("tool_name") String toolName) implements ContentBlockParam {

    public ToolReferenceBlockParam {
        if (!"tool_reference".equals(type)) {
            throw new IllegalArgumentException(
                "ToolReferenceBlockParam.type must be \"tool_reference\", got: " + type);
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("ToolReferenceBlockParam.toolName is blank");
        }
    }

    /** 工厂: 从工具名构造（type 字段固定为 "tool_reference"）。 */
    public ToolReferenceBlockParam(String toolName) {
        this("tool_reference", toolName);
    }
}
