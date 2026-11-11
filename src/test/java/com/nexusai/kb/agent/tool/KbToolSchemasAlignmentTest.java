package com.nexusai.kb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [KB 内置 Agent 对齐 · 阶段 C] 四个既有 KB 工具的入参契约。
 *
 * <p>判据来自源站 {@code internal/agent/tools/*.go} 的 schema 定义：
 * <ul>
 *   <li>{@code search_knowledge}: {@code {query*, mode(hybrid|semantic|keyword),
 *       knowledge_base_ids(≤10), limit(1..30 默认 10)}}</li>
 *   <li>{@code read_document}: {@code {id*(doc://N|chunk://N), offset, limit(1..100 默认 20),
 *       query, regex, context(0..5)}} + 返回 {@code next_offset}</li>
 *   <li>{@code list_documents}: {@code {knowledge_base_id*, keyword, page(≥1),
 *       page_size(1..100 默认 20)}}</li>
 *   <li>{@code query_knowledge_graph}: {@code {knowledge_base_ids(array), query}}</li>
 * </ul>
 */
class KbToolSchemasAlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode schemaOf(com.nexusai.application.agent.tool.Tool tool) {
        return tool.inputSchema();
    }

    // ── search_knowledge ───────────────────────────────────────────────────

    @Test
    @DisplayName("search_knowledge · schema 有 query/mode/knowledge_base_ids/limit，只有 query 必填")
    void search_knowledge_schema() {
        JsonNode s = schemaOf(new KbSearchKnowledgeTool());
        JsonNode props = s.path("properties");

        assertThat(props.has("query")).isTrue();
        assertThat(props.has("mode")).isTrue();
        assertThat(props.has("knowledge_base_ids")).isTrue();
        assertThat(props.has("limit")).isTrue();

        assertThat(s.path("required")).extracting(JsonNode::asText).containsExactly("query");
    }

    @Test
    @DisplayName("search_knowledge · mode 只能是 hybrid / semantic / keyword")
    void search_knowledge_mode_enum() {
        JsonNode mode = schemaOf(new KbSearchKnowledgeTool()).path("properties").path("mode");

        assertThat(mode.path("enum")).extracting(JsonNode::asText)
            .containsExactly("hybrid", "semantic", "keyword");
    }

    @Test
    @DisplayName("search_knowledge · limit 边界为 1..30（源站上限 30）")
    void search_knowledge_limit_bounds() {
        JsonNode limit = schemaOf(new KbSearchKnowledgeTool()).path("properties").path("limit");

        assertThat(limit.path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("maximum").asInt()).isEqualTo(30);
    }

    @Test
    @DisplayName("search_knowledge · knowledge_base_ids 是字符串数组")
    void search_knowledge_kb_ids_is_array() {
        JsonNode kbIds = schemaOf(new KbSearchKnowledgeTool())
            .path("properties").path("knowledge_base_ids");

        assertThat(kbIds.path("type").asText()).isEqualTo("array");
        assertThat(kbIds.path("items").path("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("search_knowledge · readStringArray 去重且截断到上限")
    void read_string_array_dedup_and_truncate() throws Exception {
        ObjectNode in = MAPPER.createObjectNode();
        var arr = in.putArray("ids");
        arr.add("a").add("b").add("a").add(" c ").add("d").add("e");

        assertThat(KbSearchKnowledgeTool.readStringArray(in, "ids", 3))
            .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("search_knowledge · readStringArray 对非数组/缺失字段返回空（不抛）")
    void read_string_array_tolerates_garbage() throws Exception {
        ObjectNode in = MAPPER.createObjectNode();
        in.put("ids", "not-an-array");

        assertThat(KbSearchKnowledgeTool.readStringArray(in, "ids", 10)).isEmpty();
        assertThat(KbSearchKnowledgeTool.readStringArray(in, "missing", 10)).isEmpty();
    }

    // ── KbHybridSearcher.normalizeMode ─────────────────────────────────────

    @Test
    @DisplayName("normalizeMode · null / 空 / 未知值都回落 hybrid（源站默认）")
    void normalize_mode_fallbacks() {
        assertThat(KbHybridSearcher.normalizeMode(null)).isEqualTo("hybrid");
        assertThat(KbHybridSearcher.normalizeMode("")).isEqualTo("hybrid");
        assertThat(KbHybridSearcher.normalizeMode("   ")).isEqualTo("hybrid");
        assertThat(KbHybridSearcher.normalizeMode("bogus")).isEqualTo("hybrid");
    }

    @Test
    @DisplayName("normalizeMode · 大小写与空白不敏感")
    void normalize_mode_case_insensitive() {
        assertThat(KbHybridSearcher.normalizeMode("SEMANTIC")).isEqualTo("semantic");
        assertThat(KbHybridSearcher.normalizeMode(" Keyword ")).isEqualTo("keyword");
        assertThat(KbHybridSearcher.normalizeMode("hybrid")).isEqualTo("hybrid");
    }

    // ── read_document ──────────────────────────────────────────────────────

    @Test
    @DisplayName("read_document · schema 是源站那 6 个字段，只有 id 必填")
    void read_document_schema() {
        JsonNode s = schemaOf(new KbReadDocumentTool());
        JsonNode props = s.path("properties");

        assertThat(props.has("id")).isTrue();
        assertThat(props.has("offset")).isTrue();
        assertThat(props.has("limit")).isTrue();
        assertThat(props.has("query")).isTrue();
        assertThat(props.has("regex")).isTrue();
        assertThat(props.has("context")).isTrue();

        // 旧字段必须消失（语义已被 id 取代）
        assertThat(props.has("knowledge_id")).as("旧入参 knowledge_id 应已移除").isFalse();
        assertThat(props.has("max_chunks")).as("旧入参 max_chunks 应已移除").isFalse();

        assertThat(s.path("required")).extracting(JsonNode::asText).containsExactly("id");
    }

    @Test
    @DisplayName("read_document · limit 1..100 / context 0..5（源站常量）")
    void read_document_bounds() {
        JsonNode props = schemaOf(new KbReadDocumentTool()).path("properties");

        assertThat(props.path("limit").path("minimum").asInt()).isEqualTo(1);
        assertThat(props.path("limit").path("maximum").asInt()).isEqualTo(100);
        assertThat(props.path("context").path("minimum").asInt()).isEqualTo(0);
        assertThat(props.path("context").path("maximum").asInt()).isEqualTo(5);
        assertThat(props.path("offset").path("minimum").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("read_document · 常量与源站 read_document.go 一致")
    void read_document_constants() {
        assertThat(KbReadDocumentTool.DEFAULT_LIMIT).isEqualTo(20);
        assertThat(KbReadDocumentTool.MAX_LIMIT).isEqualTo(100);
        assertThat(KbReadDocumentTool.MAX_CONTEXT).isEqualTo(5);
        assertThat(KbReadDocumentTool.MAX_MATCHES).isEqualTo(20);
    }

    @Test
    @DisplayName("read_document · 空 id 直接报错（不猜文档）")
    void read_document_rejects_blank_id() {
        var tool = new KbReadDocumentTool();
        var call = fakeCall(MAPPER.createObjectNode());

        var result = tool.execute(call);
        assertThat(result.toString()).contains("id 不能为空");
    }

    @Test
    @DisplayName("read_document · doc:// 后缺 id 报错")
    void read_document_rejects_empty_doc_id() {
        var tool = new KbReadDocumentTool();
        ObjectNode in = MAPPER.createObjectNode();
        in.put("id", "doc://");

        var result = tool.execute(fakeCall(in));
        assertThat(result.toString()).contains("缺少 knowledge_id");
    }

    @Test
    @DisplayName("read_document · 灾难性回溯正则只告警不阻断（可观测性判据）")
    void read_document_catastrophic_regex_detected() {
        assertThat(KbReadDocumentTool.looksCatastrophic("(a+)+")).isTrue();
        assertThat(KbReadDocumentTool.looksCatastrophic("(a*)*")).isTrue();
        assertThat(KbReadDocumentTool.looksCatastrophic("a+b")).isFalse();
        assertThat(KbReadDocumentTool.looksCatastrophic("\\d{3}-\\d{4}")).isFalse();
    }

    // ── list_documents ─────────────────────────────────────────────────────

    @Test
    @DisplayName("list_documents · schema 对齐源站（knowledge_base_id 必填 + 分页）")
    void list_documents_schema() {
        JsonNode s = schemaOf(new KbListDocumentsTool());
        JsonNode props = s.path("properties");

        assertThat(props.has("knowledge_base_id")).isTrue();
        assertThat(props.has("keyword")).isTrue();
        assertThat(props.has("page")).isTrue();
        assertThat(props.has("page_size")).isTrue();

        // 旧字段必须消失
        assertThat(props.has("name_contains")).as("旧入参 name_contains 应已移除").isFalse();

        assertThat(s.path("required")).extracting(JsonNode::asText)
            .containsExactly("knowledge_base_id");
    }

    @Test
    @DisplayName("list_documents · page ≥1 / page_size 1..100（源站默认 20）")
    void list_documents_bounds() {
        JsonNode props = schemaOf(new KbListDocumentsTool()).path("properties");

        assertThat(props.path("page").path("minimum").asInt()).isEqualTo(1);
        assertThat(props.path("page_size").path("minimum").asInt()).isEqualTo(1);
        assertThat(props.path("page_size").path("maximum").asInt()).isEqualTo(100);
        assertThat(KbListDocumentsTool.DEFAULT_PAGE_SIZE).isEqualTo(20);
        assertThat(KbListDocumentsTool.MAX_PAGE_SIZE).isEqualTo(100);
    }

    @Test
    @DisplayName("list_documents · 空 knowledge_base_id 直接报错")
    void list_documents_rejects_blank_kb() {
        var tool = new KbListDocumentsTool();
        var result = tool.execute(fakeCall(MAPPER.createObjectNode()));

        assertThat(result.toString()).contains("knowledge_base_id 不能为空");
    }

    // ── query_knowledge_graph ──────────────────────────────────────────────

    @Test
    @DisplayName("query_knowledge_graph · schema 是数组 + query，旧的两个 max_* 已移除")
    void graph_schema() {
        JsonNode s = schemaOf(new KbQueryGraphTool());
        JsonNode props = s.path("properties");

        assertThat(props.has("knowledge_base_ids")).isTrue();
        assertThat(props.path("knowledge_base_ids").path("type").asText()).isEqualTo("array");
        assertThat(props.has("query")).isTrue();

        assertThat(props.has("knowledge_base_id")).as("旧的单数入参应已移除").isFalse();
        assertThat(props.has("max_entities")).as("本仓自加的 max_entities 应已移除").isFalse();
        assertThat(props.has("max_relations")).as("本仓自加的 max_relations 应已移除").isFalse();
    }

    @Test
    @DisplayName("query_knowledge_graph · 无必填字段（源站 schema 也没有 required）")
    void graph_schema_has_no_required() {
        JsonNode s = schemaOf(new KbQueryGraphTool());

        assertThat(s.has("required")).isFalse();
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    /** 构造一个最小 ToolUseBlock（只填 id / name / input）。 */
    private static com.nexusai.application.agent.tool.ToolUseBlock fakeCall(JsonNode input) {
        return new com.nexusai.application.agent.tool.ToolUseBlock(
            "toolu_test", "test_tool", input);
    }
}
