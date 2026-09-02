package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.subagent.AgentModelResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SKILL.md YAML frontmatter 解析器 · 对齐 CC utils/frontmatterParser.ts parseFrontmatter()
 *
 * <p>早期手写行解析器已整体删除，替换为<b>真实 YAML 解析器</b>（Jackson YAMLMapper，底层 SnakeYAML 2.4，
 * 传递依赖已在 compile classpath，无需改 pom）。行为完全对齐 CC：
 *
 * <ul>
 *   <li>{@link #FRONTMATTER_REGEX} 精确复刻 CC {@code FRONTMATTER_REGEX}
 *       （frontmatterParser.ts:123），content 取 {@code markdown.substring(matcher.end())} <b>不 trim</b>
 *       （CC frontmatterParser.ts:145 {@code markdown.slice(match[0].length)}）</li>
 *   <li>首次 YAML 解析失败后 {@link #quoteProblemativeValues} 引号化特殊字符重试
 *       （CC frontmatterParser.ts:155-160），二次失败 slf4j log.warn（中文）带 sourcePath（:161-168）</li>
 *   <li>静态工具：{@link #coerceDescriptionToString}（frontmatterParser.ts:304）、
 *       {@link #extractDescriptionFromMarkdown}（markdownConfigLoader.ts:52）、
 *       {@link #splitPathInFrontmatter}/{@link #expandBraces}（frontmatterParser.ts:189/240）、
 *       {@link #parseSkillPaths}（loadSkillsDir.ts:159）</li>
 *   <li>P1-5 校验语义：{@link #parseEffortValue}（effort.ts:71-87）、{@link #parseShellFrontmatter}
 *       （frontmatterParser.ts:351-370）、{@link #parseHooksFromFrontmatter}（loadSkillsDir.ts:136-153 +
 *       hooks.ts:211-213 HooksSchema Zod 严格校验）</li>
 * </ul>
 *
 * <p>⚠️ 注意：coerce 语义与 {@code OutputStyleDirLoader.java:167} 的旧版本<b>不同</b>
 * （旧版取 List 首元素 / 非 String 一律 toString / 不 trim），禁止复用，以本类实现为准。
 */
public class ParseSkillFrontmatter {

    private static final Logger log = LoggerFactory.getLogger(ParseSkillFrontmatter.class);

    /**
     * 匹配 SKILL.md 开头的 YAML frontmatter 块。
     * CC 原名：FRONTMATTER_REGEX（Open-ClaudeCode/src/utils/frontmatterParser.ts:123）
     * {@code /^---\s*\n([\s\S]*?)---\s*\n?/} —— 非贪婪匹配首个 {@code ---}，Java {@code \s} 已含
     * {@code \r} 无需显式 {@code \r?}；content 为 {@code match[0]} 之后剩余部分（不 trim）。
     */
    private static final Pattern FRONTMATTER_REGEX =
        Pattern.compile("^---\\s*\\n([\\s\\S]*?)---\\s*\\n?");

    /**
     * 需引号化的 YAML 特殊字符。
     * CC 原名：YAML_SPECIAL_CHARS（frontmatterParser.ts:79）{@code /[{}[\]*&#!|>%@`]|: /}
     * —— 花括号/中括号/星号/锚点(&)/注释(#)/标签(!)/块标量(|>)/保留符(%@@``) 等指示符 + 冒号空格
     * （key 指示符）；裸 {@code :}（如 12:34、https://）不命中，保持不引号。
     */
    private static final Pattern YAML_SPECIAL_CHARS = Pattern.compile("[{}\\[\\]*&#!|>%@`]|: ");

    /** 简单 {@code key: value} 行（非缩进、非列表项、非块标量）。
     *  CC 原名：quoteProblemativeValues 内联 {@code /^([a-zA-Z_-]+):\s+(.+)$/}（frontmatterParser.ts:86） */
    private static final Pattern SIMPLE_KEY_VALUE_LINE = Pattern.compile("^([a-zA-Z_-]+):\\s+(.+)$");

    /** markdown 首个非空行去 header 前缀。
     *  CC 原名：extractDescriptionFromMarkdown 内联 {@code /^#+\s+(.+)$/}（markdownConfigLoader.ts:64） */
    private static final Pattern HEADER_PREFIX = Pattern.compile("^#+\\s+(.+)$");

    /** 花括号 glob 首个分组。
     *  CC 原名：expandBraces 内联 {@code /^([^{]*)\{([^}]+)\}(.*)$/}（frontmatterParser.ts:243） */
    private static final Pattern BRACE_GROUP = Pattern.compile("^([^{]*)\\{([^}]+)\\}(.*)$");

    /** 真实 YAML 解析器（Jackson YAMLMapper，底层 SnakeYAML 2.4，已在 compile classpath）。
     *  线程安全、重量级，故为静态单例，避免 SkillsLoader/SkillContentLoader 每次实例化重建。 */
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper(new YAMLFactory());

    /**
     * CC 原名：ParsedMarkdown（frontmatterParser.ts:61-64）——frontmatter Map + 去除 frontmatter 后的 content。
     */
    public record ParsedMarkdown(Map<String, Object> frontmatter, String content) {}

    /**
     * 解析 SKILL.md 的 YAML frontmatter（保留签名，委托 {@link #parseFrontmatter}）。
     *
     * @param raw SKILL.md 文件原始内容
     * @return frontmatter 键值对 Map（无 frontmatter 时为空 Map）
     */
    public Map<String, Object> parse(String raw) {
        return parseFrontmatter(raw, null).frontmatter();
    }

    /**
     * 提取 SKILL.md body（去除 frontmatter 后的正文，<b>不 trim</b>）。保留签名，委托
     * {@link #parseFrontmatter}。
     * CC 原名：parseFrontmatter 的 content（frontmatterParser.ts:145 {@code markdown.slice(match[0].length)}）。
     *
     * @param raw SKILL.md 文件原始内容
     * @return body 原文（首尾空白保留，属 CC 对齐行为变更）
     */
    public String extractBody(String raw) {
        return parseFrontmatter(raw, null).content();
    }

    /**
     * 静态便捷入口 · 对齐 CC frontmatterParser.ts:130-175 {@code parseFrontmatter}。
     *
     * <p>agents/plugins 模块（loadAgentsDir.ts:308 / loadPluginAgents.ts:80 消费链）P1-1 接入统一管线时
     * 复用本静态入口（无需实例化），语义与 {@link #parseFrontmatter(String, String)} 完全一致。
     *
     * @param markdown   markdown 原始内容
     * @param sourcePath 来源路径（仅用于失败日志定位，可为 null）
     * @return ParsedMarkdown
     */
    public static ParsedMarkdown parseFrontmatterStatic(String markdown, String sourcePath) {
        return new ParseSkillFrontmatter().parseFrontmatter(markdown, sourcePath);
    }

    /**
     * 解析 markdown 提取 frontmatter 与 content。
     * CC 原名：parseFrontmatter（frontmatterParser.ts:130-175）。
     *
     * <p>无匹配 → {@code {frontmatter:{}, content: markdown}}（content 原文不 trim）；有匹配 →
     * frontmatterText=group(1)、content={@code markdown.substring(matcher.end())}（不 trim）。
     * 首段 YAML 解析失败走 {@code quoteProblemativeValues} 重试，二次失败 log.warn（中文）带 sourcePath。
     *
     * @param markdown   markdown 原始内容
     * @param sourcePath 来源路径（仅用于失败日志定位，可为 null）
     * @return ParsedMarkdown
     */
    public ParsedMarkdown parseFrontmatter(String markdown, String sourcePath) {
        if (markdown == null) {
            return new ParsedMarkdown(new LinkedHashMap<>(), "");
        }
        Matcher m = FRONTMATTER_REGEX.matcher(markdown);
        if (!m.find()) {
            // CC frontmatterParser.ts:139-143 无 frontmatter → content 原文（不 trim）
            return new ParsedMarkdown(new LinkedHashMap<>(), markdown);
        }
        String frontmatterText = m.group(1) == null ? "" : m.group(1);
        // CC frontmatterParser.ts:145 content = markdown.slice(match[0].length) —— 不 trim
        String content = markdown.substring(m.end());

        Map<String, Object> frontmatter = new LinkedHashMap<>();
        try {
            Object parsed = parseYaml(frontmatterText);
            // CC :150-153 parsed 必须是对象且非数组才接受，否则保持空 Map
            if (parsed instanceof Map<?, ?>) {
                frontmatter = toMap(parsed);
            }
        } catch (Exception e) {
            // CC :155-160 首次失败 → quoteProblemativeValues 引号化特殊字符后重试
            try {
                Object retried = parseYaml(quoteProblemativeValues(frontmatterText));
                if (retried instanceof Map<?, ?>) {
                    frontmatter = toMap(retried);
                }
            } catch (Exception retryError) {
                // CC :161-168 二次失败 → logForDebugging warn（带 sourcePath）
                String location = sourcePath != null ? " 位于 " + sourcePath : "";
                String reason = retryError.getMessage() != null ? retryError.getMessage() : retryError.toString();
                log.warn("解析 YAML frontmatter 失败{}: {}", location, reason);
            }
        }
        return new ParsedMarkdown(frontmatter, content);
    }

    /**
     * 真实 YAML 解析。空/空白输入返回 null（对齐 CC yaml 包 {@code parseYaml('') → null}，不触发异常与 warn）。
     */
    private static Object parseYaml(String text) throws Exception {
        if (text == null || text.isBlank()) {
            return null;
        }
        return YAML_MAPPER.readValue(text, Object.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object parsed) {
        return (Map<String, Object>) parsed;
    }

    /**
     * 预处理 frontmatter 文本，给含 YAML 特殊字符的 {@code key: value} 行加双引号，
     * 使 glob 等模式（如 {@code **\/*.{ts,tsx}}）能被正确解析。
     * CC 原名：quoteProblemativeValues（frontmatterParser.ts:85-121）。
     *
     * @param frontmatterText frontmatter 原始文本
     * @return 引号化处理后的文本
     */
    static String quoteProblemativeValues(String frontmatterText) {
        String[] lines = frontmatterText.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            Matcher kv = SIMPLE_KEY_VALUE_LINE.matcher(line);
            if (kv.matches()) {
                String value = kv.group(2);
                // 已单/双引号包裹则跳过（CC :110-116）
                if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                    result.append(line).append('\n');
                    continue;
                }
                // 含 YAML 特殊字符 → 双引号包裹并转义 \ 与 "（CC :118-121）
                if (YAML_SPECIAL_CHARS.matcher(value).find()) {
                    String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
                    result.append(kv.group(1)).append(": \"").append(escaped).append("\"\n");
                    continue;
                }
            }
            result.append(line).append('\n');
        }
        // CC 用 join('\n') 无尾随换行：去掉每次追加的多余 \n
        if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    /**
     * 校验并 coerce frontmatter description 为字符串。
     * CC 原名：coerceDescriptionToString（frontmatterParser.ts:304-326）。
     * <ul>
     *   <li>null → null（调用方回退 extractDescriptionFromMarkdown）</li>
     *   <li>String → trim()，空串 → null</li>
     *   <li>Number/Boolean → String.valueOf</li>
     *   <li>非标量（数组/对象）→ log.warn「描述无效，已忽略」 + null</li>
     * </ul>
     * source = pluginName ? "{pluginName}:{componentName}" : (componentName ?? 'unknown')。
     * <p>⚠️ 与 OutputStyleDirLoader.java:167 旧版本语义不同（旧版取 List 首元素 / 非 String 一律
     * toString / 不 trim），此处为 CC 对齐新实现。
     *
     * @param value        raw description 值
     * @param componentName 技能/命令名（日志定位用）
     * @param pluginName    插件名（若来自插件，可为 null）
     * @return coerce 后字符串，无效/null → null
     */
    public static String coerceDescriptionToString(Object value, String componentName, String pluginName) {
        if (value == null) return null;                                   // CC :307
        if (value instanceof String s) {                                  // CC :309-312 string → trim()||null
            String trimmed = s.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        if (value instanceof Number || value instanceof Boolean) {        // CC :313-316 number|boolean → String(value)
            return String.valueOf(value);
        }
        // CC :317-326 非标量（数组/对象）→ warn + null
        String source = pluginName != null
            ? pluginName + ":" + componentName
            : (componentName != null ? componentName : "unknown");
        log.warn("描述无效，已忽略 - {}", source);
        return null;
    }

    /**
     * 从 markdown 内容提取描述：首个非空行；{@code ^#+\s+} header 去前缀；{@code >100} 字符
     * 截断为 {@code substring(0,97)+'...'}；无内容返回 default。
     * CC 原名：extractDescriptionFromMarkdown（markdownConfigLoader.ts:52-69）。
     *
     * @param content           markdown 正文
     * @param defaultDescription 无内容时的默认值
     * @return 提取的描述
     */
    public static String extractDescriptionFromMarkdown(String content, String defaultDescription) {
        if (content == null) return defaultDescription;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Matcher header = HEADER_PREFIX.matcher(trimmed);
            String text = header.matches() ? header.group(1) : trimmed;
            return text.length() > 100 ? text.substring(0, 97) + "..." : text;
        }
        return defaultDescription;
    }

    /**
     * 逗号分隔字符串 + 花括号展开。也接受 YAML 列表（字符串数组）。
     * CC 原名：splitPathInFrontmatter（frontmatterParser.ts:189-232）。
     * <ul>
     *   <li>List → 递归 flatMap</li>
     *   <li>String → 按 braceDepth 计数逗号分割（{} 内逗号不分隔），每部分 trim + 过滤空 + expandBraces</li>
     *   <li>非 String 非 List → 空 list</li>
     * </ul>
     * 例：{@code "a, src/*.{ts,tsx}" → ["a", "src/*.ts", "src/*.tsx"]}；
     * {@code "{a,b}/{c,d}" → ["a/c", "a/d", "b/c", "b/d"]}。
     *
     * @param input 逗号分隔字符串或字符串列表
     * @return 展开后的路径模式列表
     */
    public static List<String> splitPathInFrontmatter(Object input) {
        if (input instanceof List<?> list) {
            // CC :198-200 Array → flatMap 递归
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.addAll(splitPathInFrontmatter(item));
            }
            return result;
        }
        if (!(input instanceof String s)) {
            // CC :201-203 非 String 非 Array → []
            return List.of();
        }
        // CC :205-225 按 braceDepth 计数逗号分割（{} 内逗号不分隔）
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braceDepth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                braceDepth++;
                current.append(c);
            } else if (c == '}') {
                braceDepth--;
                current.append(c);
            } else if (c == ',' && braceDepth == 0) {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    parts.add(trimmed);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        // CC :227-230 过滤空 + 每部分 expandBraces 展开
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            if (p.length() > 0) {
                result.addAll(expandBraces(p));
            }
        }
        return result;
    }

    /**
     * 递归展开 glob 字符串中的花括号分组。
     * CC 原名：expandBraces（frontmatterParser.ts:240-266）。
     * {@code "src/*.{ts,tsx}" → ["src/*.ts", "src/*.tsx"]}；无花括号返回原样。
     *
     * @param pattern glob 模式
     * @return 展开后的模式列表
     */
    private static List<String> expandBraces(String pattern) {
        Matcher brace = BRACE_GROUP.matcher(pattern);
        if (!brace.matches()) {
            return List.of(pattern);
        }
        String prefix = brace.group(1);
        String alternatives = brace.group(2);
        String suffix = brace.group(3);
        String[] parts = alternatives.split(",", -1);
        List<String> expanded = new ArrayList<>();
        for (String part : parts) {
            String combined = prefix + part.trim() + suffix;
            // 递归处理剩余花括号分组
            expanded.addAll(expandBraces(combined));
        }
        return expanded;
    }

    /**
     * 解析 skill frontmatter 的 paths，格式与 CLAUDE.md paths 相同。
     * CC 原名：parseSkillPaths（loadSkillsDir.ts:159-178）。
     * null/空 → null；splitPathInFrontmatter → 去 {@code /**} 后缀 → 过滤空 → 全 {@code **} 或空 → null，
     * 否则返回 patterns。
     *
     * @param paths frontmatter 原始 paths 值（String 或 List，可为 null）
     * @return 路径模式列表，无 paths / 全匹配 → null
     */
    public static List<String> parseSkillPaths(Object paths) {
        if (paths == null) return null;
        List<String> patterns = new ArrayList<>();
        for (String pattern : splitPathInFrontmatter(paths)) {
            // CC :168-170 去 /** 后缀 —— ignore 库把 path 同时匹配自身及其内部
            String p = pattern.endsWith("/**") ? pattern.substring(0, pattern.length() - 3) : pattern;
            if (!p.isEmpty()) {
                patterns.add(p);
            }
        }
        // CC :176-178 全 **（match-all）或空 → undefined（视为无 paths）
        if (patterns.isEmpty() || patterns.stream().allMatch(p -> "**".equals(p))) {
            return null;
        }
        return patterns;
    }

    // ═════════════════════════════════════════════════════════════════════
    // P1-5: frontmatter 校验语义（model/effort/hooks/shell）
    // ═════════════════════════════════════════════════════════════════════

    /** JSON 序列化器（hooks JSON 串 → RegisterSkillHooks.fromHooksJson 消费）。 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * CC 原名：EFFORT_LEVELS（Open-ClaudeCode/src/utils/effort.ts:13-18）
     * {@code ['low','medium','high','max']} —— effort 合法字符串档位。
     */
    private static final Set<String> EFFORT_LEVELS = Set.of("low", "medium", "high", "max");

    /**
     * CC 原名：FRONTMATTER_SHELLS（Open-ClaudeCode/src/utils/frontmatterParser.ts:341）
     * {@code ['bash','powershell']} —— shell frontmatter 合法值白名单。
     */
    private static final Set<String> FRONTMATTER_SHELLS = Set.of("bash", "powershell");

    /**
     * CC 原名：HookCommand 的 type 判别值（Open-ClaudeCode/src/schemas/hooks.ts:32-163
     * discriminatedUnion('type', [BashCommandHook, PromptHook, HttpHook, AgentHook])）。
     * hooks 数组每项必带 type ∈ {command, prompt, http, agent}，且按 type 须带对应必填字段。
     */
    private static final Set<String> HOOK_COMMAND_TYPES = Set.of("command", "prompt", "http", "agent");

    /**
     * CC 原名：parseEffortValue（Open-ClaudeCode/src/utils/effort.ts:71-87）。
     *
     * <p>严格等价：
     * <ul>
     *   <li>null（CC undefined/null）→ null</li>
     *   <li>number 且整数 → 数字串（{@code Number.isInteger}，effort.ts:75-76）</li>
     *   <li>String(value).toLowerCase() in [low,medium,high,max] → 该值（effort.ts:78-81）</li>
     *   <li>前导数字（JS parseInt 语义，effort.ts:82-84）且为整数 → 数字串</li>
     *   <li>否则 → null</li>
     * </ul>
     *
     * @param value frontmatter effort 原始值（String/Number，可为 null）
     * @return 规范化 effort（字符串档位或数字串），非法/null → null
     */
    public static String parseEffortValue(Object value) {
        if (value == null) {
            return null;                                          // CC :72 value === undefined/null
        }
        if (value instanceof Number num) {
            double d = num.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {    // CC :75 isValidNumericEffort
                return String.valueOf(num.longValue());           // CC :76 返回 number
            }
        }
        String str = String.valueOf(value).toLowerCase(Locale.ROOT); // CC :78 String(value).toLowerCase()
        if (str.isEmpty()) {
            return null;                                          // CC :72 value === ''
        }
        if (EFFORT_LEVELS.contains(str)) {
            return str;                                           // CC :79-81 isEffortLevel → 字符串档位
        }
        Integer numeric = jsParseInt(str);                        // CC :82 parseInt(str, 10)
        if (numeric != null) {
            return String.valueOf(numeric);                       // CC :83-84 非 NaN 且整数 → number
        }
        return null;                                              // CC :86 否则 undefined
    }

    /**
     * JS parseInt 语义（前导空白 + 可选符号 + 前导数字串）· 对齐 CC effort.ts:82
     * {@code parseInt(str, 10)} —— JS parseInt 在首个非数字字符处截断（"5.5"→5），Java
     * Integer.parseInt 会抛异常，故手工实现。
     *
     * @param s 待解析字符串
     * @return 前导整数，无有效数字 → null
     */
    private static Integer jsParseInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = (s.charAt(i) == '-');
            i++;
        }
        int digitStart = i;
        long val = 0;
        boolean overflow = false;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            val = val * 10 + (s.charAt(i) - '0');
            if (val > 2147483648L) {
                overflow = true;
            }
            i++;
        }
        if (i == digitStart) {
            return null;                                          // 无数字 → JS NaN
        }
        if (overflow) {
            return null;                                          // 超出 int 范围（frontmatter 实践不会触发）
        }
        return (int) (neg ? -val : val);
    }

    /**
     * CC 原名：parseShellFrontmatter（Open-ClaudeCode/src/utils/frontmatterParser.ts:351-370）。
     *
     * <p>null → null；{@code String(value).trim().toLowerCase()}；空串 → null；白名单
     * ['bash','powershell'] → 命中值；未知值 log.warn（中文）+ null（回退 bash，不阻断技能加载）。
     *
     * @param value  frontmatter shell 原始值（可为 null）
     * @param source 来源标识（技能名，日志定位用；对齐 CC :353 source 参数）
     * @return 规范化 shell（bash/powershell），非法/空 → null
     */
    public static String parseShellFrontmatter(Object value, String source) {
        if (value == null) {
            return null;                                          // CC :355-357 value == null → undefined
        }
        String normalized = value.toString().trim().toLowerCase(Locale.ROOT); // CC :358
        if (normalized.isEmpty()) {
            return null;                                          // CC :359-361 '' → undefined
        }
        if (FRONTMATTER_SHELLS.contains(normalized)) {
            return normalized;                                    // CC :362-364 白名单命中
        }
        // CC :365-369 logForDebugging warn + undefined（回退 bash）
        log.warn("Frontmatter 'shell: {}' 在 {} 中不被识别。合法值: bash, powershell。回退到 bash "
                + "(CC frontmatterParser.ts:365-369)", value, source);
        return null;
    }

    /**
     * CC 原名：parseHooksFromFrontmatter（Open-ClaudeCode/src/skills/loadSkillsDir.ts:136-153）+ HooksSchema
     * （Open-ClaudeCode/src/schemas/hooks.ts:211-213）。
     *
     * <p>Zod {@code partialRecord(enum(HOOK_EVENTS), array(HookMatcherSchema))} 等价严格校验——
     * <b>任一违反整体丢弃</b>（safeParse 失败 → undefined，非逐键跳过）：
     * <ul>
     *   <li>hooksValue 非 Map → warn + null</li>
     *   <li>任一事件键不在 {@link HookEventType#ccEventNames()} 27 白名单 → warn + null（Zod strict）</li>
     *   <li>每事件值须 List；每项 matcher Map 须含 hooks 数组（matcher 字符串可选）</li>
     *   <li>hooks 数组每项 Map 须含 type ∈ {command, prompt, http, agent} 且带该 type 的必填字段
     *       （command→command / prompt→prompt / http→url / agent→prompt，Zod discriminatedUnion）</li>
     * </ul>
     * 合法 → Jackson 序列化为 JSON 串返回（供 {@code RegisterSkillHooks.fromHooksJson} 消费）。
     *
     * @param hooksValue frontmatter hooks 原始值（YAML Map，可为 null）
     * @param skillName  技能名（日志定位用，对齐 CC :138 skillName 参数）
     * @return 规范化 hooks JSON 串，无 hooks / 非法 → null
     */
    @SuppressWarnings("unchecked")
    public static String parseHooksFromFrontmatter(Object hooksValue, String skillName) {
        if (hooksValue == null) {
            return null;                                          // CC :140-142 !frontmatter.hooks → undefined
        }
        if (!(hooksValue instanceof Map<?, ?>)) {
            log.warn("技能 '{}' 的 hooks 不是映射结构，整个丢弃 (CC loadSkillsDir.ts:144-150)", skillName);
            return null;
        }
        Map<String, Object> hooksMap = (Map<String, Object>) hooksValue;
        Set<String> validEvents = HookEventType.ccEventNames();
        Map<String, Object> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : hooksMap.entrySet()) {
            // Zod partialRecord(enum(...))：未知事件键 → safeParse 整体失败（hooks.ts:211-213）
            if (!validEvents.contains(e.getKey())) {
                log.warn("技能 '{}' 的 hooks 含未知事件键 '{}'，整个 hooks 丢弃 (CC hooks.ts:212 未知枚举键 "
                        + "→ Zod strict 整体失败)", skillName, e.getKey());
                return null;
            }
            if (!(e.getValue() instanceof List<?>)) {
                log.warn("技能 '{}' 的 hooks 事件 '{}' 值不是数组，整个 hooks 丢弃 (CC hooks.ts:212 "
                        + "array(HookMatcherSchema))", skillName, e.getKey());
                return null;
            }
            List<Object> matchers = new ArrayList<>();
            for (Object m : (List<?>) e.getValue()) {
                if (!(m instanceof Map<?, ?>)) {
                    log.warn("技能 '{}' 的 hooks 事件 '{}' 含非对象 matcher，整个 hooks 丢弃", skillName, e.getKey());
                    return null;
                }
                Map<String, Object> matcher = (Map<String, Object>) m;
                Object rawHooks = matcher.get("hooks");
                if (!(rawHooks instanceof List<?>)) {
                    log.warn("技能 '{}' 的 hooks matcher 缺 hooks 数组，整个 hooks 丢弃 (CC hooks.ts:200-201 "
                            + "hooks: array(HookCommandSchema))", skillName);
                    return null;
                }
                for (Object h : (List<?>) rawHooks) {
                    if (!isValidHookCommand(h)) {
                        log.warn("技能 '{}' 的 hooks 含非法 hook 命令项（type 须 ∈ {command,prompt,http,agent} "
                                + "且带必填字段），整个 hooks 丢弃 (CC hooks.ts:183-188 discriminatedUnion)", skillName);
                        return null;
                    }
                }
                matchers.add(m);
            }
            validated.put(e.getKey(), matchers);
        }
        try {
            return JSON_MAPPER.writeValueAsString(validated);
        } catch (JsonProcessingException ex) {
            log.warn("技能 '{}' 的 hooks 序列化失败，整个丢弃: {}", skillName, ex.getMessage());
            return null;
        }
    }

    /**
     * 校验单个 hook 命令项 · 对齐 CC hooks.ts:183-188 discriminatedUnion('type', [...]).
     * 每项须为 Map 且 type ∈ {command, prompt, http, agent}，并带该 type 的必填字段
     * （command→command / prompt→prompt / http→url / agent→prompt）。任一违反 → 非法。
     */
    private static boolean isValidHookCommand(Object h) {
        if (!(h instanceof Map<?, ?> hm)) {
            return false;
        }
        Object type = hm.get("type");
        if (!(type instanceof String typeStr) || !HOOK_COMMAND_TYPES.contains(typeStr)) {
            return false;
        }
        switch (typeStr) {
            case "command":
                return hm.get("command") != null;
            case "prompt":
            case "agent":
                return hm.get("prompt") != null;
            case "http":
                return hm.get("url") != null;
            default:
                return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // P2-13: parseSkillFrontmatterFields（MCP 技能发现共用 frontmatter 解析）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 解析所有文件型与 MCP 技能加载共享的 frontmatter 字段 · 对齐 CC
     * {@code loadSkillsDir.ts:185-265 parseSkillFrontmatterFields(frontmatter, markdownContent, resolvedName, fallbackLabel='Skill')}。
     *
     * <p>调用方（MCP 路径 = {@code McpToolPool.fetchMcpSkills}）负责提供已解析的 frontmatter Map、
     * 去除 frontmatter 后的 markdown body、解析出的技能名、以及描述回退标签。source/loadedFrom/baseDir/paths
     * 由调用方单独提供（CC :182-184 JavaDoc「Caller supplies the resolved skill name and the
     * source/loadedFrom/baseDir/paths fields separately」）。
     *
     * <p>CC 真源（E2，Read loadSkillsDir.ts:185-265）语义逐项：
     * <ul>
     *   <li>:208-214 description 双段流程 —— {@code coerceDescriptionToString(description, resolvedName)}
     *       （:208-211），null 回退 {@code extractDescriptionFromMarkdown(markdownContent, fallbackLabel)}
     *       （:212-214）</li>
     *   <li>:216-219 userInvocable —— {@code frontmatter['user-invocable'] === undefined ? true : parseBooleanFrontmatter(...)}</li>
     *   <li>:221-226 model —— {@code 'inherit'} → undefined；truthy → {@code parseUserSpecifiedModel(model)}；
     *       否则 undefined（Java 用 {@link AgentModelResolver#parseUserSpecifiedModel}）</li>
     *   <li>:228-235 effort —— {@code effortRaw !== undefined ? parseEffortValue(effortRaw) : undefined}；
     *       非法值仅 {@code logForDebugging} warn（:231-235，不阻断）</li>
     *   <li>:238-239 displayName —— {@code frontmatter.name != null ? String(frontmatter.name) : undefined}</li>
     *   <li>:241 hasUserSpecifiedDescription —— {@code validatedDescription !== null}</li>
     *   <li>:242-244 allowedTools —— {@code parseSlashCommandToolsFromFrontmatter(frontmatter['allowed-tools'])}</li>
     *   <li>:245-248 argumentHint —— {@code frontmatter['argument-hint'] != null ? String(...) : undefined}</li>
     *   <li>:249-251 argumentNames —— {@code parseArgumentNames(frontmatter.arguments)}</li>
     *   <li>:252 whenToUse / :253 version / :261 agent —— string 原样</li>
     *   <li>:255-257 disableModelInvocation —— {@code parseBooleanFrontmatter(frontmatter['disable-model-invocation'])}</li>
     *   <li>:259 hooks —— {@code parseHooksFromFrontmatter(frontmatter, resolvedName)}</li>
     *   <li>:260 executionContext —— {@code frontmatter.context === 'fork' ? 'fork' : undefined}</li>
     *   <li>:263 shell —— {@code parseShellFrontmatter(frontmatter.shell, resolvedName)}</li>
     * </ul>
     *
     * <p>注册：经 {@link McpSkillBuilders#register} 注入（等价 CC loadSkillsDir.ts:1083 模块 init
     * eager 注册），供 {@code McpToolPool.fetchMcpSkills} 经 {@link McpSkillBuilders#get()} 取用。
     *
     * @param frontmatter  已解析的 YAML frontmatter Map（键为 SKILL.md 原样 kebab-case）
     * @param markdownContent 去除 frontmatter 后的 markdown body（不 trim，CC :214）
     * @param resolvedName 解析出的技能名（MCP 为资源名；日志定位 + hooks/shell 校验用）
     * @param fallbackLabel 描述回退标签（'Skill' | 'Custom command'，CC :189）
     * @return 16 字段解析结果（{@link SkillFrontmatterFields}）
     */
    public static SkillFrontmatterFields parseSkillFrontmatterFields(
            Map<String, Object> frontmatter,
            String markdownContent,
            String resolvedName,
            String fallbackLabel) {
        if (frontmatter == null) {
            frontmatter = new LinkedHashMap<>();
        }
        // CC :208-214 description 双段流程
        String validatedDescription = coerceDescriptionToString(frontmatter.get("description"), resolvedName, null);
        String description = validatedDescription != null
                ? validatedDescription
                : extractDescriptionFromMarkdown(markdownContent, fallbackLabel);

        // CC :216-219 userInvocable 未定义默认 true
        boolean userInvocable = frontmatter.get("user-invocable") == null
                ? true
                : parseBooleanFrontmatter(frontmatter.get("user-invocable"));

        // CC :221-226 model —— 'inherit'→undefined；truthy→parseUserSpecifiedModel；否则 undefined
        String model = null;
        Object modelRaw = frontmatter.get("model");
        if (modelRaw != null) {
            String modelStr = modelRaw.toString();
            if (!"inherit".equals(modelStr) && !modelStr.isBlank()) {
                model = AgentModelResolver.parseUserSpecifiedModel(modelStr);
            }
        }

        // CC :228-235 effort —— 非法仅 warn 不阻断
        Object effortRaw = frontmatter.get("effort");
        String effort = effortRaw != null ? parseEffortValue(effortRaw) : null;
        if (effortRaw != null && effort == null) {
            log.warn("技能 {} 的 effort 值无效: '{}'。合法选项: low, medium, high, max 或整数 "
                    + "(CC loadSkillsDir.ts:231-235)", resolvedName, effortRaw);
        }

        Object whenToUseRaw = frontmatter.get("when_to_use");
        Object versionRaw = frontmatter.get("version");
        Object agentRaw = frontmatter.get("agent");

        return new SkillFrontmatterFields(
                // :238-239 displayName —— frontmatter.name != null ? String(frontmatter.name) : undefined
                frontmatter.get("name") != null ? String.valueOf(frontmatter.get("name")) : null,
                // :240 description
                description,
                // :241 hasUserSpecifiedDescription —— validatedDescription !== null
                validatedDescription != null,
                // :242-244 allowedTools —— parseSlashCommandToolsFromFrontmatter(['allowed-tools'])
                parseSlashCommandToolsFromFrontmatter(frontmatter.get("allowed-tools")),
                // :245-248 argumentHint
                frontmatter.get("argument-hint") != null ? String.valueOf(frontmatter.get("argument-hint")) : null,
                // :249-251 argumentNames —— parseArgumentNames(frontmatter.arguments)
                ArgumentSubstitution.parseArgumentNames(frontmatter.get("arguments")),
                // :252 whenToUse
                whenToUseRaw != null ? String.valueOf(whenToUseRaw) : null,
                // :253 version
                versionRaw != null ? String.valueOf(versionRaw) : null,
                // :254 model
                model,
                // :255-257 disableModelInvocation —— parseBooleanFrontmatter
                parseBooleanFrontmatter(frontmatter.get("disable-model-invocation")),
                // :258 userInvocable
                userInvocable,
                // :259 hooks —— parseHooksFromFrontmatter(frontmatter.hooks, resolvedName)
                //   （Java 签名取 hooks 字段值，非整个 frontmatter Map；等价 CC 内部 frontmatter.hooks）
                parseHooksFromFrontmatter(frontmatter.get("hooks"), resolvedName),
                // :260 executionContext —— context === 'fork' ? 'fork' : undefined
                "fork".equals(frontmatter.get("context")) ? "fork" : null,
                // :261 agent
                agentRaw != null ? String.valueOf(agentRaw) : null,
                // :262 effort
                effort,
                // :263 shell —— parseShellFrontmatter(frontmatter.shell, resolvedName)
                parseShellFrontmatter(frontmatter.get("shell"), resolvedName)
        );
    }

    /**
     * 解析布尔 frontmatter 值 · 对齐 CC {@code frontmatterParser.ts:332-334 parseBooleanFrontmatter}
     * {@code return value === true || value === 'true'} —— 仅字面量 {@code true} 或字符串 {@code "true"}
     * 返回 true，其余（false / "false" / null / 其它类型）→ false。
     *
     * @param value frontmatter 原始值
     * @return 字面量 true / 字符串 "true" → true；否则 false
     */
    public static boolean parseBooleanFrontmatter(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(value);
    }

    /**
     * 解析 slash command frontmatter 的 {@code allowed-tools} · 对齐 CC
     * {@code markdownConfigLoader.ts:132-140 parseSlashCommandToolsFromFrontmatter}
     * （缺省/空 → {@code []}，不返回 null；"*" → {@code ['*']}）。
     *
     * <p>CC 真源（E2，Read markdownConfigLoader.ts:77-140）：
     * <pre>
     * parseToolListString: null/undefined → null（:79-81）；falsy（''/0）→ []（:84-86）；
     *   string → [toolsValue]（:89-90）；array → filter string 项（:91-95）；空 → []（:97-99）；
     *   parseToolListFromCLI(...)（:101）；parsed 含 '*' → ['*']（:102-104）
     * parseSlashCommandToolsFromFrontmatter: parsed === null → []（:135-137）；否则返回 parsed（:139）
     * </pre>
     * Java 实现：缺省/空 → {@code List.of()}；"*" → {@code List.of("*")}；
     * string → 逗号/空白分割（parens 内不分割，对齐 permissionSetup.ts:813-868 parseToolListFromCLI）；
     * array → 逐 string 项分割合并非 string 丢弃。
     *
     * @param toolsValue frontmatter {@code allowed-tools} 原始值（String / List，可为 null）
     * @return 解析后的工具列表（缺省/空 → 空 list，恒非 null）
     */
    public static List<String> parseSlashCommandToolsFromFrontmatter(Object toolsValue) {
        // CC markdownConfigLoader.ts:79-81 null/undefined → null
        if (toolsValue == null) {
            return List.of();
        }
        // CC :84-86 falsy（''/0/false）→ []
        if (toolsValue instanceof String s && s.trim().isEmpty()) {
            return List.of();
        }
        if (toolsValue instanceof Number n && n.doubleValue() == 0) {
            return List.of();
        }
        if (Boolean.FALSE.equals(toolsValue)) {
            return List.of();
        }
        // CC :89-95 string → [toolsValue]；array → filter string 项
        List<String> toolsArray = new ArrayList<>();
        if (toolsValue instanceof String one) {
            toolsArray.add(one);
        } else if (toolsValue instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String str) {
                    toolsArray.add(str);
                }
            }
        }
        // CC :97-99 空 → []
        if (toolsArray.isEmpty()) {
            return List.of();
        }
        // CC :101 parseToolListFromCLI + :102-104 '*' → ['*']
        List<String> parsed = parseToolListFromCLI(toolsArray);
        if (parsed.contains("*")) {
            return List.of("*");
        }
        return parsed;
    }

    /**
     * 解析 agent frontmatter 的 {@code tools}/{@code disallowedTools} · 对齐 CC
     * {@code markdownConfigLoader.ts:113-126 parseAgentToolsFromFrontmatter}（与 slash 命令版
     * {@link #parseSlashCommandToolsFromFrontmatter} 的差异：<b>missing 与 {@code '*'} → undefined
     * （全部工具）</b>，Java 以 null 表达 undefined）。
     *
     * <p>CC 真源（E2，Read markdownConfigLoader.ts:77-126）：
     * <pre>
     * parseToolListString: null/undefined → null（:79-81）；falsy（''/0）→ []（:84-86）；
     *   string → [toolsValue]（:89-90）；array → filter string 项（:91-95）；空 → []（:97-99）；
     *   parseToolListFromCLI(...)（:101）；parsed 含 '*' → ['*']（:102-104）
     * parseAgentToolsFromFrontmatter: parsed === null → toolsValue===undefined ? undefined : []（:117-120）
     *   （missing → undefined=全部工具；显式 null → []）；parsed 含 '*' → undefined（:122-124，全部工具）
     * </pre>
     * Java 实现：frontmatter 键缺失（Java 无法从 {@code fm.get(key)} 区分 missing 与显式 null，
     * 需 {@code fm.containsKey} 前置）→ 返回 null（undefined=全部工具）；显式 null → {@link List#of()}
     * （无工具）；{@code '*'} → null（undefined=全部工具）；其余 → 解析列表。
     *
     * @param frontmatter 已解析 frontmatter Map（调用方保证非 null）
     * @param key         {@code "tools"} / {@code "disallowedTools"} 等 agent 工具字段键
     * @return 工具列表；null = undefined（全部工具，不设置）——与 {@code parseAgentToolsFromFrontmatter}
     *         CC undefined 语义对齐（markdownConfigLoader.ts:122-124）
     */
    public static List<String> parseAgentToolsFromFrontmatter(Map<String, Object> frontmatter, String key) {
        // CC :117-120 missing（undefined）→ undefined（全部工具，不设置）
        if (!frontmatter.containsKey(key)) {
            return null;
        }
        Object toolsValue = frontmatter.get(key);
        // 显式 null → []（无工具）；等价 CC toolsValue===null → parseToolListString(null)→null → []
        List<String> parsed = parseToolListString(toolsValue);
        if (parsed == null) {
            return List.of();
        }
        // CC :122-124 parsed 含 '*' → undefined（全部工具）
        if (parsed.contains("*")) {
            return null;
        }
        return parsed;
    }

    /**
     * parseToolListString 内部原语 · CC original: markdownConfigLoader.ts:77-106
     * {@code parseToolListString(toolsValue)}。null/undefined → null；falsy（''/0/false）→ []；
     * string → [toolsValue]；array → filter string 项；空 → []；parseToolListFromCLI；含 '*' → ['*']。
     *
     * @param toolsValue 原始值（可为 null）
     * @return 解析列表；null = 输入 null/undefined（让上层决定 missing vs 显式 null 语义）
     */
    private static List<String> parseToolListString(Object toolsValue) {
        // CC :79-81 null/undefined → null
        if (toolsValue == null) {
            return null;
        }
        // CC :84-86 falsy（''/0/false）→ []
        if (toolsValue instanceof String s && s.trim().isEmpty()) {
            return List.of();
        }
        if (toolsValue instanceof Number n && n.doubleValue() == 0) {
            return List.of();
        }
        if (Boolean.FALSE.equals(toolsValue)) {
            return List.of();
        }
        // CC :89-95 string → [toolsValue]；array → filter string 项
        List<String> toolsArray = new ArrayList<>();
        if (toolsValue instanceof String one) {
            toolsArray.add(one);
        } else if (toolsValue instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String str) {
                    toolsArray.add(str);
                }
            }
        }
        // CC :97-99 空 → []
        if (toolsArray.isEmpty()) {
            return List.of();
        }
        // CC :101 parseToolListFromCLI + :102-104 '*' → ['*']
        List<String> parsed = parseToolListFromCLI(toolsArray);
        if (parsed.contains("*")) {
            return List.of("*");
        }
        return parsed;
    }

    /**
     * 解析 frontmatter 正整数 · 对齐 CC {@code frontmatterParser.ts:275-289 parsePositiveIntFromFrontmatter}
     * {@code value===undefined||null → undefined；typeof value==='number' ? value : parseInt(String(value),10)；
     * Number.isInteger(parsed) && parsed>0 → parsed；否则 undefined}。消费者 loadAgentsDir.ts:649 /
     * loadPluginAgents.ts:172（agent maxTurns）。
     *
     * @param value frontmatter 原始值（Number / String，可为 null）
     * @return 正整数；null = undefined（缺省/非法/非正整数）
     */
    public static Integer parsePositiveIntFromFrontmatter(Object value) {
        // CC :278-280 undefined/null → undefined
        if (value == null) {
            return null;
        }
        double parsed;
        if (value instanceof Number num) {
            // CC :282 typeof value === 'number' ? value : parseInt(String(value), 10)
            parsed = num.doubleValue();
        } else {
            // CC :282 非 number → parseInt(String(value), 10)——前导整数解析（如 "5abc" → 5）
            Integer p = jsParseInt(String.valueOf(value));
            if (p == null) {
                return null;
            }
            parsed = p;
        }
        // CC :284-286 Number.isInteger(parsed) && parsed > 0 → parsed
        if (parsed == Math.floor(parsed) && parsed > 0 && parsed <= Integer.MAX_VALUE) {
            return (int) parsed;
        }
        return null;
    }

    /**
     * 按逗号/空白分割工具列表（parens 内不分割）· 对齐 CC
     * {@code permissionSetup.ts:813-868 parseToolListFromCLI}。
     *
     * <p>逐字符扫描：{@code (} 进入 parens（:830-833）、{@code )} 退出（:834-837）、
     * 非 parens 内 {@code ,} 与 {@code ' '} 为分隔符（:838-857）、其余字符并入 current；
     * 每段 trim 后 push（:843-856/:864-866）。
     *
     * @param tools 待分割的原始工具串列表
     * @return 分割后的工具列表（逐项累加）
     */
    private static List<String> parseToolListFromCLI(List<String> tools) {
        List<String> result = new ArrayList<>();
        for (String toolString : tools) {
            if (toolString == null || toolString.isEmpty()) {
                continue;
            }
            StringBuilder current = new StringBuilder();
            boolean inParens = false;
            for (int i = 0; i < toolString.length(); i++) {
                char c = toolString.charAt(i);
                switch (c) {
                    case '(' -> {
                        inParens = true;
                        current.append(c);
                    }
                    case ')' -> {
                        inParens = false;
                        current.append(c);
                    }
                    case ',' -> {
                        if (inParens) {
                            current.append(c);
                        } else {
                            pushToolIfPresent(result, current);
                        }
                    }
                    case ' ' -> {
                        if (inParens) {
                            current.append(c);
                        } else {
                            pushToolIfPresent(result, current);
                        }
                    }
                    default -> current.append(c);
                }
            }
            pushToolIfPresent(result, current);
        }
        return result;
    }

    /** 当前累积串 trim 非空则 push 并清空（对齐 CC :843-856/:864-866 每段 trim）。 */
    private static void pushToolIfPresent(List<String> result, StringBuilder current) {
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            result.add(trimmed);
        }
        current.setLength(0);
    }
}
