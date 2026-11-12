package com.nexusai.kb.app.copy;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.image.KbImageService;
import com.nexusai.kb.domain.KbActivityService;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 知识库复制 / 副本 · 批次 W4 · V269。
 *
 * <p><b>源侧对照</b>：WeKnora {@code POST /knowledge-bases/copy}（带 {@code task_id} 异步）、
 * {@code GET /knowledge-bases/copy/progress/{task_id}}、{@code POST /knowledge-bases/{id}/duplicate}。
 *
 * <h2>⭐⭐ 两个端点语义不同，不能合并</h2>
 * <table>
 *   <tr><th></th><th>{@code /copy}</th><th>/{id}/duplicate</th></tr>
 *   <tr><td>目标</td><td>{@code target_id} <b>必须已存在</b>（把内容并进去）</td>
 *       <td>自动新建一个库</td></tr>
 *   <tr><td>同步性</td><td>异步，返 {@code task_id}，轮询进度</td><td>同步，直接返新库</td></tr>
 *   <tr><td>典型用途</td><td>把 A 库文档导进 B 库</td><td>「另存为」</td></tr>
 * </table>
 *
 * <h2>⭐ 进度放内存而不是建表</h2>
 * 复制是<b>进程内短任务</b>（同一进程内几百毫秒到几秒）。建表要额外的迁移与清理；
 * 进程重启后进度丢失，但任务本身也随进程没了 —— 不会出现「任务在跑、进度不见了」。
 * 源侧用 Redis，本仓单机用 </t>{@link ConcurrentHashMap} 等价。
 * ⚠️ 已知边界：多实例部署时进度不跨实例（本仓单机，可接受）。
 */
@Service
public class KbCopyService {

    private static final Logger log = LoggerFactory.getLogger(KbCopyService.class);

    /** 进度记录保留时长（毫秒）：完成后 10 分钟清理，避免 map 无限增长。 */
    private static final long RETENTION_MS = 10 * 60 * 1000L;

    /** 分块批量插入大小。 */
    private static final int CHUNK_BATCH = 200;

    private final KbKnowledgeBaseMapper baseMapper;
    private final KbKnowledgeMapper knowledgeMapper;
    private final KbChunkMapper chunkMapper;
    private final KbActivityService activityService;
    private final KbImageService imageService;

    /** taskId → 进度。 */
    private final Map<String, Progress> progressMap = new ConcurrentHashMap<>();

    @Autowired
    public KbCopyService(KbKnowledgeBaseMapper baseMapper,
                         KbKnowledgeMapper knowledgeMapper,
                         KbChunkMapper chunkMapper,
                         KbActivityService activityService,
                         KbImageService imageService) {
        this.baseMapper = baseMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.chunkMapper = chunkMapper;
        this.activityService = activityService;
        this.imageService = imageService;
    }

    /**
     * 复制：把 {@code sourceId} 的文档复制进<b>已存在</b>的 {@code targetId}。
     *
     * @param sourceId 源知识库
     * @param targetId 目标知识库（必须已存在且不等于源）
     * @param taskId   客户端指定的任务 ID（可空 ⇒ 服务端生成）
     * @return 任务 ID（用于轮询进度）
     */
    public String copy(String sourceId, String targetId, String taskId) {
        requireBase(sourceId, "源知识库");
        requireBase(targetId, "目标知识库");
        if (sourceId.equals(targetId)) {
            throw new ValidationException("源知识库与目标知识库不能相同");
        }
        String id = (taskId == null || taskId.isBlank()) ? UUID.randomUUID().toString() : taskId.trim();
        if (progressMap.containsKey(id)) {
            throw new ConflictException("任务 " + id + " 已存在");
        }
        Progress p = new Progress(id, sourceId, targetId);
        progressMap.put(id, p);
        // 异步执行：HTTP 立刻返回 task_id。用虚拟线程（与问答链一致）。
        Thread.ofVirtual().name("kb-copy-" + id).start(() -> runCopy(p));
        return id;
    }

