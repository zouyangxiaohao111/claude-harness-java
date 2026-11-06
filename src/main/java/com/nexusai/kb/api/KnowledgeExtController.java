package com.nexusai.kb.api;

import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.chunk.KbChunkService;
import com.nexusai.kb.app.kb.KbKnowledgeExtService;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.app.tag.KbTagService;
import com.nexusai.kb.domain.KnowledgeService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.KnowledgeDto;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识管理扩展端点 · 批次 W4（源侧「知识管理」tag 余下 15 个）。
 *
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/knowledge/batch</td><td>批量取文档</td></tr>
 *   <tr><td>GET</td><td>/knowledge/{id}/download</td><td>下载原始文件</td></tr>
 *   <tr><td>GET</td><td>/knowledge/{id}/preview</td><td>预览（文本给正文；二进制给提示）</td></tr>
 *   <tr><td>GET</td><td>/knowledge/{id}/spans</td><td>Span 树 + 解析尝试历史</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/knowledge/batch-download</td><td>批量下载（zip）</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/knowledge/manual</td><td>手工创建</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/knowledge/url</td><td>URL 导入</td></tr>
 *   <tr><td>POST</td><td>/knowledge/batch-delete</td><td>批量删除</td></tr>
 *   <tr><td>POST</td><td>/knowledge/batch-reparse</td><td>批量重解析</td></tr>
 *   <tr><td>POST</td><td>/knowledge/folder</td><td>移动文档到文件夹</td></tr>
 *   <tr><td>GET</td><td>/knowledge-bases/{id}/knowledge/folders</td><td>文件夹树</td></tr>
 *   <tr><td>PUT</td><td>/knowledge-bases/{id}/knowledge/folders</td><td>重命名/移动文件夹</td></tr>
 *   <tr><td>PUT</td><td>/knowledge/image/{id}/{chunk_id}</td><td>更新图片信息</td></tr>
 *   <tr><td>PUT</td><td>/knowledge/manual/{id}</td><td>更新手工知识</td></tr>
 *   <tr><td>PUT</td><td>/knowledge/{id}</td><td>更新文档元信息</td></tr>
 *   <tr><td>GET</td><td>/knowledge/search</td><td>按标题/文件名的简单搜索</td></tr>
 *   <tr><td>POST</td><td>/chunker/preview</td><td>分块预览（不落库）</td></tr>
 * </table>
 *
 * <h2>⭐ 下载必须校验路径归属</h2>
 * {@code file_path} 是入库时落盘的绝对路径。直接 {@code Files.readAllBytes(Path.of(filePath))}
 * 意味着如果库里某个 file_path 被篡改成 {@code C:\Windows\...}，就能读到任意文件。
 * ⇒ 校验「该路径必须落在配置的 storage-dir 之下」。这是必须的，不是可选加固。
 */
