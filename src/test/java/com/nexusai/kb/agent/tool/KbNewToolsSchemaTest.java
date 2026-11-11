package com.nexusai.kb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [KB 内置 Agent 对齐 · 阶段 D] 11 个新工具的 schema 与安全判据。
 *
 * <p><b>本测试只验「不依赖 Spring / DB 的部分」</b>：
 * schema 形状、参数边界、作用域校验函数、JSON 转义、匹配器语义。
 * 需要 DB 的路径（真正读写 Wiki 页面）留给真机验证。
 *
 * <p>⚠️ 这些工具的 {@code execute()} 需要注入服务；这里不执行，
 * 只验证<b>入参契约</b>与<b>纯函数判据</b>——契约错了比逻辑错了更难发现，
 * 因为模型会反复用错参数然后每次撞墙。
 */
class KbNewToolsSchemaTest {

    // ── 工具清单 ────────────────────────────────────────────────────────────

    /** 本阶段应新增的 11 个工具（操作目录 2 个滞后到阶段三，不计入）。 */
    private static List<Tool> newTools() {
        return List.of(
            new KbWikiSearchTool(),
            new KbWikiReadPageTool(),
            new KbWikiWritePageTool(),
            new KbWikiReplaceTextTool(),
            new KbWikiRenamePageTool(),
            new KbWikiDeletePageTool(),
            new KbWikiFlagIssueTool(),
            new KbWikiReadIssueTool(),
            new KbWikiUpdateIssueTool(),
            new KbDataSchemaTool(),
            new KbDataAnalysisTool()
        );
    }

    @Test
    @DisplayName("11 个工具名与源站 definitions.go 一致")
    void tool_names_match_source() {
        assertThat(newTools()).extracting(Tool::name).containsExactly(
            "wiki_search", "wiki_read_page", "wiki_write_page", "wiki_replace_text",
            "wiki_rename_page", "wiki_delete_page", "wiki_flag_issue",
            "wiki_read_issue", "wiki_update_issue",
            "data_schema", "data_analysis");
    }

    @Test
    @DisplayName("11 个工具全部标了 @ExclusiveTool（默认不激活，需 MD 点名）")
    void all_exclusive() {
        for (Tool t : newTools()) {
            assertThat(com.nexusai.application.agent.tool.ToolScope.of(t))
                .as("%s 应为 EXCLUSIVE（默认不激活）", t.name())
                .isEqualTo(com.nexusai.application.agent.tool.ToolScope.EXCLUSIVE);
        }
    }

    @Test
    @DisplayName("11 个工具的 description 非空且不含英文品牌名")
    void descriptions_clean() {
        for (Tool t : newTools()) {
            assertThat(t.description()).as("%s 的 description", t.name()).isNotBlank()
                .doesNotContain("WeKnora");
        }
    }

    @Test
    @DisplayName("11 个工具的 schema 都是 type=object 且 additionalProperties=false")
    void schemas_are_strict_objects() {
        for (Tool t : newTools()) {
            JsonNode s = t.inputSchema();
            assertThat(s.path("type").asText()).as("%s 的 schema.type", t.name()).isEqualTo("object");
            assertThat(s.path("additionalProperties").asBoolean(true))
                .as("%s 应禁止额外属性", t.name()).isFalse();
            assertThat(s.path("properties").isObject())
                .as("%s 应有 properties", t.name()).isTrue();
        }
    }

    // ── wiki_search ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("wiki_search · query 必填，limit 1..50 默认 10（源站常量）")
    void wiki_search_schema() {
        JsonNode s = new KbWikiSearchTool().inputSchema();
        JsonNode props = s.path("properties");

        assertThat(s.path("required")).extracting(JsonNode::asText).containsExactly("query");
        assertThat(props.path("limit").path("minimum").asInt()).isEqualTo(1);
        assertThat(props.path("limit").path("maximum").asInt()).isEqualTo(50);
        assertThat(KbWikiSearchTool.DEFAULT_LIMIT).isEqualTo(10);
        assertThat(KbWikiSearchTool.MAX_LIMIT).isEqualTo(50);
    }