    /**
     * 创建副本：新建一个与源库同配置的库，并复制全部文档。
     *
     * <p>与 {@link #copy} 的区别：这里是<b>同步</b>的，直接把新库返回给调用方。
     * 数据量很大时会让请求变慢 —— 源侧同样如此（{@code duplicate} 是同步端点）。
     *
     * @param sourceId 源知识库
     * @param newName  新库名（空则「原名 副本」）
     * @return 新知识库
     */
    public KbKnowledgeBaseRecord duplicate(String sourceId, String newName) {
        KbKnowledgeBaseRecord source = requireBase(sourceId, "知识库");

        KbKnowledgeBaseRecord copy = new KbKnowledgeBaseRecord();
        copy.setId(UUID.randomUUID().toString());
        copy.setName(newName == null || newName.isBlank()
            ? source.getName() + " 副本" : newName.trim());
        copy.setDescription(source.getDescription());
        copy.setType(source.getType());
        copy.setIsTemporary(0);
        copy.setIsPinned(0);
        copy.setChunkingConfig(source.getChunkingConfig());
        copy.setImageProcessingConfig(source.getImageProcessingConfig());
        // ⭐ V286 图片管线三项必须一起复制：三列 NOT NULL 且 Flex 会把 null 也列进 INSERT
        //   ⇒ 漏拷会直接 NOT NULL constraint failed（不是「用默认值」）。
        copy.setImageCaptionEnabled(source.getImageCaptionEnabled() == null ? 1 : source.getImageCaptionEnabled());
        copy.setImageOcrEnabled(source.getImageOcrEnabled() == null ? 1 : source.getImageOcrEnabled());
        copy.setImagePipeline(source.getImagePipeline() == null || source.getImagePipeline().isBlank()
            ? "default" : source.getImagePipeline());
        copy.setCosConfig(source.getCosConfig());
        copy.setVlmConfig(source.getVlmConfig());
        copy.setExtractConfig(source.getExtractConfig());
        copy.setFaqConfig(source.getFaqConfig());
        copy.setQuestionGenerationConfig(source.getQuestionGenerationConfig());
        copy.setAutoTagConfig(source.getAutoTagConfig());
        copy.setProfileConfig(source.getProfileConfig());
        copy.setAsrConfig(source.getAsrConfig());
        copy.setStorageProviderConfig(source.getStorageProviderConfig());
        copy.setWikiConfig(source.getWikiConfig());
        copy.setIndexingStrategy(source.getIndexingStrategy());
        copy.setEmbeddingModelId(source.getEmbeddingModelId());
        copy.setSummaryModelId(source.getSummaryModelId());
        copy.setVectorStoreId(source.getVectorStoreId());
        copy.setStorageBackendId(source.getStorageBackendId());
        copy.setRetrieverEngine(source.getRetrieverEngine());
        copy.setCreatorId(source.getCreatorId());
        baseMapper.insert(copy);

        int copied = 0;
        for (KbKnowledgeRecord doc : listDocuments(sourceId)) {
            if (dataOnly(doc, copy.getId())) {
                copied++;
            }
        }
        log.info("[知识库副本] 源={} 新={} 文档数={}", sourceId, copy.getId(), copied);
        activityService.record(copy.getId(), KbActivityService.A_COPY, "success",
            "knowledge_base", sourceId, "{\"copied_documents\":" + copied + "}");
        return baseMapper.selectOneById(copy.getId());
    }