@RestController
public class KnowledgeExtController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtController.class);

    @Autowired
    private KbKnowledgeExtService extService;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KbKnowledgeMapper knowledgeMapper;

    @Autowired
    private KbChunkService chunkService;

    @Autowired
    private KbIndexWriter indexWriter;

    @Autowired
    private KbTagService tagService;

    @org.springframework.beans.factory.annotation.Value(
        "${nexusai.knowledge.storage-dir:${user.home}/.nexusai/kb-storage}")
    private String storageDir;

    // ── 批量取 ──────────────────────────────────────────────────────

    @GetMapping("/api/v1/knowledge/batch")
    public List<KnowledgeDto> batchGet(@RequestParam("ids") String ids) {
        List<String> list = splitIds(ids);
        List<KnowledgeDto> out = new ArrayList<>(list.size());
        for (KbKnowledgeRecord row : extService.batchGet(list)) {
            out.add(toDto(row));
        }
        return out;
    }

    // ── 下载 / 预览 ─────────────────────────────────────────────────

    @GetMapping("/api/v1/knowledge/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) throws IOException {
        KbKnowledgeRecord row = requireKnowledge(id);
        Path file = resolveStorageFile(row);
        if (file == null) {
            throw new NotFoundException("文档 " + id + " 没有可下载的原始文件"
                + "（手工创建 / URL 导入的文档不保存原文件）");
        }
        byte[] data = Files.readAllBytes(file);
        String name = row.getFileName() == null || row.getFileName().isBlank()
            ? file.getFileName().toString() : row.getFileName();
        log.info("[知识API] 下载 id={} 文件={} 字节={}", id, name, data.length);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encodeFileName(name))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new ByteArrayResource(data));
    }

    /**
     * 预览。
     *
     * <p>⚠️ 纯文本给正文；二进制（PDF / Office）<b>不</b>假装能给预览 ——
     * 返回 {@code {previewable:false, reason}} 让前端显示「不支持预览，请下载」。
     * 伪造一份「已解析文本」当预览会让用户误判解析质量。
     */
    @GetMapping("/api/v1/knowledge/{id}/preview")
    public PreviewResponse preview(@PathVariable String id) {
        KbKnowledgeRecord row = requireKnowledge(id);
        String kind = classify(row);
        List<KbChunkRecord> chunks = chunkService.list(id, false);
        int chars = 0;
        for (KbChunkRecord c : chunks) {
            chars += c.getContent() == null ? 0 : c.getContent().length();
        }
        if ("text".equals(kind)) {
            StringBuilder sb = new StringBuilder();
            for (KbChunkRecord c : chunks) {
                sb.append(c.getContent() == null ? "" : c.getContent()).append("\n\n");
            }
            String text = sb.toString();
            int limit = Math.min(text.length(), 20_000);
            return new PreviewResponse(true, "text", text.substring(0, limit),
                text.length() > limit, chunks.size(), chars, null);
        }
        return new PreviewResponse(false, kind, null, false, chunks.size(), chars,
            "该格式需要专用解析器渲染，当前界面不支持内联预览；请下载后用本地应用打开。"
                + "已提取的正文可通过分块列表查看。");
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/knowledge/batch-download")
    public ResponseEntity<Resource> batchDownload(@PathVariable String id,
                                                  @RequestBody BatchDownloadRequest req) {
        if (req.knowledgeIds() == null || req.knowledgeIds().isEmpty()) {
            throw new ValidationException("knowledge_ids 不能为空");
        }
        java.io.ByteArrayOutputStream zip = new java.io.ByteArrayOutputStream();
        int added = 0;
        List<String> skipped = new ArrayList<>();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(zip)) {
            java.util.Set<String> usedNames = new java.util.HashSet<>();
            for (String kid : req.knowledgeIds()) {
                KbKnowledgeRecord row = knowledgeMapper.selectOneById(kid);
                if (row == null || !id.equals(row.getKnowledgeBaseId())) {
                    skipped.add(kid);
                    continue;
                }
                Path file = resolveStorageFile(row);
                if (file == null) {
                    skipped.add(kid);
                    continue;
                }
                String name = row.getFileName() == null || row.getFileName().isBlank()
                    ? file.getFileName().toString() : row.getFileName();
                // 同名文件加序号，避免 zip 内条目覆盖
                String entry = name;
                int n = 1;
                while (!usedNames.add(entry)) {
                    int dot = name.lastIndexOf('.');
                    entry = dot < 0 ? name + "(" + n + ")"
                        : name.substring(0, dot) + "(" + n + ")" + name.substring(dot);
                    n++;
                }
                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
                added++;
            }
        } catch (IOException e) {
            throw new ValidationException("打包失败: " + e.getMessage());
        }
        if (added == 0) {
            throw new NotFoundException("没有可下载的文件（跳过 " + skipped.size() + " 个）");
        }
        log.info("[知识API] 批量下载 kb={} 打包={} 跳过={}", id, added, skipped.size());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"knowledge-" + added + ".zip\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(new ByteArrayResource(zip.toByteArray()));
    }

    // ── 创建 ────────────────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-bases/{id}/knowledge/manual")
    public ResponseEntity<KnowledgeDto> createManual(@PathVariable String id,
                                                     @RequestBody ManualKnowledgeRequest req) {
        KbKnowledgeRecord row = extService.createManual(id, req.title(), req.content(),
            req.metadata());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(row));
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/knowledge/url")
    public ResponseEntity<KnowledgeDto> createFromUrl(@PathVariable String id,
                                                      @RequestBody UrlKnowledgeRequest req) {
        KbKnowledgeRecord row = extService.createFromUrl(id, req.url(), req.fileName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(row));
    }

    // ── 批量 ────────────────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge/batch-delete")
    public BatchResponse batchDelete(@RequestBody BatchIdsRequest req) {
        if (req.knowledgeIds() == null || req.knowledgeIds().isEmpty()) {
            throw new ValidationException("knowledge_ids 不能为空");
        }
        var r = extService.batchDelete(req.knowledgeIds());
        return new BatchResponse(r.succeeded(), r.failed());
    }

    @PostMapping("/api/v1/knowledge/batch-reparse")
    public BatchResponse batchReparse(@RequestBody BatchIdsRequest req) {
        if (req.knowledgeIds() == null || req.knowledgeIds().isEmpty()) {
            throw new ValidationException("knowledge_ids 不能为空");
        }
        var r = extService.batchReparse(req.knowledgeIds());
        return new BatchResponse(r.succeeded(), r.failed());
    }

    // ── 文件夹 ──────────────────────────────────────────────────────

    @GetMapping("/api/v1/knowledge-bases/{id}/knowledge/folders")
    public FolderResponse folders(@PathVariable String id) {
        return new FolderResponse(extService.folderTree(id));
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/knowledge/folders")
    public RenameFolderResponse renameFolder(@PathVariable String id,
                                             @RequestBody RenameFolderRequest req) {
        int affected = extService.renameFolder(id, req.from(), req.to());
        return new RenameFolderResponse(affected);
    }

    @PostMapping("/api/v1/knowledge/folder")
    public MoveFolderResponse moveToFolder(@RequestBody MoveToFolderRequest req) {
        int moved = extService.moveToFolder(req.knowledgeIds(), req.folderPath());
        return new MoveFolderResponse(moved);
    }

    // ── 更新 ────────────────────────────────────────────────────────

    @PutMapping("/api/v1/knowledge/{id}")
    public KnowledgeDto update(@PathVariable String id, @RequestBody UpdateKnowledgeRequest req) {
        KbKnowledgeRecord row = extService.update(id, req.title(), req.description(),
            req.enableStatus(), req.customMetadata(), req.folderPath());
        return toDto(row);
    }

    @PutMapping("/api/v1/knowledge/manual/{id}")
    public KnowledgeDto updateManual(@PathVariable String id,
                                     @RequestBody ManualKnowledgeRequest req) {
        KbKnowledgeRecord row = extService.updateManual(id, req.title(), req.content());
        // ⚠️ 正文重切块后必须重建索引（否则搜索还是旧文本，且不报错）
        try {
            indexWriter.reindexKnowledge(row.getId(), row.getKnowledgeBaseId(),
                row.getEmbeddingModelId());
        } catch (RuntimeException e) {
            log.error("[知识API] 手工知识已更新但索引重建失败 id={} —— 搜索仍是旧文本", id, e);
        }
        return toDto(row);
    }

    @PutMapping("/api/v1/knowledge/image/{id}/{chunkId}")
    public ChunkImageView updateImageInfo(@PathVariable String id, @PathVariable String chunkId,
                                          @RequestBody UpdateImageRequest req) {
        KbChunkRecord chunk = extService.updateImageInfo(id, chunkId,
            req.imageIndex() == null ? 0 : req.imageIndex(),
            req.caption(), req.ocrText(), req.isEnabled());
        return new ChunkImageView(chunk.getId(), chunk.getImageInfo());
    }

    // ── spans / 搜索 / 分块预览 ──────────────────────────────────────

    @GetMapping("/api/v1/knowledge/{id}/spans")
    public KbKnowledgeExtService.SpanTree spans(@PathVariable String id) {
        return extService.spans(id);
    }

    /** 源侧还有一个 {@code /knowledge/{id}/stages} 别名。 */
    @GetMapping("/api/v1/knowledge/{id}/stages")
    public KbKnowledgeExtService.SpanTree stages(@PathVariable String id) {
        return extService.spans(id);
    }

    @GetMapping("/api/v1/knowledge/search")
    public List<KnowledgeDto> search(@RequestParam("keyword") String keyword,
                                     @RequestParam(value = "kb_id", required = false) String kbId,
                                     @RequestParam(value = "limit", required = false) Integer limit) {
        List<KnowledgeDto> out = new ArrayList<>();
        for (KbKnowledgeRecord row : extService.search(keyword, kbId, limit)) {
            out.add(toDto(row));
        }
        return out;
    }

    @PostMapping("/api/v1/chunker/preview")
    public List<ChunkPreview> chunkerPreview(@RequestBody ChunkerPreviewRequest req) {
        List<KbChunk> chunks = extService.previewChunks(req.text(), req.chunkingConfig());
        List<ChunkPreview> out = new ArrayList<>(chunks.size());
        for (KbChunk c : chunks) {
            out.add(new ChunkPreview(c.seq(), c.content(), c.contextHeader(), c.start(), c.end()));
        }
        return out;
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private KbKnowledgeRecord requireKnowledge(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("文档 ID 不能为空");
        }
        KbKnowledgeRecord row = knowledgeMapper.selectOneById(id);
        if (row == null) {
            throw new NotFoundException("文档 " + id + " 不存在");
        }
        return row;
    }

    /**
     * 解析并校验落盘路径。
     *
     * <p>⭐ 必须校验「在 storage-dir 之下」：见类注释。返回 null = 没有本地文件。
     */
    private Path resolveStorageFile(KbKnowledgeRecord row) {
        String raw = row.getFilePath();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path file;
        try {
            file = Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            log.warn("[知识API] 文档 {} 的 file_path 非法：{}", row.getId(), raw);
            return null;
        }
        Path base = Path.of(storageDir).toAbsolutePath().normalize();
        if (!file.startsWith(base)) {
            // ⚠️ 不返回具体路径给外部：那是信息泄露
            log.error("[知识API] 拒绝下载：文档 {} 的 file_path 不在 storage-dir 内", row.getId());
            throw new ValidationException("该文档的文件路径不在允许范围内，拒绝访问");
        }
        return Files.exists(file) ? file : null;
    }

    private static String classify(KbKnowledgeRecord row) {
        String ext = row.getFileType() == null ? "" : row.getFileType().toLowerCase();
        return switch (ext) {
            // "text" 是手工创建（type=manual）写入的 file_type
            case "text", "txt", "md", "markdown", "csv", "json", "html", "htm" -> "text";
            case "pdf" -> "pdf";
            case "doc", "docx" -> "word";
            case "xls", "xlsx" -> "excel";
            case "ppt", "pptx" -> "powerpoint";
            case "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "image";
            default -> {
                // 没有扩展名时按 type 判定：manual / url 的正文都是纯文本
                String type = row.getType() == null ? "" : row.getType();
                yield type.equals("manual") || type.equals("url") ? "text" : "binary";
            }
        };
    }

    private static String encodeFileName(String name) {
        return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    private static List<String> splitIds(String ids) {
        List<String> out = new ArrayList<>();
        for (String s : ids.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static KnowledgeDto toDto(KbKnowledgeRecord r) {
        return new KnowledgeDto(
            r.getId(),
            r.getKnowledgeBaseId(),
            r.getType(),
            r.getChannel(),
            r.getTitle(),
            r.getDescription(),
            r.getSource(),
            r.getParseStatus(),
            r.getEnableStatus(),
            r.getSummaryStatus(),
            r.getPendingSubtasksCount() == null ? 0 : r.getPendingSubtasksCount(),
            r.getEmbeddingModelId(),
            r.getFileName(),
            r.getFileType(),
            r.getFileSize(),
            r.getFilePath(),
            r.getFileHash(),
            r.getStorageSize() == null ? 0L : r.getStorageSize(),
            r.getFolderPath(),
            r.getMetadata(),
            r.getCustomMetadata(),
            r.getErrorMessage(),
            r.getProcessedAt(),
            KbTimeFormat.format(r.getCreateTime()),
            KbTimeFormat.format(r.getUpdateTime()),
            // ⚠️ 单条更新/手工创建这类路径不查标签（避免为了一个附属字段多一次往返）⇒
            //   空数组。前端在列表里拿到的才是有标签的版本；详情页需要标签时走列表接口。
            java.util.List.of());
    }

    // ── 请求 / 响应 DTO ─────────────────────────────────────────────

    public record ManualKnowledgeRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("title") String title,
        @com.fasterxml.jackson.annotation.JsonProperty("content") String content,
        @com.fasterxml.jackson.annotation.JsonProperty("metadata") String metadata) {
    }

    public record UrlKnowledgeRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("url") String url,
        @com.fasterxml.jackson.annotation.JsonProperty("file_name") String fileName) {
    }

    public record BatchIdsRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_ids") List<String> knowledgeIds) {
    }

    public record BatchDownloadRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_ids") List<String> knowledgeIds) {
    }

    public record BatchResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("succeeded") List<String> succeeded,
        @com.fasterxml.jackson.annotation.JsonProperty("failed") java.util.Map<String, String> failed) {
    }

    public record RenameFolderRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("from") String from,
        @com.fasterxml.jackson.annotation.JsonProperty("to") String to) {
    }

    public record RenameFolderResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("affected") int affected) {
    }

    public record MoveToFolderRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_ids") List<String> knowledgeIds,
        @com.fasterxml.jackson.annotation.JsonProperty("folder_path") String folderPath) {
    }

    public record MoveFolderResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("moved") int moved) {
    }

    public record FolderResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("folders")
        List<KbKnowledgeExtService.FolderNode> folders) {
    }

    public record UpdateKnowledgeRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("title") String title,
        @com.fasterxml.jackson.annotation.JsonProperty("description") String description,
        @com.fasterxml.jackson.annotation.JsonProperty("enable_status") String enableStatus,
        @com.fasterxml.jackson.annotation.JsonProperty("custom_metadata") String customMetadata,
        @com.fasterxml.jackson.annotation.JsonProperty("folder_path") String folderPath) {
    }

    public record UpdateImageRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("image_index") Integer imageIndex,
        @com.fasterxml.jackson.annotation.JsonProperty("caption") String caption,
        @com.fasterxml.jackson.annotation.JsonProperty("ocr_text") String ocrText,
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") Boolean isEnabled) {
    }

    public record ChunkImageView(
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_id") String chunkId,
        @com.fasterxml.jackson.annotation.JsonProperty("image_info") String imageInfo) {
    }

    public record PreviewResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("previewable") boolean previewable,
        @com.fasterxml.jackson.annotation.JsonProperty("kind") String kind,
        @com.fasterxml.jackson.annotation.JsonProperty("content") String content,
        @com.fasterxml.jackson.annotation.JsonProperty("truncated") boolean truncated,
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_count") int chunkCount,
        @com.fasterxml.jackson.annotation.JsonProperty("total_chars") int totalChars,
        @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason) {
    }

    public record ChunkerPreviewRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("text") String text,
        @com.fasterxml.jackson.annotation.JsonProperty("chunking_config") String chunkingConfig) {
    }

    public record ChunkPreview(
        @com.fasterxml.jackson.annotation.JsonProperty("seq") int seq,
        @com.fasterxml.jackson.annotation.JsonProperty("content") String content,
        @com.fasterxml.jackson.annotation.JsonProperty("context_header") String contextHeader,
        @com.fasterxml.jackson.annotation.JsonProperty("start") int start,
        @com.fasterxml.jackson.annotation.JsonProperty("end") int end) {
    }
}
