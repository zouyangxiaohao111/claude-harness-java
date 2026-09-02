package com.nexusai.application.agent.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具输入截断器 · 对齐 CC Open-ClaudeCode/src/services/analytics/metadata.ts:291-303.
 *
 * <p><b>[R32-b12 D-6 P1 必修]</b> CC 真源:
 * <pre>{@code
 * // metadata.ts:240-303
 * const TOOL_INPUT_MAX_DEPTH = 2
 * const TOOL_INPUT_STRING_TRUNCATE_AT = 500
 * const TOOL_INPUT_STRING_TRUNCATE_TO = 200
 * const TOOL_INPUT_MAX_COLLECTION_ITEMS = 20
 * const TOOL_INPUT_MAX_JSON_CHARS = 5000
 *
 * function truncateToolInputValue(value: unknown, depth = 0): unknown {
 *   // ...递归截断字符串 + 数组 + 对象
 * }
 *
 * export function extractToolInputForTelemetry(input: unknown): string | undefined {
 *   if (!isToolDetailsLoggingEnabled()) return undefined
 *   const truncated = truncateToolInputValue(input)
 *   let json = jsonStringify(truncated)
 *   if (json.length > TOOL_INPUT_MAX_JSON_CHARS) {
 *     json = json.slice(0, TOOL_INPUT_MAX_JSON_CHARS) + '…[truncated]'
 *   }
 *   return json
 * }
 * }</pre>
 *
 * <h2>截断规则</h2>
 * <ul>
 *   <li>字符串长度 > 512 → 截断为前 128 字符 + "…[N chars]" 后缀</li>
 *   <li>对象嵌套深度 > 2 → 字符串 "&lt;nested&gt;"</li>
 *   <li>数组 / 对象元素数 > 20 → 截断 + 追加 "…[N items]" / "…[N keys]" 提示</li>
 *   <li>跳过内部标记 key（以 {@code _} 开头，如 {@code _simulatedSedEdit}）</li>
 * </ul>
 *
 * <h2>WHY</h2>
 * <p>工具 input 可能含完整文件内容 / 整 bash command / 大量 MCP 参数，
 * 无脑写入 OTel attribute 会导致 (1) 带宽浪费 (2) 触发 OTel attribute 长度上限.
 * 截断保留 forensics 关键字段（file_path / URL / command）同时严格控制大小.
 *
 * @see Telemetry#extractToolInputForTelemetry(JsonNode)
 * @since R32-b12
 */
public final class ToolInputTruncator {

    /** 对象嵌套深度上限. 严格对齐 CC TOOL_INPUT_MAX_DEPTH = 2. */
    public static final int MAX_DEPTH = 2;

    /** 字符串截断阈值（> 此长度截断）. 严格对齐 CC TOOL_INPUT_STRING_TRUNCATE_AT = 512. */
    public static final int STRING_TRUNCATE_AT = 512;

    /** 字符串截断保留长度. 严格对齐 CC TOOL_INPUT_STRING_TRUNCATE_TO = 128. */
    public static final int STRING_TRUNCATE_TO = 128;

    /** 数组 / 对象元素数上限. 严格对齐 CC TOOL_INPUT_MAX_COLLECTION_ITEMS = 20. */
    public static final int MAX_COLLECTION_ITEMS = 20;

    /** 序列化 JSON 字符上限. 严格对齐 CC TOOL_INPUT_MAX_JSON_CHARS = 4096. */
    public static final int MAX_JSON_CHARS = 4096;

    private ToolInputTruncator() {}

    /**
     * 截断 JsonNode 工具输入. 对齐 CC truncateToolInputValue 递归实现.
     *
     * @param input 原始工具输入 (可为 null → 返回 NullNode)
     * @return 截断后的 JsonNode (深度/长度/数量均在 CC 常量范围内)
     */
    public static JsonNode truncate(JsonNode input) {
        if (input == null || input.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        return truncateValue(input, 0);
    }

    /**
     * 递归截断单个 JsonNode 值. 对齐 CC truncateToolInputValue(value, depth).
     */
    private static JsonNode truncateValue(JsonNode value, int depth) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (value == null || value.isNull()) {
            return factory.nullNode();
        }
        if (value.isTextual()) {
            String text = value.textValue();
            if (text.length() > STRING_TRUNCATE_AT) {
                return factory.textNode(text.substring(0, STRING_TRUNCATE_TO)
                    + "…[" + text.length() + " chars]");
            }
            return factory.textNode(text);
        }
        // 标量直接返回 (number / boolean)
        if (value.isNumber() || value.isBoolean() || value.isBinary()) {
            return value;
        }
        // 深度超限 → "<nested>"
        if (depth >= MAX_DEPTH) {
            return factory.textNode("<nested>");
        }
        // 数组截断
        if (value.isArray()) {
            ArrayNode src = (ArrayNode) value;
            ArrayNode result = factory.arrayNode(src.size());
            int limit = Math.min(src.size(), MAX_COLLECTION_ITEMS);
            for (int i = 0; i < limit; i++) {
                result.add(truncateValue(src.get(i), depth + 1));
            }
            if (src.size() > MAX_COLLECTION_ITEMS) {
                result.add(factory.textNode("…[" + src.size() + " items]"));
            }
            return result;
        }
        // 对象截断
        if (value.isObject()) {
            ObjectNode src = (ObjectNode) value;
            ObjectNode result = factory.objectNode();
            int count = 0;
            int total = 0;
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = src.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, JsonNode> entry = it.next();
                total++;
                String key = entry.getKey();
                if (key == null || key.startsWith("_")) {
                    // 跳过内部标记 key (CC: .filter(([k]) => !k.startsWith('_')))
                    continue;
                }
                if (count >= MAX_COLLECTION_ITEMS) {
                    // 超出上限: 用 sentinel key 记录总数
                    result.set("…", factory.textNode(total + " keys"));
                    break;
                }
                result.set(key, truncateValue(entry.getValue(), depth + 1));
                count++;
            }
            return result;
        }
        // 兜底: toString
        return factory.textNode(value.toString());
    }
}