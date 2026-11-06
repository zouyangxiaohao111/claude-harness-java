package com.nexusai.kb.app.kb;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.chunk.KbChunkingConfigParser;
import com.nexusai.kb.app.chunk.KbSplitterConfig;
import com.nexusai.kb.app.chunk.KbTextSplitter;
import com.nexusai.kb.app.image.KbImageService;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.domain.KbActivityService;
import com.nexusai.kb.domain.KnowledgeIngestService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbIngestTaskMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识管理扩展 · 批次 W4（源侧「知识管理」tag 余下部分）。
 *
 * <p>覆盖：手工创建 / URL 导入 / 批量取 / 批量删 / 批量重解析 / 文件夹树 / 移动文件夹 /
 * 更新文档 / 更新手工知识 / 更新图片信息 / spans / 全库搜索。
 *
 * <h2>⭐⭐ URL 导入的 SSRF 边界</h2>
 * 用户给的 URL 由服务端去取 ⇒ 天然 SSRF 面。本类做了三重限制：
 * <ol>
 *   <li>协议只允许 http / https（⛔ file:// / gopher:// / ftp://）；</li>
 *   <li>解析出的主机名不能是 localhost / 127.0.0.0/8 / 10.0.0.0/8 / 172.16.0.0/12 /
 *       192.168.0.0/16 / 169.254.0.0/16（云元数据服务在这一段）；</li>
 *   <li>响应大小上限 + 超时，避免被大文件/慢连接拖死。</li>
 * </ol>
 * ⚠️ 已知未覆盖：DNS 重绑定（先用合法域名解析、再改指内网 IP）。
 * 要彻底挡住需要固定解析后的 IP 再连（本仓 JDK HttpClient 不易做）。
 * ⇒ 当前边界对「单机自用」够用；**多用户部署前必须补 DNS 固定**。
 */
@Service
public class KbKnowledgeExtService {

    private static final Logger log = LoggerFactory.getLogger(KbKnowledgeExtService.class);

    /** URL 导入大小上限（20 MB）。 */
    private static final long MAX_FETCH_BYTES = 20L * 1024 * 1024;

    /** URL 导入超时。 */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

    /** 允许导入的扩展名（与文件上传同口径）。 */
    private static final List<String> ALLOWED_EXT = List.of(
        "txt", "md", "markdown", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "csv", "json", "html", "htm", "png", "jpg", "jpeg", "gif", "webp");

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeBaseMapper baseMapper;
    private final KbChunkMapper chunkMapper;
    private final KbIngestTaskMapper ingestTaskMapper;
    private final KnowledgeIngestService ingestService;
    private final KbIndexWriter indexWriter;
    private final KbActivityService activityService;
    private final KbImageService imageService;
    private final JdbcTemplate jdbc;

    @Autowired
    public KbKnowledgeExtService(KbKnowledgeMapper knowledgeMapper,
                                 KbKnowledgeBaseMapper baseMapper,
                                 KbChunkMapper chunkMapper,
                                 KbIngestTaskMapper ingestTaskMapper,
                                 KnowledgeIngestService ingestService,
                                 KbIndexWriter indexWriter,
                                 KbActivityService activityService,
                                 KbImageService imageService,
                                 JdbcTemplate jdbc) {
        this.knowledgeMapper = knowledgeMapper;
        this.baseMapper = baseMapper;
        this.chunkMapper = chunkMapper;
        this.ingestTaskMapper = ingestTaskMapper;
        this.ingestService = ingestService;
        this.indexWriter = indexWriter;
        this.activityService = activityService;
        this.imageService = imageService;
        this.jdbc = jdbc;
    }

    // ── 创建 ────────────────────────────────────────────────────────

