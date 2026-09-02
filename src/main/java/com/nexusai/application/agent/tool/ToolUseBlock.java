package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM 发出的工具调用请求 · 对齐 CC {@code ToolUseBlock}（Anthropic SDK）。
 *
 * <p>由 {@link com.nexusai.infra.llm.LlmProvider} 在解析流式响应时生成，
 * 由 {@link Tool#execute} 消费。
 *
 * @param id     LLM 给本次调用的唯一 ID（用于把结果回传对应）
 * @param name   工具名（与 {@link ToolRegistry} 中的 key 对应）
 * @param input  工具输入（已解析的 JSON 对象，遵循 {@link Tool#inputSchema}）
 */
public record ToolUseBlock(String id, String name, JsonNode input) {

    public ToolUseBlock {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ToolUseBlock.id is blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolUseBlock.name is blank");
        }
        if (input == null) {
            throw new IllegalArgumentException("ToolUseBlock.input is null");
        }
    }
}
