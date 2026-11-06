package com.nexusai.kb.api;

import com.nexusai.kb.app.tag.KbTagService;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签端点 · 批次 W4（源侧「标签管理」4 个）+ W5（批量打标 1 个）。
 *
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/knowledge-bases/{id}/tags</td><td>标签列表（可带文档数统计）</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/tags</td><td>创建</td></tr>
 *   <tr><td>PUT</td><td>/knowledge-bases/{id}/tags/{tag_id}</td><td>更新</td></tr>
 *   <tr><td>DELETE</td><td>/knowledge-bases/{id}/tags/{tag_id}</td><td>删除（逻辑删 + 解除关联）</td></tr>
 *   <tr><td>PUT</td><td>/knowledge/tags</td><td>批量打标（<b>替换</b>语义）</td></tr>
 * </table>
 */
@RestController
public class TagController {

    private static final Logger log = LoggerFactory.getLogger(TagController.class);

    @Autowired
    private KbTagService tagService;

    @GetMapping("/api/v1/knowledge-bases/{id}/tags")
    public List<KbTagService.TagView> list(@PathVariable String id,
                                           @RequestParam(value = "with_stats", required = false)
                                           Boolean withStats) {
        return tagService.list(id, Boolean.TRUE.equals(withStats));
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/tags")
    public ResponseEntity<KbTagService.TagView> create(@PathVariable String id,
                                                       @RequestBody CreateTagRequest req) {
        KbTagService.TagView view = tagService.create(id, req.name(), req.color(), req.sortOrder());
        log.info("[标签API] POST /knowledge-bases/{}/tags → id={} name={}", id, view.id(), view.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/tags/{tagId}")
    public KbTagService.TagView update(@PathVariable String id, @PathVariable String tagId,
                                       @RequestBody UpdateTagRequest req) {
        return tagService.update(id, tagId, req.name(), req.color(), req.sortOrder());
    }

    @DeleteMapping("/api/v1/knowledge-bases/{id}/tags/{tagId}")
    public ResponseEntity<Void> delete(@PathVariable String id, @PathVariable String tagId) {
        tagService.delete(id, tagId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 批量打标。
     *
     * <p>⚠️ 替换语义：传进来的 {@code tag_ids} 就是最终集合。
     * 想「只加一个」也要把原有的全传上来 —— 否则未列出的一律被解除。
     * 这是源侧契约，前端必须知道（前端如有「加标签」按钮，要合并后再发）。
     */
    @PutMapping("/api/v1/knowledge/tags")
    public BatchTagResponse setTags(@RequestBody BatchTagRequest req) {
        if (req.tagIds() == null) {
            throw new ValidationException("tag_ids 必须显式提供（空数组 = 清除全部标签）");
        }
        int written = tagService.setTags(req.kbId(), req.knowledgeIds(), req.tagIds());
        log.info("[标签API] PUT /knowledge/tags → kb={} 文档={} 写入关联={}",
            req.kbId(), req.knowledgeIds() == null ? 0 : req.knowledgeIds().size(), written);
        return new BatchTagResponse(written);
    }

    // ── DTO ─────────────────────────────────────────────────────────

    public record CreateTagRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
        @com.fasterxml.jackson.annotation.JsonProperty("color") String color,
        @com.fasterxml.jackson.annotation.JsonProperty("sort_order") Integer sortOrder) {
    }

    public record UpdateTagRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
        @com.fasterxml.jackson.annotation.JsonProperty("color") String color,
        @com.fasterxml.jackson.annotation.JsonProperty("sort_order") Integer sortOrder) {
    }

    public record BatchTagRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("kb_id") String kbId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_ids") List<String> knowledgeIds,
        @com.fasterxml.jackson.annotation.JsonProperty("tag_ids") List<String> tagIds) {
    }

    public record BatchTagResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("written") int written) {
    }
}
