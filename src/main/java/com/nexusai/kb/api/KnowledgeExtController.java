package com.nexusai.kb.api;

import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.chunk.KbChunkService;
import com.nexusai.kb.app.chunk.KbChunkingConfigParser;
import com.nexusai.kb.app.chunk.KbSplitterConfig;
import com.nexusai.kb.app.chunk.KbTextSplitter;
import com.nexusai.kb.app.kb.KbKnowledgeExtService;
import com.nexusai.kb.app.ingest.KbPostProcessOutcome;
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
import com.nexusai.model.dto.Problem;
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
 *   <tr><td>POST</td><td>/chunker/preview</td><td>分块预览（不落库，裸数组 —— 老契约）</td></tr>
 *   <tr><td>POST</td><td>/chunker/preview/diagnostics</td><td>分块预览（带画像/策略链/降级原因 —— 对齐源站）</td></tr>
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

    /**
     * 摘要生成 · 手动重新生成用（对齐源侧 {@code POST /knowledge/{id}/regenerate-summary}）。
     *
     * <p>⚠️ 用 {@code required = false}：摘要服务晚于本控制器装配时不能让整个 Bean 起不来
     * （它依赖 LlmSideCallClient 等一长串），端点里自己判 null 并如实报「未装配」。
     */
    @Autowired(required = false)
    private com.nexusai.kb.app.ingest.KbSummaryService summaryService;

    @Autowired
    private KbTagService tagService;

    @org.springframework.beans.factory.annotation.Value("${nexusai.knowledge.storage-dir:}")
    private String storageDir;

    /** [data-root] 未显式配置 ⇒ 统一数据根（安装目录优先，configHome 兜底）；与 LocalFileStorage / KnowledgeController 同源。 */
    @jakarta.annotation.PostConstruct
    void initStorageDir() {
        if (storageDir == null || storageDir.isBlank()) {
            storageDir = com.nexusai.common.NexusaiDataRoots.sub("kb-storage").toString();
        }
    }

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

    /**
     * 手动重新生成摘要（对齐源侧 {@code POST /api/v1/knowledge/{id}/regenerate-summary}）。
     *
     * <p><b>为什么需要</b>：摘要原本只在解析入库的后处理里跑一次。那次失败（模型报错 /
     * 没配摘要模型 / 正文当时还抽不出来），之后用户<b>没有任何入口补生成</b> ——
     * 抽屉里只显示「尚未生成」，只能整篇重新解析（重跑分块与索引，成本高且块 ID 会变）。
     *
     * <p><b>同步返回</b>：摘要是一次 LLM 调用，本仓侧调用有超时上限，这里同步做完再返回
     * 真实状态（{@code summary_status} + {@code description}），前端据此立刻刷新，
     * ⛔ 不返回「已排队」骗用户（队列里其实什么都没发生）。
     *
     * @param id 文档 ID
     * @return 最新摘要状态与正文
     */
    @PostMapping("/api/v1/knowledge/{id}/regenerate-summary")
    public SummaryRegenerateResponse regenerateSummary(@PathVariable String id) {
        if (summaryService == null) {
            throw new IllegalStateException("摘要服务未装配，无法重新生成");
        }
        KbPostProcessOutcome outcome = summaryService.regenerate(id);
        // 回读真实落库状态：不要用 outcome 的文案反推状态（两者口径不同）
        KbKnowledgeRecord row = knowledgeMapper.selectOneById(id);
        String summary = row == null ? "" : (row.getDescription() == null ? "" : row.getDescription());
        String status = row == null ? "failed"
            : (row.getSummaryStatus() == null ? "" : row.getSummaryStatus());
        // ⭐ detail 必须回给前端：只回「failed」用户完全无从排查
        //   （实测踩过：知识库没配摘要模型时后端只写了一句失败，UI 只能显示「失败」）。
        String detail = outcome == null || outcome.detail() == null ? "" : outcome.detail();
        log.info("[知识API] 重新生成摘要 id={} 结果={} 状态={} 字符数={} 原因={}",
            id, outcome == null ? "null" : outcome.status(), status, summary.length(), detail);
        return new SummaryRegenerateResponse(status, summary, detail);
    }

    /**
     * 摘要重新生成结果（对齐源侧 {@code {summary_status, description}}，外加 {@code detail}）。
     *
     * @param summaryStatus {@code pending | processing | completed | failed}
     * @param description   摘要正文（未生成时为空串，⛔ 不是 null —— 前端直接渲染）
     * @param detail        后端给的失败/跳过原因（成功时为空串）。前端据此给出可操作提示，
     *                      ⛔ 只说「失败」等于让用户猜
     */
    public record SummaryRegenerateResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("summary_status") String summaryStatus,
        @com.fasterxml.jackson.annotation.JsonProperty("description") String description,
        @com.fasterxml.jackson.annotation.JsonProperty("detail") String detail) {
    }

    // ── 搜索 / 分块预览 ──────────────────────────────────────────────
    //
    // ⭐ /knowledge/{id}/spans 与 /stages 已迁至 {@link KnowledgeController#spans}
    //    （V284 真 span 表 + 源站契约：attempt 选择 / 五段 pending 占位 / stall 判定）。
    //    这里曾留有一份「用分块按 start_at/end_at 投影成叶子 span」的旧实现，
    //    与新版同路径 ⇒ Spring 启动期 Ambiguous mapping（两个 @RestController、同包、
    //    无类级前缀）⇒ 已删除。{@code KbKnowledgeExtService#spans} 随之停用。

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

    // ── 分块预览（带诊断）────────────────────────────────────────────

    /** 预览输入上限 64k 字符（源侧 {@code chunker_debug.go:33} {@code previewMaxChars}）。 */
    private static final int PREVIEW_MAX_CHARS = 64 * 1024;

    /** 预览响应最多返回的块数（源侧 {@code :39} {@code previewMaxChunks}）。 */
    private static final int PREVIEW_MAX_CHUNKS = 500;

    /** 预览超时（源侧 {@code :43} {@code previewTimeout}）。 */
    private static final long PREVIEW_TIMEOUT_MS = 5_000L;

    /**
     * 分块预览（带「为什么是这个结果」的诊断）· 对齐源侧
     * {@code handler.PreviewChunking}（{@code chunker_debug.go:124-259}）。
     *
     * <p><b>为什么新开端点而不改老的 {@code /chunker/preview}</b>：
     * 老端点返回<b>裸数组</b>，改结构就是破坏契约；源侧该接口返回的是
     * <b>对象</b>（{@code {selected_tier, tier_chain, rejected, profile, chunks, stats}}）。
     * 两者并存：老端点留给既有调用方，新端点对齐源侧形态供调试面板使用。
     *
     * <p><b>三道闸</b>（逐条对齐源侧）：
     * <ol>
     *   <li>文本 &gt; 64k 字符 ⇒ <b>413</b>（{@code :142-149}）</li>
     *   <li>块数 &gt; 500 ⇒ 只回前 500，但<b>统计按全集算</b>，并带 {@code truncated_to}（{@code :229-235}）</li>
     *   <li>超过 5s ⇒ <b>504</b>（{@code :191-200}）</li>
     * </ol>
     *
     * <p><b>⚠️ 与源侧一致的限制</b>：切分器不接受取消信号，5s 超时只是
     * <b>不再等待</b>，工作线程仍会跑完（源侧 {@code :26-32} 的 SECURITY NOTE 原文）。
     * 64k 上限就是对这个「线程堆积」风险的主要缓解。
     *
     * @param req 请求（{@code text} 必填；{@code chunking_config} 可为对象或字符串）
     * @return 诊断结果
     */
    @PostMapping("/api/v1/chunker/preview/diagnostics")
    public ResponseEntity<?> chunkerPreviewDiagnostics(@RequestBody ChunkerDiagnosticsRequest req) {
        String rawText = req.text();
        if (rawText == null || rawText.isBlank()) {
            throw new ValidationException("text 不能为空");
        }
        int runeCount = rawText.codePointCount(0, rawText.length());
        if (runeCount > PREVIEW_MAX_CHARS) {
            log.warn("[分块预览] 文本超限 chars={} limit={}", runeCount, PREVIEW_MAX_CHARS);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Problem.of(413, "Payload Too Large",
                    "文本超过预览上限 " + PREVIEW_MAX_CHARS + " 字符（实际 " + runeCount + "）"));
        }

        String cfgJson = configJsonOf(req.chunkingConfig());
        KbSplitterConfig cfg = (cfgJson == null || cfgJson.isBlank())
            ? KbSplitterConfig.defaults()
            : KbChunkingConfigParser.parse(cfgJson);

        // 预览不需要图片归属 ⇒ 可安全做行尾归一（源侧 chunker_debug.go:150 同样在此归一）
        String text = KbTextSplitter.normalizeLineEndings(rawText);

        java.util.concurrent.CompletableFuture<KbTextSplitter.SplitDiagnostics> future =
            java.util.concurrent.CompletableFuture.supplyAsync(
                () -> KbTextSplitter.splitWithDiagnostics(text, cfg));

        KbTextSplitter.SplitDiagnostics result;
        try {
            result = future.get(PREVIEW_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("[分块预览] 超时 {}ms（工作线程仍会跑完，源站同样限制）", PREVIEW_TIMEOUT_MS);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Problem.of(504, "Gateway Timeout", "分块预览超时（>" + PREVIEW_TIMEOUT_MS + "ms）"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidationException("分块预览被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("[分块预览] 切分失败", cause);
            throw new ValidationException("分块失败: " + cause.getClass().getSimpleName()
                + ": " + cause.getMessage());
        }

        KbTextSplitter.Diagnostics diag = result.diag();
        List<KbChunk> all = result.chunks();

        // 统计按全集算（源侧 :227-235）
        ChunkStatsView stats = ChunkStatsView.of(all,
            langOf(diag.profile()));

        List<KbChunk> trimmed = all;
        Integer truncatedTo = null;
        if (all.size() > PREVIEW_MAX_CHUNKS) {
            trimmed = all.subList(0, PREVIEW_MAX_CHUNKS);
            truncatedTo = all.size();
            log.info("[分块预览] 块数 {} 超过 {}，响应截断（统计仍按全集）",
                all.size(), PREVIEW_MAX_CHUNKS);
        }

        List<PreviewChunkView> chunkViews = new ArrayList<>(trimmed.size());
        String lang = langOf(diag.profile());
        for (KbChunk c : trimmed) {
            int chars = c.content().codePointCount(0, c.content().length());
            chunkViews.add(new PreviewChunkView(c.seq(), c.start(), c.end(), chars,
                com.nexusai.kb.app.chunk.KbTokenEstimator.approxTokenCountFromRuneLen(chars, lang),
                c.contextHeader(), c.content()));
        }

        List<String> chain = new ArrayList<>(diag.tierChain().size());
        for (com.nexusai.kb.app.chunk.KbStrategyTier t : diag.tierChain()) {
            chain.add(t.wire());
        }
        List<TierRejectionView> rejected = new ArrayList<>(diag.rejected().size());
        for (KbTextSplitter.TierRejection r : diag.rejected()) {
            rejected.add(new TierRejectionView(r.tier().wire(), r.reason()));
        }

        ChunkerDiagnosticsResponse body = new ChunkerDiagnosticsResponse(
            diag.selectedTier().wire(), chain, rejected,
            DocProfileView.of(diag.profile()), chunkViews,
            truncatedTo == null ? stats : stats.withTruncatedTo(truncatedTo));
        log.info("[分块预览] tier={} 链={} 被拒={} 块={} 截断={}",
            body.selectedTier(), chain, rejected.size(), all.size(), truncatedTo);
        return ResponseEntity.ok(body);
    }

    /** 取语言（画像里第一个）· 源侧 {@code chunker_debug.go:214-217}。 */
    private static String langOf(com.nexusai.kb.app.chunk.KbDocProfile profile) {
        if (profile == null || profile.detectedLangs().isEmpty()) {
            return com.nexusai.kb.app.chunk.KbTokenEstimator.LANG_MIXED;
        }
        return profile.detectedLangs().get(0);
    }

    /**
     * 把 {@code chunking_config}（对象或字符串）统一成 JSON 字符串。
     *
     * <p>本仓老端点的契约是<b>字符串</b>（{@code ChunkerPreviewRequest}），
     * 源侧是<b>对象</b>。新端点两者都收 —— 调试面板贴哪一份都能跑。
     */
    private static String configJsonOf(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
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

    // ── 分块预览（带诊断）· DTO 区 ────────────────────────────────────

    /**
     * 诊断预览请求 · 对齐源侧 {@code PreviewChunkingRequest}（{@code chunker_debug.go:48-51}）。
     *
     * @param text           待切文本（必填）
     * @param chunkingConfig 分块配置（对象或 JSON 字符串，均可为 null）
     */
    public record ChunkerDiagnosticsRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("text") String text,
        @com.fasterxml.jackson.annotation.JsonProperty("chunking_config")
        com.fasterxml.jackson.databind.JsonNode chunkingConfig) {
    }

    /**
     * 单个预览块 · 对齐源侧 {@code PreviewChunkResult}（{@code :70-78}）。
     */
    public record PreviewChunkView(
        @com.fasterxml.jackson.annotation.JsonProperty("seq") int seq,
        @com.fasterxml.jackson.annotation.JsonProperty("start") int start,
        @com.fasterxml.jackson.annotation.JsonProperty("end") int end,
        @com.fasterxml.jackson.annotation.JsonProperty("size_chars") int sizeChars,
        @com.fasterxml.jackson.annotation.JsonProperty("size_tokens_approx") int sizeTokensApprox,
        @com.fasterxml.jackson.annotation.JsonProperty("context_header") String contextHeader,
        @com.fasterxml.jackson.annotation.JsonProperty("content") String content) {
    }

    /**
     * 被拒层级 · 对齐源侧 {@code TierRejection}（{@code strategy.go:63-66}）。
     */
    public record TierRejectionView(
        @com.fasterxml.jackson.annotation.JsonProperty("tier") String tier,
        @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason) {
    }

    /**
     * 尺寸统计 · 对齐源侧 {@code PreviewChunkingStats}（{@code chunker_debug.go:84-93}）。
     *
     * @param count       块数
     * @param avgChars    平均字符
     * @param minChars    最小字符
     * @param maxChars    最大字符
     * @param stddevChars 标准差
     * @param approxTokens 全块 token 估算合计（源侧无此字段，本仓为「测试排查」补上）
     * @param truncatedTo 响应截断前的原始块数（未截断为 null）
     */
    public record ChunkStatsView(
        @com.fasterxml.jackson.annotation.JsonProperty("count") int count,
        @com.fasterxml.jackson.annotation.JsonProperty("avg_chars") int avgChars,
        @com.fasterxml.jackson.annotation.JsonProperty("min_chars") int minChars,
        @com.fasterxml.jackson.annotation.JsonProperty("max_chars") int maxChars,
        @com.fasterxml.jackson.annotation.JsonProperty("stddev_chars") int stddevChars,
        @com.fasterxml.jackson.annotation.JsonProperty("approx_tokens") int approxTokens,
        @com.fasterxml.jackson.annotation.JsonProperty("truncated_to") Integer truncatedTo) {

        /**
         * 全量计算 · 对齐源侧 {@code computeChunkSizeStats}（{@code chunker_debug.go:265-294}）。
         *
         * <p>方差用 {@code E[x²]-E[x]²} 并 clamp 到 0（浮点误差会跑到负数，sqrt 会出 NaN）。
         */
        static ChunkStatsView of(List<KbChunk> chunks, String lang) {
            if (chunks == null || chunks.isEmpty()) {
                return new ChunkStatsView(0, 0, 0, 0, 0, 0, null);
            }
            int n = chunks.size();
            double sum = 0;
            double sumSq = 0;
            long tokens = 0;
            int min = Integer.MAX_VALUE;
            int max = 0;
            for (KbChunk c : chunks) {
                int len = c.content().codePointCount(0, c.content().length());
                sum += len;
                sumSq += (double) len * len;
                if (len < min) {
                    min = len;
                }
                if (len > max) {
                    max = len;
                }
                tokens += com.nexusai.kb.app.chunk.KbTokenEstimator
                    .approxTokenCountFromRuneLen(len, lang);
            }
            double avg = sum / n;
            double variance = sumSq / n - avg * avg;
            if (variance < 0) {
                variance = 0;
            }
            return new ChunkStatsView(n, (int) (avg + 0.5), min, max,
                (int) (Math.sqrt(variance) + 0.5), (int) tokens, null);
        }

        /** 追加截断信息（统计保持全集口径）。 */
        ChunkStatsView withTruncatedTo(Integer originalCount) {
            return new ChunkStatsView(count, avgChars, minChars, maxChars, stddevChars,
                approxTokens, originalCount);
        }
    }

    /**
     * 画像视图 · 对齐源侧 {@code DocProfile} 的 JSON 形状（{@code profiler.go:17-45}）。
     *
     * <p>19 个字段全给：源侧注释明文「JSON shape 是 preview endpoint 的公开 API，
     * 字段名必须稳定」。用户要求「测试时能准确排查」，缺字段就只能猜。
     */
    public record DocProfileView(
        @com.fasterxml.jackson.annotation.JsonProperty("total_chars") int totalChars,
        @com.fasterxml.jackson.annotation.JsonProperty("total_lines") int totalLines,
        @com.fasterxml.jackson.annotation.JsonProperty("avg_line_len") double avgLineLen,
        @com.fasterxml.jackson.annotation.JsonProperty("std_line_len") double stdLineLen,
        @com.fasterxml.jackson.annotation.JsonProperty("md_heading_counts")
        java.util.Map<String, Integer> mdHeadingCounts,
        @com.fasterxml.jackson.annotation.JsonProperty("md_heading_total") int mdHeadingTotal,
        @com.fasterxml.jackson.annotation.JsonProperty("numbered_section_count")
        int numberedSectionCount,
        @com.fasterxml.jackson.annotation.JsonProperty("all_caps_short_line_count")
        int allCapsShortLineCount,
        @com.fasterxml.jackson.annotation.JsonProperty("blank_paragraph_breaks")
        int blankParagraphBreaks,
        @com.fasterxml.jackson.annotation.JsonProperty("form_feed_count") int formFeedCount,
        @com.fasterxml.jackson.annotation.JsonProperty("visual_sep_count") int visualSepCount,
        @com.fasterxml.jackson.annotation.JsonProperty("german_chapter_count") int germanChapterCount,
        @com.fasterxml.jackson.annotation.JsonProperty("english_chapter_count") int englishChapterCount,
        @com.fasterxml.jackson.annotation.JsonProperty("chinese_chapter_count") int chineseChapterCount,
        @com.fasterxml.jackson.annotation.JsonProperty("repeated_footer_count")
        int repeatedFooterCount,
        @com.fasterxml.jackson.annotation.JsonProperty("has_tables") boolean hasTables,
        @com.fasterxml.jackson.annotation.JsonProperty("has_code") boolean hasCode,
        @com.fasterxml.jackson.annotation.JsonProperty("code_ratio") double codeRatio,
        @com.fasterxml.jackson.annotation.JsonProperty("detected_langs") List<String> detectedLangs,
        @com.fasterxml.jackson.annotation.JsonProperty("heading_density") double headingDensity,
        @com.fasterxml.jackson.annotation.JsonProperty("dominant_heading_level")
        int dominantHeadingLevel,
        @com.fasterxml.jackson.annotation.JsonProperty("heuristic_marker_total")
        int heuristicMarkerTotal) {

        /** 从领域画像投影。 */
        static DocProfileView of(com.nexusai.kb.app.chunk.KbDocProfile p) {
            if (p == null) {
                return null;
            }
            java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
            for (int level = 1; level <= 6; level++) {
                int c = p.headingCount(level);
                if (c > 0) {
                    counts.put(String.valueOf(level), c);
                }
            }
            return new DocProfileView(
                p.totalChars(), p.totalLines(), p.avgLineLen(), p.stdLineLen(),
                counts, p.mdHeadingTotal(), p.numberedSectionCount(),
                p.allCapsShortLineCount(), p.blankParagraphBreaks(), p.formFeedCount(),
                p.visualSepCount(), p.germanChapterCount(), p.englishChapterCount(),
                p.chineseChapterCount(), p.repeatedFooterCount(),
                p.hasTables(), p.hasCode(), p.codeRatio(), p.detectedLangs(),
                p.headingDensity(), p.dominantHeadingLevel(), p.heuristicMarkerTotal());
        }
    }

    /**
     * 诊断预览响应 · 对齐源侧 {@code PreviewChunkingResponse}（{@code chunker_debug.go:96-103}）。
     */
    public record ChunkerDiagnosticsResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("selected_tier") String selectedTier,
        @com.fasterxml.jackson.annotation.JsonProperty("tier_chain") List<String> tierChain,
        @com.fasterxml.jackson.annotation.JsonProperty("rejected") List<TierRejectionView> rejected,
        @com.fasterxml.jackson.annotation.JsonProperty("profile") DocProfileView profile,
        @com.fasterxml.jackson.annotation.JsonProperty("chunks") List<PreviewChunkView> chunks,
        @com.fasterxml.jackson.annotation.JsonProperty("stats") ChunkStatsView stats) {

        ChunkerDiagnosticsResponse withTruncatedTo(Integer originalCount) {
            return new ChunkerDiagnosticsResponse(selectedTier, tierChain, rejected, profile,
                chunks, stats.withTruncatedTo(originalCount));
        }
    }
}
