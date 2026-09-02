package com.nexusai.model.session.dto;

/**
 * PDF multipart 上传响应 · U1 混合传输（C）&gt;5MB 大 PDF 上传落盘端点返回值。
 *
 * <p>前端拿到 {@link #contentId()} 后，可放入后续 {@code SendMessageRequest.attachments} 的
 * {@code {type:'pdf', contentId: <本值>}} 元素引用该已落盘 PDF（后端经
 * {@code com.nexusai.application.agent.attachment.PdfAttachmentStore} 路径通道解析，CC 路径通道语义）。
 *
 * @param contentId PDF 路径通道 contentId（PdfAttachmentStore 分配的整数 id 字符串，等价
 *                  {@link AttachmentRequest#contentId()} 承载形态）
 * @param filename  客户端原始文件名（显示名）
 * @param mediaType MIME 类型（恒 {@code application/pdf}）
 * @param size      原始字节数
 */
public record PdfUploadResponse(
    String contentId,
    String filename,
    String mediaType,
    long size
) {}
