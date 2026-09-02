package com.nexusai.infra.util;

/**
 * 图片缩放失败错误 · 对齐 CC utils/imageResizer.ts:37-42 ImageResizeError。
 *
 * <p>CC 原形（grep 自验 imageResizer.ts:37-42）：
 * <pre>
 * export class ImageResizeError extends Error {
 *   constructor(message: string) {
 *     super(message)
 *     this.name = 'ImageResizeError'
 *   }
 * }
 * </pre>
 *
 * <p>Java 端映射为 {@link RuntimeException}（Java 无 TS 动态 this.name，异常类名天然承载
 * {@code ImageResizeError}）。在图片处理失败且图片超过 API 限制时抛出，供 query 错误分类
 * （query.ts:971 instanceof ImageResizeError → image_error）识别为用户友好图片错误。
 *
 * <p><b>[V-IMG-02 修正 · P-35] 实际 throw 点</b>：{@code ImageResizer.java:99/:165/:271/:339}
 * 在图片缩放/压缩失败时 throw（read 自验：空 buffer / 解码失败且超尺寸 / 压缩失败）；
 * {@code ReadFileTool.java:1432-1439} 本地 catch 转 {@code ToolResult.error}（用户友好图片
 * 错误，原字节直发路径已删无兼容壳）——故本异常<b>不</b>传播到 LlmAgentLoop 错误面，
 * {@code isImageError()} 不保留其 instanceof 判定（对 loop 为死检查，语义由 ReadFileTool
 * 本地转化兜底）。保留本类供图片缩放链路复用（CC imageResizer.ts:37-42 类型映射锚点）。
 */
public class ImageResizeError extends RuntimeException {

    public ImageResizeError(String message) {
        super(message);
    }

    public ImageResizeError(String message, Throwable cause) {
        super(message, cause);
    }
}
