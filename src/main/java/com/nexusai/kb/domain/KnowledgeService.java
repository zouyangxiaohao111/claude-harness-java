package com.nexusai.kb.domain;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.ingest.KbIngestTaskQueue;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.app.retrieval.KbRerankStage;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.KnowledgeDto;
import com.nexusai.kb.dto.KnowledgePageResponse;
import com.nexusai.kb.dto.SearchResultDto;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 知识条目领域服务 · 批次 B3（知识管理 24 端点中的核心子集）。
 *
 * <p><b>源侧对照</b>：
 * <ul>
 *   <li>创建：{@code internal/application/service/knowledge_create.go:27}（{@code createKnowledgeFromFile}）</li>
 *   <li>列表：{@code internal/handler/knowledge.go:1072}（{@code ListKnowledge}）</li>
 *   <li>详情：{@code internal/handler/knowledge.go:644}（{@code GetKnowledge}）</li>
 * </ul>
 *
 * <p><b>⭐ 创建时 {@code enable_status} 必须是 {@code disabled}</b>
 * （源侧 {@code knowledge_create.go} 逐字如此）。检索链路只查 {@code enabled}
 * ⇒ 解析把分块写完、由 {@code KnowledgeParseProcessor} 转 {@code enabled} 之前，
 * 文档**不可被检索**。⛔ 不要图省事在创建时就置 enabled。
 *
 * <p><b>错误契约</b>：{@link ValidationException} → 400、{@link NotFoundException} → 404、
 * {@link ConflictException} → 409（文件重复）。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /**
     * 支持的导入扩展名 · 逐字抄源侧 {@code internal/utils/import_filetypes.go:12-25}
     * （37 个，同一个真源覆盖上传 / URL 导入 / 数据源连接器）。
     */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "pdf", "txt", "docx", "doc", "epub",
        "docm", "odt", "rtf",
        "html", "htm", "mhtml", "md", "markdown",
        "xmind",
        "png", "jpg", "jpeg", "gif",
        "bmp", "tiff", "webp",
        "csv", "xlsx", "xls", "pptx", "ppt", "json",
        "xlsm", "ods", "pptm", "odp",
        "mp3", "wav", "m4a", "flac", "ogg");

    /** 视频类型：源侧明确拒绝（{@code knowledge_create.go:61}）。 */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
        "mp4", "mov", "avi", "mkv", "webm", "flv", "wmv", "m4v");

    /** 无扩展名时的哨兵值（源侧 {@code unknownFileType}）。 */
    private static final String UNKNOWN_FILE_TYPE = "unknown";

    /** 列表默认每页数量（源侧 {@code default(20)}）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 列表每页上限（源侧 {@code maximum(1000)}，但 x-agent 建议 50）。 */
    private static final int MAX_PAGE_SIZE = 1000;

    /** 检索默认返回条数（源侧 {@code DefaultRetrievalTopK}）。 */
    private static final int DEFAULT_MATCH_COUNT = 10;

    /** 检索返回上限（源侧 x-agent-maximum=30）。 */
    private static final int MAX_MATCH_COUNT = 30;

    /** 送入重排的最大候选数（重排成本随候选线性增长，不能把 50 条全送）。 */
    private static final int MAX_RERANK_CANDIDATES = 30;

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbKnowledgeStateRepository stateRepository;
    private final KbIngestTaskQueue queue;
    private final KbHybridSearcher searcher;

    /**
     * 检索驱动注册表（W1）。
     *
     * <p>⚠️ 保留 {@link #searcher} 字段是为了「重排之后再取 chunk 序号」这类
     * 与驱动无关的辅助查询；<b>真正的召回</b>一律经 {@link #engineRegistry}，
     * 这样换驱动时不用改本类。
     */
    private final com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry engineRegistry;
    private final KbRerankStage rerankStage;
    private final com.nexusai.kb.config.KbConfigService kbConfigService;
    private final KbChunkMapper chunkMapper;

    /**
     * 原生连接（只用于标签 JOIN —— 那两张表没有对应 mapper）。
     *
     * <p>⚠️ 不用 {@code @Transactional} 包这层读：本类其它写路径已有事务，
     * 这里只读、且失败要降级为「无标签」，包进事务会被异常直接回滚整页。
     */
    private final javax.sql.DataSource dataSource;

    public KnowledgeService(
        KbKnowledgeMapper knowledgeMapper,
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbKnowledgeStateRepository stateRepository,
        KbIngestTaskQueue queue,
        KbHybridSearcher searcher,
        KbRerankStage rerankStage,
        com.nexusai.kb.config.KbConfigService kbConfigService,
        KbChunkMapper chunkMapper,
        com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry engineRegistry,
        javax.sql.DataSource dataSource
    ) {
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.stateRepository = stateRepository;
        this.queue = queue;
        this.searcher = searcher;
        this.rerankStage = rerankStage;
        this.kbConfigService = kbConfigService;
        this.chunkMapper = chunkMapper;
        this.engineRegistry = engineRegistry;
        this.dataSource = dataSource;
    }

    /**
     * 从上传的文件创建知识条目，并立即入队解析。
     *
     * <p><b>流程（逐条对齐源侧）</b>：
     * <ol>
     *   <li>知识库必须存在，且 <b>不能是 FAQ 型</b>（源侧明确拒绝：FAQ 走导入 API）</li>
     *   <li>扩展名必须在白名单；视频类型明确拒绝</li>
     *   <li>算 SHA-256；同库内同 hash 视为重复 ⇒ <b>409</b>（并发侧由调用方决定是否幂等返回）</li>
     *   <li>落盘 → 写行（{@code parse_status=pending} / {@code enable_status=disabled}）→ 入队</li>
     * </ol>
     *
     * @param knowledgeBaseId 知识库 ID
     * @param fileName        原始文件名（可取自定义名）
     * @param content         文件内容
     * @param storageDir      落盘根目录
     * @param metadataJson    元数据 JSON（可空）
     * @return 创建后的知识条目
     */
    @Transactional
    public KnowledgeDto createFromFile(
        String knowledgeBaseId, String fileName, InputStream content,
        Path storageDir, String metadataJson
    ) {
        KbKnowledgeBaseRecord kb = requireKnowledgeBase(knowledgeBaseId);

        String safeName = sanitizeFileName(fileName);
        String ext = extensionOf(safeName);
        validateExtension(ext, safeName);

        if ("faq".equalsIgnoreCase(kb.getType())) {
            throw new ValidationException("FAQ 知识库不支持文件上传，请使用 FAQ 导入功能");
        }

        // 落盘 + 边写边算 hash（大文件不重复读两遍）
        String id = UUID.randomUUID().toString();
        Path targetDir = storageDir == null ? null : storageDir.resolve(knowledgeBaseId);
        Path saved;
        String hash;
        long size;
        try {
            if (targetDir != null) {
                Files.createDirectories(targetDir);
            }
            saved = targetDir == null
                ? Files.createTempFile("kb-upload-", "." + ext)
                : targetDir.resolve(id + "." + ext);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream in = new DigestInputStream(content, digest)) {
                size = Files.copy(in, saved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            hash = HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new ValidationException("文件保存失败: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }

        // 同库内同 hash ⇒ 重复（源侧 409 DuplicateFile）
        List<KbKnowledgeRecord> same = knowledgeMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", knowledgeBaseId)
                .and("file_hash = ?", hash));
        if (!same.isEmpty()) {
            deleteQuietly(saved);
            KbKnowledgeRecord existing = same.get(0);
            throw new ConflictException(
                "文件已存在（同名同内容）: " + existing.getTitle() + "（id=" + existing.getId() + "）");
        }

        KbKnowledgeRecord row = new KbKnowledgeRecord();
        row.setId(id);
        row.setKnowledgeBaseId(knowledgeBaseId);
        row.setType("file");
        // ⚠️ channel 必须显式写：Flex 的 insert 会把 null 也列进 INSERT 清单，DB DEFAULT 不生效
        row.setChannel("web");
        row.setTitle(safeName);
        row.setSource(saved.toString());
        row.setParseStatus(KbKnowledgeStateRepository.STATUS_PENDING);
        // ⭐ 创建时 disabled（源侧 knowledge_create.go 逐字如此）；解析完才转 enabled
        row.setEnableStatus("disabled");
        row.setEmbeddingModelId(kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId());
        row.setFileName(safeName);
        row.setFileType(ext);
        row.setFileSize(size);
        row.setFilePath(saved.toString());
        row.setFileHash(hash);
        row.setStorageSize(0L);
        row.setSummaryStatus("none");
        row.setPendingSubtasksCount(0);
        row.setCustomMetadata("{}");
        // ⭐ 文件夹来自上传名里的目录前缀（源侧 `buildUploadFileName` 的约定：
        //   浏览器把 webkitRelativePath 拼进 fileName ⇒ 后端按最后一个 `/` 拆）。
        //   ⛔ 此前这里写死 ""，导致「选了文件夹上传」的文档全落在根目录 ——
        //   用户看不到子文件夹，因为根本没有子文件夹。
        row.setFolderPath(folderPathOf(fileName));
        row.setParseAttempt(0);

        knowledgeMapper.insert(row);

        // 入队解析（attempt 从 1 开始：首轮也是「一轮尝试」）
        Integer attempt = stateRepository.beginAttempt(id);
        queue.enqueue(id, KbIngestTaskRecord.TYPE_PARSE,
            attempt == null ? 1 : attempt, null);

        log.info("[知识] 已创建文件知识 id={} kb={} name={} size={}B sha256={}",
            id, knowledgeBaseId, safeName, size, hash.substring(0, 12));
        return toDto(requireKnowledge(id));
    }

    /**
     * 列表 · 支持分页 + 状态/类型/关键词/文件夹筛选。
     *
     * <p><b>⭐ 「不传 parse_status 时不返回 deleting」</b>（源侧 swagger 明写）：
     * 删除中的文档对用户是噪音，只有显式按状态筛时才可见。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param page            页码（1 起）
     * @param pageSize        每页数量
     * @param parseStatus     解析状态筛选（可空）
     * @param fileType        文件类型筛选（可空）
     * @param keyword         关键词（匹配标题，可空）
     * @param folderPath      文件夹筛选（null = 不按文件夹过滤；"" = 根目录）
     * @return 分页结果
     */
    public KnowledgePageResponse<KnowledgeDto> list(
        String knowledgeBaseId, Integer page, Integer pageSize,
        String parseStatus, String fileType, String keyword, String folderPath
    ) {
        requireKnowledgeBase(knowledgeBaseId);

        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        QueryWrapper countQuery = QueryWrapper.create()
            .where("knowledge_base_id = ?", knowledgeBaseId);
        QueryWrapper dataQuery = QueryWrapper.create()
            .where("knowledge_base_id = ?", knowledgeBaseId);

        if (parseStatus != null && !parseStatus.isBlank()) {
            countQuery.and("parse_status = ?", parseStatus);
            dataQuery.and("parse_status = ?", parseStatus);
        } else {
            // ⭐ 不指定状态时不返回 deleting（源侧 swagger 明写）
            countQuery.and("parse_status <> ?", KbKnowledgeStateRepository.STATUS_DELETING);
            dataQuery.and("parse_status <> ?", KbKnowledgeStateRepository.STATUS_DELETING);
        }
        if (fileType != null && !fileType.isBlank()) {
            countQuery.and("file_type = ?", fileType);
            dataQuery.and("file_type = ?", fileType);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            countQuery.and("title LIKE ?", like);
            dataQuery.and("title LIKE ?", like);
        }
        if (folderPath != null) {
            countQuery.and("folder_path = ?", folderPath);
            dataQuery.and("folder_path = ?", folderPath);
        }

        long total = knowledgeMapper.selectCountByQuery(countQuery);
        dataQuery.orderBy("create_time", false).orderBy("id", true)
            .limit((long) (p - 1) * size, size);

        List<KbKnowledgeRecord> rows = knowledgeMapper.selectListByQuery(dataQuery);
        // ⭐ 标签一次批量查（⛔ 不是每行一次 —— 200 行就是 200 次查询）
        java.util.Map<String, List<KnowledgeDto.KnowledgeTagView>> tagMap = loadTags(rows);
        List<KnowledgeDto> dtos = new ArrayList<>(rows.size());
        for (KbKnowledgeRecord row : rows) {
            dtos.add(toDto(row, tagMap.getOrDefault(row.getId(), List.of())));
        }
        if (log.isDebugEnabled()) {
            log.debug("[知识] 列表 kb={} page={}/{} 返回 {} 条（共 {}）",
                knowledgeBaseId, p, size, dtos.size(), total);
        }
        return KnowledgePageResponse.of(dtos, p, size, total);
    }

    /**
     * 详情。
     *
     * @param id 知识条目 ID
     * @return 知识条目
     * @throws NotFoundException 不存在（含已逻辑删除）
     */
    public KnowledgeDto getById(String id) {
        return toDto(requireKnowledge(id));
    }

    /**
     * 逻辑删除知识条目，并作废其未认领的解析任务。
     *
     * @param id 知识条目 ID
     * @throws NotFoundException 不存在
     */
    @Transactional
    public void delete(String id) {
        requireKnowledge(id);
        queue.cancelQueued(id);
        knowledgeMapper.deleteById(id);
        log.info("[知识] 已逻辑删除 id={}", id);
    }

    /**
     * 重新解析 · 作废旧任务 → attempt+1 → 入队。
     *
     * <p>⚠️ 与 {@link KnowledgeIngestService#submitParse} 的区别：这里先校验知识条目存在，
     * 并且不允许对 {@code deleting} 的行发起。两者最终都走同一套 attempt 语义。
     *
     * @param id 知识条目 ID
     * @return 新任务 ID
     */
    public long reparse(String id) {
        KbKnowledgeRecord row = requireKnowledge(id);
        if (KbKnowledgeStateRepository.STATUS_DELETING.equals(row.getParseStatus())) {
            throw new ConflictException("知识条目正在删除，不能重新解析: " + id);
        }
        queue.cancelQueued(id);
        Integer attempt = stateRepository.beginAttempt(id);
        if (attempt == null) {
            throw new ConflictException("知识条目状态已变，无法重新解析: " + id);
        }
        long taskId = queue.enqueue(id, KbIngestTaskRecord.TYPE_PARSE, attempt, null);
        log.info("[知识] 已提交重新解析 id={} attempt={} task={}", id, attempt, taskId);
        return taskId;
    }

    /**
     * 取消解析 · 保留行与已生成分块。
     *
     * @param id 知识条目 ID
     */
    public void cancelParse(String id) {
        requireKnowledge(id);
        queue.cancelQueued(id);
        if (!stateRepository.markCancelled(id)) {
            throw new ConflictException("当前状态不可取消: " + id);
        }
        log.info("[知识] 已取消解析 id={}", id);
    }

    // ── 检索 ────────────────────────────────────────────────────────

    /**
     * 混合检索 · 对齐源侧 {@code GET|POST /knowledge-bases/{id}/hybrid-search}。
     *
     * <p><b>⭐ 只检索「可被检索」的文档</b>：候选命中后按
     * {@code enable_status='enabled'} 与 {@code parse_status='completed'} 过滤。
     * 少了这道闸，未解析完 / 解析失败的文档会混进结果（用户会看到空内容或乱码）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param queryText       查询文本
     * @param matchCount      返回条数（null/&lt;=0 → 10；上限 30，对齐源侧 x-agent）
     * @param disableVector   关闭向量路
     * @param disableKeyword  关闭关键词路
     * @param knowledgeIds    限定文档（null/空 = 整库）
     * @return 命中列表（按分数降序）
     */
    public List<SearchResultDto> hybridSearch(
        String knowledgeBaseId, String queryText, Integer matchCount,
        Boolean disableVector, Boolean disableKeyword, List<String> knowledgeIds,
        String rerankModelId, Integer rerankTopN
    ) {
        KbKnowledgeBaseRecord kb = requireKnowledgeBase(knowledgeBaseId);
        String q = queryText == null ? "" : queryText.trim();
        if (q.isEmpty()) {
            throw new ValidationException("查询文本不能为空");
        }
        boolean noVector = Boolean.TRUE.equals(disableVector);
        boolean noKeyword = Boolean.TRUE.equals(disableKeyword);
        if (noVector && noKeyword) {
            throw new ValidationException("不能同时关闭向量与关键词检索");
        }

        int limit = matchCount == null || matchCount <= 0 ? DEFAULT_MATCH_COUNT : Math.min(matchCount, MAX_MATCH_COUNT);

        // ⭐ 没有嵌入模型时，向量路自动跳过（不是错误）
        String modelId = noVector ? null : blankToNull(kb.getEmbeddingModelId());

        // ⭐ W1：召回走驱动注册表。解析顺序 = 知识库级 → 全局默认 → sqlite。
        //    未注册/本机不可用的驱动会在这里抛 IllegalStateException（fail-loud），
        //    ⛔ 不会静默回落 —— 静默回落会让用户以为数据在外部库里。
        com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine engine =
            engineRegistry.require(engineRegistry.resolveEngineType(knowledgeBaseId));

        List<KbHybridSearcher.Hit> hits = toSearchHits(engine.retrieve(
            new com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine.RetrieveParams(
                knowledgeBaseId, q, null, modelId, limit, noVector, noKeyword)));
        if (hits.isEmpty()) {
            return List.of();
        }

        // ⭐ 重排（第二阶段精排）：RRF 只看排名，不理解细粒度相关性；
        //    重排模型直接读「查询+文档」对，排序更准。
        //    未配模型 / 调用失败都会原样返回 RRF 顺序（见 KbRerankStage 的降级语义）。
        // 重排模型三级回落：请求参数 → 知识库级 → 全局默认（用户 2026-11-04 裁定）
        String effectiveRerankModel = resolveRerankModelId(rerankModelId, kb);
        int rerankInput = Math.min(hits.size(), MAX_RERANK_CANDIDATES);
        hits = rerankStage.rerank(q, hits.subList(0, rerankInput), effectiveRerankModel, rerankTopN);
        if (hits.size() > limit) {
            hits = hits.subList(0, limit);
        }

        // 批量取文档信息 + 过滤不可检索的文档
        Set<String> hitKnowledgeIds = new java.util.LinkedHashSet<>();
        for (KbHybridSearcher.Hit h : hits) {
            hitKnowledgeIds.add(h.knowledgeId());
        }
        java.util.Map<String, KbKnowledgeRecord> knowledgeById = new java.util.HashMap<>();
        for (KbKnowledgeRecord row : knowledgeMapper.selectListByQuery(
            QueryWrapper.create().where("id IN ("
                + String.join(",", hitKnowledgeIds.stream().map(x -> "?").toList()) + ")",
                hitKnowledgeIds.toArray()))) {
            knowledgeById.put(row.getId(), row);
        }

        Set<String> allow = knowledgeIds == null ? Set.of() : new java.util.HashSet<>(knowledgeIds);
        List<SearchResultDto> out = new ArrayList<>(hits.size());
        for (KbHybridSearcher.Hit h : hits) {
            KbKnowledgeRecord doc = knowledgeById.get(h.knowledgeId());
            if (doc == null) {
                continue;
            }
            // ⭐ 检索可见性闸：只有 enabled + completed 的文档可被召回
            if (!"enabled".equals(doc.getEnableStatus())
                || !KbKnowledgeStateRepository.STATUS_COMPLETED.equals(doc.getParseStatus())) {
                continue;
            }
            if (!allow.isEmpty() && !allow.contains(h.knowledgeId())) {
                continue;
            }
            String matchType = h.vectorRank() > 0 && h.keywordRank() > 0
                ? "hybrid" : (h.vectorRank() > 0 ? "vector" : "keyword");
            out.add(new SearchResultDto(
                h.chunkId(),
                h.content(),
                h.contextHeader(),
                h.knowledgeId(),
                doc.getTitle(),
                chunkIndexOf(h.chunkId()),
                round6(h.score()),
                matchType));
        }
        return out;
    }

    /**
     * 重排模型 ID · 三级回落（用户 2026-11-04 裁定「全局默认 + 每库可覆盖」）。
     *
     * <p>顺序：<b>请求参数</b>（调用方显式指定，最具体）→ <b>知识库级</b> → <b>全局默认</b>。
     *
     * @return 实际使用的模型 ID；全部未配置时 null（= 不重排）
     */
    private String resolveRerankModelId(String requested, KbKnowledgeBaseRecord kb) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (kb != null && kb.getRerankModelId() != null && !kb.getRerankModelId().isBlank()) {
            return kb.getRerankModelId();
        }
        // ⚠️ kbConfigService 可为 null（单测场景）⇒ 视为「未配置」，不是错误。
        // ⭐ readRerankModelId() 在未显式配置时会自动选用第一个启用的 rerank 模型。
        return kbConfigService == null ? null : kbConfigService.readRerankModelId();
    }

    /**
     * 引擎命中 → 检索层 Hit。
     *
     * <p>两个 record 字段完全一致，但<b>不能合并成一个类型</b>：
     * 引擎层是「驱动契约」（各驱动都要满足），检索层是「本仓内部结构」。
     * 合成一个会让驱动接口被迫依赖 {@code KbHybridSearcher} 的内部类型，
     * 换驱动时就改不动了。
     */
    private static List<KbHybridSearcher.Hit> toSearchHits(
        List<com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine.Hit> hits
    ) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<KbHybridSearcher.Hit> out = new ArrayList<>(hits.size());
        for (com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine.Hit h : hits) {
            out.add(new KbHybridSearcher.Hit(h.chunkId(), h.knowledgeId(), h.content(),
                h.header(), h.score(), h.vectorRank(), h.keywordRank()));
        }
        return out;
    }

    /** 块序号缓存（同一次检索内多处使用，避免重复查库）。 */
    private final java.util.Map<String, Integer> chunkIndexCache = new java.util.concurrent.ConcurrentHashMap<>();

    private int chunkIndexOf(String chunkId) {
        Integer cached = chunkIndexCache.get(chunkId);
        if (cached != null) {
            return cached;
        }
        KbChunkRecord row = chunkMapper == null ? null : chunkMapper.selectOneById(chunkId);
        int idx = row == null || row.getChunkIndex() == null ? 0 : row.getChunkIndex();
        chunkIndexCache.put(chunkId, idx);
        return idx;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private KbKnowledgeRecord requireKnowledge(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("知识条目 ID 不能为空");
        }
        KbKnowledgeRecord row = knowledgeMapper.selectOneById(id);
        if (row == null) {
            throw new NotFoundException("知识条目 " + id + " 不存在");
        }
        return row;
    }

    private KbKnowledgeBaseRecord requireKnowledgeBase(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
        KbKnowledgeBaseRecord kb = knowledgeBaseMapper.selectOneById(id);
        if (kb == null) {
            throw new NotFoundException("知识库 " + id + " 不存在");
        }
        return kb;
    }

    /** 文件名安全化 · 去掉路径分隔符与首尾空白（源侧另有字符白名单，此处只防路径穿越）。 */
    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("文件名不能为空");
        }
        // 只取最后一段，防 ../../etc/passwd 这类路径穿越
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new ValidationException("文件名非法: " + raw);
        }
        return name;
    }

    /**
     * 从上传名里取目录前缀（文件夹上传时浏览器把它拼进 {@code fileName}）。
     *
     * <p>源侧 {@code buildUploadFileName}（{@code folderTree.ts} 附近）的约定：
     * {@code fileName = "目标文件夹/webkit 相对路径/文件名"} ⇒ 后端按<b>最后一个 `/`</b> 拆。
     *
     * <p>⚠️ 必须防路径穿越：{@code ../../etc} 这类段一律丢弃，
     * 只保留安全的普通段（对齐 {@code sanitizeFileName} 的严格度）。
     *
     * @param raw 原始上传名（可能含目录）
     * @return 规范化后的文件夹路径；无目录 ⇒ {@code ""}（根）
     */
    private static String folderPathOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash <= 0) {
            return "";
        }
        java.util.List<String> safe = new ArrayList<>();
        for (String seg : name.substring(0, slash).split("/")) {
            String t = seg.trim();
            if (t.isEmpty() || ".".equals(t) || "..".equals(t)) {
                continue;   // ⛔ 路径穿越与空段一律丢弃
            }
            safe.add(t);
        }
        return String.join("/", safe);
    }

    /** 取小写扩展名（不含点）；无扩展名返回哨兵值。 */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return UNKNOWN_FILE_TYPE;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void validateExtension(String ext, String fileName) {
        if (VIDEO_EXTENSIONS.contains(ext)) {
            throw new ValidationException("暂不支持上传视频文件: " + fileName);
        }
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new ValidationException("不支持的文件类型: " + ext + "（文件 " + fileName + "）");
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[知识] 清理临时文件失败 path={} 原因={}", path, e.getMessage());
        }
    }

    /** 实体 → DTO（无标签）。 */
    private static KnowledgeDto toDto(KbKnowledgeRecord r) {
        return toDto(r, List.of());
    }

    /**
     * 实体 + 标签 → DTO。
     *
     * @param r    行
     * @param tags 该行的标签（空列表而非 null）
     */
    private static KnowledgeDto toDto(KbKnowledgeRecord r, List<KnowledgeDto.KnowledgeTagView> tags) {
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
            tags == null ? List.of() : tags);
    }

    /**
     * 批量取「文档 → 标签」映射。
     *
     * <p>⭐ 一次 JOIN 查完所有行，⛔ 不在循环里逐条查（200 行 ⇒ 200 次往返）。
     * 失败降级为「无标签」而不是抛 —— 标签是展示增强，不该让文档列表 500。
     *
     * @param rows 本页文档行
     * @return 文档 id → 标签列表（无标签的文档不出现在 map 里）
     */
    private java.util.Map<String, List<KnowledgeDto.KnowledgeTagView>> loadTags(
        List<KbKnowledgeRecord> rows
    ) {
        java.util.Map<String, List<KnowledgeDto.KnowledgeTagView>> out = new java.util.HashMap<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        List<String> ids = new ArrayList<>(rows.size());
        for (KbKnowledgeRecord r : rows) {
            if (r != null && r.getId() != null) {
                ids.add(r.getId());
            }
        }
        if (ids.isEmpty()) {
            return out;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT r.knowledge_id, t.id, t.name, t.color "
            + "FROM kb_knowledge_tag_relation r "
            + "JOIN kb_knowledge_tag t ON t.id = r.tag_id AND t.deleted = 0 "
            + "WHERE r.knowledge_id IN (" + placeholders + ") "
            + "ORDER BY t.sort_order, t.name";
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setString(i + 1, ids.get(i));
            }
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(new KnowledgeDto.KnowledgeTagView(
                            rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (RuntimeException | java.sql.SQLException e) {
            log.warn("[知识] 读标签失败，本次不返回标签 原因={}", e.getMessage());
        }
        return out;
    }
}