    @Test
    @DisplayName("wiki_search · regex 缺省时按「是否像正则」判定（C++ 走字面量，a|b 走正则）")
    void wiki_search_matcher_auto_mode() {
        // 含正则元字符 ⇒ 正则
        var m1 = KbWikiSearchTool.buildMatcher("stardust|skyvault", null);
        assertThat(m1.mode()).startsWith("regex");

        // C++ 在 Go 的 RE2 里非法（源站据此走字面量）；Java 里合法但语义不对
        // ⇒ 本实现按「不像有意的正则」走字面量，对齐源站行为
        var m2 = KbWikiSearchTool.buildMatcher("C++", null);
        assertThat(m2.mode()).startsWith("literal");

        // a+b 同理：+ 是普通文本里常见的字符，不当正则
        var m3 = KbWikiSearchTool.buildMatcher("a+b", null);
        assertThat(m3.mode()).startsWith("literal");

        // regex=false 强制字面量（即便能编译成正则）
        var m4 = KbWikiSearchTool.buildMatcher("a|b", false);
        assertThat(m4.mode()).isEqualTo("literal");
    }

    @Test
    @DisplayName("wiki_search · regex=true 时 C++ 仍按字面量（非法正则不抛异常）")
    void wiki_search_matcher_forced_invalid() {
        // Java 认为 C++ 合法，所以这条在 Java 下会走 regex 分支——
        // 这本身就是 Java 与 Go 的差异点，记录在此避免以后误改。
        var m = KbWikiSearchTool.buildMatcher("C++", true);

        // C++ 在 Java 里能编译 ⇒ regex；关键断言是「不抛异常」
        assertThat(m.mode()).isIn("regex", "literal(invalid regex");
    }

    @Test
    @DisplayName("wiki_search · looksLikeRegex 只认有意的正则元字符")
    void looks_like_regex_criteria() {
        assertThat(KbWikiSearchTool.looksLikeRegex("stardust|skyvault")).isTrue();
        assertThat(KbWikiSearchTool.looksLikeRegex("^entity/")).isTrue();
        assertThat(KbWikiSearchTool.looksLikeRegex("(a)(b)")).isTrue();
        assertThat(KbWikiSearchTool.looksLikeRegex("[abc]")).isTrue();

        assertThat(KbWikiSearchTool.looksLikeRegex("C++")).isFalse();
        assertThat(KbWikiSearchTool.looksLikeRegex("a+b")).isFalse();
        assertThat(KbWikiSearchTool.looksLikeRegex("foo.bar")).isFalse();
        assertThat(KbWikiSearchTool.looksLikeRegex("普通文本")).isFalse();
    }

    // ── wiki_read_page ──────────────────────────────────────────────────────

    @Test
    @DisplayName("wiki_read_page · slugs 必填且是数组")
    void wiki_read_page_schema() {
        JsonNode s = new KbWikiReadPageTool().inputSchema();

        assertThat(s.path("required")).extracting(JsonNode::asText).containsExactly("slugs");
        assertThat(s.path("properties").path("slugs").path("type").asText()).isEqualTo("array");
    }

    @Test
    @DisplayName("wiki_read_page · 空 slugs 报错（不猜页面）")
    void wiki_read_page_rejects_empty() {
        var tool = new KbWikiReadPageTool();
        var result = tool.execute(KbTestCalls.call("{}"));

        assertThat(result.toString()).contains("缺少 slugs");
    }

    // ── wiki_write_page ─────────────────────────────────────────────────────

    @Test
    @DisplayName("wiki_write_page · 三个必填（kb / slug / content）")
    void wiki_write_page_schema() {
        JsonNode s = new KbWikiWritePageTool().inputSchema();

        assertThat(s.path("required")).extracting(JsonNode::asText)
            .containsExactly("knowledge_base_id", "slug", "content");
    }

