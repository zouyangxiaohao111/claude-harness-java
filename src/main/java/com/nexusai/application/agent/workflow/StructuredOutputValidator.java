package com.nexusai.application.agent.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 引擎边界结构化输出校验 · 对齐 CC {@code engine/structuredOutput.ts:44 全}（Ajv 式）。
 *
 * <p><b>W-1c 自包含编译声明</b>：P1 W-2b 将引入完整 JSON Schema 校验（CC 用 Ajv 编译）；
 * P0 本类提供最小确定性实现，支撑 hooks.agent 的两处契约（hooks.ts:73 前置编译 +
 * hooks.ts:188 二次校验 + hooks.ts:94-98 journal 重放复验）。</p>
 *
 * <p><b>W-2b 升级（Execute-W2b）</b>：{@code AgentRunResultOk.output} 为 Java 端
 * {@link String}（见 {@link AgentRunResultOk} 文档），schema 模式下 adapter 存的是提取出的
 * JSON 文本。本类升级为<b> Ajv 式完整形状校验</b>：
 * <ul>
 *   <li>{@link #resolveOutput}：String 输出若可解析为 JSON 则按解析值校验（schema 模式 JSON 文本
 *       正确通过 type=object）；不可解析则按原字符串校验（旧 journal/裸文本不回归，A.2 测试依赖）。</li>
 *   <li>{@link #validateValue}：递归校验 type/required/properties/items + <b>D-2 新增关键字</b>
 *       oneOf / anyOf / enum / pattern / format / additionalProperties（Ajv 语义），错误格式
 *       {@code "{instancePath} {message}"} 对齐 CC {@code e.instancePath ? ...} (structuredOutput.ts:39-42)。</li>
 * </ul>
 * </p>
 *
 * <p><b>D-2 全关键字升级（Fix-D2）</b>：P1 Report D-2 指出旧实现为 manual 子集
 * （type/required/properties/items），CC Ajv 全关键字（allErrors/strict:false）下
 * {@code oneOf/anyOf/enum/pattern/format} 静默跳过。本类补齐 Ajv 关键字（CC {@code new Ajv({allErrors:true, strict:false})}
 * structuredOutput.ts:9 + validate errors 映射 :39-42）：
 * <ul>
 *   <li>{@code oneOf}：值必须<b>恰一个</b>子 schema 通过（Ajv 语义，0 或 >1 均报错）；</li>
 *   <li>{@code anyOf}：值必须<b>至少一个</b>子 schema 通过；</li>
 *   <li>{@code enum}：值必须等于枚举之一，深比较 + 数字数值相等归一化（JS 中 1 与 1.0 同值）；</li>
 *   <li>{@code pattern}：String 部分匹配（JS {@code RegExp.test} 语义 → Java {@code find()}，非全匹配）；</li>
 *   <li>{@code format}：String 已知格式（email/uri/date/time/date-time/uuid/ipv4/ipv6/hostname/regex/…）
 *       校验，未知格式忽略（对齐 Ajv strict:false 未知格式不抛 "unknown format" 而放行）；</li>
 *   <li>{@code additionalProperties}：{@code false} 拒绝未知键、schema 对象则按该 schema 校验未知键，
 *       键归属判定含 {@code patternProperties}（properties ∪ patternProperties 为"已知键"）；</li>
 *   <li>错误消息带 instancePath 前缀，根级（{@code ""}）无前缀（对齐 CC
 *       {@code e.instancePath ? `${e.instancePath} ${message}` : message}）。</li>
 * </ul>
 * </p>
 *
 * <ul>
 *   <li>{@link #assertValidJsonSchema}：schema 存在且非 Map → 配置错误直接抛（不重试）。</li>
 *   <li>{@link #validateAgainstSchema}：schema 形状校验（Ajv 式）。</li>
 *   <li>{@link #validateStructuredResult}：ok 结果不匹配 schema → dead{INVALID_STRUCTURED_OUTPUT}。</li>
 * </ul>
 */
public final class StructuredOutputValidator {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputValidator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * pattern 编译缓存 · CC original: {@code cache = new WeakMap<object, ValidateFunction>()} (structuredOutput.ts:3)。
     * Ajv 对每个 schema 编译一次 ValidateFunction，这里对每个 pattern 编译一次（避免每值重编译）。
     */
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TIME = Pattern.compile(
            "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d+)?([Zz]|[+-]([01]\\d|2[0-3]):[0-5]\\d)?$");
    private static final Pattern DATE_TIME = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}[Tt]([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d+)?([Zz]|[+-]([01]\\d|2[0-3]):[0-5]\\d)?$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern IPV4 = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
    private static final Pattern IPV6 = Pattern.compile(
            "^(?:(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}"
                    + "|(?:[0-9a-fA-F]{1,4}:){1,7}:"
                    + "|(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}"
                    + "|(?:[0-9a-fA-F]{1,4}:){1,5}(?::[0-9a-fA-F]{1,4}){1,2}"
                    + "|(?:[0-9a-fA-F]{1,4}:){1,4}(?::[0-9a-fA-F]{1,4}){1,3}"
                    + "|(?:[0-9a-fA-F]{1,4}:){1,3}(?::[0-9a-fA-F]{1,4}){1,4}"
                    + "|(?:[0-9a-fA-F]{1,4}:){1,2}(?::[0-9a-fA-F]{1,4}){1,5}"
                    + "|[0-9a-fA-F]{1,4}:(?:(?::[0-9a-fA-F]{1,4}){1,6})"
                    + "|:(?:(?::[0-9a-fA-F]{1,4}){1,7}|:))$");
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");

    private StructuredOutputValidator() {
    }

    /**
     * 校验 JSON Schema 合法性 · CC original: {@code assertValidJsonSchema} (structuredOutput.ts:21-23，
     * 前置编译 Ajv {@code getValidator} structuredOutput.ts:5-18)。
     *
     * <p>无效 schema 是 workflow 配置错误，非瞬时 agent 失败 → 直接抛，不重试（hooks.ts:71-73）。
     *
     * <p><b>D-3 升级（对齐 structuredOutput.ts:10-15）</b>：Ajv 编译后若
     * {@code validate.$async === true}（schema 含 {@code $async: true}）→ 抛
     * {@code 'Async JSON schemas are not supported'}（structuredOutput.ts:13-15 逐字）。
     * Java 无 Ajv 编译对象，直接检查 schema Map 的 {@code $async} 键（等价的
     * `$async` 值传播语义）。
     *
     * @param schema 待校验 schema（null 放行）
     * @throws IllegalArgumentException schema 非合法 JSON Schema（非 Map 或 {@code $async: true}）
     */
    public static void assertValidJsonSchema(Object schema) {
        if (schema != null && !(schema instanceof Map)) {
            throw new IllegalArgumentException(
                    "invalid JSON schema: expected an object, got " + schema.getClass().getSimpleName());
        }
        // D-3 · CC structuredOutput.ts:13-15 `if (validate.$async) throw new Error('Async JSON schemas are not supported')`
        if (schema instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("$async"))) {
            throw new IllegalArgumentException("Async JSON schemas are not supported");
        }
    }

    /**
     * 校验输出是否符合 schema · CC original: {@code validateAgainstSchema} (structuredOutput.ts:29-44)。
     *
     * @param output 待校验输出（String 视为 JSON 文本，可解析则按解析值校验）
     * @param schema JSON Schema（null → 恒 valid）
     * @return 校验结果（valid + 错误列表）
     */
    public static ValidationResult validateAgainstSchema(Object output, Object schema) {
        if (schema == null) {
            return ValidationResult.validResult();
        }
        if (!(schema instanceof Map<?, ?> m)) {
            return ValidationResult.invalid("schema is not a JSON object");
        }
        Object value = resolveOutput(output);
        List<String> errors = new ArrayList<>();
        validateValue(value, m, "", errors, true);
        return errors.isEmpty()
                ? ValidationResult.validResult()
                : new ValidationResult(false, errors);
    }

    /**
     * 解析输出值 · Java 端 ok.output 为 {@link String}（schema 模式存 JSON 文本）。
     * String 可解析为 JSON → 返回解析值（数字/数组/对象/布尔）；不可解析 → 返回原字符串
     * （旧 journal 裸文本不回归：type=object 下 String 必 invalid，对齐 A.2 测试语义）。
     */
    private static Object resolveOutput(Object output) {
        if (output instanceof String s) {
            try {
                JsonNode node = MAPPER.readTree(s);
                if (node == null) {
                    return null;
                }
                return MAPPER.convertValue(node, Object.class);
            } catch (JsonProcessingException e) {
                // 非 JSON 文本（如 agent 裸叙述 "stale-string"）→ 按原字符串处理
                return output;
            }
        }
        return output;
    }

    /**
     * 递归 Ajv 式全关键字形状校验 · CC original: {@code validateAgainstSchema} 的 Ajv 编译语义
     * （structuredOutput.ts:9-17 + validate 错误映射 :39-42）。
     *
     * <p>支持关键字：{@code type} / {@code required} / {@code properties} / {@code items} /
     * {@code oneOf} / {@code anyOf} / {@code enum} / {@code pattern} / {@code format} /
     * {@code patternProperties} / {@code additionalProperties}。未知关键字不拦截（对齐 Ajv
     * {@code strict: false}）。错误路径格式：根为 {@code ""}，属性为 {@code /name}，嵌套
     * {@code /a/b}，数组下标 {@code /0}。</p>
     *
     * @param logFailures 是否对失败输出 debug 数据流日志（子 schema 试探匹配传 false，
     *                    避免 oneOf/anyOf 内部试探刷日志；顶层 {@link #validateAgainstSchema} 传 true）
     */
    private static void validateValue(Object value, Map<?, ?> schema, String instancePath, List<String> errors,
                                      boolean logFailures) {
        Object type = schema.get("type");
        if (type != null) {
            String typeName = String.valueOf(type);
            if (!typeMatches(value, typeName)) {
                addError(errors, instancePath, "must be " + typeName, logFailures);
                return; // 类型不符则不再深入子结构
            }
        }
        // [D-2] enum：值必须 deep-equal 命中其一（Ajv enum · fast-deep-equal 语义）
        Object enumVal = schema.get("enum");
        if (enumVal instanceof List<?> allowed) {
            boolean hit = false;
            for (Object a : allowed) {
                if (jsonEquals(a, value)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                addError(errors, instancePath, "must be equal to one of the allowed values", logFailures);
            }
        }
        // [D-2] pattern：仅作用于字符串，JS RegExp.test 局部匹配语义 → Java Pattern.find
        Object pattern = schema.get("pattern");
        if (pattern instanceof String p && value instanceof String s) {
            if (!patternMatches(p, s)) {
                addError(errors, instancePath, "must match pattern \"" + p + "\"", logFailures);
            }
        }
        // [D-2] format：仅作用于字符串；已知格式校验，未知格式忽略（对齐 Ajv strict:false）
        if (value instanceof String s) {
            if (schema.get("format") instanceof String formatName && !isFormatValid(s, formatName)) {
                addError(errors, instancePath, "must match format \"" + formatName + "\"", logFailures);
            }
        }
        // [D-2] oneOf：恰好一个子 schema 命中（Ajv oneOf；0 或 2+ 命中均报错）
        Object oneOf = schema.get("oneOf");
        if (oneOf instanceof List<?> variants) {
            int matches = 0;
            for (Object v : variants) {
                if (v instanceof Map<?, ?> subSchema && matchesSchema(value, subSchema)) {
                    matches++;
                }
            }
            if (matches != 1) {
                addError(errors, instancePath, "must match exactly one schema in oneOf", logFailures);
            }
        }
        // [D-2] anyOf：至少一个子 schema 命中（Ajv anyOf）
        Object anyOf = schema.get("anyOf");
        if (anyOf instanceof List<?> variants) {
            boolean any = false;
            for (Object v : variants) {
                if (v instanceof Map<?, ?> subSchema && matchesSchema(value, subSchema)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                addError(errors, instancePath, "must match a schema in anyOf", logFailures);
            }
        }
        if (value instanceof Map<?, ?> obj) {
            Object required = schema.get("required");
            if (required instanceof List<?> reqList) {
                for (Object r : reqList) {
                    if (r instanceof String name && !obj.containsKey(name)) {
                        addError(errors, instancePath, "must have required property '" + name + "'", logFailures);
                    }
                }
            }
            Map<?, ?> props = schema.get("properties") instanceof Map<?, ?> pm ? pm : Map.of();
            for (Map.Entry<?, ?> e : props.entrySet()) {
                if (obj.containsKey(e.getKey()) && e.getValue() instanceof Map<?, ?> propSchema) {
                    validateValue(obj.get(e.getKey()), propSchema, instancePath + "/" + e.getKey(),
                            errors, logFailures);
                }
            }
            // patternProperties — 键名匹配 pattern 的额外属性按对应 schema 校验（只处理不在 properties 的键）
            Map<?, ?> patternProps = schema.get("patternProperties") instanceof Map<?, ?> ppm ? ppm : Map.of();
            for (Map.Entry<?, ?> ppe : patternProps.entrySet()) {
                Pattern compiled = safeCompile(String.valueOf(ppe.getKey()));
                if (compiled == null || !(ppe.getValue() instanceof Map<?, ?> propSchema)) {
                    continue;
                }
                for (Object key : obj.keySet()) {
                    if (!props.containsKey(key) && key instanceof String ks && compiled.matcher(ks).find()) {
                        validateValue(obj.get(key), propSchema, instancePath + "/" + key, errors, logFailures);
                    }
                }
            }
            // additionalProperties — false 拒绝未知键；schema 对象则未知键按该 schema 校验
            Object additional = schema.get("additionalProperties");
            if (additional instanceof Boolean bool) {
                if (!bool) {
                    for (Object key : obj.keySet()) {
                        if (props.containsKey(key) || matchesPatternProperty(patternProps, key)) {
                            continue;
                        }
                        addError(errors, instancePath, "must NOT have additional properties", logFailures);
                    }
                }
            } else if (additional instanceof Map<?, ?> additionalSchema) {
                for (Object key : obj.keySet()) {
                    if (props.containsKey(key) || matchesPatternProperty(patternProps, key)) {
                        continue;
                    }
                    validateValue(obj.get(key), additionalSchema, instancePath + "/" + key, errors, logFailures);
                }
            }
        } else if (value instanceof List<?> arr) {
            Object items = schema.get("items");
            if (items instanceof Map<?, ?> itemSchema) {
                for (int i = 0; i < arr.size(); i++) {
                    validateValue(arr.get(i), itemSchema, instancePath + "/" + i, errors, logFailures);
                }
            }
        }
    }

    /** 未知键归属：键名匹配任一 patternProperties pattern → 非 additional。 */
    private static boolean matchesPatternProperty(Map<?, ?> patternProps, Object key) {
        if (!(key instanceof String ks)) {
            return false;
        }
        for (Object patternKey : patternProps.keySet()) {
            Pattern compiled = safeCompile(String.valueOf(patternKey));
            if (compiled != null && compiled.matcher(ks).find()) {
                return true;
            }
        }
        return false;
    }

    /** 子 schema 匹配（oneOf/anyOf 试探）· 只读布尔，不收集错误、不刷日志。 */
    private static boolean matchesSchema(Object value, Map<?, ?> schema) {
        List<String> scratch = new ArrayList<>();
        validateValue(value, schema, "", scratch, false);
        return scratch.isEmpty();
    }

    /**
     * 错误写入 + 数据流日志 · CC original: {@code e.instancePath ? `${e.instancePath} ${message}` : message}
     * (structuredOutput.ts:39-42)。根级（instancePath 空）不加前缀，嵌套带 {@code /path} 前缀。
     */
    private static void addError(List<String> errors, String instancePath, String message, boolean logFailures) {
        if (logFailures && log.isDebugEnabled()) {
            log.debug("StructuredOutputValidator 校验失败 path={} 消息={}", instancePath, message);
        }
        errors.add(instancePath.isEmpty() ? message : instancePath + " " + message);
    }

    /**
     * pattern 编译缓存 · 非法 Java 正则跳过该次校验（对齐 Ajv strict:false 不因 schema 细节抛运行时异常；
     * 非法 schema 应由 assertValidJsonSchema 前置拦截）。
     */
    private static Pattern safeCompile(String regex) {
        if (regex == null || regex.isEmpty()) {
            return null;
        }
        try {
            return PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
        } catch (PatternSyntaxException e) {
            if (log.isDebugEnabled()) {
                log.debug("StructuredOutputValidator 非法 pattern 跳过校验：{}", regex);
            }
            return null;
        }
    }

    /** JSON pattern · JS {@code RegExp.test} 局部匹配语义（非 Java 全匹配）→ {@code Pattern.find}。 */
    private static boolean patternMatches(String regex, String value) {
        Pattern compiled = safeCompile(regex);
        return compiled != null && compiled.matcher(value).find();
    }

    /**
     * JSON 深比较 · Ajv enum 的 fast-deep-equal 语义（Map/List/标量递归）。
     *
     * <p>JS 数字无 int/long/double 类型分号（{@code 1 === 1.0} 同值），Java 端跨数字类型按值比较
     * （Integer 1 ≡ Long 1 ≡ Double 1.0），避免 JSON 反序列化的 Integer/Long 与 schema 里手写的
     * Double/Long 误判不等；Map/List 递归比较（含嵌套数字归一化）。</p>
     */
    private static boolean jsonEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return new BigDecimal(na.toString()).compareTo(new BigDecimal(nb.toString())) == 0;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey()) || !jsonEquals(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!jsonEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /**
     * String format 校验 · 已知格式 email/uri/uri-reference/date/time/date-time/uuid/ipv4/ipv6/hostname/
     * regex/int32/int64；未知格式忽略（对齐 Ajv strict:false 下未知格式不抛 "unknown format" 而放行）。
     */
    private static boolean isFormatValid(String value, String format) {
        return switch (format) {
            case "email" -> EMAIL.matcher(value).matches();
            case "uri" -> isUri(value, true);
            case "uri-reference" -> isUri(value, false);
            case "date" -> isDate(value);
            case "time" -> TIME.matcher(value).matches();
            case "date-time" -> DATE_TIME.matcher(value).matches();
            case "uuid" -> UUID.matcher(value).matches();
            case "ipv4" -> IPV4.matcher(value).matches();
            case "ipv6" -> IPV6.matcher(value).matches();
            case "hostname" -> HOSTNAME.matcher(value).matches();
            case "regex" -> isRegex(value);
            case "int32" -> isIntegerInRange(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case "int64" -> isIntegerInRange(value, Long.MIN_VALUE, Long.MAX_VALUE);
            default -> true; // 未知 format → 忽略
        };
    }

    private static boolean isUri(String value, boolean absolute) {
        try {
            URI uri = URI.create(value);
            return !absolute || uri.getScheme() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isDate(String value) {
        if (!DATE.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isRegex(String value) {
        try {
            Pattern.compile(value);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private static boolean isIntegerInRange(String value, long min, long max) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** JSON Schema 类型匹配 · Ajv 语义：number 含 integer；integer 仅整型。 */
    private static boolean typeMatches(Object value, String type) {
        return switch (type) {
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true; // 未知 type 关键字不拦截（宽松，对齐 Ajv strict:false）
        };
    }

    /**
     * 引擎边界统一二次校验 · CC original: {@code validateStructuredResult} (hooks.ts:346-363)。
     *
     * <p>schema 存在且 result 为 ok 时校验 output（String 输出自动解析为 JSON 再校验）；
     * 不匹配 → dead{invalid-structured-output}。journal 重放复验走同一函数（hooks.ts:90-93）。</p>
     */
    public static AgentRunResult validateStructuredResult(AgentRunResult result, Object schema) {
        if (schema == null || !(result instanceof AgentRunResultOk ok)) {
            return result;
        }
        ValidationResult v = validateAgainstSchema(ok.output(), schema);
        if (v.valid()) {
            return result;
        }
        String errors = String.join("; ", v.errors());
        log.warn("StructuredOutputValidator 引擎边界二次校验失败 → dead{{invalid-structured-output}}：{}",
                errors);
        return new AgentRunResultDead(
                AgentRunResult.DeadReason.INVALID_STRUCTURED_OUTPUT,
                errors);
    }

    /** 校验结果 · CC original: {@code {valid, errors}} (structuredOutput.ts)。 */
    public record ValidationResult(boolean valid, List<String> errors) {

        /**
         * 静态工厂 validResult · 命名避开 record 组件 {@code valid} 的自动访问器
         * {@code valid()}（与静态同名方法冲突，javac 报「record accessor invalid」）。
         */
        static ValidationResult validResult() {
            return new ValidationResult(true, List.of());
        }

        static ValidationResult invalid(String error) {
            return new ValidationResult(false, List.of(error));
        }
    }
}