    /**
     * 查询复制进度。
     *
     * @param taskId 任务 ID
     * @return 进度快照
     * @throws NotFoundException 任务不存在（从未创建 / 已清理）
     */
    public Progress progress(String taskId) {
        Progress p = progressMap.get(taskId);
        if (p == null) {
            throw new NotFoundException("复制任务 " + taskId + " 不存在或已过期");
        }
        return p;
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private void runCopy(Progress p) {
        try {
            List<KbKnowledgeRecord> docs = listDocuments(p.sourceId);
            p.total.set(docs.size());
            for (KbKnowledgeRecord doc : docs) {
                if (p.cancelled.get()) {
                    p.status = "cancelled";
                    return;
                }
                if (dataOnly(doc, p.targetId)) {
                    p.done.incrementAndGet();
                } else {
                    p.failed.incrementAndGet();
                }
            }
            p.status = "completed";
            p.finishedAt = System.currentTimeMillis();
            log.info("[知识库复制] 任务={} 源={} 目标={} 共={} 成功={} 失败={}", p.taskId,
                p.sourceId, p.targetId, p.total.get(), p.done.get(), p.failed.get());
            activityService.record(p.targetId, KbActivityService.A_COPY, "success",
                "knowledge_base", p.sourceId,
                "{\"copied_documents\":" + p.done.get() + "}");
        } catch (RuntimeException e) {
            p.status = "failed";
            p.error = e.getMessage();
            p.finishedAt = System.currentTimeMillis();
            log.warn("[知识库复制] 任务={} 失败：{}", p.taskId, e.getMessage());
        }
    }

    /**
     * 复制一份文档及其分块（新 ID）。
     *
     * <p>⚠️ 复制后新文档的 {@code parse_status} = {@code completed}、
     * {@code enable_status} = {@code enabled} —— 因为分块是原样搬过来的，
     * 不需要重新解析。若置成 {@code unprocessed}，用户会看到「复制成功但搜不到」。
     *
     * @return true = 成功
     */
    private boolean dataOnly(KbKnowledgeRecord source, String targetKbId) {
        try {
            KbKnowledgeRecord copy = new KbKnowledgeRecord();
            String newId = UUID.randomUUID().toString();
            copy.setId(newId);
            copy.setKnowledgeBaseId(targetKbId);
            copy.setType(source.getType());
            copy.setTitle(source.getTitle());
            copy.setDescription(source.getDescription());
            copy.setSource(source.getSource());
            copy.setParseStatus("completed");
            copy.setEnableStatus("enabled");
            copy.setEmbeddingModelId(source.getEmbeddingModelId());
            copy.setFileName(source.getFileName());
            copy.setFileType(source.getFileType());
            copy.setFileSize(source.getFileSize());
            // ⚠️ 不复制 file_path：两个库共用同一个物理文件，删除任一库时会误删另一个的文件。
            //    源侧同样是「只复制记录」。留 null 表示「没有本地文件副本」。
            copy.setFileHash(source.getFileHash());
            copy.setStorageSize(source.getStorageSize());
            copy.setSummaryStatus(source.getSummaryStatus());
            copy.setPendingSubtasksCount(0);
            copy.setChannel(source.getChannel());
            copy.setMetadata(source.getMetadata());
            copy.setCustomMetadata(source.getCustomMetadata());
            copy.setProfile(source.getProfile());
            copy.setFolderPath(source.getFolderPath());
            copy.setProcessedAt(source.getProcessedAt());
            copy.setParseAttempt(0);
            knowledgeMapper.insert(copy);

            List<KbChunkRecord> chunks = listChunks(source.getId());
            List<KbChunkRecord> buffer = new ArrayList<>(CHUNK_BATCH);
            for (KbChunkRecord c : chunks) {
                buffer.add(cloneChunk(c, newId, targetKbId));
                if (buffer.size() >= CHUNK_BATCH) {
                    chunkMapper.insertBatch(buffer);
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                chunkMapper.insertBatch(buffer);
            }
            // ⚠️ 图片投影不会随 chunk 拷贝自动生成 ⇒ 必须重投影，
            //    否则复制出来的库「图库」是空的（正文有图、图库没有）。
            imageService.projectKnowledge(newId);
            return true;
        } catch (RuntimeException e) {
            log.warn("[知识库复制] 文档 {} 复制失败：{}", source.getId(), e.getMessage());
            return false;
        }
    }

    private static KbChunkRecord cloneChunk(KbChunkRecord c, String newKnowledgeId, String newKbId) {
        KbChunkRecord n = new KbChunkRecord();
        n.setId(UUID.randomUUID().toString());
        n.setKnowledgeBaseId(newKbId);
        n.setKnowledgeId(newKnowledgeId);
        n.setContent(c.getContent());
        n.setChunkIndex(c.getChunkIndex());
        n.setIsEnabled(c.getIsEnabled());
        n.setStartAt(c.getStartAt());
        n.setEndAt(c.getEndAt());
        // pre/next/parent 是块间链：复制后 ID 全变了，指向旧块会成悬空引用 ⇒ 置空。
        n.setPreChunkId(null);
        n.setNextChunkId(null);
        n.setParentChunkId(null);
        n.setChunkType(c.getChunkType());
        n.setImageInfo(c.getImageInfo());
        n.setRelationChunks(c.getRelationChunks());
        n.setIndirectRelationChunks(c.getIndirectRelationChunks());
        n.setVideoInfo(c.getVideoInfo());
        n.setSourceContent(c.getSourceContent());
        n.setContentRevision(0);
        n.setIndexStatus("ready");
        n.setLastEditorId("");
        n.setContextHeader(c.getContextHeader());
        n.setStatus(c.getStatus());
        n.setContentHash(c.getContentHash());
        n.setFlags(c.getFlags());
        n.setSeqId(c.getSeqId());
        n.setTagId(c.getTagId());
        n.setMetadata(c.getMetadata());
        n.setSourceLocators(c.getSourceLocators());
        n.setCreateTime(LocalDateTime.now());
        n.setUpdateTime(LocalDateTime.now());
        return n;
    }

    private List<KbKnowledgeRecord> listDocuments(String kbId) {
        return new ArrayList<>(knowledgeMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_base_id = ?", kbId).orderBy("create_time", true)));
    }

    private List<KbChunkRecord> listChunks(String knowledgeId) {
        return new ArrayList<>(chunkMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId).orderBy("chunk_index", true)));
    }

    private KbKnowledgeBaseRecord requireBase(String id, String label) {
        if (id == null || id.isBlank()) {
            throw new ValidationException(label + " ID 不能为空");
        }
        KbKnowledgeBaseRecord row = baseMapper.selectOneById(id);
        if (row == null) {
            throw new NotFoundException(label + " " + id + " 不存在");
        }
        return row;
    }

    /** 清理过期进度（每次创建任务时顺手做，避免定时器）。 */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        progressMap.entrySet().removeIf(e -> {
            Progress p = e.getValue();
            return p.finishedAt > 0 && now - p.finishedAt > RETENTION_MS;
        });
    }

    /** 复制进度快照。 */
    public static final class Progress {
        private final String taskId;
        private final String sourceId;
        private final String targetId;
        private final AtomicInteger total = new AtomicInteger();
        private final AtomicInteger done = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile String status = "running";
        private volatile String error;
        private volatile long finishedAt;

        Progress(String taskId, String sourceId, String targetId) {
            this.taskId = taskId;
            this.sourceId = sourceId;
            this.targetId = targetId;
        }

        public String taskId() { return taskId; }
        public String sourceId() { return sourceId; }
        public String targetId() { return targetId; }
        public int total() { return total.get(); }
        public int done() { return done.get(); }
        public int failed() { return failed.get(); }
        public String status() { return status; }
        public String error() { return error; }

        /** 进度百分比（0–100；total=0 时视为 100）。 */
        public int percent() {
            int t = total.get();
            if (t <= 0) {
                return "completed".equals(status) ? 100 : 0;
            }
            return (int) Math.min(100, (done.get() + failed.get()) * 100L / t);
        }

        public void cancel() { cancelled.set(true); }
    }

    /** 时间格式化（出站用）。 */
    public static String formatTime(LocalDateTime t) {
        return KbTimeFormat.format(t);
    }
}
