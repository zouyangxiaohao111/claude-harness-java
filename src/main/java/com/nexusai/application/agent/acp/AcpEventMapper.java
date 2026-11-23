package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACP {@code session/update} → 本仓流事件语义的映射器（纯函数，零 IO）。
 *
 * <p>映射表（影响面 C1）：
 * <ul>
 *   <li>{@code agent_message_chunk} → 正文增量（{@code MessageChunkEvent.delta}）</li>
 *   <li>{@code agent_thought_chunk} → 推理增量（{@code MessageChunkEvent.reasoning}）</li>
 *   <li>{@code tool_call}（pending/in_progress）→ 工具卡出现</li>
 *   <li>{@code tool_call_update}（completed/failed + content）→ 填入结果</li>
 *   <li>{@code usage_update} / {@code plan} / {@code config_option_update} / 未知 → 丢弃</li>
 * </ul>
 *
 * <p>⛔ 未知 update 必须<b>安全丢弃</b>：ACP 是演进中的协议，上游多加一种 update
 * 不该让整次委派失败。
 */
public final class AcpEventMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 映射结果类别。 */
    public enum Kind {
        /** 正文增量。 */
        CHUNK,
        /** 推理增量。 */
        REASONING,
        /** 工具调用开始（首次出现）。 */
        TOOL_CALL,
        /** 工具调用状态更新（完成 / 失败）。 */
        TOOL_RESULT,
        /** 不关心的 update（usage/plan/config/未知）。 */
        IGNORE
    }

    /**
     * 映射后的更新。
     *
     * @param kind       类别
     * @param toolCallId 工具调用 id（非工具类为 null）
     * @param toolName   工具显示名（标题）
     * @param arguments  工具入参（已转 Map）
     * @param text       正文/推理文本增量，或工具结果文本
     * @param isError    工具结果是否失败（仅 TOOL_RESULT）
     * @param status     工具状态原始值（pending/in_progress/completed/failed）
     */
    public record MappedUpdate(
            Kind kind,
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            String text,
            Boolean isError,
            String status
    ) {
        static MappedUpdate ignore() {
            return new MappedUpdate(Kind.IGNORE, null, null, null, null, null, null);
        }
    }

    private AcpEventMapper() {
    }

    /** 把一条 update 映射成内部事件；不认识的返回 {@link Kind#IGNORE}。 */
    public static MappedUpdate map(JsonNode update) {
        if (update == null || !update.isObject()) {
            return MappedUpdate.ignore();
        }
        String type = update.path("sessionUpdate").asText("");
        switch (type) {
            case "agent_message_chunk":
                return new MappedUpdate(Kind.CHUNK, null, null, null,
                        contentText(update.get("content")), null, null);
            case "agent_thought_chunk":
                return new MappedUpdate(Kind.REASONING, null, null, null,
                        contentText(update.get("content")), null, null);
            case "tool_call":
                return toolCall(update);
            case "tool_call_update":
                return toolCallUpdate(update);
            default:
                return MappedUpdate.ignore();
        }
    }

    private static MappedUpdate toolCall(JsonNode update) {
        String id = update.path("toolCallId").asText("");
        if (id.isBlank()) {
            return MappedUpdate.ignore();
        }
        return new MappedUpdate(Kind.TOOL_CALL, id, toolName(update),
                toMap(update.get("rawInput")), null, null,
                update.path("status").asText(null));
    }

    private static MappedUpdate toolCallUpdate(JsonNode update) {
        String id = update.path("toolCallId").asText("");
        if (id.isBlank()) {
            return MappedUpdate.ignore();
        }
        String status = update.path("status").asText("");
        // 只有终态才发 result；中间态（pending/in_progress）留在卡上不刷结果
        if (!"completed".equals(status) && !"failed".equals(status)) {
            return new MappedUpdate(Kind.IGNORE, id, toolName(update), null, null, null, status);
        }
        return new MappedUpdate(Kind.TOOL_RESULT, id, toolName(update),
                toMap(update.get("rawInput")), contentText(update.get("content")),
                "failed".equals(status), status);
    }

    private static String toolName(JsonNode update) {
        String title = update.path("title").asText("");
        if (!title.isBlank()) {
            return title;
        }
        String name = update.path("name").asText("");
        return name.isBlank() ? "acp-tool" : name;
    }

    /** 从 content 块取文本；支持 {@code {type:"text"}}、content 数组、content 包装。 */
    static String contentText(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                String part = contentText(block);
                if (part != null && !part.isEmpty()) {
                    sb.append(part);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        if (!content.isObject()) {
            return null;
        }
        String type = content.path("type").asText("");
        if ("text".equals(type)) {
            return content.path("text").asText(null);
        }
        if ("diff".equals(type)) {
            // diff 只把路径 + 新内容作为可见结果（前端没有 diff 渲染通道）
            String path = content.path("path").asText("");
            String newText = content.path("newText").asText("");
            return (path.isBlank() ? "" : path + "\n") + newText;
        }
        if ("content".equals(type) || content.has("content")) {
            return contentText(content.get("content"));
        }
        return null;
    }

    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = JSON.convertValue(node, Map.class);
            return map == null ? null : new LinkedHashMap<>(map);
        }
        Map<String, Object> single = new LinkedHashMap<>();
        if (node.isTextual()) {
            single.put("value", node.asText());
        } else if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(item.isTextual() ? item.asText() : item.toString()));
            single.put("args", list);
        } else {
            single.put("value", node.toString());
        }
        return single;
    }
}