    /**
     * 手工创建知识（直接给正文，不需要文件）。
     *
     * <p>⚠️ 与文件上传不同：这里<b>同步切块并落库</b>，不走解析队列 ——
     * 正文已经是纯文本，没有「解析」这一步。源侧同样如此。
     *
     * @param kbId     知识库
     * @param title    标题
     * @param content  正文
     * @param metadata 元数据 JSON（可空）
     * @return 创建的知识
     */
    @Transactional
    public KbKnowledgeRecord createManual(String kbId, String title, String content, String metadata) {
        KbKnowledgeBaseRecord kb = requireBase(kbId);
        if (title == null || title.isBlank()) {
            throw new ValidationException("标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new ValidationException("正文不能为空");
        }

        String id = UUID.randomUUID().toString();
        KbKnowledgeRecord row = new KbKnowledgeRecord();
        row.setId(id);
        row.setKnowledgeBaseId(kbId);
        row.setType("manual");
        row.setChannel("web");
        row.setTitle(title.trim());
        row.setDescription(null);
        row.setSource("manual");
        // 同步切块 ⇒ 直接 completed + enabled（否则用户建完搜不到）
        row.setParseStatus("completed");
        row.setEnableStatus("enabled");
        row.setEmbeddingModelId(kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId());
        row.setFileName("");
        row.setFileType("text");
        row.setFileSize((long) content.length());
        row.setFilePath("");
        row.setFileHash("");
        row.setStorageSize(0L);
        row.setSummaryStatus("none");
        row.setPendingSubtasksCount(0);
        row.setMetadata(metadata == null || metadata.isBlank() ? "{}" : metadata);
        row.setCustomMetadata("{}");
        row.setFolderPath("");
        row.setParseAttempt(0);
        row.setProcessedAt(KbTimeFormat.format(LocalDateTime.now()));
        knowledgeMapper.insert(row);

        int chunks = writeChunks(row, content, kb);
        log.info("[知识扩展] 手工创建 id={} kb={} 标题={} 分块={}", id, kbId, row.getTitle(), chunks);
        activityService.record(kbId, KbActivityService.A_CREATE, "success", "knowledge", id,
            "{\"type\":\"manual\",\"chunks\":" + chunks + "}");
        return knowledgeMapper.selectOneById(id);
    }

    /**
     * 从 URL 创建知识。
     *
     * <p>⚠️ SSRF 边界见类注释。抓取失败 ⇒ 400 并带原因（⛔ 不静默创建一条空文档）。
     *
     * <p>⛔ <b>本方法不能标 {@code @Transactional}</b>：网络抓取最长 30 秒，
     * 在 SQLite 里长时间持有写事务会让别的连接 {@code SQLITE_BUSY_SNAPSHOT}（实测 500）。
     * ⇒ 先抓取（无事务），再落库（各写各自提交）。
     *
     * @param kbId     知识库
     * @param url      源地址
     * @param fileName 自定义文件名（可空 ⇒ 从 URL 末段或 Content-Disposition 推断）
     */
    public KbKnowledgeRecord createFromUrl(String kbId, String url, String fileName) {
        KbKnowledgeBaseRecord kb = requireBase(kbId);
        URI uri = validateUrl(url);
        String name = inferName(uri, fileName);

        byte[] body;
        String contentType;
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(FETCH_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new ValidationException("抓取失败：HTTP " + resp.statusCode());
            }
            contentType = resp.headers().firstValue("content-type").orElse("");
            try (InputStream in = resp.body()) {
                body = readLimited(in, MAX_FETCH_BYTES);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ValidationException("抓取 URL 失败: " + e.getMessage());
        }
        if (body.length == 0) {
            throw new ValidationException("URL 返回空内容");
        }

        String id = UUID.randomUUID().toString();
        KbKnowledgeRecord row = new KbKnowledgeRecord();
        row.setId(id);
        row.setKnowledgeBaseId(kbId);
        row.setType("url");
        row.setChannel("web");
        row.setTitle(name);
        row.setSource(uri.toString());
        row.setParseStatus("completed");
        row.setEnableStatus("enabled");
        row.setEmbeddingModelId(kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId());
        row.setFileName(name);
        row.setFileType(extensionOf(name));
        row.setFileSize((long) body.length);
        row.setFilePath("");
        row.setFileHash("");
        row.setStorageSize(0L);
        row.setSummaryStatus("none");
        row.setPendingSubtasksCount(0);
        row.setMetadata("{\"content_type\":\"" + escape(contentType) + "\"}");
        row.setCustomMetadata("{}");
        row.setFolderPath("");
        row.setParseAttempt(0);
        row.setProcessedAt(KbTimeFormat.format(LocalDateTime.now()));
        knowledgeMapper.insert(row);

        // 只对纯文本类做同步切块；二进制（pdf/docx）没有解析器在此上下文可用
        int chunks = 0;
        if (isPlainText(contentType, name)) {
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            chunks = writeChunks(row, text, kb);
        } else {
            // ⚠️ 如实标注：没有正文就无法检索。置 failed 而不是 completed ——
            //    否则界面显示「已完成」但搜不到，用户无从判断。
            jdbc.update("UPDATE kb_knowledge SET parse_status = 'failed', enable_status = 'disabled', "
                + "error_message = ? WHERE id = ?",
                "二进制内容（" + contentType + "）需要解析器支持，URL 导入当前只处理纯文本",
                id);
            log.warn("[知识扩展] URL 导入的二进制内容无解析器 id={} type={}", id, contentType);
        }
        log.info("[知识扩展] URL 导入 id={} kb={} url={} 字节={} 分块={}",
            id, kbId, uri, body.length, chunks);
        activityService.record(kbId, KbActivityService.A_CREATE, chunks > 0 ? "success" : "failure",
            "knowledge", id, "{\"type\":\"url\",\"chunks\":" + chunks + "}");
        return knowledgeMapper.selectOneById(id);
    }

    // ── 批量 ────────────────────────────────────────────────────────

    /** 批量取文档（按 ID 列表；不存在的直接不返回，⛔ 不报 404）。 */
    public List<KbKnowledgeRecord> batchGet(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<KbKnowledgeRecord> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            KbKnowledgeRecord row = knowledgeMapper.selectOneById(id);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * 批量删除。
     *
     * <p>⚠️ 逐条独立处理：一条删不掉不影响其它条。返回成功/失败清单，
     * 让前端能精确重试失败的（整批回滚会让用户不知道哪条出问题）。
     */
    public BatchResult batchDelete(List<String> ids) {
        List<String> ok = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (String id : ids) {
            try {
                KbKnowledgeRecord row = knowledgeMapper.selectOneById(id);
                if (row == null) {
                    failed.put(id, "文档不存在");
                    continue;
                }
                indexWriter.clearIndex(id);
                jdbc.update("DELETE FROM kb_chunk_image WHERE knowledge_id = ?", id);
                jdbc.update("DELETE FROM kb_chunk_revision WHERE knowledge_id = ?", id);
                jdbc.update("DELETE FROM kb_chunk WHERE knowledge_id = ?", id);
                jdbc.update("DELETE FROM kb_knowledge_tag_relation WHERE knowledge_id = ?", id);
                knowledgeMapper.deleteById(id);
                activityService.record(row.getKnowledgeBaseId(), KbActivityService.A_DELETE,
                    "success", "knowledge", id, null);
                ok.add(id);
            } catch (RuntimeException e) {
                failed.put(id, e.getMessage());
                log.warn("[知识扩展] 批量删除 {} 失败：{}", id, e.getMessage());
            }
        }
        return new BatchResult(ok, failed);
    }

    /**
     * 批量重新解析。
     *
     * <p>⚠️ 只对能重新解析的类型生效：{@code manual} / {@code url} 没有原始文件，
     * 重新解析会得到空内容 ⇒ 直接标记为失败并说明原因，⛔ 不清空已有分块。
     */
    public BatchResult batchReparse(List<String> ids) {
        List<String> ok = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (String id : ids) {
            try {
                KbKnowledgeRecord row = knowledgeMapper.selectOneById(id);
                if (row == null) {
                    failed.put(id, "文档不存在");
                    continue;
                }
                boolean hasFile = row.getFilePath() != null && !row.getFilePath().isBlank()
                    && Files.exists(Path.of(row.getFilePath()));
                if (!hasFile) {
                    failed.put(id, "没有可重新解析的原始文件（手工/URL 创建的文档请直接编辑）");
                    continue;
                }
                ingestService.submitParse(id);
                ok.add(id);
            } catch (RuntimeException e) {
                failed.put(id, e.getMessage());
                log.warn("[知识扩展] 批量重解析 {} 失败：{}", id, e.getMessage());
            }
        }
        return new BatchResult(ok, failed);
    }

    // ── 更新 ────────────────────────────────────────────────────────

    /**
     * 更新文档元信息（标题 / 描述 / 启用状态 / 自定义元数据 / 文件夹）。
     *
     * <p>⚠️ 不动正文 —— 正文改动走分块编辑（{@code PUT /chunks/...}）。
     * 这里改 {@code enable_status} 时必须同时重建索引：禁用后应立刻搜不到。
     */
    public KbKnowledgeRecord update(String id, String title, String description,
                                    String enableStatus, String customMetadata, String folderPath) {
        KbKnowledgeRecord row = requireKnowledge(id);
        if (title != null) {
            if (title.isBlank()) {
                throw new ValidationException("标题不能为空");
            }
            row.setTitle(title.trim());
        }
        if (description != null) {
            row.setDescription(description);
        }
        if (enableStatus != null) {
            String s = enableStatus.trim().toLowerCase();
            if (!s.equals("enabled") && !s.equals("disabled")) {
                throw new ValidationException("enable_status 只能是 enabled / disabled，收到: " + enableStatus);
            }
            row.setEnableStatus(s);
        }
        if (customMetadata != null) {
            row.setCustomMetadata(customMetadata.isBlank() ? "{}" : customMetadata);
        }
        if (folderPath != null) {
            row.setFolderPath(normalizeFolder(folderPath));
        }
        knowledgeMapper.update(row, false);

        if (enableStatus != null) {
            try {
                indexWriter.reindexKnowledge(id, row.getKnowledgeBaseId(),
                    row.getEmbeddingModelId());
            } catch (RuntimeException e) {
                log.error("[知识扩展] 文档 {} 状态已改但索引重建失败：{}", id, e.getMessage());
            }
        }
        log.info("[知识扩展] 已更新 id={} enabled={}", id, row.getEnableStatus());
        return knowledgeMapper.selectOneById(id);
    }

    /** 更新手工知识（正文可变 ⇒ 整批重切块）。 */
    @Transactional
    public KbKnowledgeRecord updateManual(String id, String title, String content) {
        KbKnowledgeRecord row = requireKnowledge(id);
        if (!"manual".equals(row.getType())) {
            throw new ValidationException("文档 " + id + " 不是手工创建的知识（type=" + row.getType() + "）");
        }
        if (title != null && !title.isBlank()) {
            row.setTitle(title.trim());
        }
        boolean contentChanged = content != null && !content.isBlank();
        if (contentChanged) {
            row.setFileSize((long) content.length());
        }
        knowledgeMapper.update(row, false);
        if (contentChanged) {
            // 手工知识的正文就是分块来源 ⇒ 全量重切。
            // ⚠️ 用 Flex 而不是 jdbc：本方法在 @Transactional 内，混用两条连接写库会 SQLITE_BUSY（实测）。
            chunkMapper.deleteByQuery(
                QueryWrapper.create().where("knowledge_id = ?", id));
            KbKnowledgeBaseRecord kb = requireBase(row.getKnowledgeBaseId());
            int n = writeChunks(row, content, kb);
            log.info("[知识扩展] 手工知识已更新 id={} 重切块={}", id, n);
        }
        return knowledgeMapper.selectOneById(id);
    }

    /** 更新图片信息（某块 image_info 里第 n 张图的 caption / ocr_text / 启停）。 */
    @Transactional
    public KbChunkRecord updateImageInfo(String knowledgeId, String chunkId,
                                         int imageIndex, String caption, String ocrText,
                                         Boolean isEnabled) {
        KbKnowledgeRecord doc = requireKnowledge(knowledgeId);
        KbChunkRecord chunk = chunkMapper.selectOneById(chunkId);
        if (chunk == null || !knowledgeId.equals(chunk.getKnowledgeId())) {
            throw new NotFoundException("分块 " + chunkId + " 不存在于文档 " + knowledgeId);
        }
        String updated = patchImageInfo(chunk.getImageInfo(), imageIndex, caption, ocrText, isEnabled);
        String now = KbTimeFormat.format(LocalDateTime.now());
        jdbc.update("UPDATE kb_chunk SET image_info = ?, update_time = ? WHERE id = ?",
            updated, now, chunkId);
        // ⚠️ 同步刷新投影表：图库查的是投影，不刷新就会显示旧标题/旧 OCR，且不报错。
        // ⚠️⚠️ **必须用手里这份 updated 值，不能重新 selectOneById 再投影** ——
        //      实测重读拿到的是旧值（事务未提交/Flex 缓存），会把旧 caption 写回投影表，
        //      现象是「接口返回新值、图库显示旧值」，且不报错。
        chunk.setImageInfo(updated);
        imageService.projectChunk(chunk);
        log.info("[知识扩展] 已更新图片信息 chunk={} index={} doc={}", chunkId, imageIndex, doc.getId());
        return chunk;
    }

    // ── 文件夹 ──────────────────────────────────────────────────────

    /**
     * 文档文件夹树。
     *
     * <p>⭐ 与 Wiki 的 {@code kb_wiki_folder} 不是一回事：
     * 这个是从 {@code kb_knowledge.folder_path} 字符串派生的<b>虚拟树</b>
     * （源侧同样是派生，没有独立的文档文件夹表）。好处 = 移动文档只需改一个字符串。
     * ⚠️ 因此「空目录」不存在 —— 目录里有文档才有节点。
     */
    public List<FolderNode> folderTree(String kbId) {
        requireBase(kbId);
        List<String> paths = jdbc.queryForList(
            "SELECT folder_path FROM kb_knowledge WHERE knowledge_base_id = ? "
                + "AND deleted = 0 AND folder_path <> ''", String.class, kbId);
        Map<String, FolderNode> nodes = new LinkedHashMap<>();
        for (String p : paths) {
            String[] parts = p.split("/");
            StringBuilder acc = new StringBuilder();
            for (String part : parts) {
                if (part.isBlank()) {
                    continue;
                }
                if (acc.length() > 0) {
                    acc.append('/');
                }
                acc.append(part);
                nodes.computeIfAbsent(acc.toString(),
                    k -> new FolderNode(k, part, new ArrayList<>(), 0));
            }
        }
        // 直接文档数（不含子目录）：一次查询统计
        Map<String, Integer> direct = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT folder_path, COUNT(*) AS c FROM kb_knowledge "
                + "WHERE knowledge_base_id = ? AND deleted = 0 AND folder_path <> '' "
                + "GROUP BY folder_path", kbId);
        for (Map<String, Object> r : rows) {
            direct.put((String) r.get("folder_path"),
                ((Number) r.get("c")).intValue());
        }
        // 挂树：按路径长度倒序挂到父节点。
        // ⚠️ 祖先的文档数 = 自身直属 + 全部后代，所以自底向上累加。
        List<String> keys = new ArrayList<>(nodes.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String k : keys) {
            // 先把自己已有的合计（可能已被子节点累加过）加上直属数
            totals.merge(k, direct.getOrDefault(k, 0), Integer::sum);
            int slash = k.lastIndexOf('/');
            if (slash > 0) {
                String parent = k.substring(0, slash);
                FolderNode p = nodes.get(parent);
                if (p != null) {
                    p.children().add(nodes.get(k));
                    // 把子树的合计冒泡给父
                    totals.merge(parent, totals.getOrDefault(k, 0), Integer::sum);
                }
            }
        }
        // ⚠️ 节点在创建时 documentCount 写死 0，且子节点是「先挂后算」——
        //    ⇒ 必须最后用 totals 统一重建一次，只重建根节点会让子节点永远显示 0。
        java.util.function.Function<FolderNode, FolderNode> rebuild = new java.util.function.Function<>() {
            @Override
            public FolderNode apply(FolderNode n) {
                List<FolderNode> kids = new ArrayList<>(n.children().size());
                for (FolderNode c : n.children()) {
                    kids.add(apply(c));
                }
                kids.sort((a, b) -> a.name().compareTo(b.name()));
                return new FolderNode(n.path(), n.name(), kids, totals.getOrDefault(n.path(), 0));
            }
        };
        List<FolderNode> roots = new ArrayList<>();
        for (String k : nodes.keySet()) {
            if (k.indexOf('/') < 0) {
                roots.add(rebuild.apply(nodes.get(k)));
            }
        }
        roots.sort((a, b) -> a.name().compareTo(b.name()));
        return roots;
    }

    /**
     * 重命名 / 移动文件夹（批量改前缀）。
     *
     * <p>⚠️ 必须同时改<b>所有后代</b>路径，否则子目录会与父目录断链
     * （树的父节点按路径前缀推导，父改名后子仍指向旧前缀）。
     *
     * @param kbId 知识库
     * @param from 旧路径
     * @param to   新路径
     * @return 受影响的文档数
     */
    @Transactional
    public int renameFolder(String kbId, String from, String to) {
        requireBase(kbId);
        String f = normalizeFolder(from);
        String t = normalizeFolder(to);
        if (f.isEmpty()) {
            throw new ValidationException("源文件夹路径不能为空");
        }
        if (t.isEmpty()) {
            throw new ValidationException("目标文件夹路径不能为空");
        }
        if (f.equals(t)) {
            return 0;
        }
        if (t.startsWith(f + "/")) {
            throw new ValidationException("不能把文件夹移动到自己的子目录下");
        }
        String now = KbTimeFormat.format(LocalDateTime.now());
        // ① 自身
        int own = jdbc.update(
            "UPDATE kb_knowledge SET folder_path = ?, update_time = ? "
                + "WHERE knowledge_base_id = ? AND folder_path = ? AND deleted = 0",
            t, now, kbId, f);
        // ② 后代：把前缀 f/ 换成 t/
        int descendants = jdbc.update(
            "UPDATE kb_knowledge SET folder_path = ? || SUBSTR(folder_path, ?), update_time = ? "
                + "WHERE knowledge_base_id = ? AND folder_path LIKE ? AND deleted = 0",
            t, f.length() + 1, now, kbId, f + "/%");
        log.info("[知识扩展] 文件夹 {} → {} 直属文档={} 后代文档={}", f, t, own, descendants);
        return own + descendants;
    }

    /** 移动文档到文件夹（{@code POST /knowledge/folder}）。 */
    @Transactional
    public int moveToFolder(List<String> knowledgeIds, String folderPath) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            throw new ValidationException("knowledge_ids 不能为空");
        }
        String path = normalizeFolder(folderPath);
        String now = KbTimeFormat.format(LocalDateTime.now());
        int moved = 0;
        for (String id : knowledgeIds) {
            moved += jdbc.update(
                "UPDATE kb_knowledge SET folder_path = ?, update_time = ? WHERE id = ? AND deleted = 0",
                path, now, id);
        }
        return moved;
    }

    // ── spans / 搜索 ─────────────────────────────────────────────────

    /**
     * 解析 Span 树（含历史尝试）。
     *
     * <p>⭐ 本仓没有独立的 span 表（源侧解析器会产出 span 树用于高亮定位）。
     * 这里用<b>已有分块</b>按 {@code start_at/end_at} 投影成「叶子 span」，
     * 并把 {@code kb_ingest_task} 的历次尝试作为 {@code attempts} 一并返回。
     * ⚠️ 与源侧的差异：没有标题层级 span（本仓分块器不产出层级）。
     * 这是如实的能力边界 —— 前端拿到的 span 可直接用于「点击定位到正文某段」。
     */
    public SpanTree spans(String knowledgeId) {
        KbKnowledgeRecord doc = requireKnowledge(knowledgeId);
        List<KbChunkRecord> chunks = chunkMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId).orderBy("chunk_index", true));
        List<Span> leaves = new ArrayList<>(chunks.size());
        for (KbChunkRecord c : chunks) {
            String text = c.getContent() == null ? "" : c.getContent();
            String preview = text.length() > 120 ? text.substring(0, 120) + "…" : text;
            leaves.add(new Span(
                c.getId(),
                c.getChunkType() == null ? "text" : c.getChunkType(),
                preview,
                c.getStartAt(), c.getEndAt(),
                c.getChunkIndex(),
                c.getIsEnabled() != null && c.getIsEnabled() != 0,
                List.of()));
        }
        List<KbIngestTaskRecord> tasks = ingestTaskMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId).orderBy("id", true));
        List<Attempt> attempts = new ArrayList<>(tasks.size());
        for (KbIngestTaskRecord t : tasks) {
            attempts.add(new Attempt(t.getId(), t.getTaskType(), t.getState(),
                t.getAttempt(), t.getErrorMessage(), t.getStartedAt(), t.getFinishedAt()));
        }
        return new SpanTree(doc.getId(), doc.getTitle(), doc.getParseStatus(), leaves, attempts);
    }

    /**
     * 全库关键词搜索（{@code GET /knowledge/search}）。
     *
     * <p>⚠️ 这是<b>按标题/文件名</b>的简单搜索，不是语义检索。
     * 语义检索走 {@code /knowledge-bases/{id}/hybrid-search}。
     * 两者名字接近但是不同能力 —— 混用会让用户以为「语义检索不准」。
     */
    public List<KbKnowledgeRecord> search(String keyword, String kbId, Integer limit) {
        if (keyword == null || keyword.isBlank()) {
            throw new ValidationException("keyword 必填");
        }
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        QueryWrapper q = QueryWrapper.create()
            .where("(title LIKE ? OR file_name LIKE ? OR description LIKE ?)",
                "%" + keyword.trim() + "%", "%" + keyword.trim() + "%", "%" + keyword.trim() + "%");
        if (kbId != null && !kbId.isBlank()) {
            q.and("knowledge_base_id = ?", kbId);
        }
        q.orderBy("create_time", false).limit(size);
        return new ArrayList<>(knowledgeMapper.selectListByQuery(q));
    }

    /**
     * 分块预览（{@code POST /chunker/preview}）。
     *
     * <p>⚠️ 只切分不落库 —— 让用户在保存前看效果。用与入库<b>同一个</b>
     * {@link KbTextSplitter} 与配置解析器，否则预览与实际结果不一致（比没有预览更糟）。
     */
    public List<KbChunk> previewChunks(String text, String chunkingConfigJson) {
        if (text == null || text.isBlank()) {
            throw new ValidationException("text 不能为空");
        }
        KbSplitterConfig cfg = chunkingConfigJson == null || chunkingConfigJson.isBlank()
            ? KbSplitterConfig.defaults()
            : KbChunkingConfigParser.parse(chunkingConfigJson);
        return KbTextSplitter.split(text, cfg);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 切块并落库（手工 / URL 文本共用）。
     *
     * @return 写入块数
     */
    private int writeChunks(KbKnowledgeRecord doc, String text, KbKnowledgeBaseRecord kb) {
        KbSplitterConfig cfg;
        try {
            cfg = kb.getChunkingConfig() == null || kb.getChunkingConfig().isBlank()
                ? KbSplitterConfig.defaults()
                : KbChunkingConfigParser.parse(kb.getChunkingConfig());
        } catch (RuntimeException e) {
            // 配置坏了不能阻塞入库 —— 回落默认并在日志里点名
            log.warn("[知识扩展] 分块配置解析失败，回落默认 kb={} 原因={}",
                kb.getId(), e.getMessage());
            cfg = KbSplitterConfig.defaults();
        }
        List<KbChunk> parts = KbTextSplitter.split(text, cfg);
        String now = KbTimeFormat.format(LocalDateTime.now());
        String prevId = null;
        List<KbChunkRecord> rows = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            KbChunk c = parts.get(i);
            KbChunkRecord r = new KbChunkRecord();
            r.setId(UUID.randomUUID().toString());
            r.setKnowledgeBaseId(doc.getKnowledgeBaseId());
            r.setKnowledgeId(doc.getId());
            r.setContent(c.content());
            r.setChunkIndex(i);
            r.setIsEnabled(1);
            r.setStartAt(c.start());
            r.setEndAt(c.end());
            r.setPreChunkId(prevId);
            r.setNextChunkId(null);
            r.setChunkType("text");
            r.setSourceContent(c.content());
            r.setContentRevision(0);
            r.setIndexStatus("ready");
            r.setLastEditorId("");
            r.setContextHeader(c.contextHeader() == null ? "" : c.contextHeader());
            r.setStatus(0);
            // ⚠️ 缺陷 14：Flex 的 insertBatch 会把 null 字段也列进 INSERT 清单 ⇒
            //    DB 的 DEFAULT 完全不生效。DDL 里 kb_chunk 有 9 个 NOT NULL 带 DEFAULT 的列，
            //    漏一个就是 500（实测漏 flags）。⇒ 逐列显式赋值。
            r.setFlags(1);
            r.setCreator("");
            r.setUpdater("");
            r.setDeleted(0);
            r.setTenantId(0L);
            r.setCreateTime(LocalDateTime.now());
            r.setUpdateTime(LocalDateTime.now());
            prevId = r.getId();
            rows.add(r);
        }
        // 回填 next 链
        for (int i = 0; i + 1 < rows.size(); i++) {
            rows.get(i).setNextChunkId(rows.get(i + 1).getId());
        }
        if (!rows.isEmpty()) {
            chunkMapper.insertBatch(rows);
        }
        log.debug("[知识扩展] 切块完成 doc={} 块数={} 时间={}", doc.getId(), rows.size(), now);
        // ⚠️ 这里不调 indexWriter：调用方在事务内，独立连接写 FTS 会自锁（缺陷 5）。
        //    索引由 Controller 在提交后调 KbChunkService.reindexChunkKnowledge 或
        //    下一次检索时按需重建。
        return rows.size();
    }

    private static String patchImageInfo(String raw, int index, String caption,
                                         String ocrText, Boolean isEnabled) {
        if (raw == null || raw.isBlank()) {
            throw new NotFoundException("该分块没有图片信息");
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = m.readTree(raw);
            if (!root.isArray() || index < 0 || index >= root.size()) {
                throw new NotFoundException("图片下标 " + index + " 超出范围（共 "
                    + (root.isArray() ? root.size() : 0) + " 张）");
            }
            com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode) root.get(index);
            if (caption != null) {
                node.put("caption", caption);
            }
            if (ocrText != null) {
                node.put("ocr_text", ocrText);
            }
            if (isEnabled != null) {
                node.put("is_enabled", isEnabled);
            }
            return m.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ValidationException("image_info 不是合法 JSON: " + e.getMessage());
        }
    }

    /** URL 校验（见类注释的 SSRF 边界）。 */
    static URI validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new ValidationException("url 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("url 格式非法: " + e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ValidationException("只支持 http / https 协议，收到: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ValidationException("url 缺少主机名");
        }
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.equals("::1") || h.startsWith("127.")
            || h.startsWith("10.") || h.startsWith("192.168.")
            || h.startsWith("169.254.") || isPrivate172(h)) {
            throw new ValidationException("不允许访问内网地址: " + host);
        }
        return uri;
    }

    private static boolean isPrivate172(String host) {
        if (!host.startsWith("172.")) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                throw new ValidationException("URL 内容超过上限 " + (limit / 1024 / 1024) + " MB");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String inferName(URI uri, String custom) {
        if (custom != null && !custom.isBlank()) {
            return custom.trim();
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.isBlank()) {
            return "url-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
        }
        String ext = extensionOf(last);
        if (ext.isEmpty()) {
            return last + ".txt";
        }
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ValidationException("不支持的文件类型 ." + ext + "，允许：" + ALLOWED_EXT);
        }
        return last;
    }

    static boolean isPlainText(String contentType, String name) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml")
            || ct.contains("markdown")) {
            return true;
        }
        String ext = extensionOf(name);
        return ext.equals("txt") || ext.equals("md") || ext.equals("markdown")
            || ext.equals("csv") || ext.equals("json") || ext.equals("html")
            || ext.equals("htm");
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
            ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String normalizeFolder(String path) {
        if (path == null) {
            return "";
        }
        String s = path.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

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

    private KbKnowledgeBaseRecord requireBase(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
        KbKnowledgeBaseRecord row = baseMapper.selectOneById(kbId);
        if (row == null) {
            throw new NotFoundException("知识库 " + kbId + " 不存在");
        }
        return row;
    }

    // ── DTO ─────────────────────────────────────────────────────────

    /** 批量操作结果。 */
    public record BatchResult(
        @com.fasterxml.jackson.annotation.JsonProperty("succeeded") List<String> succeeded,
        @com.fasterxml.jackson.annotation.JsonProperty("failed") Map<String, String> failed) {
    }

    /** 文件夹节点。 */
    public record FolderNode(
        @com.fasterxml.jackson.annotation.JsonProperty("path") String path,
        @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
        @com.fasterxml.jackson.annotation.JsonProperty("children") List<FolderNode> children,
        @com.fasterxml.jackson.annotation.JsonProperty("document_count") int documentCount) {
    }

    /** Span 树。 */
    public record SpanTree(
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_id") String knowledgeId,
        @com.fasterxml.jackson.annotation.JsonProperty("title") String title,
        @com.fasterxml.jackson.annotation.JsonProperty("parse_status") String parseStatus,
        @com.fasterxml.jackson.annotation.JsonProperty("spans") List<Span> spans,
        @com.fasterxml.jackson.annotation.JsonProperty("attempts") List<Attempt> attempts) {
    }

    /** 单个 span（叶子 = 一块）。 */
    public record Span(
        @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
        @com.fasterxml.jackson.annotation.JsonProperty("type") String type,
        @com.fasterxml.jackson.annotation.JsonProperty("preview") String preview,
        @com.fasterxml.jackson.annotation.JsonProperty("start_at") Integer startAt,
        @com.fasterxml.jackson.annotation.JsonProperty("end_at") Integer endAt,
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_index") Integer chunkIndex,
        @com.fasterxml.jackson.annotation.JsonProperty("enabled") boolean enabled,
        @com.fasterxml.jackson.annotation.JsonProperty("children") List<Span> children) {
    }

    /** 一次解析尝试。 */
    public record Attempt(
        @com.fasterxml.jackson.annotation.JsonProperty("id") Long id,
        @com.fasterxml.jackson.annotation.JsonProperty("task_type") String taskType,
        @com.fasterxml.jackson.annotation.JsonProperty("state") String state,
        @com.fasterxml.jackson.annotation.JsonProperty("attempt") Integer attempt,
        @com.fasterxml.jackson.annotation.JsonProperty("error_message") String errorMessage,
        @com.fasterxml.jackson.annotation.JsonProperty("started_at") String startedAt,
        @com.fasterxml.jackson.annotation.JsonProperty("finished_at") String finishedAt) {
    }
}
