package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session L+ · {@link ToolResult} META_OUTPUT_TYPE 常量 + typedResult 工厂契约验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * R4 抽 {@link ToolResult#META_OUTPUT_TYPE} 常量替代 5 个工厂方法内的 "read_file_output_type" 字符串
 * 重复. typedResult 私有工厂集中维护成功 + 结构化 metadata 包装. 本测试断言 5 个工厂方法:
 * <ol>
 *   <li>metadata 都含 {@code META_OUTPUT_TYPE} key (集中化契约)</li>
 *   <li>每个 type 值 (image / pdf / notebook / parts / file_unchanged) 正确 (CC union 对齐)</li>
 *   <li>常量值是 "read_file_output_type" (CC 端 L 已定)</li>
 * </ol>
 */
@DisplayName("Session L+ · ToolResult META_OUTPUT_TYPE 常量 + typedResult 工厂")
class ToolResultTest {

    @Test
    @DisplayName("META_OUTPUT_TYPE 常量值 = 'read_file_output_type' (CC L 已定, 不擅自改)")
    void metaOutputTypeConstant() {
        assertThat(ToolResult.META_OUTPUT_TYPE)
            .as("L 决策已定, 任何 R4 改动不能改字符串值 (下游消费者已硬编码 'read_file_output_type')")
            .isEqualTo("read_file_output_type");
    }

    @Test
    @DisplayName("image 工厂: data(JsonNode) 含 META_OUTPUT_TYPE=image + base64 + mediaType (CC union 完整)")
    void imageFactoryUsesMetaOutputTypeConstant() {
        ToolResult result = ToolResult.image("call-1", "Read image (42KB)",
            "BASE64DATA", "image/png", 42_000L, null);

        // A1 退役 metadata 旁路后, 结构化字段折入 data(JsonNode) (CC data:T).
        JsonNode data = (JsonNode) result.data();
        assertThat(data.get(ToolResult.META_OUTPUT_TYPE).asText())
            .as("image 分支 read_file_output_type=image (CC union discriminator)")
            .isEqualTo("image");
        assertThat(data.get(ToolResult.META_IMAGE_BASE64).asText()).isEqualTo("BASE64DATA");
        assertThat(data.get(ToolResult.META_IMAGE_MEDIA_TYPE).asText()).isEqualTo("image/png");
        assertThat(data.get("read_file_original_size").asText()).isEqualTo("42000");
        // [IMP-C2] ToolResult 4 字段契约：无 isError 字段（组 2-1 拍板），改断言 data 形状完整
        assertThat(result.newMessages()).isEmpty();
        assertThat(result.contextModifier()).isNull();
        assertThat(result.mcpMeta()).isNull();
    }

    @Test
    @DisplayName("pdf 工厂: data(JsonNode) 含 META_OUTPUT_TYPE=pdf (P-CC-01 起 ReadFileTool.dispatchPdfFull 真实可达; P-AL-01 起携带 newMessages)")
    void pdfFactoryUsesMetaOutputTypeConstant() {
        ToolResult result = ToolResult.pdf("call-1", "Read PDF (1MB)",
            "BASE64PDF", "application/pdf", 1_000_000L, java.util.List.of());

        JsonNode data = (JsonNode) result.data();
        assertThat(data.get(ToolResult.META_OUTPUT_TYPE).asText()).isEqualTo("pdf");
        assertThat(data.get(ToolResult.META_DOCUMENT_BASE64).asText()).isEqualTo("BASE64PDF");
        assertThat(data.get(ToolResult.META_DOCUMENT_MEDIA_TYPE).asText()).isEqualTo("application/pdf");
        assertThat(result.newMessages()).isEmpty();
    }

    @Test
    @DisplayName("notebook 工厂: data(JsonNode) 含 META_OUTPUT_TYPE=notebook + processed cells + <cell> 渲染 + file path")
    void notebookFactoryUsesMetaOutputTypeConstant() {
        com.fasterxml.jackson.databind.node.ArrayNode cells =
            new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        cells.addObject().put("cellType", "code").put("source", "print('hi')")
            .put("cell_id", "cell-0").put("language", "python");
        ToolResult result = ToolResult.notebook("call-1", "Read notebook (3 cells)",
            cells, "[{\"cellType\":\"code\"}]",
            "<cell id=\"cell-0\">print('hi')</cell id=\"cell-0\">", "docs/analysis.ipynb");

        JsonNode data = (JsonNode) result.data();
        assertThat(data.get(ToolResult.META_OUTPUT_TYPE).asText()).isEqualTo("notebook");
        assertThat(data.get("notebook_cells_json").asText()).isEqualTo("[{\"cellType\":\"code\"}]");
        assertThat(data.get("notebook_rendered").asText()).contains("<cell id=\"cell-0\">");
        assertThat(data.get("notebook_file_path").asText()).isEqualTo("docs/analysis.ipynb");
    }

    @Test
    @DisplayName("parts 工厂: data(JsonNode) 含 META_OUTPUT_TYPE=parts (P-CC-01 起 ReadFileTool.dispatchPdfPages 真实可达; P-AL-01 起携带 newMessages)")
    void partsFactoryUsesMetaOutputTypeConstant() {
        ToolResult result = ToolResult.parts("call-1", "Read PDF parts (5 pages)",
            "doc.pdf", 500_000L, 5, "/tmp/pdf-pages/", java.util.List.of());

        JsonNode data = (JsonNode) result.data();
        assertThat(data.get(ToolResult.META_OUTPUT_TYPE).asText()).isEqualTo("parts");
        assertThat(data.get("read_file_original_size").asText()).isEqualTo("500000");
        assertThat(data.get("read_file_parts_count").asText()).isEqualTo("5");
        assertThat(data.get("read_file_parts_output_dir").asText()).isEqualTo("/tmp/pdf-pages/");
        assertThat(result.newMessages()).isEmpty();
    }

    @Test
    @DisplayName("fileUnchanged 工厂: data(JsonNode) 含 META_OUTPUT_TYPE=file_unchanged (dedup 命中)")
    void fileUnchangedFactoryUsesMetaOutputTypeConstant() {
        ToolResult result = ToolResult.fileUnchanged("call-1",
            "<file_unchanged> path=src/Main.java (offset=1, limit=2000)", "src/Main.java");

        JsonNode data = (JsonNode) result.data();
        assertThat(data.get(ToolResult.META_OUTPUT_TYPE).asText()).isEqualTo("file_unchanged");
        assertThat(data.get("read_file_file_path").asText()).isEqualTo("src/Main.java");
        // [IMP-C2] ToolResult 4 字段契约：无 isError 字段（组 2-1 拍板）
        assertThat(result.newMessages()).isEmpty();
    }

    @Test
    @DisplayName("[IMP-C2] successWithStructuredOutput 折入 data: data 为 Map 携带 summary + 呈现字段")
    void structuredOutputFoldedIntoData() {
        java.util.Map<String, Object> structuredOutput = new java.util.LinkedHashMap<>();
        structuredOutput.put("startLine", 1);
        structuredOutput.put("injectReminder", true);
        ToolResult<java.util.Map<String, Object>> result =
            ToolResult.successWithStructuredOutput("call-1", "raw-content", structuredOutput);
        // [IMP-C2] ToolResult 4 字段契约：structuredOutput 字段删除，呈现元数据折入 data（Map）
        assertThat(result.data())
            .containsEntry("summary", "raw-content")
            .containsEntry("startLine", 1)
            .containsEntry("injectReminder", true);
    }
}
