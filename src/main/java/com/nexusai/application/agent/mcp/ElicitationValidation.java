package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Elicitation 输入校验 · 对齐 CC utils/mcp/elicitationValidation.ts（337 行，全链移植）。
 *
 * <p>L1 语义（CC 全链 :15-336）：schema（PrimitiveSchemaDefinition，@modelcontextprotocol/sdk/types.js）
 * → zod v4 等价校验 → ValidationResult。Java 侧 schema 以 Jackson {@link JsonNode} 承载
 * （MCP tools/list inputSchema 同构），zod 语义按 zod v4 文档化映射（见 {@code getZodSchema} 等价说明）。
 *
 * <p>L2 契约（5 Release Gate）：
 * <ul>
 *   <li><b>A1</b>: isEnumSchema/isMultiSelectEnumSchema/getMultiSelectValues/Labels/Label/
 *       getEnumValues/Labels/Label/getFormatHint/isDateTimeSchema（CC :43-133/:258-301 静态工具）+
 *       validateElicitationInput（:225-243）+ validateElicitationInputAsync（:307-336 实例）。</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 表单输入 + schema → zod 等价校验：
 *       string min/max/email/uri/date/date-time 消息字面量照抄 CC :146-177；
 *       number/integer coerce+int+min/max 区间消息 rangeMsg（:191-198，formatNum .0 语义）；
 *       boolean coerce（zod v4 字符串映射）；enum 值集合校验；空 enum → 恒失败（z.never 等价）；
 *       非法 schema 类型 → throw（CC :222）。</li>
 *   <li><b>A3</b>: async 路径（:307-336）：sync 校验通过 → 直接返回；schema 为 date/date-time
 *       且值非 ISO 8601（looksLikeISO8601，dateTimeParser.ts:117-121）→
 *       {@link DateTimeParser#parseNaturalLanguageDateTime(String, DateTimeParser.Format)}
 *       慢路径（LLM）→ success 则复验 parse 结果（ISO）→ 通过返回；否则回落 syncResult。
 *       —— 即 R2-05 X-1『dateTimeParser 0 消费方』的接线消费点（前端弹窗 T-02 联动）。</li>
 *   <li><b>A4</b>: null schema/值 → 不抛（boolean 语义）；未知 format → 无格式校验（CC :178-180）；
 *       DateTimeParser 未注入（@Autowired(required=false)）→ async 回落 syncResult 不 NPE。</li>
 *   <li><b>A5</b>: 真实场景 — 前端 elicitation 弹窗（待实现.md T-02）输入日期「明天下午3点」
 *       经 async 慢路径解析为 ISO 后复验通过。</li>
 * </ul>
 *
 * <p>L3（Java idiom / CC 偏差登记）：
 * <ul>
 *   <li>CC zod v4（node_modules 源码不在仓库，无法逐字核对）→ 按 zod v4 文档化映射实现，
 *       消息文本全部取 CC 字符串字面量，测试锁定（ElicitationValidationTest）；boolean coerce
 *       采用 v4 字符串映射（'true'/'1'/'on' → true，'false'/'0'/'off' → false，其余失败）。</li>
 *   <li>CC {@code validateElicitationInputAsync(stringValue, schema, signal)} 的 AbortSignal 参数
 *       （:310）在 Java 侧不暴露 —— DateTimeParser 内部 queryOptions 自建 AbortController
 *       （DateTimeParser.java:200-209），Java 无调用方取消语义，javadoc 登记为受控适配。</li>
 *   <li>ValidationResult.value（CC string|number|boolean 联合）→ JsonNode（TextNode/DoubleNode/BooleanNode）。</li>
 * </ul>
 */
@Component
public class ElicitationValidation {

    private static final Logger log = LoggerFactory.getLogger(ElicitationValidation.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC ValidationResult（elicitationValidation.ts:15-19）· value 可空（失败时无值）。 */
    public record ValidationResult(JsonNode value, boolean isValid, String error) {
        public static ValidationResult ok(JsonNode value) {
            return new ValidationResult(value, true, null);
        }
        public static ValidationResult err(String error) {
            return new ValidationResult(null, false, error);
        }
    }

    /** CC STRING_FORMATS（:21-38）· getFormatHint 的 description/example 常量表。 */
    private static final java.util.Map<String, String[]> STRING_FORMATS = java.util.Map.of(
        "email", new String[]{"email address", "user@example.com"},
        "uri", new String[]{"URI", "https://example.com"},
        "date", new String[]{"date", "2024-03-15"},
        "date-time", new String[]{"date-time", "2024-03-15T14:30:00Z"});

    /** zod v4 email 近似 regex（RFC 5322 简化，宽松语义同 zod）。 */
    private static final Pattern EMAIL_RE = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    /** zod v4 date（:167-169）：YYYY-MM-DD 格式校验。 */
    private static final Pattern DATE_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    /** zod v4 datetime({offset:true})（:171-177）：ISO 8601 + Z 或 ±HH:MM 时区偏移。 */
    private static final Pattern DATETIME_RE =
        Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$");

    private final DateTimeParser dateTimeParser;

    /** Spring 构造 · DateTimeParser 可选注入（未装配 → async 回落 syncResult，不 NPE）。 */
    @Autowired
    public ElicitationValidation(@Autowired(required = false) DateTimeParser dateTimeParser) {
        this.dateTimeParser = dateTimeParser;
    }

    /** 测试/直构 · 无 DateTimeParser（async 回落 syncResult）。 */
    public ElicitationValidation() {
        this(null);
    }

    // ═══════════ 枚举 schema 判别与取值（CC :43-133）═══════════

    /** CC isEnumSchema（:43-47）：type=string 且含 enum 或 oneOf。 */
    public static boolean isEnumSchema(JsonNode schema) {
        return schema != null
            && "string".equals(schema.path("type").asText())
            && (schema.has("enum") || schema.has("oneOf"));
    }

    /** CC isMultiSelectEnumSchema（:52-62）：type=array 且 items 含 enum 或 anyOf。 */
    public static boolean isMultiSelectEnumSchema(JsonNode schema) {
        if (schema == null || !"array".equals(schema.path("type").asText())) return false;
        JsonNode items = schema.path("items");
        return items.isObject() && (items.has("enum") || items.has("anyOf"));
    }

    /** CC getMultiSelectValues（:67-75）：anyOf → const 列表；enum → 原样列表。 */
    public static List<String> getMultiSelectValues(JsonNode schema) {
        JsonNode items = schema.path("items");
        if (items.has("anyOf")) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : items.path("anyOf")) {
                result.add(item.path("const").asText(""));
            }
            return result;
        }
        if (items.has("enum")) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : items.path("enum")) {
                result.add(item.asText(""));
            }
            return result;
        }
        return List.of();
    }

    /** CC getMultiSelectLabels（:80-88）：anyOf → title 列表；enum → 原样。 */
    public static List<String> getMultiSelectLabels(JsonNode schema) {
        JsonNode items = schema.path("items");
        if (items.has("anyOf")) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : items.path("anyOf")) {
                result.add(item.path("title").asText(""));
            }
            return result;
        }
        if (items.has("enum")) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : items.path("enum")) {
                result.add(item.asText(""));
            }
            return result;
        }
        return List.of();
    }

    /** CC getMultiSelectLabel（:93-99）：按值查标签，未命中回退原值。 */
    public static String getMultiSelectLabel(JsonNode schema, String value) {
        int index = getMultiSelectValues(schema).indexOf(value);
        if (index < 0) return value;
        List<String> labels = getMultiSelectLabels(schema);
        return index < labels.size() ? labels.get(index) : value;
    }

    /** CC getEnumValues（:104-112）：oneOf → const 列表；enum → 原样列表。 */
    public static List<String> getEnumValues(JsonNode schema) {
        if (schema.has("oneOf")) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : schema.path("oneOf")) {
                result.add(item.path("const").asText(""));
            }
            return result;
        }
        if (schema.has("enum")) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : schema.path("enum")) {
                result.add(item.asText(""));
            }
            return result;
        }
        return List.of();
    }

    /** CC getEnumLabels（:117-125）：oneOf → title 列表；enum → enumNames ?? enum。 */
    public static List<String> getEnumLabels(JsonNode schema) {
        if (schema.has("oneOf")) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : schema.path("oneOf")) {
                result.add(item.path("title").asText(""));
            }
            return result;
        }
        if (schema.has("enum")) {
            if (schema.has("enumNames")) {
                List<String> result = new ArrayList<>();
                for (JsonNode item : schema.path("enumNames")) {
                    result.add(item.asText(""));
                }
                return result;
            }
            return getEnumValues(schema);
        }
        return List.of();
    }

    /** CC getEnumLabel（:130-133）：按值查标签，未命中回退原值。 */
    public static String getEnumLabel(JsonNode schema, String value) {
        int index = getEnumValues(schema).indexOf(value);
        if (index < 0) return value;
        List<String> labels = getEnumLabels(schema);
        return index < labels.size() ? labels.get(index) : value;
    }

    // ═══════════ format 提示（CC :258-288）═══════════

    /**
     * CC getFormatHint（:258-288）· string format → 「description, e.g. example」；
     * number/integer → 区间提示（formatNum .0 语义同 :272-273）；其余 → undefined。
     */
    public static String getFormatHint(JsonNode schema) {
        if (schema == null) return null;
        String type = schema.path("type").asText();
        if ("string".equals(type)) {
            JsonNode format = schema.path("format");
            if (!format.isTextual()) return null;
            String[] pair = STRING_FORMATS.get(format.asText());
            if (pair == null) return null;
            return pair[0] + ", e.g. " + pair[1];
        }
        if ("number".equals(type) || "integer".equals(type)) {
            boolean isInteger = "integer".equals(type);
            JsonNode minimum = schema.path("minimum");
            JsonNode maximum = schema.path("maximum");
            if (!minimum.isMissingNode() && !maximum.isMissingNode()) {
                return "(" + type + " between " + formatNumber(minimum.asDouble(), isInteger)
                    + " and " + formatNumber(maximum.asDouble(), isInteger) + ")";
            } else if (!minimum.isMissingNode()) {
                return "(" + type + " >= " + formatNumber(minimum.asDouble(), isInteger) + ")";
            } else if (!maximum.isMissingNode()) {
                return "(" + type + " <= " + formatNumber(maximum.asDouble(), isInteger) + ")";
            } else {
                return "(" + type + ", e.g. " + (isInteger ? "42" : "3.14") + ")";
            }
        }
        return null;
    }

    private static String formatDouble(double d) {
        if (d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    // ═══════════ date-time schema 判别（CC :293-301）═══════════

    /** CC formatNum（:187-188/:272-273）· number 类型且值为整数 → 「N.0」，integer 恒整数格式。 */
    private static String formatNumber(double n, boolean isInteger) {
        if (n == Math.rint(n) && !isInteger) {
            return formatDouble(n) + ".0";
        }
        return formatDouble(n);
    }

    /** CC isDateTimeSchema（:293-301）：type=string 且 format 为 date 或 date-time。 */
    public static boolean isDateTimeSchema(JsonNode schema) {
        if (schema == null || !"string".equals(schema.path("type").asText())) return false;
        String format = schema.path("format").asText("");
        return "date".equals(format) || "date-time".equals(format);
    }

    // ═══════════ 同步校验（CC :225-243 + getZodSchema :135-223 zod v4 等价）═══════════

    /**
     * CC validateElicitationInput（:225-243）· zod 等价校验（safeParse 语义）。
     *
     * <p>错误消息与 zod v4 等价映射：
     * <ul>
     *   <li>string min/max → 「Must be at least/most N character(s)」（CC :146-153，plural 语义）</li>
     *   <li>email/uri/date/date-time 格式消息字面量照抄 CC :156-177（datetime offset:true）</li>
     *   <li>number/integer → coerce（zod Number() 语义）+ int + min/max，rangeMsg :191-198
     *       （formatNum：number 类型整数 minimum 输出「N.0」）</li>
     *   <li>boolean → coerce（zod v4 字符串映射：true/1/on ↔ false/0/off）</li>
     *   <li>enum → 值集合校验；空 enum → 恒失败（z.never 等价，:138-140）</li>
     *   <li>其余类型 → throw IllegalArgumentException（CC :222 Unsupported schema 消息）</li>
     * </ul>
     * 失败 error = 各 issue message 以「; 」拼接（CC :241）。
     *
     * @param stringValue 表单输入（zod safeParse 的输入恒为字符串，CC :230）
     * @param schema      PrimitiveSchemaDefinition（JsonNode 形态）
     * @return ValidationResult；value 为转换后的原始值（string/number/boolean 节点）
     */
    public ValidationResult validateElicitationInput(String stringValue, JsonNode schema) {
        List<String> issues = new ArrayList<>();
        JsonNode value = validateAgainstSchema(stringValue, schema, issues);
        if (value != null) {
            return ValidationResult.ok(value);
        }
        return ValidationResult.err(String.join("; ", issues));
    }

    /**
     * getZodSchema 等价（CC :135-223）· 校验失败收集 message 到 issues 并返回 null，
     * 成功返回转换后值节点。string 未命中格式时不收集（CC :178-180 无校验）。
     */
    private static JsonNode validateAgainstSchema(String input, JsonNode schema, List<String> issues) {
        if (schema == null) {
            throw new IllegalArgumentException("Unsupported schema: null");
        }
        if (isEnumSchema(schema)) {
            List<String> values = getEnumValues(schema);
            if (values.isEmpty()) {
                // CC :138-140 空 enum → z.never() 恒失败
                issues.add("Invalid input: expected never, received string");
                return null;
            }
            if (values.contains(input)) {
                return TextNode.valueOf(input);
            }
            issues.add("Invalid enum value. Expected " + String.join(" | ", values) + ", received " + input);
            return null;
        }
        String type = schema.path("type").asText("");
        if ("string".equals(type)) {
            JsonNode minLen = schema.path("minLength");
            JsonNode maxLen = schema.path("maxLength");
            if (!minLen.isMissingNode() && input.length() < minLen.asInt()) {
                issues.add("Must be at least " + minLen.asInt() + " " + plural(minLen.asInt(), "character"));
            }
            if (!maxLen.isMissingNode() && input.length() > maxLen.asInt()) {
                issues.add("Must be at most " + maxLen.asInt() + " " + plural(maxLen.asInt(), "character"));
            }
            String format = schema.path("format").asText("");
            switch (format) {
                case "email" -> {
                    if (!EMAIL_RE.matcher(input).matches()) {
                        issues.add("Must be a valid email address, e.g. user@example.com");
                    }
                }
                case "uri" -> {
                    if (!isValidUri(input)) {
                        issues.add("Must be a valid URI, e.g. https://example.com");
                    }
                }
                case "date" -> {
                    if (!DATE_RE.matcher(input).matches()) {
                        issues.add("Must be a valid date, e.g. 2024-03-15, today, next Monday");
                    }
                }
                case "date-time" -> {
                    if (!DATETIME_RE.matcher(input).matches()) {
                        issues.add("Must be a valid date-time, e.g. 2024-03-15T14:30:00Z, tomorrow at 3pm");
                    }
                }
                default -> {
                    // CC :178-180 无特定格式校验
                }
            }
            return issues.isEmpty() ? TextNode.valueOf(input) : null;
        }
            if ("number".equals(type) || "integer".equals(type)) {
                boolean isInteger = "integer".equals(type);
            String typeLabel = isInteger ? "an integer" : "a number";
            JsonNode minimum = schema.path("minimum");
            JsonNode maximum = schema.path("maximum");
            String rangeMsg;
            if (!minimum.isMissingNode() && !maximum.isMissingNode()) {
                rangeMsg = "Must be " + typeLabel + " between " + formatNumber(minimum.asDouble(), isInteger)
                    + " and " + formatNumber(maximum.asDouble(), isInteger);
            } else if (!minimum.isMissingNode()) {
                rangeMsg = "Must be " + typeLabel + " >= " + formatNumber(minimum.asDouble(), isInteger);
            } else if (!maximum.isMissingNode()) {
                rangeMsg = "Must be " + typeLabel + " <= " + formatNumber(maximum.asDouble(), isInteger);
            } else {
                rangeMsg = "Must be " + typeLabel;
            }

            // z.coerce.number 等价（CC :200-202）：zod Number() 语义，空串 → 0，不可解析 → error
            double num;
            String trimmed = input == null ? "" : input.trim();
            if (trimmed.isEmpty()) {
                num = 0d;
            } else {
                try {
                    num = Double.parseDouble(trimmed);
                } catch (NumberFormatException e) {
                    issues.add(rangeMsg);
                    return null;
                }
            }
            if (isInteger && num != Math.rint(num)) {
                issues.add(rangeMsg); // z.number().int（CC :203-205）
            }
            if (!minimum.isMissingNode() && num < minimum.asDouble()) {
                issues.add(rangeMsg); // z.number().min（CC :206-210）
            }
            if (!maximum.isMissingNode() && num > maximum.asDouble()) {
                issues.add(rangeMsg); // z.number().max（CC :211-215）
            }
            return issues.isEmpty() ? DoubleNode.valueOf(num) : null;
        }
        if ("boolean".equals(type)) {
            // z.coerce.boolean 等价（CC :218-219）· zod v4 字符串映射（v3 的 Boolean() 坑已修复）
            Boolean b = coerceBoolean(input);
            if (b == null) {
                issues.add("Invalid input: expected boolean, received string");
                return null;
            }
            return BooleanNode.valueOf(b);
        }
        // CC :222 throw new Error(`Unsupported schema: ${jsonStringify(schema)}`)
        throw new IllegalArgumentException("Unsupported schema: " + safeJson(schema));
    }

    /** zod v4 coerce.boolean 字符串映射：true/1/on ↔ false/0/off（其余 → 校验失败）。 */
    private static Boolean coerceBoolean(String input) {
        if (input == null) return null;
        return switch (input.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "on" -> Boolean.TRUE;
            case "false", "0", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** zod v4 url() 等价：绝对 URI（new URL 语义，Java URI + isAbsolute）。 */
    private static boolean isValidUri(String input) {
        try {
            URI uri = URI.create(input);
            return uri.isAbsolute() && uri.getScheme() != null && !uri.getScheme().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** CC plural（stringUtils.ts）：n === 1 → word，否则 word + 's'。 */
    private static String plural(int n, String word) {
        return n == 1 ? word : word + "s";
    }

    private static String safeJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            return node == null ? "null" : node.toString();
        }
    }

    // ═══════════ 异步校验（CC :307-336 · dateTimeParser 消费接线）═══════════

    /**
     * CC validateElicitationInputAsync（:307-336）· Java 同步版（DateTimeParser 内部 LLM
     * 调用为同步 chatWithOptions；CC AbortSignal 参数 :310 由 DateTimeParser.queryOptions
     * 内部 AbortController 适配，见类 javadoc）。
     *
     * <p>流程：sync 校验通过 → 直接返回（:312-314）；schema 为 date/date-time 且值
     * 非 ISO 8601（:317）→ {@link DateTimeParser#parseNaturalLanguageDateTime} 慢路径 →
     * success 则复验 parse 结果（:324-331）通过返回；任何失败回落 syncResult（:334-335）。
     * DateTimeParser 未注入 → 直接回落 syncResult（不抛）。
     *
     * @param stringValue 表单输入
     * @param schema      PrimitiveSchemaDefinition（JsonNode 形态）
     * @return ValidationResult；失败时与同步校验结果一致
     */
    public ValidationResult validateElicitationInputAsync(String stringValue, JsonNode schema) {
        ValidationResult syncResult = validateElicitationInput(stringValue, schema);
        if (syncResult.isValid()) {
            return syncResult;
        }
        if (isDateTimeSchema(schema) && !DateTimeParser.looksLikeISO8601(stringValue)) {
            if (dateTimeParser == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[ElicitationValidation] date/date-time schema 非 ISO 输入但 DateTimeParser 未注入，回落同步校验结果: input={}", stringValue);
                }
                return syncResult;
            }
            if (log.isDebugEnabled()) {
                log.debug("[ElicitationValidation] date/date-time schema 非 ISO 输入 → DateTimeParser 慢路径解析: input={}, format={}",
                    stringValue, "date".equals(schema.path("format").asText()) ? "DATE" : "DATE_TIME");
            }
            DateTimeParser.DateTimeParseResult parseResult = dateTimeParser.parseNaturalLanguageDateTime(
                stringValue,
                "date".equals(schema.path("format").asText()) ? DateTimeParser.Format.DATE : DateTimeParser.Format.DATE_TIME);
            if (parseResult.success()) {
                ValidationResult validatedParsed = validateElicitationInput(parseResult.value(), schema);
                if (validatedParsed.isValid()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[ElicitationValidation] DateTimeParser 解析结果复验通过: input={} → parsed={}",
                            stringValue, parseResult.value());
                    }
                    return validatedParsed;
                }
                if (log.isDebugEnabled()) {
                    log.debug("[ElicitationValidation] DateTimeParser 解析结果未通过复验（{}），回落同步校验结果", parseResult.value());
                }
            } else if (log.isDebugEnabled()) {
                log.debug("[ElicitationValidation] DateTimeParser 解析失败: error={}，回落同步校验结果", parseResult.error());
            }
        }
        return syncResult;
    }
}