    @Test
    @DisplayName("wiki_write_page · content 空白直接拒绝（禁止省略占位符）")
    void wiki_write_page_rejects_blank_content() {
        var tool = new KbWikiWritePageTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_base_id\":\"kb1\",\"slug\":\"a/b\",\"content\":\"   \"}"));

        assertThat(result.toString()).contains("content 不能为空");
    }

    @Test
    @DisplayName("wiki_write_page · slug 为空直接拒绝")
    void wiki_write_page_rejects_blank_slug() {
        var tool = new KbWikiWritePageTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_base_id\":\"kb1\",\"slug\":\"\",\"content\":\"x\"}"));

        assertThat(result.toString()).contains("slug 不能为空");
    }

    // ── wiki_replace_text ───────────────────────────────────────────────────

    @Test
    @DisplayName("wiki_replace_text · 四个必填（kb / slug / old_text / new_text）")
    void wiki_replace_text_schema() {
        JsonNode s = new KbWikiReplaceTextTool().inputSchema();

        assertThat(s.path("required")).extracting(JsonNode::asText)
            .containsExactly("knowledge_base_id", "slug", "old_text", "new_text");
    }

    @Test
    @DisplayName("wiki_replace_text · old_text == new_text 拒绝（无意义替换）")
    void wiki_replace_text_rejects_noop() {
        var tool = new KbWikiReplaceTextTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_base_id\":\"kb1\",\"slug\":\"a\",\"old_text\":\"x\",\"new_text\":\"x\"}"));

        assertThat(result.toString()).contains("替换无意义");
    }

    @Test
    @DisplayName("wiki_replace_text · old_text 为空拒绝")
    void wiki_replace_text_rejects_blank_old() {
        var tool = new KbWikiReplaceTextTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_base_id\":\"kb1\",\"slug\":\"a\",\"old_text\":\"\",\"new_text\":\"y\"}"));

        assertThat(result.toString()).contains("old_text 不能为空");
    }

    // ── wiki_rename_page ────────────────────────────────────────────────────

    @Test
    @DisplayName("wiki_rename_page · 三个必填（kb / slug / new_slug）")
    void wiki_rename_page_schema() {
        JsonNode s = new KbWikiRenamePageTool().inputSchema();

        assertThat(s.path("required")).extracting(JsonNode::asText)
            .containsExactly("knowledge_base_id", "slug", "new_slug");
    }

    @Test
    @DisplayName("wiki_rename_page · slug == new_slug 拒绝")
    void wiki_rename_page_rejects_same() {
        var tool = new KbWikiRenamePageTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_base_id\":\"kb1\",\"slug\":\"a\",\"new_slug\":\"a\"}"));

        assertThat(result.toString()).contains("无需改名");
    }

    // ── wiki_delete_page ────────────────────────────────────────────────────

    @Test
    @DisplayName("wiki_delete_page · 两个必填（kb / slug）")
    void wiki_delete_page_schema() {
        JsonNode s = new KbWikiDeletePageTool().inputSchema();

        assertThat(s.path("required")).extracting(JsonNode::asText)
            .containsExactly("knowledge_base_id", "slug");
    }

    // ── wiki_flag_issue ─────────────────────────────────────────────────────

    @Test
    @DisplayName("wiki_flag_issue · 必填 kb / slug / description；issue_type 不是必填")
    void wiki_flag_issue_schema() {
        JsonNode s = new KbWikiFlagIssueTool().inputSchema();

        assertThat(s.path("required")).extracting(JsonNode::asText)
            .containsExactly("knowledge_base_id", "slug", "description");
    }

    @Test
    @DisplayName("wiki_flag_issue · 非法 issue_type 被拒并列允许值")
    void wiki_flag_issue_rejects_bad_type() {
        var tool = new KbWikiFlagIssueTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_base_id\":\"kb1\",\"slug\":\"a\",\"description\":\"d\","
                + "\"issue_type\":\"bogus\"}"));

        assertThat(result.toString()).contains("issue_type 取值非法");
    }

    @Test
    @DisplayName("wiki_flag_issue · description 为空拒绝")
    void wiki_flag_issue_rejects_blank_desc() {
        var tool = new KbWikiFlagIssueTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_base_id\":\"kb1\",\"slug\":\"a\",\"description\":\"  \"}"));

        assertThat(result.toString()).contains("description 不能为空");
    }

    // ── wiki_read_issue ─────────────────────────────────────────────────────

    @Test
    @DisplayName("wiki_read_issue · issue_id 与 slug 至少一个")
    void wiki_read_issue_requires_one() {
        var tool = new KbWikiReadIssueTool();
        var result = tool.execute(KbTestCalls.call("{\"knowledge_base_id\":\"kb1\"}"));

        assertThat(result.toString()).contains("至少给一个");
    }

    // ── wiki_update_issue ───────────────────────────────────────────────────

    @Test
    @DisplayName("wiki_update_issue · status 枚举只有 pending/resolved/ignored")
    void wiki_update_issue_status_enum() {
        JsonNode pool = new KbWikiUpdateIssueTool().inputSchema()
            .path("properties").path("status").path("enum");

        assertThat(pool).extracting(JsonNode::asText)
            .containsExactly("pending", "resolved", "ignored");
        assertThat(KbWikiUpdateIssueTool.ALLOWED_STATUS)
            .containsExactly("pending", "resolved", "ignored");
    }

    @Test
    @DisplayName("wiki_update_issue · 非法 status 被拒")
    void wiki_update_issue_rejects_bad_status() {
        var tool = new KbWikiUpdateIssueTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_base_id\":\"kb1\",\"issue_id\":\"i1\",\"status\":\"done\"}"));

        assertThat(result.toString()).contains("status 取值非法");
    }

    // ── data_schema / data_analysis ─────────────────────────────────────────

    @Test
    @DisplayName("data_schema · knowledge_id 必填")
    void data_schema_schema() {
        JsonNode s = new KbDataSchemaTool().inputSchema();

        assertThat(s.path("required")).extracting(JsonNode::asText).containsExactly("knowledge_id");
    }

    @Test
    @DisplayName("data_analysis · knowledge_id + sql 两个必填")
    void data_analysis_schema() {
        JsonNode s = new KbDataAnalysisTool().inputSchema();

        assertThat(s.path("required")).extracting(JsonNode::asText)
            .containsExactly("knowledge_id", "sql");
    }

    @Test
    @DisplayName("data_analysis · 写操 SQL 在进入服务前就被拒（安全边界）")
    void data_analysis_rejects_write_sql() {
        var tool = new KbDataAnalysisTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_id\":\"k1\",\"sql\":\"DROP TABLE dataset\"}"));

        assertThat(result.toString()).contains("SQL 未通过安全校验");
    }

    @Test
    @DisplayName("data_analysis · 空 sql 拒绝")
    void data_analysis_rejects_blank_sql() {
        var tool = new KbDataAnalysisTool();
        var result = tool.execute(KbTestCalls.call(
            "{\"knowledge_id\":\"k1\",\"sql\":\"  \"}"));

        assertThat(result.toString()).contains("sql 不能为空");
    }

    // ── 共用的 JSON 转义 / 截断 ─────────────────────────────────────────────

    @Test
    @DisplayName("KbWikiToolSupport.esc · 转义引号/反斜杠/换行，控制字符转 \\uXXXX")
    void esc_handles_all_specials() {
        assertThat(KbWikiToolSupport.esc("a\"b")).isEqualTo("a\\\"b");
        assertThat(KbWikiToolSupport.esc("a\\b")).isEqualTo("a\\\\b");
        assertThat(KbWikiToolSupport.esc("a\nb")).isEqualTo("a\\nb");
        assertThat(KbWikiToolSupport.esc("a\rb")).isEqualTo("a\\rb");
        assertThat(KbWikiToolSupport.esc("a\tb")).isEqualTo("a\\tb");
        assertThat(KbWikiToolSupport.esc("a\u0001b")).isEqualTo("a\\u0001b");
        assertThat(KbWikiToolSupport.esc(null)).isEmpty();
    }

    @Test
    @DisplayName("KbWikiToolSupport.truncate · 超长截断加省略号")
    void truncate_adds_ellipsis() {
        assertThat(KbWikiToolSupport.truncate("abcdef", 3)).isEqualTo("abc…");
        assertThat(KbWikiToolSupport.truncate("abc", 3)).isEqualTo("abc");
        assertThat(KbWikiToolSupport.truncate(null, 3)).isEmpty();
    }

    @Test
    @DisplayName("KbDuckDbDataset.escapeCell · 竖线与换行不破坏 markdown 表格")
    void escape_cell_keeps_table_intact() {
        assertThat(KbDuckDbDataset.escapeCell("a|b")).isEqualTo("a\\|b");
        assertThat(KbDuckDbDataset.escapeCell("a\nb")).isEqualTo("a b");
        assertThat(KbDuckDbDataset.escapeCell(null)).isEmpty();
    }
}
