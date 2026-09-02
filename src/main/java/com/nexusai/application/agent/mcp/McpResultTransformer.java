package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolResultStorage;
import com.nexusai.application.agent.tool.impl.ImageResizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * MCP 工具结果转换器 · 对齐 CC {@code client.ts transformMCPResult / inferCompactSchema /
 * transformResultContent}（:2478-2591 / :2644-2660 / :2662-2706）。
 *
 * <p>[impl-I-4 T4] 结果三件套前半：
 * <ul>
 *   <li>{@link #transformMCPResult}：toolResult / structuredContent / contentArray 三分支 + schema；
 *       格式不符抛「MCP tool unexpected response format」</li>
 *   <li>{@link #inferCompactSchema}：null→'null' / 数组→[schema] / 对象→前 10 键 + ', ...' / 标量→typeof</li>
 *   <li>{@link #transformResultContent}：text / audio / image / resource / resource_link 全分支</li>
 * </ul>
 *
 * <p>[G28④ TR-E3-Q8] 双实现合并：image / resource-blob-image 分支统一走
 * {@link ImageResizer#resizeMcpImage}（CC {@code maybeResizeAndDownsampleImageBuffer}，client.ts:2503-2563），
 * 与 {@link McpToolPool#transformResultContent}（P2-16）行为一致；resource text 分支对齐 CC
 * {@code 'text' in resource}（键存在判定，含 null）。原「Java 无 resize 等价 → base64 直通」偏差已消除
 * （ImageResizer 即等价实现）。
 */
public final class McpResultTransformer {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(McpResultTransformer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private McpResultTransformer() {
        // 工具类
    }

    /**
     * 转换上下文 · 供 audio/blob 落盘（{@link ToolResultStorage#persistBinaryContent}）。
     * workspaceDir/sessionId 任一为 null → 不落盘（audio/blob 返回解码失败提示，不悬挂）。
     */
    public record TransformContext(Path workspaceDir, String sessionId) {}

    /**
     * transformMCPResult 产物 · 对齐 CC {@code TransformedMCPResult}（client.ts:2662-2706）。
     *
     * @param content       已转换内容（string 或 content block 数组的 JSON 序列化）
     * @param type          'toolResult' | 'structuredContent' | 'contentArray'
     * @param schema        inferCompactSchema 产物（仅 structuredContent/contentArray 有）
     * @param containsImages 转换内容含 image 块（CC processMCPResult 含图片 → 截断而非落盘）
     * @param contentNode   contentArray 分支的已转换块数组 JsonNode（toolResult/structuredContent 为 null）·
     *                      CC original: content: ContentBlockParam[]（client.ts:2689）。非截断 contentArray
     *                      时透传块结构（McpServerTool.execute 的 data 载体），截断/落盘走
     *                      {@link McpOutputProcessor} 的 String（CC MCPToolResult = string | ContentBlockParam[]）。
     */
    public record TransformedMCPResult(String content, String type, String schema,
                                       boolean containsImages, JsonNode contentNode) {

        /**
         * 4 参便捷构造（toolResult/structuredContent：无块数组载体 → contentNode=null）。
         * 保留既有 4 参调用方（McpOutputProcessorTest / 旧接线）。
         */
        public TransformedMCPResult(String content, String type, String schema, boolean containsImages) {
            this(content, type, schema, containsImages, null);
        }
    }

    /**
     * 结果三分支分类 · 对齐 CC {@code transformMCPResult}（client.ts:2662-2706）。
     *
     * @param result MCP tools/call 结果 JSON
     * @param tool   工具名（错误消息用）
     * @param name   server 名（transformResultContent 前缀 + 错误消息用）
     * @param ctx    落盘上下文（audio/blob；null → 不落盘）
     * @return 转换结果
     * @throws IllegalArgumentException 格式不符（CC「MCP tool unexpected response format」）
     */
    public static TransformedMCPResult transformMCPResult(JsonNode result, String tool, String name,
                                                          TransformContext ctx) {
        if (result != null && result.isObject()) {
            JsonNode toolResult = result.get("toolResult");
            if (toolResult != null && !toolResult.isNull()) {
                // CC :2668-2672 {content: String(result.toolResult), type: 'toolResult'}
                return new TransformedMCPResult(
                    toolResult.isValueNode() ? toolResult.asText() : toolResult.toString(),
                    "toolResult", null, false);
            }

            JsonNode structuredContent = result.get("structuredContent");
            if (structuredContent != null && !structuredContent.isNull()) {
                // CC :2674-2680 {content: jsonStringify, type: 'structuredContent', schema}
                return new TransformedMCPResult(
                    structuredContent.toString(), "structuredContent",
                    inferCompactSchema(structuredContent, 2), false);
            }

            JsonNode content = result.get("content");
            if (content != null && content.isArray()) {
                // CC :2682-2692 逐块 transformResultContent 平铺 + schema
                ArrayNode transformed = mapper.createArrayNode();
                boolean hasImages = false;
                for (JsonNode block : content) {
                    List<ObjectNode> blocks = transformResultContent(block, name, ctx);
                    for (ObjectNode b : blocks) {
                        if ("image".equals(b.path("type").asText())) {
                            hasImages = true;
                        }
                    }
                    transformed.addAll(blocks);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[McpResultTransformer] {}.{} contentArray 分类：块数={} 含图片={} schema={}",
                        name, tool, transformed.size(), hasImages, inferCompactSchema(transformed, 2));
                }
                return new TransformedMCPResult(
                    transformed.toString(), "contentArray",
                    inferCompactSchema(transformed, 2), hasImages, transformed);
            }
        }
        String errorMessage = "MCP server \"" + name + "\" tool \"" + tool
            + "\": unexpected response format";
        throw new IllegalArgumentException(errorMessage);
    }

    /**
     * 紧凑 schema 推断 · 对齐 CC {@code inferCompactSchema}（client.ts:2644-2660，depth=2）。
     *
     * @param value 值
     * @param depth 递归深度（数组取首元素 / 对象逐值递归，depth-1）
     * @return schema 字符串
     */
    public static String inferCompactSchema(JsonNode value, int depth) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isArray()) {
            if (value.isEmpty()) {
                return "[]";
            }
            return "[" + inferCompactSchema(value.get(0), depth - 1) + "]";
        }
        if (value.isObject()) {
            if (depth <= 0) {
                return "{...}";
            }
            List<String> props = new ArrayList<>();
            int count = 0;
            var it = value.fields();
            while (it.hasNext() && count < 10) {
                var entry = it.next();
                props.add(entry.getKey() + ": " + inferCompactSchema(entry.getValue(), depth - 1));
                count++;
            }
            String suffix = value.size() > 10 ? ", ..." : "";
            return "{" + String.join(", ", props) + suffix + "}";
        }
        // 标量 → typeof（textual/number/boolean 近似 JS typeof）
        if (value.isTextual()) {
            return "string";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        return "object";
    }

    /**
     * 单 content block 转换 · 对齐 CC {@code transformResultContent}（client.ts:2478-2591）。
     *
     * <p>分支：text→text 块；audio→落盘文本提示；image→base64 直通（受控偏差）；
     * resource→text 前缀拼接 / blob 落盘；resource_link→文本。
     *
     * @param block      content block（MCP CallToolResult.content 数组元素）
     * @param serverName server 名（前缀 + 落盘 persistId）
     * @param ctx        落盘上下文（null / sessionId null → audio/blob 返回失败提示，不悬挂）
     * @return 转换后的 content block 数组（可空/多元素）
     */
    public static List<ObjectNode> transformResultContent(JsonNode block, String serverName,
                                                          TransformContext ctx) {
        List<ObjectNode> result = new ArrayList<>();
        if (block == null || !block.isObject()) {
            return result;
        }
        String type = block.path("type").asText("");
        switch (type) {
            case "text" -> {
                ObjectNode textBlock = mapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", block.path("text").asText(""));
                result.add(textBlock);
            }
            case "audio" -> {
                // CC :2487-2493 persistBlobToTextBlock
                ObjectNode textBlock = mapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", persistAudio(block, serverName, ctx));
                result.add(textBlock);
            }
            case "image" -> {
                // [G28④] 合并双实现（TR-E3-Q8）：CC :2503-2523 maybeResizeAndDownsampleImageBuffer →
                //   resize 后 image 块。Java 旧实现 base64 直通（受控偏差）与 McpToolPool.transformResultContent
                //   （P2-16 ImageResizer）行为分裂；统一走 ImageResizer.resizeMcpImage（标准缩放，无 token
                //   预算激进压缩，client.ts:2505-2511）。resize 失败抛 ImageResizeError（CC throw 语义，
                //   由调用方 McpServerTool catch 统一 fail-loud）。
                String data = block.path("data").asText("");
                String mimeType = block.path("mimeType").asText("");
                ImageResizer.ResizedMcpImage resized =
                    ImageResizer.resizeMcpImage(Base64.getDecoder().decode(data), mimeType);
                ObjectNode imageBlock = mapper.createObjectNode();
                imageBlock.put("type", "image");
                ObjectNode source = imageBlock.putObject("source");
                source.put("type", "base64");
                source.put("media_type", resized.mediaType());
                source.put("data", resized.base64());
                result.add(imageBlock);
            }
            case "resource" -> {
                // CC :2513-2552 resource text/blob
                JsonNode resource = block.get("resource");
                if (resource != null && resource.isObject()) {
                    String uri = resource.path("uri").asText("");
                    String prefix = "[Resource from " + serverName + " at " + uri + "] ";
                    // [G28④] CC :2528 'text' in resource —— 键存在判定（含 text:null）
                    if (resource.has("text")) {
                        ObjectNode textBlock = mapper.createObjectNode();
                        textBlock.put("type", "text");
                        textBlock.put("text", prefix + resource.path("text").asText(""));
                        result.add(textBlock);
                    } else if (resource.has("blob")) {
                        // blob：图片 resize → [text prefix, image]，非图片落盘（CC :2535-2571）
                        String mimeType = resource.path("mimeType").asText("");
                        if (isImageMime(mimeType)) {
                            // CC :2538-2563 image blob → [text prefix, image block]（统一 McpToolPool
                            //   resource-blob-image 行为，旧实现漏 text prefix 前缀块）
                            if (!prefix.isEmpty()) {
                                ObjectNode textBlock = mapper.createObjectNode();
                                textBlock.put("type", "text");
                                textBlock.put("text", prefix);
                                result.add(textBlock);
                            }
                            ImageResizer.ResizedMcpImage resized = ImageResizer.resizeMcpImage(
                                Base64.getDecoder().decode(resource.path("blob").asText("")), mimeType);
                            ObjectNode imageBlock = mapper.createObjectNode();
                            imageBlock.put("type", "image");
                            ObjectNode source = imageBlock.putObject("source");
                            source.put("type", "base64");
                            source.put("media_type", resized.mediaType());
                            source.put("data", resized.base64());
                            result.add(imageBlock);
                        } else {
                            ObjectNode textBlock = mapper.createObjectNode();
                            textBlock.put("type", "text");
                            textBlock.put("text", persistBlob(resource.path("blob").asText(""),
                                mimeType, serverName, prefix, ctx));
                            result.add(textBlock);
                        }
                    }
                }
            }
            case "resource_link" -> {
                // CC :2554-2565 [Resource link: name] uri (description)
                String name = block.path("name").asText("");
                String uri = block.path("uri").asText("");
                String description = block.path("description").asText("");
                String text = "[Resource link: " + name + "] " + uri;
                if (!description.isEmpty()) {
                    text += " (" + description + ")";
                }
                ObjectNode textBlock = mapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", text);
                result.add(textBlock);
            }
            default -> {
                // CC :2567-2569 default → []（不抛，静默跳过未知类型）
            }
        }
        return result;
    }

    /** audio 块落盘 → 文本提示（对齐 McpServerTool.transformAudioBlock 既有语义）。 */
    private static String persistAudio(JsonNode block, String serverName, TransformContext ctx) {
        String mimeType = block.hasNonNull("mimeType") ? block.path("mimeType").asText() : null;
        String data = block.hasNonNull("data") ? block.path("data").asText() : null;
        return persistBlob(data, mimeType, serverName, "[Audio from " + serverName + "] ", ctx);
    }

    /**
     * blob → 落盘 → 文本提示 · 对齐 CC {@code persistBlobToTextBlock}（client.ts:2598-2622）+
     * {@code getBinaryBlobSavedMessage}（mcpOutputStorage.ts:181-189）。
     * 无上下文 / base64 解码失败 → 失败模板（不悬挂）。
     */
    private static String persistBlob(String data, String mimeType, String serverName,
                                      String prefix, TransformContext ctx) {
        String mt = mimeType != null && !mimeType.isEmpty() ? mimeType : "unknown type";
        if (ctx == null || ctx.workspaceDir() == null || ctx.sessionId() == null || data == null) {
            return prefix + "Binary content (" + mt + ", 0 bytes) could not be saved to disk: "
                + "missing persistence context";
        }
        String persistId = "mcp-" + McpStringUtils.normalizeNameForMCP(serverName)
            + "-blob-" + System.currentTimeMillis() + "-" + randomBase36(6);
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            ToolResultStorage.BinaryPersistResult r = ToolResultStorage.persistBinaryContent(
                ctx.workspaceDir(), ctx.sessionId(), bytes, mimeType, persistId).join();
            if (r.isSuccess()) {
                return prefix + "Binary content (" + mt + ", "
                    + ToolResultStorage.formatFileSize(r.size()) + ") saved to " + r.filepath();
            }
            return prefix + "Binary content (" + mt + ", " + bytes.length
                + " bytes) could not be saved to disk: " + r.error();
        } catch (IllegalArgumentException e) {
            return prefix + "Binary content (" + mt + ", could not decode base64) could not be "
                + "saved to disk: " + e.getMessage();
        }
    }

    private static boolean isImageMime(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
    }

    /** CC Math.random().toString(36).slice(2, 8) 等价 · 6 位 base36 随机串。 */
    private static String randomBase36(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append("0123456789abcdefghijklmnopqrstuvwxyz"
                .charAt((int) (Math.random() * 36)));
        }
        return sb.toString();
    }
}
