package com.nexusai.kb.api;

import com.nexusai.kb.app.chunk.KbChunkService;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbChunkRevisionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块管理端点 · 批次 W4（源侧「分块管理」tag 共 8 个，本类实现 7 个）。
 *
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/chunks/{knowledge_id}</td><td>列出文档的分块</td></tr>
 *   <tr><td>GET</td><td>/chunks/by-id/{id}</td><td>按块 ID 取（不需文档 ID）</td></tr>
 *   <tr><td>PUT</td><td>/chunks/{knowledge_id}/{id}</td><td>编辑内容 / 启停</td></tr>
 *   <tr><td>DELETE</td><td>/chunks/{knowledge_id}/{id}</td><td>删单个分块</td></tr>
 *   <tr><td>DELETE</td><td>/chunks/{knowledge_id}</td><td>清空文档全部块</td></tr>
 *   <tr><td>GET</td><td>/chunks/{knowledge_id}/{id}/revisions</td><td>版本历史</td></tr>
 *   <tr><td>POST</td><td>/chunks/{knowledge_id}/{id}/revert</td><td>回滚到指定版本</td></tr>
 * </table>
 *
 * <p>⚠️ 生成问题的三个端点里，本仓只做 <b>DELETE</b>（源侧 {@code DELETE /chunks/by-id/{id}/questions}）。
 * PUT（手写问句）与 POST（重新生成）不在核心 178 清单内，未做 —— ⛔ 不建假端点。
 *
 * <h2>⭐⭐ 编辑后必须重建索引，且必须在事务外</h2>
 * 见 {@link KbChunkService#reindexChunkKnowledge}。本 Controller 在 service 方法返回后
 * （即事务已提交）再调它 —— 顺序反了会 SQLITE_BUSY（缺陷 5）。
 */
@RestController
public class ChunkController {

    private static final Logger log = LoggerFactory.getLogger(ChunkController.class);

    @Autowired
    private KbChunkService chunkService;

    /**
     * 删除分块里的一条生成问句（源侧 {@code DELETE /chunks/by-id/{id}/questions}）。
     *
     * <p>⚠️ <b>本仓差异</b>：源侧每条问句有独立 id，本仓存在
     * {@code kb_chunk.metadata.generated_questions} 的字符串数组里，没有 id
     * ⇒ {@code questionId} 按<b>问句文本</b>匹配。
     *
     * @param id   分块 ID
     * @param body {@code {"question_id": "<问句文本>"}}
     * @return 删除结果
     */
    @DeleteMapping("/api/v1/chunks/by-id/{id}/questions")
    public java.util.Map<String, Object> deleteGeneratedQuestion(
            @PathVariable String id,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String questionId = body == null ? null : body.get("question_id");
        if (questionId == null || questionId.isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("question_id 不能为空");
        }
        boolean deleted = chunkService.deleteGeneratedQuestion(id, questionId);
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("deleted", deleted);
        if (!deleted) {
            resp.put("note", "该分块里没有这条问句（本仓按问句文本匹配，源侧用 question_id）");
        }
        return resp;
    }

    @GetMapping("/api/v1/chunks/{knowledgeId}")
    public List<ChunkView> list(@PathVariable String knowledgeId) {
        List<KbChunkRecord> rows = chunkService.list(knowledgeId, true);
        List<ChunkView> out = new ArrayList<>(rows.size());
        for (KbChunkRecord r : rows) {
            out.add(toView(r));
        }
        return out;
    }

    @GetMapping("/api/v1/chunks/by-id/{id}")
    public ChunkView getById(@PathVariable String id) {
        return toView(chunkService.getById(id));
    }

    @PutMapping("/api/v1/chunks/{knowledgeId}/{id}")
    public ChunkView update(@PathVariable String knowledgeId, @PathVariable String id,
                            @RequestBody UpdateChunkRequest req) {
        KbChunkRecord updated = chunkService.update(knowledgeId, id,
            req.content(), req.isEnabled());
        // ⚠️ 事务已提交 ⇒ 此时重建索引不会自锁
        if (req.content() != null) {
            try {
                chunkService.reindexChunkKnowledge(updated);
            } catch (RuntimeException e) {
                // 索引失败不否决编辑本身：内容已经改好了，用户可以再点「重建索引」。
                // ⚠️ 必须打 ERROR 让用户/运维知道「搜索还是旧的」。
                log.error("[分块API] 内容已更新但索引重建失败 chunk={} —— 搜索仍是旧文本", id, e);
            }
        }
        return toView(updated);
    }

    @DeleteMapping("/api/v1/chunks/{knowledgeId}/{id}")
    public DeleteResponse delete(@PathVariable String knowledgeId, @PathVariable String id) {
        chunkService.delete(knowledgeId, id);
        return new DeleteResponse(1);
    }

    @DeleteMapping("/api/v1/chunks/{knowledgeId}")
    public DeleteResponse deleteAll(@PathVariable String knowledgeId) {
        return new DeleteResponse(chunkService.deleteByKnowledge(knowledgeId));
    }

    @GetMapping("/api/v1/chunks/{knowledgeId}/{id}/revisions")
    public List<RevisionView> revisions(@PathVariable String knowledgeId, @PathVariable String id) {
        List<KbChunkRevisionRecord> rows = chunkService.listRevisions(knowledgeId, id);
        List<RevisionView> out = new ArrayList<>(rows.size());
        for (KbChunkRevisionRecord r : rows) {
            out.add(new RevisionView(r.getId(), r.getChunkId(), r.getKnowledgeId(),
                r.getKnowledgeBaseId(), r.getRevision(), r.getContent(),
                r.getIsEnabled() != null && r.getIsEnabled() != 0,
                r.getEditorId(), r.getEditSource(),
                KbChunkService.formatTime(r.getEditedAt()),
                KbChunkService.formatTime(r.getCreateTime())));
        }
        return out;
    }

    @PostMapping("/api/v1/chunks/{knowledgeId}/{id}/revert")
    public ChunkView revert(@PathVariable String knowledgeId, @PathVariable String id,
                            @RequestBody(required = false) RevertRequest req) {
        if (req == null || req.revision() == null) {
            throw new com.nexusai.infra.exception.ValidationException("revision 必填");
        }
        KbChunkRecord updated = chunkService.revert(knowledgeId, id, req.revision());
        try {
            chunkService.reindexChunkKnowledge(updated);
        } catch (RuntimeException e) {
            log.error("[分块API] 回滚成功但索引重建失败 chunk={} —— 搜索仍是旧文本", id, e);
        }
        return toView(updated);
    }

    // ── DTO ─────────────────────────────────────────────────────────

    private static ChunkView toView(KbChunkRecord r) {
        return new ChunkView(
            r.getId(), r.getKnowledgeId(), r.getKnowledgeBaseId(), r.getContent(),
            r.getChunkIndex(), r.getIsEnabled() != null && r.getIsEnabled() != 0,
            r.getStartAt(), r.getEndAt(), r.getPreChunkId(), r.getNextChunkId(),
            r.getParentChunkId(), r.getChunkType(), r.getImageInfo(),
            r.getContentRevision(), r.getIndexStatus(), r.getContextHeader(),
            r.getSourceLocators());
    }

    /** 分块出站视图（snake_case，对齐源侧 chunk 契约）。 */
    public record ChunkView(
        @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_id") String knowledgeId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @com.fasterxml.jackson.annotation.JsonProperty("content") String content,
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_index") Integer chunkIndex,
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") boolean isEnabled,
        @com.fasterxml.jackson.annotation.JsonProperty("start_at") Integer startAt,
        @com.fasterxml.jackson.annotation.JsonProperty("end_at") Integer endAt,
        @com.fasterxml.jackson.annotation.JsonProperty("pre_chunk_id") String preChunkId,
        @com.fasterxml.jackson.annotation.JsonProperty("next_chunk_id") String nextChunkId,
        @com.fasterxml.jackson.annotation.JsonProperty("parent_chunk_id") String parentChunkId,
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_type") String chunkType,
        @com.fasterxml.jackson.annotation.JsonProperty("image_info") String imageInfo,
        @com.fasterxml.jackson.annotation.JsonProperty("content_revision") Integer contentRevision,
        @com.fasterxml.jackson.annotation.JsonProperty("index_status") String indexStatus,
        @com.fasterxml.jackson.annotation.JsonProperty("context_header") String contextHeader,
        @com.fasterxml.jackson.annotation.JsonProperty("source_locators") String sourceLocators) {
    }

    /** 更新请求（null = 不改）。 */
    public record UpdateChunkRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("content") String content,
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") Boolean isEnabled) {
    }

    /** 回滚请求。 */
    public record RevertRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("revision") Integer revision) {
    }

    /** 删除结果。 */
    public record DeleteResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("deleted") int deleted) {
    }

    /** 版本视图。 */
    public record RevisionView(
        @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_id") String chunkId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_id") String knowledgeId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @com.fasterxml.jackson.annotation.JsonProperty("revision") Integer revision,
        @com.fasterxml.jackson.annotation.JsonProperty("content") String content,
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") boolean isEnabled,
        @com.fasterxml.jackson.annotation.JsonProperty("editor_id") String editorId,
        @com.fasterxml.jackson.annotation.JsonProperty("edit_source") String editSource,
        @com.fasterxml.jackson.annotation.JsonProperty("edited_at") String editedAt,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") String createdAt) {
    }
}
