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
 * <p>⚠️ 生成问题三个端点（源侧 {@code /chunks/by-id/{id}/questions}）：
 * <b>DELETE</b> / <b>PUT</b> / <b>POST …/regenerate</b> 均已实现。
 * <ul>
 *   <li>PUT：手写新增或修改一条 —— 本仓问句<b>没有独立 id</b>，
 *       {@code question_id} 空 = 新增，非空 = <b>旧问句文本</b>（原地替换）。</li>
 *   <li>POST regenerate：复用入库后处理的问题生成服务，对该分块单独重跑一轮。</li>
 * </ul>
 *
 * <p>⚠️ <b>出站字段现状（18 个）</b>：{@code metadata} 与 {@code source_locators} 都是
 * <b>原始 JSON 字符串原样透传</b>（⛔ 不解析、不裁剪）。前者承载
 * {@code generated_questions[].question}（形态见 {@code KbGeneratedQuestions}），
 * 后者承载 {@code KbSourceLocator} 数组（7 种 type）。
 * {@code metadata} 是本批新增出站字段（此前只落库、不出站 ⇒ 前端按钮只能弹「未接入」）。
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

    /**
     * 新增 / 修改一条生成问句（源侧 {@code PUT /chunks/by-id/{id}/questions}）。
     *
     * <p><b>⭐⭐ 本仓差异</b>：源侧每条问句有 {@code uuid}，本仓存在
     * {@code kb_chunk.metadata.generated_questions} 里、<b>没有 id</b>
     * ⇒ {@code question_id} 的语义改为：<b>空 = 新增；非空 = 旧问句文本（原地替换）</b>。
     * 找不到该文本 ⇒ 400（⛔ 不静默新增，否则用户以为改掉了、实际多出一条）。
     *
     * <p>写入后由 service 重建索引（问句会拼进索引文本，见 {@code KbGeneratedQuestions.indexText}）。
     * 索引失败<b>不否决保存</b>，但响应里 {@code index_rebuilt=false} 并打 ERROR。
     *
     * @param id   分块 ID
     * @param req  {@code {"question_id": "<旧问句文本，可空>", "question": "<新问句>"}}
     * @return 更新后的分块 + 最新问句列表
     */
    @PutMapping("/api/v1/chunks/by-id/{id}/questions")
    public QuestionUpsertResponse upsertGeneratedQuestion(
            @PathVariable String id,
            @RequestBody(required = false) UpsertQuestionRequest req) {
        if (req == null) {
            throw new com.nexusai.infra.exception.ValidationException("question 必填");
        }
        KbChunkService.QuestionUpsertResult r =
            chunkService.upsertGeneratedQuestion(id, req.questionId(), req.question());
        return new QuestionUpsertResponse(
            toView(r.chunk()), r.added(), r.questions(), r.indexRebuilt());
    }

    /**
     * 重新生成某分块的辅助问句（源侧 {@code POST /chunks/by-id/{id}/questions/regenerate}）。
     *
     * <p>复用 {@code KbQuestionGenerationService#generateBatch}（与入库后处理同一套：
     * 读知识库问题配置、调模型、写回 metadata、重建索引）。
     *
     * <p>⚠️ {@code status} 可能不是 {@code DONE}：问句配置关闭 / 无可用模型 / 分块无正文时
     * 返回 {@code SKIPPED} 并带原因 ⇒ 前端必须照实展示，⛔ 不能说「已重新生成」。
     */
    @PostMapping("/api/v1/chunks/by-id/{id}/questions/regenerate")
    public QuestionRegenerateResponse regenerateGeneratedQuestions(@PathVariable String id) {
        KbChunkService.RegenerateResult r = chunkService.regenerateGeneratedQuestions(id);
        return new QuestionRegenerateResponse(
            toView(r.chunk()), r.questions(), r.status(), r.detail());
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
            r.getSourceLocators(), r.getMetadata());
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
        @com.fasterxml.jackson.annotation.JsonProperty("source_locators") String sourceLocators,
        /**
         * 分块元数据原文（JSON 字符串，原样透传，⛔ 不在 controller 里解析/裁剪）。
         *
         * <p>前端「辅助召回问题」按钮要读 {@code generated_questions[].question}
         * （形态见 {@code KbGeneratedQuestions} 类注释）。此前本字段未出站 ⇒ 前端拿不到，
         * 按钮只能弹「未接入」。此处仅新增透传，不改变其它 17 个字段的位置与语义。
         */
        @com.fasterxml.jackson.annotation.JsonProperty("metadata") String metadata) {
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

    /**
     * 问句 upsert 请求（源侧 {@code UpsertGeneratedQuestionRequest} 同形）。
     *
     * @param questionId 空 = 新增；非空 = 要替换的<b>旧问句文本</b>（本仓问句无独立 id）
     * @param question   新问句（trim 后不能为空）
     */
    public record UpsertQuestionRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("question_id") String questionId,
        @com.fasterxml.jackson.annotation.JsonProperty("question") String question) {
    }

    /** 问句 upsert 响应。 */
    public record QuestionUpsertResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("chunk") ChunkView chunk,
        @com.fasterxml.jackson.annotation.JsonProperty("added") boolean added,
        @com.fasterxml.jackson.annotation.JsonProperty("questions") List<String> questions,
        /**
         * 索引是否已重建。false ⇒ 问句已保存但<b>搜索仍是旧文本</b>（服务端已打 ERROR）。
         * ⚠️ 前端要能显示这一状态，⛔ 不要笼统说「已保存」。
         */
        @com.fasterxml.jackson.annotation.JsonProperty("index_rebuilt") boolean indexRebuilt) {
    }

    /** 问句重新生成响应。 */
    public record QuestionRegenerateResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("chunk") ChunkView chunk,
        @com.fasterxml.jackson.annotation.JsonProperty("questions") List<String> questions,
        /** DONE / SKIPPED / SUPERSEDED / FAILED（见 {@code KbPostProcessOutcome.Status}）。 */
        @com.fasterxml.jackson.annotation.JsonProperty("status") String status,
        /** 人可读原因（SKIPPED/FAILED 时有值，用于如实告知用户）。 */
        @com.fasterxml.jackson.annotation.JsonProperty("detail") String detail) {
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
