package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Anthropic Messages API 的 {@code tool_result} 块参数 · 对齐
 * {@code @anthropic-ai/sdk/resources/messages/messages.mjs} 的 {@code ToolResultBlockParam}
 * + CC 端 {@code mapToolResultToToolResultBlockParam} 契约。
 *
 * <p>序列化为：
 * <pre>
 * { "type": "tool_result", "tool_use_id": "...", "content": "..." }
 * </pre>
 * 或（多块）：
 * <pre>
 * { "type": "tool_result", "tool_use_id": "...", "content": [{type:"text",...},{type:"image",...}] }
 * </pre>
 *
 * <p>{@code content} 形态契约（对齐 Anthropic SDK union）：
 * <ul>
 *   <li><b>字符串</b> — 纯文本（{@link #textContent(String)}）</li>
 *   <li><b>{@code List<ContentBlockParam>}</b> — 多块（{@link #blocksContent(List)}），
 *       可包含 text/image/document</li>
 * </ul>
 *
 * <p>本类用 {@code Object} 承载 content（与 Anthropic SDK 的 union 等价）—— JSON
 * 序列化输出完全一致；调用方按需 cast。
 *
 * @param toolUseId 对应的 {@code tool_use.id}（必填）
 * @param type      固定为 {@code "tool_result"}
 * @param content   字符串 或 {@code List<ContentBlockParam>}
 * @param isError   是否错误（true → LLM 看到错误，可重试；与 {@code ToolResult.isError()} 对齐）
 */
public record ToolResultBlockParam(
        @JsonProperty("tool_use_id") String toolUseId,
        @JsonProperty("type") String type,
        @JsonProperty("content") Object content,
        @JsonProperty("is_error") boolean isError) {

    public ToolResultBlockParam {
        if (toolUseId == null || toolUseId.isBlank()) {
            throw new IllegalArgumentException("ToolResultBlockParam.toolUseId is blank");
        }
        if (!"tool_result".equals(type)) {
            throw new IllegalArgumentException(
                "ToolResultBlockParam.type must be \"tool_result\", got: " + type);
        }
        if (content == null) {
            throw new IllegalArgumentException("ToolResultBlockParam.content is null");
        }
        if (!(content instanceof String) && !(content instanceof List<?>)) {
            throw new IllegalArgumentException(
                "ToolResultBlockParam.content must be String or List<ContentBlockParam>, got: "
                    + content.getClass().getName());
        }
    }

    /** 工厂: 文本内容（最常见）。 */
    public static ToolResultBlockParam textContent(String toolUseId, String text) {
        return new ToolResultBlockParam(toolUseId, "tool_result",
            text == null ? "" : text, false);
    }

    /** 工厂: 多块内容（image / document / text 混合）。 */
    public static ToolResultBlockParam blocksContent(
            String toolUseId, List<ContentBlockParam> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("blocks must be non-empty");
        }
        return new ToolResultBlockParam(toolUseId, "tool_result",
            List.copyOf(blocks), false);
    }

    /** 工厂: 错误结果（is_error=true）。 */
    public static ToolResultBlockParam errorContent(String toolUseId, String message) {
        return new ToolResultBlockParam(toolUseId, "tool_result",
            message == null ? "unknown error" : message, true);
    }
}