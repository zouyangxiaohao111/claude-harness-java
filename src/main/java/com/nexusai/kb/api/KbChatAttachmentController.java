package com.nexusai.kb.api;

import com.nexusai.kb.app.attachment.KbChatAttachmentService;
import com.nexusai.kb.repo.entity.KbChatAttachmentRecord;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库对话 · <b>会话临时附件</b>上传（对齐源站 {@code POST /sessions/{id}/attachments}）。
 *
 * <p><b>⛔ 独立路径，不碰主会话</b>：主会话走 {@code /api/v1/attachments/upload}（只存字节、
 * 靠 Read 工具读）。知识库各人格没有 Read（且 Read 对 docx/xlsx 是二进制黑名单），
 * 所以这里上传即解析成正文，发消息时按预算内联。
 *
 * <p>路径前缀 {@code /kb/chat/**} 与统一入口同族，前端据此区分「知识库上传」。
 */
@RestController
public class KbChatAttachmentController {

    private final KbChatAttachmentService service;

    public KbChatAttachmentController(KbChatAttachmentService service) {
        this.service = service;
    }

    /**
     * 上传一个文件并同步解析。
     *
     * @param sessionId 会话 id
     * @param file      multipart 文件（字段名 {@code file}）
     * @return 附件状态（ready = 正文可用；failed = errorMessage 给原因）
     */
    @PostMapping("/api/v1/kb/chat/{sessionId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentDto upload(@PathVariable String sessionId,
                                @RequestParam("file") MultipartFile file) {
        KbChatAttachmentRecord row = service.upload(sessionId, file);
        return new AttachmentDto(
            row.getId(), row.getFileName(), row.getFileType(), row.getFileSize(),
            row.getStatus(), row.getTokenCount(), row.getErrorMessage());
    }

    /** 上传结果（前端据此决定是否可发送 / 显示「解析中/失败」）。 */
    public record AttachmentDto(
        String id,
        String fileName,
        String fileType,
        Integer fileSize,
        String status,
        Integer tokenCount,
        String errorMessage
    ) {
    }

    /**
     * 读取一条会话临时附件（含解析后的正文）—— 气泡点击「展示」用它。
     *
     * <p>⛔ 只返回本会话名下的附件（越权保护在 service 的 {@code findOwned} 里）；
     * 未解析完成 / 解析失败时 content 为空，status 与 errorMessage 给出原因。
     */
    @GetMapping("/api/v1/kb/chat/{sessionId}/attachments/{id}")
    public AttachmentDetailDto get(@PathVariable String sessionId, @PathVariable String id) {
        KbChatAttachmentRecord row = service.findOwned(sessionId, id);
        if (row == null) {
            throw new com.nexusai.infra.exception.NotFoundException("附件不存在或不属于该会话: " + id);
        }
        return new AttachmentDetailDto(
            row.getId(), row.getFileName(), row.getFileType(), row.getFileSize(),
            row.getStatus(), row.getTokenCount(), row.getErrorMessage(), row.getContent());
    }

    /** 附件详情（正文为解析结果；点气泡展示用）。 */
    public record AttachmentDetailDto(
        String id,
        String fileName,
        String fileType,
        Integer fileSize,
        String status,
        Integer tokenCount,
        String errorMessage,
        String content
    ) {
    }

    /**
     * 原始文件字节 —— 点气泡后用**现成的原格式渲染器**（PDF / docx-preview / exceljs）展示，
     * 而不是把解析文本摊出来。
     *
     * <p>⛔ 只返回本会话名下的附件（{@code findOwned} 越权保护）。
     */
    @GetMapping("/api/v1/kb/chat/{sessionId}/attachments/{id}/raw")
    public ResponseEntity<Resource> raw(@PathVariable String sessionId, @PathVariable String id) {
        KbChatAttachmentRecord row = service.findOwned(sessionId, id);
        if (row == null) {
            throw new com.nexusai.infra.exception.NotFoundException("附件不存在或不属于该会话: " + id);
        }
        java.nio.file.Path file = service.fileOf(row);
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new com.nexusai.infra.exception.NotFoundException("附件原始文件已丢失: " + id);
        }
        return ResponseEntity.ok()
            .contentType(mediaTypeOf(row.getFileType()))
            .header("Content-Disposition", "inline")
            .body(new FileSystemResource(file));
    }

    /** 扩展名 → Content-Type（渲染器靠它判断怎么解）；未知一律 octet-stream。 */
    private static MediaType mediaTypeOf(String ext) {
        String e = ext == null ? "" : ext.toLowerCase(java.util.Locale.ROOT);
        return switch (e) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "docx" -> MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xlsx" -> MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "md", "markdown", "txt", "csv" -> MediaType.parseMediaType("text/plain;charset=UTF-8");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
