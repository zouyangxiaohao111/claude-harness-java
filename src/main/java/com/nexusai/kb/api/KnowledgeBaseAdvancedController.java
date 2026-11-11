package com.nexusai.kb.api;

import com.nexusai.kb.app.copy.KbCopyService;
import com.nexusai.kb.app.image.KbImageService;
import com.nexusai.kb.app.kb.KbAdvancedService;
import com.nexusai.kb.app.profile.KbProfileService;
import com.nexusai.kb.domain.KbActivityService;
import com.nexusai.kb.dto.KnowledgeBaseDto;
import com.nexusai.kb.domain.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库高级端点 · 批次 W4（12 个）。
 *
 * <p><b>源侧对照</b>：WeKnora {@code routes_knowledge.go} 第 231–320 行。
 *
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>PUT</td><td>/knowledge-bases/{id}/pin</td><td>置顶（body 无参 = 置顶？不，是 toggle）</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/copy</td><td>复制进已有库（异步，返 task_id）</td></tr>
 *   <tr><td>GET</td><td>/knowledge-bases/copy/progress/{task_id}</td><td>复制进度</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/duplicate</td><td>另存为（同步）</td></tr>
 *   <tr><td>GET</td><td>/knowledge-bases/{id}/move-targets</td><td>可移动目标</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/rebuild-index</td><td>重建索引</td></tr>
 *   <tr><td>DELETE</td><td>/knowledge-bases/{id}/knowledge</td><td>清空内容</td></tr>
 *   <tr><td>GET</td><td>/knowledge-bases/{id}/activity</td><td>活动流</td></tr>
 *   <tr><td>GET/POST</td><td>/knowledge-bases/{id}/images</td><td>图片资产</td></tr>
 *   <tr><td>GET</td><td>/image-attrs/schema</td><td>图片属性字典</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/profile/generate</td><td>生成画像</td></tr>
 *   <tr><td>POST</td><td>/knowledge/move</td><td>文档移动</td></tr>
 *   <tr><td>GET</td><td>/knowledge/move/progress/{task_id}</td><td>移动进度（本仓同步完成 ⇒ 恒 100）</td></tr>
 * </table>
 *
 * <h2>⚠️ 置顶是 toggle，不是「设为 true」</h2>
 * 源侧 {@code PUT /{id}/pin} 没有 body，语义是<b>切换</b>。前端点一次置顶、再点取消。
 * ⇒ 本端点在服务端读当前值再翻转（不是幂等 PUT 的语义，但这是源侧契约）。
 */
