package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolNameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Notebook cell 处理 · 全量 port CC {@code Open-ClaudeCode/src/utils/notebook.ts}。
 *
 * <p>CC 真源（不信注释，逐函数读实际 TS）：
 * <ul>
 *   <li>{@code isLargeOutputs}（notebook.ts:22-32）：LARGE_OUTPUT_THRESHOLD=10000，累计
 *       {@code (text?.length ?? 0) + (image?.image_data.length ?? 0)} 超阈返回 true。</li>
 *   <li>{@code processOutputText}（:34-39）：text 数组 join('') 或 string，再经
 *       {@code formatOutput().truncatedContent}（BashTool/utils.ts:133-165，默认 30000 字符截断）。</li>
 *   <li>{@code extractImage}（:41-57）：data['image/png']→image/png（白空格剥离）、
 *       data['image/jpeg']→image/jpeg、否则 undefined。</li>
 *   <li>{@code processOutput}（:59-81）：stream→{output_type,text}；execute_result/display_data
 *       →{output_type,text,image}；error→{output_type,text=`${ename}: ${evalue}\n${traceback.join('\n')}`}。</li>
 *   <li>{@code processCell}（:83-117）：cellId=id??`cell-${index}`；source 数组 join('')；
 *       execution_count 仅 code cell 且 falsy 置 undefined；cell_id=cellId；code cell 才带 language；
 *       code 且有 outputs 时 map(processOutput)，includeLargeOutputs=false 且 isLargeOutputs 超阈时
 *       替换为 stream 提示。</li>
 *   <li>{@code cellContentToToolResult}（:119-132）/ {@code cellOutputToToolResult}（:134-153）/
 *       {@code mapNotebookCellsToToolResult}（:188-215）：产 {@code <cell id=...>} 文本块 + 相邻 text
 *       合并（{@code prev.text += '\n' + curr.text}）+ 输出 image 块。</li>
 * </ul>
 *
 * <p><b>Java 架构偏离</b>：CC {@code mapNotebookCellsToToolResult} 产 {@code content} 块数组
 * （text 块 + image 块），Java 端 tool_result 载荷是单 String（{@code ToolResult.renderToolResultPayloadText}），
 * 故本类 {@link #renderCells} 只产合并后的 text（{@code <cell>} 块 + 文本输出），image 输出块不落入
 * String 载荷（其 base64 仍保留在结构化 {@code notebook_cells} 数据中，供前端/DB 消费）。此偏离登记 residual。
 */
public final class NotebookCellProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotebookCellProcessor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CC notebook.ts:20 {@code const LARGE_OUTPUT_THRESHOLD = 10000}. */
    private static final int LARGE_OUTPUT_THRESHOLD = 10_000;

    /** CC shell/outputLimits.ts:4 {@code BASH_MAX_OUTPUT_DEFAULT = 30_000}. */
    private static final int MAX_OUTPUT_LENGTH = 30_000;

    private NotebookCellProcessor() {
    }

    /**
     * 图片输出块 · 对齐 CC {@code NotebookOutputImage}（notebook.ts 内部类型）
     * {@code { image_data: string, media_type: 'image/png' | 'image/jpeg' }}.
     */
    public record NotebookOutputImage(String imageData, String mediaType) {
    }

    /**
     * cell 输出 · 对齐 CC {@code NotebookCellSourceOutput}（processOutput 返回值）.
     *
     * @param outputType CC original: output_type（stream/execute_result/display_data/error）
     * @param text       CC original: text（processOutputText 处理后的纯文本）
     * @param image      CC original: image（extractImage 结果，可 null）
     */
    public record NotebookCellSourceOutput(String outputType, String text, NotebookOutputImage image) {
    }

    /**
     * 处理后的 cell · 对齐 CC {@code NotebookCellSource}（processCell 返回值，notebook.ts:90-96）.
     *
     * @param cellType        CC original: cellType（cell_type）
     * @param source          CC original: source（数组 join('') 后的源码）
     * @param executionCount  CC original: execution_count（仅 code cell；falsy → null，序列化时省略）
     * @param cellId          CC original: cell_id（id ?? `cell-${index}`）
     * @param language        CC original: language（仅 code cell 携带，notebook metadata.language_info.name ?? 'python'）
     * @param outputs         CC original: outputs（仅 code cell 且有 outputs 时非 null）
     */
    public record NotebookCellSource(
            String cellType,
            String source,
            Integer executionCount,
            String cellId,
            String language,
            List<NotebookCellSourceOutput> outputs) {
    }

    // ──────────────────────────────────────────────────────────────────────────
    // readNotebook（全量，无 cellId 变体）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 解析 notebook JSON → 处理后的 cells（对齐 CC {@code readNotebook}(notebook.ts:164-183)，
     * 无 cellId 变体）。language 取自 {@code metadata.language_info.name ?? 'python'}（CC notebook.ts:172）。
     */
    public static List<NotebookCellSource> processNotebook(JsonNode root) {
        String language = resolveLanguage(root);
        JsonNode cellsNode = root != null ? root.get("cells") : null;
        List<NotebookCellSource> result = new ArrayList<>();
        if (cellsNode != null && cellsNode.isArray()) {
            int index = 0;
            for (JsonNode cell : cellsNode) {
                result.add(processCell(cell, index, language, false));
                index++;
            }
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // processCell（notebook.ts:83-117）
    // ──────────────────────────────────────────────────────────────────────────

    private static NotebookCellSource processCell(
            JsonNode cell, int index, String codeLanguage, boolean includeLargeOutputs) {
        String cellType = textOf(cell, "cell_type");
        String cellId = cell.has("id") && !cell.get("id").isNull()
                ? cell.get("id").asText()
                : "cell-" + index;
        String source = joinSource(cell.get("source"));
        // CC notebook.ts:93-94 execution_count 仅 code cell 且 falsy 置 undefined
        Integer executionCount = null;
        if ("code".equals(cellType)) {
            JsonNode ec = cell.get("execution_count");
            if (ec != null && ec.isNumber()) {
                int v = ec.asInt();
                if (v != 0) {
                    executionCount = v;
                }
            }
        }
        NotebookCellSource cellData = new NotebookCellSource(
                cellType, source, executionCount, cellId,
                "code".equals(cellType) ? codeLanguage : null,
                null);

        if ("code".equals(cellType)) {
            JsonNode outputsNode = cell.get("outputs");
            if (outputsNode != null && outputsNode.isArray() && outputsNode.size() > 0) {
                List<NotebookCellSourceOutput> outputs = new ArrayList<>();
                for (JsonNode out : outputsNode) {
                    outputs.add(processOutput(out));
                }
                if (!includeLargeOutputs && isLargeOutputs(outputs)) {
                    // CC notebook.ts:104-110 大输出替换为 stream 提示（<notebook_path> 为字面占位，非插值）
                    outputs = List.of(new NotebookCellSourceOutput(
                            "stream",
                            "Outputs are too large to include. Use " + ToolNameConstants.BASH_TOOL_NAME
                                    + " with: cat <notebook_path> | jq '.cells[" + index + "].outputs'",
                            null));
                }
                cellData = new NotebookCellSource(
                        cellType, source, executionCount, cellId,
                        "code".equals(cellType) ? codeLanguage : null,
                        outputs);
            }
        }
        return cellData;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // processOutput（notebook.ts:59-81）
    // ──────────────────────────────────────────────────────────────────────────

    private static NotebookCellSourceOutput processOutput(JsonNode output) {
        String outputType = textOf(output, "output_type");
        switch (outputType) {
            case "stream":
                return new NotebookCellSourceOutput(outputType, processOutputText(jsonText(output.get("text"))), null);
            case "execute_result":
            case "display_data": {
                JsonNode data = output.get("data");
                JsonNode textPlain = data != null ? data.get("text/plain") : null;
                return new NotebookCellSourceOutput(
                        outputType, processOutputText(jsonText(textPlain)), extractImage(data));
            }
            case "error": {
                String ename = textOf(output, "ename");
                String evalue = textOf(output, "evalue");
                JsonNode tracebackNode = output.get("traceback");
                List<String> traceback = new ArrayList<>();
                if (tracebackNode != null && tracebackNode.isArray()) {
                    for (JsonNode line : tracebackNode) {
                        traceback.add(line.asText());
                    }
                }
                return new NotebookCellSourceOutput(
                        outputType,
                        processOutputText(ename + ": " + evalue + "\n" + String.join("\n", traceback)),
                        null);
            }
            default:
                // CC switch 无 default —— 未知 output_type 返回 undefined；Java 空输出对齐。
                if (log.isDebugEnabled()) {
                    log.debug("NotebookCellProcessor: 未知 output_type={} 跳过输出（CC switch 无 default）", outputType);
                }
                return new NotebookCellSourceOutput(outputType, "", null);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // extractImage（notebook.ts:41-57）
    // ──────────────────────────────────────────────────────────────────────────

    private static NotebookOutputImage extractImage(JsonNode data) {
        if (data == null) {
            return null;
        }
        JsonNode png = data.get("image/png");
        if (png != null && png.isTextual()) {
            return new NotebookOutputImage(png.asText().replaceAll("\\s", ""), "image/png");
        }
        JsonNode jpeg = data.get("image/jpeg");
        if (jpeg != null && jpeg.isTextual()) {
            return new NotebookOutputImage(jpeg.asText().replaceAll("\\s", ""), "image/jpeg");
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // isLargeOutputs（notebook.ts:22-32）
    // ──────────────────────────────────────────────────────────────────────────

    private static boolean isLargeOutputs(List<NotebookCellSourceOutput> outputs) {
        int size = 0;
        for (NotebookCellSourceOutput o : outputs) {
            if (o == null) {
                continue;
            }
            size += (o.text() != null ? o.text().length() : 0)
                    + (o.image() != null && o.image().imageData() != null ? o.image().imageData().length() : 0);
            if (size > LARGE_OUTPUT_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // processOutputText（notebook.ts:34-39 + BashTool/utils.ts formatOutput）
    // ──────────────────────────────────────────────────────────────────────────

    private static String processOutputText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return truncateOutput(text);
    }

    /** CC notebook.ts:35 {@code Array.isArray(text) ? text.join('') : text}（string | string[] → string）。 */
    private static String jsonText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode el : node) {
                sb.append(el.asText());
            }
            return sb.toString();
        }
        return node.asText();
    }

    /** CC BashTool/utils.ts:133-165 {@code formatOutput().truncatedContent}（30000 字符截断 + 行数提示）。 */
    private static String truncateOutput(String content) {
        if (content.length() <= MAX_OUTPUT_LENGTH) {
            return content;
        }
        String truncatedPart = content.substring(0, MAX_OUTPUT_LENGTH);
        int remainingLines = 1;
        for (int i = MAX_OUTPUT_LENGTH; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                remainingLines++;
            }
        }
        return truncatedPart + "\n\n... [" + remainingLines + " lines truncated] ...";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 序列化：processed cells → JSON（供 readFileState content + data.notebook_cells）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 处理后的 cells 重序列化为 JSON 字符串 · 对齐 CC {@code jsonStringify(cells)}
     * （FileReadTool.ts:824，readFileState 存的是这个「已处理 cells」JSON，非 raw 文件内容）。
     */
    public static String serializeCells(List<NotebookCellSource> cells) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (NotebookCellSource cell : cells) {
            arr.add(toJsonNode(cell));
        }
        return arr.toString();
    }

    /** 处理后的 cells → JsonNode 数组（存 ToolResult.notebook 的 data.notebook_cells）。 */
    public static ArrayNode cellsToJsonNode(List<NotebookCellSource> cells) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (NotebookCellSource cell : cells) {
            arr.add(toJsonNode(cell));
        }
        return arr;
    }

    private static ObjectNode toJsonNode(NotebookCellSource cell) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("cellType", cell.cellType());
        node.put("source", cell.source());
        // CC jsonStringify 省略 undefined 字段：execution_count 仅 code cell 且非 falsy 才出现
        if (cell.executionCount() != null) {
            node.put("execution_count", cell.executionCount());
        }
        node.put("cell_id", cell.cellId());
        if (cell.language() != null) {
            node.put("language", cell.language());
        }
        if (cell.outputs() != null) {
            ArrayNode outputsNode = node.putArray("outputs");
            for (NotebookCellSourceOutput out : cell.outputs()) {
                ObjectNode outNode = outputsNode.addObject();
                outNode.put("output_type", out.outputType());
                if (out.text() != null) {
                    outNode.put("text", out.text());
                }
                if (out.image() != null) {
                    ObjectNode imageNode = outNode.putObject("image");
                    imageNode.put("image_data", out.image().imageData());
                    imageNode.put("media_type", out.image().mediaType());
                }
            }
        }
        return node;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 渲染：cellContentToToolResult + cellOutputToToolResult + mapNotebookCellsToToolResult
    // （notebook.ts:119-215；Java 单 String 载荷，image 块不落入 text）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 渲染 processed cells → 合并后的文本（{@code <cell id=...>} 块 + 文本输出）。
     * 对齐 CC {@code mapNotebookCellsToToolResult}（notebook.ts:188-215）相邻 text 块合并
     * （{@code prev.text += '\n' + curr.text}）。
     */
    public static String renderCells(List<NotebookCellSource> cells) {
        List<String> blocks = new ArrayList<>();
        for (NotebookCellSource cell : cells) {
            blocks.add(cellContentToText(cell));
            if (cell.outputs() != null) {
                for (NotebookCellSourceOutput out : cell.outputs()) {
                    if (out.text() != null && !out.text().isEmpty()) {
                        // CC cellOutputToToolResult :136-140 text 块前缀 '\n'
                        blocks.add("\n" + out.text());
                    }
                    // image 块（CC :142-151）不落入 String 载荷 —— base64 保留在 notebook_cells
                }
            }
        }
        // CC reduce：相邻 text 块 prev.text += '\n' + curr.text
        StringBuilder sb = new StringBuilder();
        for (String block : blocks) {
            if (sb.length() == 0) {
                sb.append(block);
            } else {
                sb.append('\n').append(block);
            }
        }
        return sb.toString();
    }

    /** CC cellContentToToolResult（notebook.ts:119-132）。 */
    private static String cellContentToText(NotebookCellSource cell) {
        StringBuilder metadata = new StringBuilder();
        if (!"code".equals(cell.cellType())) {
            metadata.append("<cell_type>").append(cell.cellType()).append("</cell_type>");
        }
        if ("code".equals(cell.cellType()) && cell.language() != null && !"python".equals(cell.language())) {
            metadata.append("<language>").append(cell.language()).append("</language>");
        }
        return "<cell id=\"" + cell.cellId() + "\">" + metadata + cell.source()
                + "</cell id=\"" + cell.cellId() + "\">";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static String textOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String joinSource(JsonNode sourceNode) {
        if (sourceNode == null || sourceNode.isNull()) {
            return "";
        }
        if (sourceNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode el : sourceNode) {
                sb.append(el.asText());
            }
            return sb.toString();
        }
        return sourceNode.asText();
    }

    /** CC notebook.ts:172 {@code notebook.metadata.language_info?.name ?? 'python'}。 */
    private static String resolveLanguage(JsonNode root) {
        if (root == null) {
            return "python";
        }
        JsonNode metadata = root.get("metadata");
        JsonNode langInfo = metadata != null ? metadata.get("language_info") : null;
        JsonNode name = langInfo != null ? langInfo.get("name") : null;
        if (name != null && name.isTextual() && !name.asText().isBlank()) {
            return name.asText();
        }
        return "python";
    }
}
