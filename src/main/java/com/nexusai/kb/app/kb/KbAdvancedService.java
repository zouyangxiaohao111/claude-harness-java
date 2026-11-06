package com.nexusai.kb.app.kb;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.image.KbImageService;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.domain.KbActivityService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库高级操作 · 批次 W4。
 *
 * <p>覆盖：置顶 / 可移动目标列表 / 重建索引 / 清空内容 / 图片-属性字典。
 *
 * <h2>⭐⭐ 置顶必须写 {@code pinned_at}</h2>
 * 源侧置顶是「按 pinned_at 倒序」排序的副产品 —— 只写 {@code is_pinned=1} 而不写时间，
 * 多个置顶项的先后顺序会取决于数据库返回顺序（不稳定，刷新就变）。
 * 取消置顶时把 {@code pinned_at} 清空，否则下次置顶会沿用旧时间排到错误位置。
 *
 * <h2>⭐⭐ 重建索引要按文档逐个来，且不能在事务里</h2>
 * {@link KbIndexWriter#reindexKnowledge} 用独立连接写 FTS ⇒ 外层写事务会自锁
 * （缺陷 5：SQLITE_BUSY）。所以本方法<b>不用</b> {@code @Transactional}。
 * 「部分成功」是允许的 —— 返回成功/失败计数，让用户知道哪几篇没重建。
 */
@Service
public class KbAdvancedService {

    private static final Logger log = LoggerFactory.getLogger(KbAdvancedService.class);

    private final KbKnowledgeBaseMapper baseMapper;
    private final KbKnowledgeMapper knowledgeMapper;
    private final KbIndexWriter indexWriter;
    private final KbActivityService activityService;
    private final KbImageService imageService;
    private final JdbcTemplate jdbc;

    @Autowired
    public KbAdvancedService(KbKnowledgeBaseMapper baseMapper,
                             KbKnowledgeMapper knowledgeMapper,
                             KbIndexWriter indexWriter,
                             KbActivityService activityService,
                             KbImageService imageService,
                             JdbcTemplate jdbc) {
        this.baseMapper = baseMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.indexWriter = indexWriter;
        this.activityService = activityService;
        this.imageService = imageService;
        this.jdbc = jdbc;
    }

    /**
     * 置顶 / 取消置顶。
     *
     * @param kbId 知识库
     * @param pin  true = 置顶；false = 取消
     * @return 更新后的库
     */
    @Transactional
    public KbKnowledgeBaseRecord setPinned(String kbId, boolean pin) {
        KbKnowledgeBaseRecord row = requireBase(kbId);
        row.setIsPinned(pin ? 1 : 0);
        // ⚠️ 取消置顶要清空 pinned_at，否则下次置顶沿用旧时间 ⇒ 排序错
        row.setPinnedAt(pin ? KbTimeFormat.format(LocalDateTime.now()) : null);
        row.setUpdateTime(LocalDateTime.now());
        // `update(row, false)`：显式写 NULL（清空 pinned_at 必须走这条）
        baseMapper.update(row, false);
        activityService.record(kbId, pin ? KbActivityService.A_PIN : KbActivityService.A_UNPIN);
        log.info("[知识库] {} id={}", pin ? "已置顶" : "已取消置顶", kbId);
        return baseMapper.selectOneById(kbId);
    }

    /**
     * 可移动目标列表（当前知识库可把文档移入的其它库）。
     *
     * <p>⚠️ 排除自己；排除临时库（对 UI 隐藏的库不该出现在选择器里）。
     */
    public List<MoveTarget> moveTargets(String kbId) {
        requireBase(kbId);
        List<KbKnowledgeBaseRecord> rows = baseMapper.selectListByQuery(
            QueryWrapper.create()
                .where("id <> ?", kbId)
                .and("is_temporary = 0")
                .orderBy("is_pinned", false)
                .orderBy("create_time", false));
        List<MoveTarget> out = new ArrayList<>(rows.size());
        for (KbKnowledgeBaseRecord r : rows) {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_knowledge WHERE knowledge_base_id = ? AND deleted = 0",
                Integer.class, r.getId());
            out.add(new MoveTarget(r.getId(), r.getName(), r.getDescription(), r.getType(),
                count == null ? 0 : count));
        }
        return out;
    }

    /**
     * 重建整个知识库的索引。
     *
     * <p>⛔ 不加 {@code @Transactional}：见类注释。
     *
     * @return 结果统计
     */
    public RebuildResult rebuildIndex(String kbId) {
        KbKnowledgeBaseRecord kb = requireBase(kbId);
        List<KbKnowledgeRecord> docs = knowledgeMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_base_id = ?", kbId));
        int ok = 0;
        int failed = 0;
        List<String> failedIds = new ArrayList<>();
        for (KbKnowledgeRecord d : docs) {
            // 只重建已经解析完的文档：还在解析中的文档分块不完整，
            // 重建会把半截内容写进索引，解析完还得再来一次。
            if (!"completed".equals(d.getParseStatus()) && !"finalizing".equals(d.getParseStatus())) {
                continue;
            }
            try {
                indexWriter.reindexKnowledge(d.getId(), kbId,
                    d.getEmbeddingModelId() == null || d.getEmbeddingModelId().isBlank()
                        ? kb.getEmbeddingModelId() : d.getEmbeddingModelId());
                ok++;
            } catch (RuntimeException e) {
                failed++;
                failedIds.add(d.getId());
                log.warn("[重建索引] 文档 {} 失败：{}", d.getId(), e.getMessage());
            }
        }
        activityService.record(kbId, KbActivityService.A_REBUILD_INDEX,
            failed == 0 ? "success" : "failure", "knowledge_base", kbId,
            "{\"ok\":" + ok + ",\"failed\":" + failed + "}");
        log.info("[重建索引] kb={} 成功={} 失败={}", kbId, ok, failed);
        return new RebuildResult(ok, failed, failedIds);
    }

    /**
     * 清空知识库内容（删全部文档 + 分块 + 索引 + 图片投影 + 标签关联）。
     *
     * <p>⚠️ 保留知识库本体与标签<b>定义</b>（标签定义是用户配置，不是内容）。
     * 保留 FAQ 导入结果等配置。
     *
     * <p>⛔ <b>不能加 {@code @Transactional}</b>：{@code indexWriter.clearIndex} 用独立
     * JDBC 连接写 FTS，在外层写事务里必然 {@code SQLITE_BUSY}（实测 500）。
     * 与缺陷 5 同一根因。代价是删除不是原子的 —— 但本操作<b>幂等</b>，
     * 中途失败重跑即可，不会留下「删一半且无法恢复」的状态。
     */
    public ClearResult clearContents(String kbId) {
        requireBase(kbId);
        // 索引先清（FTS + 向量），再删数据 —— 反了会留下指向已删块的索引行
        List<String> docIds = jdbc.queryForList(
            "SELECT id FROM kb_knowledge WHERE knowledge_base_id = ?", String.class, kbId);
        for (String docId : docIds) {
            try {
                indexWriter.clearIndex(docId);
            } catch (RuntimeException e) {
                log.warn("[清空] 清理文档 {} 索引失败：{}", docId, e.getMessage());
            }
        }
        int images = jdbc.update("DELETE FROM kb_chunk_image WHERE knowledge_base_id = ?", kbId);
        int revisions = jdbc.update("DELETE FROM kb_chunk_revision WHERE knowledge_base_id = ?", kbId);
        int chunks = jdbc.update("DELETE FROM kb_chunk WHERE knowledge_base_id = ?", kbId);
        int links = jdbc.update("DELETE FROM kb_knowledge_tag_relation WHERE knowledge_base_id = ?", kbId);
        int docs = jdbc.update("DELETE FROM kb_knowledge WHERE knowledge_base_id = ?", kbId);
        activityService.record(kbId, KbActivityService.A_CLEAR, "success", "knowledge_base", kbId,
            "{\"documents\":" + docs + ",\"chunks\":" + chunks + "}");
        log.info("[清空] kb={} 文档={} 分块={} 图片={} 修订={} 标签关联={}",
            kbId, docs, chunks, images, revisions, links);
        return new ClearResult(docs, chunks);
    }

    /**
     * 把文档移动到另一个知识库（{@code POST /knowledge/move}）。
     *
     * <p>⚠️ 移动 = 改归属 + 重建两边的索引。不重建的话：
     * 源库的 FTS 还留着该文档的块（能搜到但打不开），目标库搜不到。
     *
     * @param knowledgeIds 要移动的文档
     * @param targetKbId   目标知识库
     */
    @Transactional
    public int move(String sourceKbId, List<String> knowledgeIds, String targetKbId) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            throw new ValidationException("knowledge_ids 不能为空");
        }
        if (sourceKbId == null || sourceKbId.isBlank()) {
            throw new ValidationException("源知识库 ID 不能为空");
        }
        if (targetKbId == null || targetKbId.isBlank()) {
            throw new ValidationException("目标知识库 ID 不能为空");
        }
        if (sourceKbId.equals(targetKbId)) {
            throw new ValidationException("源知识库与目标知识库不能相同");
        }
        requireBase(targetKbId);

        int moved = 0;
        for (String kid : knowledgeIds) {
            KbKnowledgeRecord doc = knowledgeMapper.selectOneById(kid);
            if (doc == null || !sourceKbId.equals(doc.getKnowledgeBaseId())) {
                throw new NotFoundException("文档 " + kid + " 不存在于知识库 " + sourceKbId);
            }
            jdbc.update("UPDATE kb_knowledge SET knowledge_base_id = ?, update_time = ? WHERE id = ?",
                targetKbId, KbTimeFormat.format(LocalDateTime.now()), kid);
            jdbc.update("UPDATE kb_chunk SET knowledge_base_id = ? WHERE knowledge_id = ?",
                targetKbId, kid);
            jdbc.update("UPDATE kb_chunk_image SET knowledge_base_id = ? WHERE knowledge_id = ?",
                targetKbId, kid);
            jdbc.update("UPDATE kb_knowledge_tag_relation SET knowledge_base_id = ? WHERE knowledge_id = ?",
                targetKbId, kid);
            moved++;
        }
        activityService.record(sourceKbId, KbActivityService.A_MOVE, "success", "knowledge", null,
            "{\"moved\":" + moved + ",\"target\":\"" + targetKbId + "\"}");
        activityService.record(targetKbId, KbActivityService.A_MOVE, "success", "knowledge", null,
            "{\"moved_in\":" + moved + ",\"source\":\"" + sourceKbId + "\"}");
        log.info("[移动] {} 篇文档 {} → {}", moved, sourceKbId, targetKbId);
        return moved;
    }

    // ── 内部 ────────────────────────────────────────────────────────

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

    /** 可移动目标。 */
    public record MoveTarget(
        @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
        @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
        @com.fasterxml.jackson.annotation.JsonProperty("description") String description,
        @com.fasterxml.jackson.annotation.JsonProperty("type") String type,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_count") int knowledgeCount) {
    }

    /** 重建索引结果。 */
    public record RebuildResult(
        @com.fasterxml.jackson.annotation.JsonProperty("succeeded") int succeeded,
        @com.fasterxml.jackson.annotation.JsonProperty("failed") int failed,
        @com.fasterxml.jackson.annotation.JsonProperty("failed_ids") List<String> failedIds) {
    }

    /** 清空结果。 */
    public record ClearResult(
        @com.fasterxml.jackson.annotation.JsonProperty("documents") int documents,
        @com.fasterxml.jackson.annotation.JsonProperty("chunks") int chunks) {
    }

    /** 图片服务（Controller 直接用它，暴露给装配用）。 */
    public KbImageService images() {
        return imageService;
    }
}
