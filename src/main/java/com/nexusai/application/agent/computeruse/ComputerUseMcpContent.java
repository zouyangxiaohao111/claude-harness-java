package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.tool.ContentBlockParam;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP content block · 对齐 MCP {@code CallToolResult.content} 元素形状
 * （{@code {type:'image', data:<base64>, mimeType:'image/jpeg'}}}）。
 *
 * <p><b>CCB 对照</b>：{@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2210-2214} 产出
 * <pre>
 * {
 *   type: 'image',
 *   data: shot.base64,
 *   mimeType: detectMimeFromBase64(shot.base64),
 * }
 * </pre>
 * 再由 {@code src/utils/computerUse/wrapper.tsx:302-318} 映射成 Anthropic API 的 base64 image 块：
 * <pre>
 * item.type === 'image'
 *   ? { type: 'image', source: { type: 'base64', media_type: item.mimeType ?? detectImageFormatFromBase64(item.data), data: item.data } }
 *   : { type: 'text', text: item.type === 'text' ? item.text : '' }
 * </pre>
 *
 * <p><b>本仓的映射落点</b>：本工具是<b>原生 Java Tool</b>（非 MCP server 工具），
 * 因此<b>不经过</b> {@code McpResultTransformer}；但本记录 + {@link #toApiBlocks} 复刻了
 * CCB 的同一映射规则，产出的目标形状与
 * {@code McpResultTransformer.transformResultContent}（{@code :207-224}）的 image 分支
 * （{@code {type:'image', source:{type:'base64', media_type, data}}}）<b>逐字段一致</b>，
 * 也与本仓既有唯一在役的 image block 生产者
 * {@code ReadFileTool.mapToToolResultBlockParam}（{@code :1404-1418}）同款。
 */
public record ComputerUseMcpContent(String type, String data, String mimeType, String text) {

    /** MCP content 类型：图片。 */
    public static final String TYPE_IMAGE = "image";
    /** MCP content 类型：文本。 */
    public static final String TYPE_TEXT = "text";

    /**
     * 构造图片 content block。
     *
     * @param base64   base64 载荷（无 {@code data:} 前缀）
     * @param mimeType MIME（{@code image/jpeg}）
     * @return content block
     */
    public static ComputerUseMcpContent image(String base64, String mimeType) {
        return new ComputerUseMcpContent(TYPE_IMAGE, base64, mimeType, null);
    }

    /**
     * 构造文本 content block。
     *
     * @param text 文本
     * @return content block
     */
    public static ComputerUseMcpContent text(String text) {
        return new ComputerUseMcpContent(TYPE_TEXT, null, null, text);
    }

    /**
     * MCP content → Anthropic API content block · 对齐 CCB {@code wrapper.tsx:302-318}。
     *
     * @param content MCP content 数组
     * @return API content block 数组（image → {@link ContentBlockParam.ImageBlockParam}；
     *         其余 → 文本块，空串兜底，与 CCB 的 fallthrough 一致）
     */
    public static List<ContentBlockParam> toApiBlocks(List<ComputerUseMcpContent> content) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        if (content == null) {
            return blocks;
        }
        for (ComputerUseMcpContent item : content) {
            if (item == null) {
                continue;
            }
            if (TYPE_IMAGE.equals(item.type())) {
                blocks.add(ContentBlockParam.ImageBlockParam.of(item.mimeType(), item.data()));
            } else {
                blocks.add(new ContentBlockParam.TextBlockParam(
                        item.text() == null ? "" : item.text()));
            }
        }
        return blocks;
    }
}
