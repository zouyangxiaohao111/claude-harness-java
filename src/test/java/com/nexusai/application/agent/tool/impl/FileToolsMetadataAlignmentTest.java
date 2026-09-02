package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.PathGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-C rv-a-r1 · {@link EditFileTool}/{@link ReadFileTool}/{@link WriteFileTool} 三文件工具
 * 的 buildTool 元数据（strict / searchHint / maxResultSizeChars）与 inputSchema 的
 * {@code z.strictObject → additionalProperties:false} 对齐 CC 契约验证。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * 这三项元数据与 inputSchema 严格性是 CC 工具定义的「广告层 + 意图层」契约，缺任一项都让
 * 模型侧工具调用语义与 CC 漂移：
 * <ol>
 *   <li><b>strict = true</b> —— CC FileEditTool.ts:90 / FileReadTool.ts:343 / FileWriteTool.ts:98
 *       均 {@code strict: true}（buildTool 配置块相邻三行之一）。Java {@code Tool.strict()}
 *       默认 false → 若漏 override，{@code ToolRegistry.toOpenAiToolsArray} 的
 *       {@code flag && tool.strict()} 恒 false，三工具不进入 SDK 严格模式，模型可注入额外字段。</li>
 *   <li><b>searchHint</b> —— CC 三工具逐字值：Edit {@code 'modify file contents in place'}、
 *       Read {@code 'read files, images, PDFs, notebooks'}、Write {@code 'create or overwrite files'}。
 *       供 ToolSearch 关键词匹配（CC Tool.ts:378），漏 override 则 searchHint() 默认 null，
 *       三工具在工具搜索中不可达。</li>
 *   <li><b>maxResultSizeChars</b> —— Edit/Write {@code 100_000}（CC FileEditTool.ts:89 /
 *       FileWriteTool.ts:97），Read {@code Infinity}（CC FileReadTool.ts:342，Java Long.MAX_VALUE 等价）。
 *       Java 默认 50_000 → Edit/Write 50k-100k 字符结果被误落盘，偏离 CC 阈值。</li>
 *   <li><b>additionalProperties:false</b> —— CC 三工具 inputSchema 均 {@code z.strictObject}
 *       （FileEditTool/types.ts:7 / FileReadTool.ts:228 / FileWriteTool.ts:57），未知键拒绝。
 *       漏 declare 则 ToolInputValidator:230-232 读不到 additionalProperties，未知键不拒绝。</li>
 * </ol>
 */
@DisplayName("FileToolsMetadataAlignmentTest · 三文件工具 strict/searchHint/maxResultSizeChars + strictObject 对齐 CC")
class FileToolsMetadataAlignmentTest {

    @TempDir
    Path workspace;

    private EditFileTool editTool() {
        return new EditFileTool(new PathGuard(workspace));
    }

    private ReadFileTool readTool() {
        return new ReadFileTool(new PathGuard(workspace));
    }

    private WriteFileTool writeTool() {
        return new WriteFileTool(new PathGuard(workspace));
    }

    // ───────────────────────── strict() = true ─────────────────────────

    @Test
    @DisplayName("三工具 strict() = true（CC FileEditTool.ts:90 / FileReadTool.ts:343 / FileWriteTool.ts:98）")
    void allThreeTools_strict_explicitlyTrue() {
        // WHY: Tool.strict() 默认 false；漏 override → ToolRegistry flag && tool.strict()
        //      恒 false，三工具不进入 SDK 严格模式（模型可注入额外字段），偏离 CC buildTool strict:true。
        assertThat(editTool().strict()).as("Edit strict（CC FileEditTool.ts:90）").isTrue();
        assertThat(readTool().strict()).as("Read strict（CC FileReadTool.ts:343）").isTrue();
        assertThat(writeTool().strict()).as("Write strict（CC FileWriteTool.ts:98）").isTrue();
    }

    // ───────────────────────── searchHint 逐字对齐 ─────────────────────────

    @Test
    @DisplayName("searchHint 逐字对齐 CC 三工具值")
    void searchHint_matchesCcVerbatim() {
        // WHY: ToolSearch 关键词匹配（ToolSearchTool.ts:484/500/518）依赖逐字值；
        //      Read 值含逗号（'read files, images, PDFs, notebooks'），偏移即关键词错配。
        assertThat(editTool().searchHint())
            .as("CC FileEditTool.ts:88").isEqualTo("modify file contents in place");
        assertThat(readTool().searchHint())
            .as("CC FileReadTool.ts:339").isEqualTo("read files, images, PDFs, notebooks");
        assertThat(writeTool().searchHint())
            .as("CC FileWriteTool.ts:96").isEqualTo("create or overwrite files");
    }

    // ───────────────────────── maxResultSizeChars ─────────────────────────

    @Test
    @DisplayName("maxResultSizeChars：Edit/Write = 100_000，Read = Long.MAX_VALUE（Infinity 等价）")
    void maxResultSizeChars_editWrite100k_readInfinity() {
        // WHY: Java 默认 50_000 偏离 CC 100_000；Edit/Write 50k-100k 字符结果被误落盘。
        //      Read 恒不落盘（落盘→Read 循环），Long.MAX_VALUE = CC Infinity。
        assertThat(editTool().maxResultSizeChars())
            .as("CC FileEditTool.ts:89").isEqualTo(100_000L);
        assertThat(writeTool().maxResultSizeChars())
            .as("CC FileWriteTool.ts:97").isEqualTo(100_000L);
        assertThat(readTool().maxResultSizeChars())
            .as("CC FileReadTool.ts:342 Infinity → Long.MAX_VALUE").isEqualTo(Long.MAX_VALUE);
    }

    // ───────────────────────── inputSchema additionalProperties:false ─────────────────────────

    @Test
    @DisplayName("inputSchema 顶层 additionalProperties = false（CC z.strictObject 未知键拒绝）")
    void inputSchema_additionalProperties_false() {
        // WHY: CC 三工具 inputSchema 均 z.strictObject（FileEditTool/types.ts:7 /
        //      FileReadTool.ts:228 / FileWriteTool.ts:57）。Java 端广告层 additionalProperties:false
        //      是 ToolInputValidator:230-232 拒绝未知键（unrecognized_keys）的前提；
        //      漏 declare → UNSPECIFIED 策略跟随不到 false → 未知键静默放行。
        assertAdditionalPropertiesFalse(editTool(), "EditFileTool/types.ts:7");
        assertAdditionalPropertiesFalse(readTool(), "FileReadTool.ts:228");
        assertAdditionalPropertiesFalse(writeTool(), "FileWriteTool.ts:57");
    }

    private static void assertAdditionalPropertiesFalse(com.nexusai.application.agent.tool.Tool tool, String ccAnchor) {
        JsonNode schema = tool.inputSchema();
        assertThat(schema.get("additionalProperties"))
            .as("%s z.strictObject → additionalProperties 必须声明", ccAnchor)
            .isNotNull();
        assertThat(schema.get("additionalProperties").asBoolean())
            .as("%s z.strictObject → additionalProperties:false（拒绝未知键）", ccAnchor)
            .isFalse();
    }
}
