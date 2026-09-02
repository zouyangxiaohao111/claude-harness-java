package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具执行结果 · 严格对齐 CC {@code ToolResult<T>} (Open-ClaudeCode/src/Tool.ts:321-336).
 *
 * <p>CC 真源 (主代理 grep 实证 Pattern #2/#9 — 不信注释, 读实际 TS):
 * <pre>
 * export type ToolResult&lt;T&gt; = {
 *   data: T                                                          // Tool.ts:322
 *   newMessages?: (UserMessage | AssistantMessage | AttachmentMessage)[]  // Tool.ts:323
 *   contextModifier?: (context: ToolUseContext) => ToolUseContext   // Tool.ts:330
 *   mcpMeta?: { _meta?: Record&lt;string, unknown&gt;; structuredContent?: Record&lt;string, unknown&gt; }  // Tool.ts:331-335
 * }
 * </pre>
 *
 * <h2>[IMP-C2] 4 字段契约（组 2-1 拍板 · 2026-08-15）</h2>
 * Java 端原 {@code toolUseId}/{@code isError}/{@code errorCategory}/{@code structuredOutput}
 * 4 个偏离字段已<b>全部删除</b>，本 record 仅保留 CC 4 字段。
 * <ul>
 *   <li>{@code toolUseId} — 由 mapper 参数透传/推导（CC toolExecution.ts:1292
 *       {@code mapToolResultToToolResultBlockParam(result.data, toolUseID)}），不存于结果。</li>
 *   <li>{@code isError} — 由执行器/错误路径推导（CC is_error 在错误路径直构块，
 *       toolExecution.ts:402/481/671/724/1034/1722），不存于结果。</li>
 *   <li>{@code errorCategory} — OTel 错误分类改走 OTel 通道（StreamingToolExecutor
 *       经 {@code ToolErrorFormatter.classifyToolError} 在发射点计算并透传），不存于结果。</li>
 *   <li>{@code structuredOutput} — SyntheticOutput/compact 持久化改走 AgentState 通道
 *       （ToolResultApplier 经 AgentState.recordStructuredOutput + structured_output attachment，
 *       对齐 CC toolExecution.ts:1274-1279），不存于结果。</li>
 * </ul>
 *
 * <p><b>工厂方法签名兼容说明（IMP-C2 消费点改道）</b>: 60+ 既有调用点（76 个工具文件）以
 * {@code ToolResult.success(call.id(), data)} 形式传入工具调用 ID。record 已不存储该 ID
 * （由 mapper 从调用块推导），但<b>工厂方法保留该首参签名</b>以保证 60+ 消费点编译通过；
 * 参数在工厂内部丢弃（不进入 record）。删除签名需改 76 文件，超出本任务目标文件范围。
 *
 * <h2>s 系列假代码清理 (用户提示)</h2>
 * 退役旧 {@code metadata Map&lt;String,String&gt;} 旁路 (s05-P2-9 发明, CC 无对应).
 * 旧 typed 工厂 (image/pdf/notebook/parts/fileUnchanged) 把结构化字段塞 metadata, 现
 * 统一走 {@code data:T} 泛型通道 (对齐 CC data:T).
 *
 * <h2>退役 ExtendedToolResult (用户批准 · 严格 CC)</h2>
 * CC 的 newMessages + contextModifier 已在 ToolResult&lt;T&gt; 内 (Tool.ts:323/330).
 * Java 的 ExtendedToolResult 是拆分产物, 现字段折入本 record, ExtendedToolResult 退役.
 *
 * @param <T>             CC original: T (Tool.ts:322) — 工具 outputSchema 对应的结构化数据类型
 * @param data            CC original: data (Tool.ts:322) — 工具结构化输出 (替代旧 content:String)
 * @param newMessages     CC original: newMessages (Tool.ts:323) — 注入对话历史的额外消息
 * @param contextModifier CC original: contextModifier (Tool.ts:330) — 仅 concurrency-safe=false 工具生效
 * @param mcpMeta         CC original: mcpMeta (Tool.ts:331-335) — MCP 透传元数据, never sent to model
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult<T>(
        T data,
        List<ChatMessageDto> newMessages,
        Function<ToolUseContext, ToolUseContext> contextModifier,
        McpMeta mcpMeta
) implements AgentToolResult<T> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ToolResult.class);

    /**
     * [L+ R4] 多类型输出 factory 共享的 data 字段 key · 对齐 CC {@code read_file_output_type} zod union 标签.
     *
     * <p>5 个工厂方法 ({@link #image} / {@link #pdf} / {@link #notebook} / {@link #parts} /
     * {@link #fileUnchanged}) 都把 "read_file_output_type" 当 data(JsonNode) 的 discriminator,
     * 集中维护, 后续对齐 CC 增/删 type 时只改 1 处.
     */
    public static final String META_OUTPUT_TYPE = "read_file_output_type";

    /**
     * data(JsonNode) key: image base64 载荷。
     *
     * <p>Java 端扁平 data key 约定：CC 真源用 {@code data.file.base64}（FileReadTool.ts:274），
     * 未定义 image_base64 顶层 key；Java 端 30+ 消费点（ChatMessageDto 序列化）与测试
     * （ReadFileToolTest/PdfDeliveryAlignmentTest）按字面量读取，值不可改。
     */
    public static final String META_IMAGE_BASE64 = "image_base64";
    /** data(JsonNode) key: image media type（e.g. "image/png"）· CC original: data.file.type (FileReadTool.ts:275)。 */
    public static final String META_IMAGE_MEDIA_TYPE = "image_media_type";
    /**
     * data(JsonNode) key: document base64 载荷。
     *
     * <p>CC 真源 pdf case 只回文本摘要（FileReadTool.ts:672-678），document 块经 newMessages 送达，
     * 无 document_base64 顶层 key——Java 端扁平 data key 约定（对齐 CC data:T 精神），值不可改。
     */
    public static final String META_DOCUMENT_BASE64 = "document_base64";
    /** data(JsonNode) key: document media type（e.g. "application/pdf"）。 */
    public static final String META_DOCUMENT_MEDIA_TYPE = "document_media_type";

    public ToolResult {
        if (data == null) {
            throw new IllegalArgumentException("ToolResult.data is null");
        }
        newMessages = newMessages == null ? List.of() : List.copyOf(newMessages);
        // contextModifier 可空 (CC Tool.ts:330 optional) · SkillTool inline 经
        // successWithNewMessagesWithContextModifier（:217）传真实三件套（SkillToolImpl:1385 起）；
        // 其余 5 个多类型输出工厂（image/pdf/notebook/parts/fileUnchanged）不携带（无 modifier 语义）
        // mcpMeta 可空 (CC Tool.ts:331 optional) · 非 MCP 工具不强塞
    }

    /**
     * MCP 协议透传元数据 · 严格对齐 CC {@code mcpMeta} (Tool.ts:331-335).
     *
     * <p>CC 实证 (主代理 grep, 不信注释):
     * <ul>
     *   <li>{@code messages.ts:483} "mcpMeta ... never sent to model" — SDK 消费者透传, 不进 LLM tool_result</li>
     *   <li>{@code toolExecution.ts:1464,1727} {@code mcpMeta: toolUseContext.agentId ? undefined : mcpMeta}
     *       — subagent 路径 (agentId 非空) 抑制 mcpMeta</li>
     *   <li>{@code mcp/client.ts:1899-1902} 仅 MCP 工具结果填充 (_meta + structuredContent)</li>
     * </ul>
     *
     * @param meta             CC original: _meta (Tool.ts:333) — MCP server 任意元数据 (Object 非 String, 承载任意 JSON)
     * @param structuredContent CC original: structuredContent (Tool.ts:334) — MCP 结构化输出 (JsonNode 等价 Record&lt;string,unknown&gt;)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McpMeta(
            Map<String, Object> meta,
            JsonNode structuredContent
    ) {
        public McpMeta {
            meta = meta == null ? Map.of() : Map.copyOf(meta);
            // structuredContent 可空 (CC structuredContent? optional)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 工厂方法
    // ════════════════════════════════════════════════════════════════════════
    // [IMP-C2] 4 字段契约下 toolUseId/isError/errorCategory/structuredOutput 参数已不存储。
    // 为保 60+ 消费点（76 工具文件）编译通过，工厂方法首参签名保留（参数在工厂内丢弃，
    // 不进入 record）；tool_use_id/is_error 由 mapper 推导，errorCategory 走 OTel，
    // structuredOutput 走 AgentState 通道。删除签名需改 76 文件，超出本任务范围。

    /** 成功结果 (String data, 最常见路径 — 60+ 文本工具调用点 drop-in)。 */
    public static ToolResult<String> success(String toolUseId, String data) {
        return new ToolResult<>(data, null, null, null);
    }

    /** 成功结果 (typed data — 结构化输出工具走泛型通道, 对齐 CC data:T). */
    public static <T> ToolResult<T> success(String toolUseId, T data) {
        return new ToolResult<>(data, null, null, null);
    }

    /** 失败结果 (data 承载错误消息; is_error 由执行器推导). */
    public static ToolResult<String> error(String toolUseId, String message) {
        return new ToolResult<>(message == null ? "unknown error" : message, null, null, null);
    }

    /**
     * [R32-b15 C15 遗留] 失败结果 (带错误分类) — errorCategory 已改走 OTel 通道,
     * 参数保留仅供 60+ 调用点编译通过, 内部丢弃.
     */
    public static ToolResult<String> error(String toolUseId, String message, String errorCategory) {
        return new ToolResult<>(message == null ? "unknown error" : message, null, null, null);
    }

    /**
     * [Java 偏离·A1 遗憾 退役] 成功结果 + 独立结构化输出载荷 — structuredOutput 字段已从
     * ToolResult record 删除（组 2-1 拍板，对齐 CC ToolResult 4 字段契约）。本工厂保留
     * 签名供 60+ 调用点编译通过，并把 presentation 元数据<b>折入 data</b>（对齐 CC
     * {@code mapToolResultToToolResultBlockParam(content, toolUseID)}：mapper 收到 content=data，
     * 呈现字段在 data 内，如 FileEditTool.ts data.userModified）。
     *
     * <p>data 为 {@code LinkedHashMap}：{@code "summary"} 承载原 summary 文本（LLM 兜底 content），
     * 其余键为原 structuredOutput 呈现字段。mapper 经 {@link #presentationMeta(ToolResult)} 读取。
     * 持久化通道（AgentState.recordStructuredOutput + structured_output attachment）由
     * {@code ToolResultApplier} 经 data 解析（对齐 CC toolExecution.ts:1274-1279）。
     *
     * @param toolUseId        工具调用 ID (mapper 推导, 工厂内丢弃)
     * @param summary          摘要文本 (给 LLM 看, content 路径)
     * @param structuredOutput 结构化输出呈现元数据 (折入 data)
     */
    @SuppressWarnings("unchecked")
    public static ToolResult<Map<String, Object>> successWithStructuredOutput(
            String toolUseId, String summary, Map<String, Object> structuredOutput) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("summary", summary == null ? "" : summary);
        if (structuredOutput != null) {
            data.putAll(structuredOutput);
        }
        return new ToolResult<>(data, null, null, null);
    }

    /**
     * [IMP-C2] 从 ToolResult 提取 presentation 元数据（原 structuredOutput 载荷）。
     *
     * <p>组 2-1 拍板后 structuredOutput 字段删除，呈现元数据折入 {@code data}（Map）。本方法
     * 供 per-tool mapper 读取：data 为 Map 时返回该 Map（含 "summary" 键），否则空 Map。
     *
     * @param result 工具执行结果
     * @return presentation 元数据 Map（不可变空 Map 兜底）
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> presentationMeta(ToolResult<?> result) {
        if (result == null || result.data() == null) {
            return Map.of();
        }
        Object data = result.data();
        if (data instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /**
     * [A1·退役 ExtendedToolResult] 成功结果 + newMessages (跨 turn 注入对话历史).
     * CC ToolResult&lt;T&gt;.newMessages (Tool.ts:323). 替代退役的 ExtendedToolResult.withNewMessages.
     *
     * @param toolUseId   工具调用 ID (mapper 推导, 工厂内丢弃)
     * @param data        工具结构化输出 (SkillTool 为元数据 JSON 字符串)
     * @param newMessages 注入对话历史的额外消息 (CC Tool.ts:323)
     */
    public static ToolResult<String> successWithNewMessages(
            String toolUseId, String data, List<ChatMessageDto> newMessages) {
        return new ToolResult<>(data, newMessages, null, null);
    }

    /**
     * [P0-2] 成功结果 + newMessages + contextModifier · 对齐 CC SkillTool.ts:767-774 call()
     * 返回协议 {@code {data, newMessages, contextModifier}}（Tool.ts:322/323/330）。
     *
     * <p><b>WHY 新工厂</b>: SkillTool inline 技能展开（SkillTool.ts:775-839）需要同时携带
     * 技能指令 newMessage（注入对话历史）与 contextModifier（allowedTools/model/effort 三件套
     * 调整上下文）。既有 {@link #successWithNewMessages} 只带 newMessages、contextModifier 恒 null。
     * 纯增量重载，走 canonical 4 参构造，不破坏既有工厂调用点。
     *
     * <p><b>消费链路</b>: StreamingToolExecutor.executeAsync 在 deferred 模式下把
     * {@code contextModifier()} 按 toolUseId 入队（StreamingToolExecutor.java:1500-1514），
     * 批次结束后 {@code applyDeferredContextModifiers} 按 add 顺序真实 apply
     * （对齐 CC toolOrchestration.ts:53-61 queuedContextModifiers）。
     *
     * @param toolUseId        工具调用 ID (mapper 推导, 工厂内丢弃)
     * @param data             工具结构化输出（SkillTool 为元数据 JSON 字符串）
     * @param newMessages      注入对话历史的额外消息（CC Tool.ts:323）
     * @param contextModifier  上下文调整函数（CC original: contextModifier，Tool.ts:330）
     * @return 携带 contextModifier 的成功结果
     */
    public static ToolResult<String> successWithNewMessagesWithContextModifier(
            String toolUseId, String data, List<ChatMessageDto> newMessages,
            Function<ToolUseContext, ToolUseContext> contextModifier) {
        return new ToolResult<>(data, newMessages, contextModifier, null);
    }

    /**
     * [A1·退役 ExtendedToolResult] 失败结果 + newMessages (permission retry 等场景: error + isMeta 重试消息).
     * CC ToolResult&lt;T&gt; newMessages (Tool.ts:323) 不限 success, error 亦可携带.
     *
     * @param toolUseId   工具调用 ID (mapper 推导, 工厂内丢弃)
     * @param message     错误信息 (data, LLM 可见)
     * @param newMessages 注入对话历史的额外消息 (如 isMeta 重试指令)
     */
    public static ToolResult<String> errorWithNewMessages(
            String message, List<ChatMessageDto> newMessages) {
        return new ToolResult<>(message == null ? "unknown error" : message, newMessages, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [Session L / GAP-E] 多类型输出工厂: 与 CC FileReadTool.ts outputSchema 对齐
    // 严格 CC: 结构化字段折入 data(JsonNode) (退役 s05-P2-9 metadata 旁路).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Image 输出 · 对齐 CC {@code FileReadTool.ts:270-298} image 分支.
     *
     * <p>WHY data(JsonNode): CC {@code data:T} 承载结构化 (base64 + mediaType + tag);
     * 旧 Java 把这些塞 metadata (s05-P2-9 假代码旁路, CC 无对应). 现折入 data,
     * 从 {@link #META_IMAGE_BASE64} / {@link #META_IMAGE_MEDIA_TYPE} 读.
     *
     * @param toolUseId    工具调用 ID (mapper 推导, 工厂内丢弃)
     * @param summary      人类可读摘要 (给 LLM 看, fallback text 路径)
     * @param base64       base64 编码 (无 {@code data:} 前缀)
     * @param mediaType    image MIME (image/png 等)
     * @param originalSize 原始字节数
     */
    public static ToolResult<JsonNode> image(String toolUseId, String summary, String base64,
                                              String mediaType, long originalSize,
                                              ImageDimensions dimensions) {
        return image(toolUseId, summary, base64, mediaType, originalSize, dimensions, null);
    }

    /**
     * Image 输出（携带 newMessages）· [rv-b-r1 gap2] 追加重载：CC {@code FileReadTool.ts:879-890}
     * standalone image 分支 resize 后经 {@code createImageMetadataText} 产 isMeta 文本消息，
     * 走 {@code newMessages} 参数送达（单条 isMeta user 消息，CC {@code createUserMessage({content,isMeta:true})}）。
     */
    public static ToolResult<JsonNode> image(String toolUseId, String summary, String base64,
                                              String mediaType, long originalSize,
                                              ImageDimensions dimensions,
                                              List<ChatMessageDto> newMessages) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put(META_OUTPUT_TYPE, "image");
        data.put("summary", summary);
        data.put(META_IMAGE_BASE64, base64);
        data.put(META_IMAGE_MEDIA_TYPE, mediaType);
        data.put("read_file_original_size", originalSize);
        // [B 组对齐 2026-08-04] dimensions 字段名对齐 CC FileReadTool.ts:276-295 (camelCase)
        if (dimensions != null) {
            ObjectNode dimNode = data.putObject("dimensions");
            if (dimensions.originalWidth() != null) dimNode.put("originalWidth", dimensions.originalWidth());
            if (dimensions.originalHeight() != null) dimNode.put("originalHeight", dimensions.originalHeight());
            if (dimensions.displayWidth() != null) dimNode.put("displayWidth", dimensions.displayWidth());
            if (dimensions.displayHeight() != null) dimNode.put("displayHeight", dimensions.displayHeight());
        }
        return new ToolResult<>(data, newMessages, null, null);
    }

    /**
     * PDF 输出 · 对齐 CC {@code FileReadTool.ts:306-313} pdf 分支 + :999-1016 数据形状.
     *
     * <p>[P-CC-01] 已可达：ReadFileTool.dispatchPdfFull（CC :987-1016 readPDF 分支）真实调用本工厂。
     * data(JsonNode) 携带 document_base64 + document_media_type（内联常量 {@link #META_DOCUMENT_BASE64}）；
     * [P-AL-01] CC 的 document block newMessages（FileReadTool.ts:1001-1015）经
     * {@code newMessages} 参数送达 —— isMeta user 消息携带 document block（base64 不进 tool_result
     * 载荷，toolResultMessage 只渲染 summary 文本，对齐 CC mapToolResultToToolResultBlockParam
     * pdf case :672-678）。
     */
    public static ToolResult<JsonNode> pdf(String toolUseId, String summary, String base64,
                                           String mediaType, long originalSize,
                                           List<ChatMessageDto> newMessages) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put(META_OUTPUT_TYPE, "pdf");
        data.put("summary", summary);
        data.put(META_DOCUMENT_BASE64, base64);
        data.put(META_DOCUMENT_MEDIA_TYPE, mediaType);
        data.put("read_file_original_size", originalSize);
        return new ToolResult<>(data, newMessages, null, null);
    }

    /**
     * PDF 已抽取为多张图片 · 对齐 CC {@code FileReadTool.ts:314-324} parts 分支.
     *
     * <p>[P-CC-01] 已可达：ReadFileTool.dispatchPdfPages（CC :895-946 extractPDFPages 分支）真实调用。
     * data(JsonNode) 携带 CC 同款 file 字段（count / outputDir / originalSize / filePath）；
     * [P-AL-01] CC 的页图 image blocks newMessages（FileReadTool.ts:938-945）经
     * {@code newMessages} 参数送达 —— 单条 isMeta user 消息携带全部页图 image blocks。
     */
    public static ToolResult<JsonNode> parts(String toolUseId, String summary, String filePath,
                                             long originalSize, int count, String outputDir,
                                             List<ChatMessageDto> newMessages) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put(META_OUTPUT_TYPE, "parts");
        data.put("summary", summary);
        data.put("read_file_original_size", originalSize);
        data.put("read_file_parts_count", count);
        data.put("read_file_parts_output_dir", outputDir);
        data.put("read_file_file_path", filePath);
        return new ToolResult<>(data, newMessages, null, null);
    }

    /**
     * Notebook (.ipynb) 输出 · 对齐 CC {@code FileReadTool.ts:299-305} notebook 分支.
     *
     * <p>[rv-b-r1 gap3] 数据形状由 raw cellsJson 改为处理后的 cells（CC {@code data.file.cells}
     * = NotebookCellSource[]）：{@code notebook_cells} 存处理后的 cells（execution_count 仅 code、
     * source 数组 join、cell 输出经 processOutput），{@code notebook_rendered} 存
     * {@code mapNotebookCellsToToolResult} 的 {@code <cell>} 块渲染。
     *
     * @param toolUseId   工具调用 ID (mapper 推导, 工厂内丢弃)
     * @param summary     摘要文本 (给 LLM 看)
     * @param cellsNode   处理后的 cells (CC data.file.cells = NotebookCellSource[])
     * @param cellsJson   处理后的 cells 重序列化 JSON 字符串 (readFileState content 同源, CC jsonStringify(cells))
     * @param rendered    {@code <cell>} 块渲染文本 (CC mapNotebookCellsToToolResult)
     * @param filePath    notebook 路径
     */
    public static ToolResult<JsonNode> notebook(String toolUseId, String summary,
                                                JsonNode cellsNode, String cellsJson,
                                                String rendered, String filePath) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put(META_OUTPUT_TYPE, "notebook");
        data.put("summary", summary);
        data.set("notebook_cells", cellsNode);
        data.put("notebook_cells_json", cellsJson);
        data.put("notebook_rendered", rendered);
        data.put("notebook_file_path", filePath);
        return typedResult(toolUseId, data);
    }


    /**
     * 文件未变 (dedup 命中) · 对齐 CC {@code FileReadTool.ts:325-329} file_unchanged 分支.
     */
    public static ToolResult<JsonNode> fileUnchanged(String toolUseId, String summary, String filePath) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put(META_OUTPUT_TYPE, "file_unchanged");
        data.put("summary", summary);
        data.put("read_file_file_path", filePath);
        return typedResult(toolUseId, data);
    }

    /**
     * [L+ R4] 私有工厂 · 5 个多类型输出方法共用的"成功 + 结构化 data(JsonNode)"包装.
     *
     * <p>WHY 抽工厂: 5 个方法 (image / pdf / notebook / parts / fileUnchanged) 都构造
     * {@code new ToolResult<>(data, ...)} 这条链, 集中到 typedResult 维护,
     * 后续对齐 CC 增/删 type 时只改 typedResult 即可.
     */
    private static ToolResult<JsonNode> typedResult(String toolUseId, ObjectNode data) {
        return new ToolResult<>(data, null, null, null);
    }

    /**
     * [P-AL-01] tool_result 载荷文本渲染 · 对齐 CC {@code FileReadTool.ts:652-717}
     * {@code mapToolResultToToolResultBlockParam} 的 content 部分.
     *
     * <p>WHY（P-CC-01 P1 缺口）: 旧实现 {@code String.valueOf(data)} 把 data(JsonNode)
     * 全量 stringify —— PDF 的 document_base64（20MB PDF → ~27MB base64 文本）整体进入
     * tool_result 载荷（token 爆炸）。CC 真源行为（自验）:
     * <ul>
     *   <li>pdf case → {@code `PDF file read: ${filePath} (${formatFileSize(originalSize)})`}
     *       （FileReadTool.ts:672-678）—— 纯文本摘要，base64 只进 newMessages document block</li>
     *   <li>parts case → {@code `PDF pages extracted: ${count} page(s) ...`}（:679-685）</li>
     *   <li>file_unchanged case → FILE_UNCHANGED_STUB 文本（:686-691）</li>
     *   <li>text case → 行号内容（:692-714）</li>
     * </ul>
     * Java 端 pdf/parts/file_unchanged 的 summary 字段即 CC 同款摘要文本，直接渲染；
     * image/notebook/其他 JsonNode 维持旧行为（image 送达缺口 12.1-1 同源，后续批次登记）。
     *
     * @param result 工具执行结果（data 可为 String 或 JsonNode）
     * @return tool_result 载荷文本（CC mapToolResultToToolResultBlockParam content 等价）
     */
    public static String renderToolResultPayloadText(ToolResult<?> result) {
        if (result == null || result.data() == null) {
            return "";
        }
        Object data = result.data();
        if (data instanceof String s) {
            return s;
        }
        if (data instanceof JsonNode node) {
            JsonNode typeNode = node.get(META_OUTPUT_TYPE);
            String type = typeNode == null ? null : typeNode.asText();
            if ("pdf".equals(type) || "parts".equals(type) || "file_unchanged".equals(type)) {
                JsonNode summary = node.get("summary");
                if (summary != null && summary.isTextual() && !summary.asText().isBlank()) {
                    return summary.asText();
                }
            }
            // [rv-b-r1 gap3] notebook → <cell> 块渲染（CC mapNotebookCellsToToolResult notebook 分支，
            //   FileReadTool.ts:656-664）；rendered 由 dispatchNotebook 经 NotebookCellProcessor 预生成。
            if ("notebook".equals(type)) {
                JsonNode rendered = node.get("notebook_rendered");
                if (rendered != null && rendered.isTextual()) {
                    return rendered.asText();
                }
            }
            // image / 其他结构化数据 → 维持旧行为（image 送达为 12.1-1 同源缺口，登记后续批次）
            return node.toString();
        }
        // [OPD-TS-09-01] 防御性守卫: data 为 List（结构化多块载体，如 tool_reference 块数组）
        // 时上层应走 mapper 结构化透传路径，不应在此 String.valueOf 压平；返回空串避免任何
        // 路径仍把结构化载体压成扁平字符串（CC ToolSearchTool.ts:462-469 content 为块数组）。
        // String/JsonNode 既有路径零改动（见上方分支）。
        if (data instanceof List<?>) {
            if (log.isDebugEnabled()) {
                log.debug("ToolResult 载荷渲染跳过 List 载体（结构化透传，不压平）: 元素数={}",
                    ((List<?>) data).size());
            }
            return "";
        }
        // [IMP-C2 返工] successWithStructuredOutput 折入 data(Map) 后，模型侧渲染文本在 "summary" 键
        //   （ReadFileTool/BashTool 持久化/TaskUpdate 等双通道工具），渲染器须提取 summary 而非 Map toString。
        if (data instanceof Map<?, ?> m) {
            Object summary = m.get("summary");
            if (summary != null) {
                return String.valueOf(summary);
            }
            return "";
        }
        return String.valueOf(data);
    }
}