@RestController
public class KnowledgeBaseAdvancedController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseAdvancedController.class);

    @Autowired
    private KbAdvancedService advancedService;

    @Autowired
    private KbCopyService copyService;

    @Autowired
    private KbProfileService profileService;

    @Autowired
    private KbActivityService activityService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    // ── 置顶 ────────────────────────────────────────────────────────

    @PutMapping("/api/v1/knowledge-bases/{id}/pin")
    public KnowledgeBaseDto togglePin(@PathVariable String id) {
        // 读出当前值再翻转（源侧无 body，是 toggle 语义）
        KnowledgeBaseDto current = knowledgeBaseService.getById(id);
        boolean next = !current.isPinned();
        advancedService.setPinned(id, next);
        log.info("[知识API] PUT /knowledge-bases/{}/pin → {}", id, next ? "置顶" : "取消");
        return knowledgeBaseService.getById(id);
    }

    // ── 复制 ────────────────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-bases/copy")
    public CopyResponse copy(@RequestBody CopyRequest req) {
        String taskId = copyService.copy(req.sourceId(), req.targetId(), req.taskId());
        return new CopyResponse("复制任务已创建", req.sourceId(), req.targetId(), taskId);
    }

    @GetMapping("/api/v1/knowledge-bases/copy/progress/{taskId}")
    public CopyProgressResponse copyProgress(@PathVariable String taskId) {
        KbCopyService.Progress p = copyService.progress(taskId);
        return new CopyProgressResponse(p.taskId(), p.sourceId(), p.targetId(),
            p.total(), p.done(), p.failed(), p.status(), p.percent(), p.error());
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/duplicate")
    public KnowledgeBaseDto duplicate(@PathVariable String id,
                                      @RequestBody(required = false) DuplicateRequest req) {
        String name = req == null ? null : req.name();
        var record = copyService.duplicate(id, name);
        log.info("[知识API] POST /knowledge-bases/{}/duplicate → 新库 {}", id, record.getId());
        return knowledgeBaseService.getById(record.getId());
    }

    // ── 移动 ────────────────────────────────────────────────────────

    @GetMapping("/api/v1/knowledge-bases/{id}/move-targets")
    public List<KbAdvancedService.MoveTarget> moveTargets(@PathVariable String id) {
        return advancedService.moveTargets(id);
    }

    @PostMapping("/api/v1/knowledge/move")
    public MoveResponse move(@RequestBody MoveRequest req) {
        int moved = advancedService.move(req.sourceKbId(), req.knowledgeIds(), req.targetKbId());
        return new MoveResponse(moved, moved);
    }

    /**
     * 移动进度。
     *
     * <p>⚠️ 与源侧的差异：源侧移动是异步任务（Redis 队列），本仓是同步完成。
     * 端点保留（前端可能轮询），恒返回 completed/100 —— ⛔ 不返回 404 让前端误判失败。
     */
    @GetMapping("/api/v1/knowledge/move/progress/{taskId}")
    public CopyProgressResponse moveProgress(@PathVariable String taskId) {
        return new CopyProgressResponse(taskId, "", "", 0, 0, 0, "completed", 100, null);
    }

    // ── 重建索引 / 清空 ──────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-bases/{id}/rebuild-index")
    public RebuildResponse rebuildIndex(@PathVariable String id) {
        KbAdvancedService.RebuildResult r = advancedService.rebuildIndex(id);
        log.info("[知识API] POST /knowledge-bases/{}/rebuild-index → 成功={} 失败={}",
            id, r.succeeded(), r.failed());
        return new RebuildResponse(r.succeeded(), r.failed(), r.failedIds());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/knowledge-bases/{id}/knowledge")
    public ClearResponse clearKnowledge(@PathVariable String id) {
        KbAdvancedService.ClearResult r = advancedService.clearContents(id);
        log.info("[知识API] DELETE /knowledge-bases/{}/knowledge → 删文档={} 分块={}",
            id, r.documents(), r.chunks());
        return new ClearResponse(r.documents(), r.chunks());
    }

    // ── 活动流 ──────────────────────────────────────────────────────

    @GetMapping("/api/v1/knowledge-bases/{id}/activity")
    public ActivityPage activity(@PathVariable String id,
                                 @RequestParam(value = "after_id", required = false) Long afterId,
                                 @RequestParam(value = "limit", required = false) Integer limit,
                                 @RequestParam(value = "action", required = false) String action,
                                 @RequestParam(value = "outcome", required = false) String outcome,
                                 @RequestParam(value = "actor", required = false) String actor) {
        var rows = activityService.list(id, afterId, limit, action, outcome, actor);
        List<ActivityItem> items = new ArrayList<>(rows.size());
        for (var r : rows) {
            items.add(new ActivityItem(
                r.getId(), r.getAction(), r.getOutcome(), r.getActorUserId(), r.getActorRole(),
                r.getScopeType(), r.getScopeId(), r.getTargetType(), r.getTargetId(),
                r.getTargetUserId(), r.getRequestMethod(), r.getRequestPath(), r.getDetails(),
                KbActivityService.formatTime(r.getCreatedAt())));
        }
        return new ActivityPage(items);
    }

    // ── 图片 ────────────────────────────────────────────────────────

    @GetMapping("/api/v1/knowledge-bases/{id}/images")
    public ImagePage images(@PathVariable String id,
                            @RequestParam(value = "page", required = false) Integer page,
                            @RequestParam(value = "page_size", required = false) Integer pageSize,
                            @RequestParam(value = "keyword", required = false) String keyword,
                            @RequestParam(value = "search_in", required = false) String searchIn,
                            @RequestParam(value = "sort_by", required = false) String sortBy,
                            @RequestParam(value = "sort_order", required = false) String sortOrder,
                            @RequestParam(value = "is_enabled", required = false) Boolean isEnabled,
                            @RequestParam(value = "knowledge_ids", required = false) String knowledgeIds,
                            @RequestParam(value = "attr_filters", required = false) String attrFilters) {
        KbImageService.Page p = advancedService.images().list(id, page, pageSize, keyword, searchIn,
            sortBy, sortOrder, isEnabled, knowledgeIds, attrFilters);
        return new ImagePage(p.data(), p.page(), p.pageSize(), p.total());
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/images")
    public ImagePage imagesPost(@PathVariable String id,
                                @RequestBody(required = false) ImageListRequest req) {
        KbImageService.Page p = advancedService.images().list(id,
            req == null ? null : req.page(),
            req == null ? null : req.pageSize(),
            req == null ? null : req.keyword(),
            req == null ? null : req.searchIn(),
            req == null ? null : req.sortBy(),
            req == null ? null : req.sortOrder(),
            req == null ? null : req.isEnabled(),
            req == null ? null : req.knowledgeIds(),
            req == null ? null : req.attrFilters());
        return new ImagePage(p.data(), p.page(), p.pageSize(), p.total());
    }

    @GetMapping("/api/v1/image-attrs/schema")
    public KbImageService.AttrSchema imageAttrSchema() {
        return advancedService.images().attrSchema();
    }

    // ── 画像 ────────────────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-bases/{id}/profile/generate")
    public ProfileResponse generateProfile(@PathVariable String id,
                                           @RequestBody(required = false) ProfileRequest req) {
        KbProfileService.Result r = profileService.generate(id, req == null ? null : req.model());
        log.info("[知识API] POST /knowledge-bases/{}/profile/generate → status={} 文档数={}",
            id, r.status(), r.documentCount());
        return new ProfileResponse(r.profile(), r.status(), r.documentCount(), r.model());
    }

    // ── 请求 / 响应 DTO（snake_case，逐字段钉死）─────────────────────

    /** 复制请求。 */
    public record CopyRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("source_id") String sourceId,
        @com.fasterxml.jackson.annotation.JsonProperty("target_id") String targetId,
        @com.fasterxml.jackson.annotation.JsonProperty("task_id") String taskId) {
    }

    /** 复制响应。 */
    public record CopyResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("message") String message,
        @com.fasterxml.jackson.annotation.JsonProperty("source_id") String sourceId,
        @com.fasterxml.jackson.annotation.JsonProperty("target_id") String targetId,
        @com.fasterxml.jackson.annotation.JsonProperty("task_id") String taskId) {
    }

    /** 复制进度。 */
    public record CopyProgressResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("task_id") String taskId,
        @com.fasterxml.jackson.annotation.JsonProperty("source_id") String sourceId,
        @com.fasterxml.jackson.annotation.JsonProperty("target_id") String targetId,
        @com.fasterxml.jackson.annotation.JsonProperty("total") int total,
        @com.fasterxml.jackson.annotation.JsonProperty("done") int done,
        @com.fasterxml.jackson.annotation.JsonProperty("failed") int failed,
        @com.fasterxml.jackson.annotation.JsonProperty("status") String status,
        @com.fasterxml.jackson.annotation.JsonProperty("percent") int percent,
        @com.fasterxml.jackson.annotation.JsonProperty("error") String error) {
    }

    /** 副本请求。 */
    public record DuplicateRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("name") String name) {
    }

    /** 移动请求。 */
    public record MoveRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("source_kb_id") String sourceKbId,
        @com.fasterxml.jackson.annotation.JsonProperty("target_kb_id") String targetKbId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_ids") List<String> knowledgeIds) {
    }

    /** 移动响应。 */
    public record MoveResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("moved") int moved,
        @com.fasterxml.jackson.annotation.JsonProperty("total") int total) {
    }

    /** 重建索引响应。 */
    public record RebuildResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("succeeded") int succeeded,
        @com.fasterxml.jackson.annotation.JsonProperty("failed") int failed,
        @com.fasterxml.jackson.annotation.JsonProperty("failed_ids") List<String> failedIds) {
    }

    /** 清空响应。 */
    public record ClearResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("documents") int documents,
        @com.fasterxml.jackson.annotation.JsonProperty("chunks") int chunks) {
    }

    /** 活动流条目。 */
    public record ActivityItem(
        @com.fasterxml.jackson.annotation.JsonProperty("id") Long id,
        @com.fasterxml.jackson.annotation.JsonProperty("action") String action,
        @com.fasterxml.jackson.annotation.JsonProperty("outcome") String outcome,
        @com.fasterxml.jackson.annotation.JsonProperty("actor_user_id") String actorUserId,
        @com.fasterxml.jackson.annotation.JsonProperty("actor_role") String actorRole,
        @com.fasterxml.jackson.annotation.JsonProperty("scope_type") String scopeType,
        @com.fasterxml.jackson.annotation.JsonProperty("scope_id") String scopeId,
        @com.fasterxml.jackson.annotation.JsonProperty("target_type") String targetType,
        @com.fasterxml.jackson.annotation.JsonProperty("target_id") String targetId,
        @com.fasterxml.jackson.annotation.JsonProperty("target_user_id") String targetUserId,
        @com.fasterxml.jackson.annotation.JsonProperty("request_method") String requestMethod,
        @com.fasterxml.jackson.annotation.JsonProperty("request_path") String requestPath,
        @com.fasterxml.jackson.annotation.JsonProperty("details") String details,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") String createdAt) {
    }

    /** 活动流分页（源侧形状：{@code {data, ...}}）。 */
    public record ActivityPage(
        @com.fasterxml.jackson.annotation.JsonProperty("data") List<ActivityItem> data) {
    }

    /** 图片列表 body 版请求。 */
    public record ImageListRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("page") Integer page,
        @com.fasterxml.jackson.annotation.JsonProperty("page_size") Integer pageSize,
        @com.fasterxml.jackson.annotation.JsonProperty("keyword") String keyword,
        @com.fasterxml.jackson.annotation.JsonProperty("search_in") String searchIn,
        @com.fasterxml.jackson.annotation.JsonProperty("sort_by") String sortBy,
        @com.fasterxml.jackson.annotation.JsonProperty("sort_order") String sortOrder,
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") Boolean isEnabled,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_ids") String knowledgeIds,
        @com.fasterxml.jackson.annotation.JsonProperty("attr_filters") String attrFilters) {
    }

    /** 图片分页响应（源侧 PageResponse 形状）。 */
    public record ImagePage(
        @com.fasterxml.jackson.annotation.JsonProperty("data") List<KbImageService.ImageAsset> data,
        @com.fasterxml.jackson.annotation.JsonProperty("page") int page,
        @com.fasterxml.jackson.annotation.JsonProperty("page_size") int pageSize,
        @com.fasterxml.jackson.annotation.JsonProperty("total") int total) {
    }

    /** 画像请求。 */
    public record ProfileRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("model") String model) {
    }

    /**
     * 画像响应。
     *
     * <p>⚠️ 2026-11-11：{@code profile} 从 {@code String} 改成
     * {@code KbKnowledgeBaseProfile} 对象 —— 前端早就按对象读（{@code r.profile?.gist}），
     * 后端返字符串 ⇒ 契约错配。对象形态同时与源站 {@code KnowledgeBaseProfile} 对齐。
     */
    public record ProfileResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("profile")
        com.nexusai.kb.app.profile.KbKnowledgeBaseProfile profile,
        @com.fasterxml.jackson.annotation.JsonProperty("status") String status,
        @com.fasterxml.jackson.annotation.JsonProperty("document_count") int documentCount,
        @com.fasterxml.jackson.annotation.JsonProperty("model") String model) {
    }
}
