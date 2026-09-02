package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-6 ParseSkillFrontmatter 真 YAML 解析器 + coerce + extract 测试（RED→GREEN）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>花括号 glob 是 B4 的意图</b>——CC loadSkillsDir.ts:159-178 用
 *       {@code splitPathInFrontmatter + expandBraces + /** 去尾} 把 {@code paths: src/**\/*.{ts,tsx}}
 *       展开为 {@code ["src/**\/*.ts", "src/**\/*.tsx"]}，手写行解析器既无法解析特殊字符 glob
 *       （会抛/错断），也无花括号展开能力，故必须真解析器 + 引号化重试。</li>
 *   <li><b>coerce/extract 双段流程</b>——description 为数组/对象时 CC 不取首元素而是
 *       warn+null 回退 markdown 首行提取（frontmatterParser.ts:304-326 + markdownConfigLoader.ts:52），
 *       与 OutputStyleDirLoader 旧版语义相悖。</li>
 *   <li><b>content 不 trim</b>——CC frontmatterParser.ts:145 {@code markdown.slice(match[0].length)}
 *       原样保留正文首尾空白，是 P0-6 的行为变更契约。</li>
 * </ol>
 */
class ParseSkillFrontmatterTest {

    private final ParseSkillFrontmatter parser = new ParseSkillFrontmatter();

    // ═════════════════════════════════════════════════════════════════════
    // parseFrontmatter：FRONTMATTER_REGEX + content 不 trim + 无 match 原文
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无 frontmatter → frontmatter 空 Map，content 为原文（不 trim）")
    void parseFrontmatter_noMatch_contentKeepsOriginal() {
        String md = "  这只是正文，首尾有空白  \n\n第二行";
        ParseSkillFrontmatter.ParsedMarkdown parsed = parser.parseFrontmatter(md, null);

        assertThat(parsed.frontmatter()).isEmpty();
        assertThat(parsed.content()).isEqualTo(md);
    }

    @Test
    @DisplayName("有 frontmatter → content 不 trim（尾部空白保留）；closing --- 后的 \\s*\\n? 吞掉首行前导空白（CC 同款）")
    void parseFrontmatter_withMatch_contentNotTrimmed() {
        String md = "---\nname: inline-skill\n---\n正文内容  \n";
        ParseSkillFrontmatter.ParsedMarkdown parsed = parser.parseFrontmatter(md, null);

        assertThat(parsed.frontmatter()).containsEntry("name", "inline-skill");
        // CC frontmatterParser.ts:145 content = markdown.slice(match[0].length) —— 尾部空白不 trim
        assertThat(parsed.content()).isEqualTo("正文内容  \n");

        // closing --- 后的 `\s*\n?` 是贪婪的：会把首个非空行前导空白一并吞掉（CC FRONTMATTER_REGEX 同款行为）
        String mdLeading = "---\nname: x\n---\n  body  ";
        assertThat(parser.extractBody(mdLeading)).isEqualTo("body  ");
    }

    @Test
    @DisplayName("正文含非行首 --- 时，CC 非贪婪正则仅在首个 --- 截断")
    void parseFrontmatter_stopsAtFirstClosingDelimiter() {
        String md = "---\nname: a\n---\nbody with --- marker inside\n";
        ParseSkillFrontmatter.ParsedMarkdown parsed = parser.parseFrontmatter(md, null);

        assertThat(parsed.frontmatter()).containsEntry("name", "a");
        assertThat(parsed.content()).isEqualTo("body with --- marker inside\n");
    }

    @Test
    @DisplayName("parse/extractBody 保留签名委托 parseFrontmatter")
    void parse_and_extractBody_delegate() {
        String md = "---\ndescription: 委托测试\n---\n  body  ";
        assertThat(parser.parse(md)).containsEntry("description", "委托测试");
        // extractBody 不 trim：closing --- 后的前导空白被 `\s*\n?` 吞掉，body 尾空白保留
        assertThat(parser.extractBody(md)).isEqualTo("body  ");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 真 YAML 解析：标量/内联数组/列表 + 特殊字符引号化重试
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("真 YAML 解析：标量、内联数组、布尔、数字")
    void parse_realYaml_scalarsAndArrays() {
        String md = "---\n"
            + "name: my-skill\n"
            + "allowed-tools: [Bash, Read]\n"
            + "user-invocable: true\n"
            + "version: 3\n"
            + "description: 多行\n"
            + "  续行描述\n"
            + "---\nbody";
        Map<String, Object> fm = parser.parse(md);

        assertThat(fm.get("name")).isEqualTo("my-skill");
        assertThat(fm.get("allowed-tools")).isEqualTo(List.of("Bash", "Read"));
        assertThat(fm.get("user-invocable")).isEqualTo(Boolean.TRUE);
        assertThat(fm.get("version")).isEqualTo(3);
        // 真 YAML 解析器支持多行 plain 续行，手写行解析器无法处理
        assertThat(fm.get("description")).isEqualTo("多行 续行描述");
    }

    @Test
    @DisplayName("特殊字符值经 quoteProblemativeValues 重试后正确解析为字符串")
    void parse_specialChars_quoteRetry() {
        // description 值含 [ ] 流序列指示符，走引号化重试路径（frontmatterParser.ts:155-160）
        String md = "---\ndescription: Uses [Bash, Read]\n---\nbody";
        Map<String, Object> fm = parser.parse(md);

        assertThat(fm.get("description")).isEqualTo("Uses [Bash, Read]");
    }

    @Test
    @DisplayName("特殊字符 glob paths（**/*.{ts,tsx}）经重试解析为字符串，可被 splitPathInFrontmatter 展开")
    void parse_globPaths_quoteRetryThenExpand() {
        String md = "---\npaths: src/**/*.{ts,tsx}\n---\nbody";
        Map<String, Object> fm = parser.parse(md);

        assertThat(fm.get("paths")).isEqualTo("src/**/*.{ts,tsx}");
        assertThat(ParseSkillFrontmatter.splitPathInFrontmatter(fm.get("paths")))
            .containsExactly("src/**/*.ts", "src/**/*.tsx");
    }

    // ═════════════════════════════════════════════════════════════════════
    // coerceDescriptionToString
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("coerce：string trim / 空串→null / number→String / boolean→String")
    void coerce_stringAndPrimitives() {
        assertThat(ParseSkillFrontmatter.coerceDescriptionToString("  描述内容  ", "s", null))
            .isEqualTo("描述内容");
        assertThat(ParseSkillFrontmatter.coerceDescriptionToString("   ", "s", null)).isNull();
        assertThat(ParseSkillFrontmatter.coerceDescriptionToString(42, "s", null)).isEqualTo("42");
        assertThat(ParseSkillFrontmatter.coerceDescriptionToString(1.5, "s", null)).isEqualTo("1.5");
        assertThat(ParseSkillFrontmatter.coerceDescriptionToString(Boolean.TRUE, "s", null)).isEqualTo("true");
    }

    @Test
    @DisplayName("coerce：null→null；数组/对象非标量→null（omit，不取首元素）")
    void coerce_nullAndNonScalar() {
        assertThat(ParseSkillFrontmatter.coerceDescriptionToString(null, "s", null)).isNull();
        assertThat(ParseSkillFrontmatter.coerceDescriptionToString(List.of("a", "b"), "s", null)).isNull();
        assertThat(ParseSkillFrontmatter.coerceDescriptionToString(Map.of("k", "v"), "s", null)).isNull();
    }

    // ═════════════════════════════════════════════════════════════════════
    // extractDescriptionFromMarkdown
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extract：首个非空行 / header 去 # 前缀")
    void extract_firstNonEmptyAndHeaderStrip() {
        assertThat(ParseSkillFrontmatter.extractDescriptionFromMarkdown("\n\n首行描述\n第二行", "Skill"))
            .isEqualTo("首行描述");
        assertThat(ParseSkillFrontmatter.extractDescriptionFromMarkdown("\n# 技能标题\n正文", "Skill"))
            .isEqualTo("技能标题");
    }

    @Test
    @DisplayName("extract：>100 字符截断为 97+...；无内容回退 default")
    void extract_truncateAndDefault() {
        String longLine = "A".repeat(120);
        String extracted = ParseSkillFrontmatter.extractDescriptionFromMarkdown("# " + longLine, "Skill");
        assertThat(extracted).isEqualTo("A".repeat(97) + "...");
        assertThat(extracted).hasSize(100);

        assertThat(ParseSkillFrontmatter.extractDescriptionFromMarkdown("\n   \n\t", "Skill"))
            .isEqualTo("Skill");
    }

    // ═════════════════════════════════════════════════════════════════════
    // splitPathInFrontmatter / expandBraces（B4 花括号 glob 笛卡尔）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("splitPath：逗号分割 + 花括号内逗号不分隔 + 递归展开")
    void splitPath_commaAndBraceExpansion() {
        assertThat(ParseSkillFrontmatter.splitPathInFrontmatter("a, b"))
            .containsExactly("a", "b");
        // {a,b}/{c,d} 笛卡尔展开：a/c, a/d, b/c, b/d
        assertThat(ParseSkillFrontmatter.splitPathInFrontmatter("{a,b}/{c,d}"))
            .containsExactly("a/c", "a/d", "b/c", "b/d");
        // 花括号内逗号不是分隔符
        assertThat(ParseSkillFrontmatter.splitPathInFrontmatter("src/*.{ts,tsx}"))
            .containsExactly("src/*.ts", "src/*.tsx");
    }

    @Test
    @DisplayName("splitPath：List 递归 flatMap；非 String 非 List → 空")
    void splitPath_listAndNonString() {
        assertThat(ParseSkillFrontmatter.splitPathInFrontmatter(List.of("a", "src/*.{ts,tsx}")))
            .containsExactly("a", "src/*.ts", "src/*.tsx");
        assertThat(ParseSkillFrontmatter.splitPathInFrontmatter(42)).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // parseSkillPaths
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseSkillPaths：/** 去尾；全 ** → null；null/空 → null")
    void parseSkillPaths_stripAndMatchAll() {
        assertThat(ParseSkillFrontmatter.parseSkillPaths("src/**"))
            .containsExactly("src");
        assertThat(ParseSkillFrontmatter.parseSkillPaths("src/**/*.{ts,tsx}"))
            .containsExactly("src/**/*.ts", "src/**/*.tsx");
        assertThat(ParseSkillFrontmatter.parseSkillPaths("**")).isNull();
        assertThat(ParseSkillFrontmatter.parseSkillPaths("")).isNull();
        assertThat(ParseSkillFrontmatter.parseSkillPaths(List.of())).isNull();
        assertThat(ParseSkillFrontmatter.parseSkillPaths(null)).isNull();
    }

    // ═════════════════════════════════════════════════════════════════════
    // P1-5: parseEffortValue —— CC effort.ts:71-87
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseEffortValue：字符串档位 low/medium/high/max + 大小写归一 + 数字整数")
    void effort_levelsAndNumbers() {
        assertThat(ParseSkillFrontmatter.parseEffortValue("low")).isEqualTo("low");
        assertThat(ParseSkillFrontmatter.parseEffortValue("medium")).isEqualTo("medium");
        assertThat(ParseSkillFrontmatter.parseEffortValue("high")).isEqualTo("high");
        assertThat(ParseSkillFrontmatter.parseEffortValue("max")).isEqualTo("max");
        // CC :78 String(value).toLowerCase() —— 大小写归一
        assertThat(ParseSkillFrontmatter.parseEffortValue("MAX")).isEqualTo("max");
        // CC :75-76 number 且整数 → number
        assertThat(ParseSkillFrontmatter.parseEffortValue(5)).isEqualTo("5");
        assertThat(ParseSkillFrontmatter.parseEffortValue(5.0)).isEqualTo("5");
        assertThat(ParseSkillFrontmatter.parseEffortValue(0)).isEqualTo("0");
        // CC :82-84 字符串数字 → parseInt 后整数
        assertThat(ParseSkillFrontmatter.parseEffortValue("5")).isEqualTo("5");
    }

    @Test
    @DisplayName("parseEffortValue：JS parseInt 前导数字（5.5→5）；非法串/空/null → null")
    void effort_jsParseIntAndInvalid() {
        // JS parseInt("5.5")=5（首个非数字字符截断）—— Java Integer.parseInt 会抛，故手工 jsParseInt
        assertThat(ParseSkillFrontmatter.parseEffortValue("5.5")).isEqualTo("5");
        assertThat(ParseSkillFrontmatter.parseEffortValue("10px")).isEqualTo("10");
        assertThat(ParseSkillFrontmatter.parseEffortValue("abc")).isNull();
        assertThat(ParseSkillFrontmatter.parseEffortValue("")).isNull();
        assertThat(ParseSkillFrontmatter.parseEffortValue(null)).isNull();
        assertThat(ParseSkillFrontmatter.parseEffortValue("super-duper")).isNull();
    }

    // ═════════════════════════════════════════════════════════════════════
    // P1-5: parseShellFrontmatter —— CC frontmatterParser.ts:351-370
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseShellFrontmatter：bash/powershell 白名单命中 + 大小写/空白归一")
    void shell_whitelist() {
        assertThat(ParseSkillFrontmatter.parseShellFrontmatter("bash", "s")).isEqualTo("bash");
        assertThat(ParseSkillFrontmatter.parseShellFrontmatter("powershell", "s")).isEqualTo("powershell");
        // CC :358 String(value).trim().toLowerCase() —— "BASH" / " powershell " 归一
        assertThat(ParseSkillFrontmatter.parseShellFrontmatter("BASH", "s")).isEqualTo("bash");
        assertThat(ParseSkillFrontmatter.parseShellFrontmatter("  powershell  ", "s")).isEqualTo("powershell");
    }

    @Test
    @DisplayName("parseShellFrontmatter：null/空 → null；未知值 → null（warn 回退 bash）")
    void shell_nullAndUnknown() {
        assertThat(ParseSkillFrontmatter.parseShellFrontmatter(null, "s")).isNull();
        assertThat(ParseSkillFrontmatter.parseShellFrontmatter("", "s")).isNull();
        assertThat(ParseSkillFrontmatter.parseShellFrontmatter("   ", "s")).isNull();
        // CC :365-369 未知值 logForDebugging warn + undefined（回退 bash，不阻断加载）
        assertThat(ParseSkillFrontmatter.parseShellFrontmatter("zsh", "s")).isNull();
        assertThat(ParseSkillFrontmatter.parseShellFrontmatter(42, "s")).isNull();
    }

    // ═════════════════════════════════════════════════════════════════════
    // P1-5: parseHooksFromFrontmatter —— CC loadSkillsDir.ts:136-153 + hooks.ts:211-213
    // ═════════════════════════════════════════════════════════════════════

    /** 构造合法 matcher：{ matcher, hooks: [{type, ...}] }。 */
    private static Map<String, Object> matcher(String matcher, List<Map<String, Object>> hooks) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (matcher != null) m.put("matcher", matcher);
        m.put("hooks", hooks);
        return m;
    }

    private static Map<String, Object> hook(String type, String requiredField, String value) {
        Map<String, Object> h = new java.util.LinkedHashMap<>();
        h.put("type", type);
        h.put(requiredField, value);
        return h;
    }

    @Test
    @DisplayName("parseHooksFromFrontmatter：合法结构 → JSON 串（事件键/type/matcher/hooks 数组齐全）")
    void hooks_validJson() {
        Map<String, Object> hooks = new java.util.LinkedHashMap<>();
        hooks.put("PreToolUse", List.of(
            matcher("Write", List.of(hook("command", "command", "echo hi"))),
            matcher(null, List.of(hook("http", "url", "https://example.com/hook")))));

        String json = ParseSkillFrontmatter.parseHooksFromFrontmatter(hooks, "skill-x");

        assertThat(json).isNotNull();
        assertThat(json).contains("PreToolUse");
        assertThat(json).contains("\"matcher\":\"Write\"");
        assertThat(json).contains("\"type\":\"command\"");
        assertThat(json).contains("echo hi");
        assertThat(json).contains("\"type\":\"http\"");
    }

    @Test
    @DisplayName("parseHooksFromFrontmatter：matcher 缺省仍合法；null/非 Map → null")
    void hooks_nullAndMissingMatcher() {
        // CC hooks.ts:196-198 matcher 可选 —— 缺省不违法
        Map<String, Object> hooks = new java.util.LinkedHashMap<>();
        hooks.put("PreToolUse", List.of(matcher(null, List.of(hook("command", "command", "x")))));
        assertThat(ParseSkillFrontmatter.parseHooksFromFrontmatter(hooks, "skill-x")).isNotNull();

        // CC loadSkillsDir.ts:140-142 !frontmatter.hooks → undefined
        assertThat(ParseSkillFrontmatter.parseHooksFromFrontmatter(null, "skill-x")).isNull();
        assertThat(ParseSkillFrontmatter.parseHooksFromFrontmatter("not-a-map", "skill-x")).isNull();
    }

    @Test
    @DisplayName("parseHooksFromFrontmatter：未知事件键 → 整体丢弃（Zod strict，非逐键跳过）")
    void hooks_unknownEventKey_dropsAll() {
        Map<String, Object> hooks = new java.util.LinkedHashMap<>();
        hooks.put("NotARealEvent", List.of(matcher(null, List.of(hook("command", "command", "x")))));

        // CC hooks.ts:211-213 partialRecord(enum(HOOK_EVENTS))：带外键 → safeParse 整体失败 → undefined
        assertThat(ParseSkillFrontmatter.parseHooksFromFrontmatter(hooks, "skill-x")).isNull();
    }

    @Test
    @DisplayName("parseHooksFromFrontmatter：hooks 数组缺/项缺 type/type 缺必填字段 → 整体丢弃")
    void hooks_malformed_dropsAll() {
        // matcher 缺 hooks 数组 → 整体丢弃（CC hooks.ts:200-201 hooks: array(HookCommandSchema)）
        Map<String, Object> matcherNoHooks = new java.util.LinkedHashMap<>();
        matcherNoHooks.put("matcher", "Write");
        Map<String, Object> hooks1 = new java.util.LinkedHashMap<>();
        hooks1.put("PreToolUse", List.of(matcherNoHooks));
        assertThat(ParseSkillFrontmatter.parseHooksFromFrontmatter(hooks1, "skill-x")).isNull();

        // hooks 项缺 type（discriminatedUnion 无判别值）→ 整体丢弃
        Map<String, Object> hookNoType = new java.util.LinkedHashMap<>();
        hookNoType.put("command", "echo");
        Map<String, Object> hooks2 = new java.util.LinkedHashMap<>();
        hooks2.put("PreToolUse", List.of(matcher(null, List.of(hookNoType))));
        assertThat(ParseSkillFrontmatter.parseHooksFromFrontmatter(hooks2, "skill-x")).isNull();

        // type=command 缺必填 command 字段（Zod object 必填）→ 整体丢弃
        Map<String, Object> hookNoField = new java.util.LinkedHashMap<>();
        hookNoField.put("type", "command");
        Map<String, Object> hooks3 = new java.util.LinkedHashMap<>();
        hooks3.put("PreToolUse", List.of(matcher(null, List.of(hookNoField))));
        assertThat(ParseSkillFrontmatter.parseHooksFromFrontmatter(hooks3, "skill-x")).isNull();

        // 事件值不是数组 → 整体丢弃
        Map<String, Object> hooks4 = new java.util.LinkedHashMap<>();
        hooks4.put("PreToolUse", "not-a-list");
        assertThat(ParseSkillFrontmatter.parseHooksFromFrontmatter(hooks4, "skill-x")).isNull();
    }
}
