package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 工具结果 content 块参数 · 对齐 Anthropic SDK {@code ContentBlockParam}（{@code
 * @anthropic-ai/sdk/resources/messages/messages.mjs}）。
 *
 * <p>CC 端 {@code mapToolResultToToolResultBlockParam} 的 {@code content} 字段支持两种形态：
 * <ol>
 *   <li><b>字符串</b>：纯文本（WebFetchTool / TaskStopTool 用）</li>
 *   <li><b>块数组</b>：可用于插入 image / document 等非文本块（FileReadTool PDF
 *       {@code createUserMessage({content:[{type:'document',...}]})}、MCP
 *       multimodal 响应）</li>
 * </ol>
 *
 * <p>本 sealed interface 抽象 4 种块类型：
 * <ul>
 *   <li>{@link TextBlockParam} — {@code {type:"text", text:"..."}}</li>
 *   <li>{@link ImageBlockParam} — {@code {type:"image", source:{type:"base64", media_type:"image/...", data:"..."}}}</li>
 *   <li>{@link DocumentBlockParam} — {@code {type:"document", source:{type:"base64", media_type:"application/pdf", data:"..."}}}</li>
 *   <li>{@link ToolReferenceBlockParam} — {@code {type:"tool_reference", tool_name:"..."}}（ToolSearch 命中输出，CC ToolSearchTool.ts:465-468）</li>
 * </ul>
 *
 * <p>序列化策略：Jackson 通过 record 的 {@code @JsonProperty} 直接序列化（visible type
 * property + Jackson 2.11 不支持 EXISTING_PROPERTY）。多态分发通过 wrapper
 * {@code @JsonTypeInfo(use=NAME, property="type", visible=true, defaultImpl=TextBlockParam.class)}
 * + {@code @JsonSubTypes} 保证 deserialization 时按 type 字段正确分派。
 *
 * @see <a href="https://docs.anthropic.com/en/api/messages">Anthropic Messages API</a>
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    visible = true,
    defaultImpl = ContentBlockParam.TextBlockParam.class
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentBlockParam.TextBlockParam.class, name = "text"),
    @JsonSubTypes.Type(value = ContentBlockParam.ImageBlockParam.class, name = "image"),
    @JsonSubTypes.Type(value = ContentBlockParam.DocumentBlockParam.class, name = "document"),
    @JsonSubTypes.Type(value = ToolReferenceBlockParam.class, name = "tool_reference"),
})
public sealed interface ContentBlockParam
        permits ContentBlockParam.TextBlockParam,
                ContentBlockParam.ImageBlockParam,
                ContentBlockParam.DocumentBlockParam,
                ToolReferenceBlockParam {

    /**
     * 块类型标签（"text" / "image" / "document"）。每个 record 通过显式
     * {@link JsonProperty} 序列化此字段。
     */
    String type();

    /**
     * 文本块 · 对齐 Anthropic {@code TextBlockParam}。
     *
     * @param type 固定 "text"
     * @param text 文本内容
     */
    record TextBlockParam(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text) implements ContentBlockParam {
        public TextBlockParam {
            if (!"text".equals(type)) {
                throw new IllegalArgumentException(
                    "TextBlockParam.type must be \"text\", got: " + type);
            }
            if (text == null) {
                throw new IllegalArgumentException("TextBlockParam.text is null");
            }
        }

        /** 工厂: 从文本构造（type 字段固定为 "text"）。 */
        public TextBlockParam(String text) {
            this("text", text);
        }
    }

    /**
     * Base64 编码媒体源 · 对齐 Anthropic {@code Base64ImageSource} / {@code Base64PDFSource}。
     *
     * @param mediaType MIME 类型（{@code "image/png"} / {@code "image/jpeg"} /
     *                  {@code "application/pdf"} 等）
     * @param data      base64 编码后的字节流（无 {@code data:} 前缀）
     */
    record Base64Source(
            @JsonProperty("type") String type,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("data") String data) {

        public Base64Source {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("Base64Source.type is blank");
            }
            if (mediaType == null || mediaType.isBlank()) {
                throw new IllegalArgumentException("Base64Source.mediaType is blank");
            }
            if (data == null || data.isBlank()) {
                throw new IllegalArgumentException("Base64Source.data is blank");
            }
            if (!"base64".equals(type)) {
                throw new IllegalArgumentException(
                    "Base64Source.type must be \"base64\", got: " + type);
            }
        }

        /** 工厂: 构造 base64 源（type 字段固定为 "base64"）。 */
        public static Base64Source of(String mediaType, String data) {
            return new Base64Source("base64", mediaType, data);
        }
    }

    /**
     * 图片块 · 对齐 Anthropic {@code ImageBlockParam}。
     *
     * <p>CC 端用法：FileReadTool 读图像文件 → 构造 image block 注入 user 消息
     * （{@code FileReadTool.ts:920-944}）。
     *
     * @param type   固定 "image"
     * @param source base64 媒体源
     */
    record ImageBlockParam(
            @JsonProperty("type") String type,
            @JsonProperty("source") Base64Source source)
            implements ContentBlockParam {
        public ImageBlockParam {
            if (!"image".equals(type)) {
                throw new IllegalArgumentException(
                    "ImageBlockParam.type must be \"image\", got: " + type);
            }
            if (source == null) {
                throw new IllegalArgumentException("ImageBlockParam.source is null");
            }
        }

        /** 工厂: 从 mediaType + base64 data 构造（type 字段固定为 "image"）。 */
        public static ImageBlockParam of(String mediaType, String data) {
            return new ImageBlockParam("image", Base64Source.of(mediaType, data));
        }
    }

    /**
     * 文档块 · 对齐 Anthropic {@code DocumentBlockParam}。
     *
     * <p>CC 端用法：FileReadTool 读 PDF → 构造 document block 注入 user 消息
     * （{@code FileReadTool.ts:999-1016}）。
     *
     * @param type   固定 "document"
     * @param source base64 媒体源（通常 {@code mediaType=application/pdf}）
     */
    record DocumentBlockParam(
            @JsonProperty("type") String type,
            @JsonProperty("source") Base64Source source)
            implements ContentBlockParam {
        public DocumentBlockParam {
            if (!"document".equals(type)) {
                throw new IllegalArgumentException(
                    "DocumentBlockParam.type must be \"document\", got: " + type);
            }
            if (source == null) {
                throw new IllegalArgumentException("DocumentBlockParam.source is null");
            }
        }

        /** 工厂: 从 mediaType + base64 data 构造（type 字段固定为 "document"）。 */
        public static DocumentBlockParam of(String mediaType, String data) {
            return new DocumentBlockParam("document", Base64Source.of(mediaType, data));
        }
    }
}