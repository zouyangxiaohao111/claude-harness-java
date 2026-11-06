package com.nexusai.kb.api;

import com.nexusai.kb.app.faq.KbFaqService;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FAQ 管理端点 · 批次 W5（源侧「FAQ管理」tag 共 14 个，全实现）。
 *
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/knowledge-bases/{id}/faq/entries</td><td>列表（分页/标签/关键词/启用过滤）</td></tr>
 *   <tr><td>GET</td><td>/knowledge-bases/{id}/faq/entries/{entry_id}</td><td>详情</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/faq/entry</td><td>创建单条</td></tr>
 *   <tr><td>PUT</td><td>/knowledge-bases/{id}/faq/entries/{entry_id}</td><td>更新（整体替换）</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/faq/entries</td><td>批量 upsert（append / replace）</td></tr>
 *   <tr><td>DELETE</td><td>/knowledge-bases/{id}/faq/entries</td><td>批量删除</td></tr>
 *   <tr><td>PATCH</td><td>/knowledge-bases/{id}/faq/entries</td><td>批量改状态/标签</td></tr>
 *   <tr><td>PUT</td><td>/knowledge-bases/{id}/faq/entries/fields</td><td>批量改字段（by_id / by_tag）</td></tr>
 *   <tr><td>PUT</td><td>/knowledge-bases/{id}/faq/entries/tags</td><td>批量改标签</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/faq/entries/{entry_id}/similar-questions</td><td>追相似问</td></tr>
 *   <tr><td>POST</td><td>/knowledge-bases/{id}/faq/search</td><td>语义搜索</td></tr>
 *   <tr><td>GET</td><td>/knowledge-bases/{id}/faq/entries/export</td><td>导出 CSV</td></tr>
 *   <tr><td>GET</td><td>/faq/import/progress/{task_id}</td><td>导入进度</td></tr>
 *   <tr><td>PUT</td><td>/knowledge-bases/{id}/faq/import/last-result/display</td><td>导入结果展示状态</td></tr>
 * </table>
 *
 * <h2>⭐⭐ 两个接口显式区分「没传」与「传了 false」</h2>
 * 列表的 {@code is_enabled}：不传 = 不过滤（含停用），传 {@code false} = 只要停用的。
 * 用 {@code Boolean}（对象）而不是 {@code boolean}，因为 {@code boolean} 的缺省 false
 * 会让「不过滤」变成「只返回停用条目」—— 这是最典型的「静默筛掉数据」。
 *
 * <h2>⚠️ 导入进度</h2>
 * 本仓的批量导入是<b>同步</b>完成的（不像源侧走排队），所以进度端点恒返回
 * {@code completed / 100}。保留端点只为前端契约兼容 —— ⛔ 不返回 404 让前端误判失败。
 */
@RestController
public class FaqController {

    private static final Logger log = LoggerFactory.getLogger(FaqController.class);

    @Autowired
    private KbFaqService faqService;

    // ── 列表 / 详情 ─────────────────────────────────────────────────

    @GetMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public ListResponse list(@PathVariable String id,
                             @RequestParam(value = "page", required = false) Integer page,
                             @RequestParam(value = "page_size", required = false) Integer pageSize,
                             @RequestParam(value = "tag_ids", required = false) String tagIds,
                             @RequestParam(value = "tag_id", required = false) Long tagId,
                             @RequestParam(value = "keyword", required = false) String keyword,
                             @RequestParam(value = "search_field", required = false) String searchField,
                             @RequestParam(value = "sort_order", required = false) String sortOrder,
                             @RequestParam(value = "is_enabled", required = false) Boolean isEnabled) {
        List<Long> seqIds = parseTagSeqIds(tagIds);
        // 兼容源侧的旧参数 tag_id（单个 seq_id）
        if (seqIds.isEmpty() && tagId != null && tagId > 0) {
            seqIds = List.of(tagId);
        }
        KbFaqService.Page p = faqService.list(id, page, pageSize, seqIds, keyword,
            searchField, sortOrder, isEnabled);
        return new ListResponse(p.data(), p.page(), p.pageSize(), p.total());
    }

    @GetMapping("/api/v1/knowledge-bases/{id}/faq/entries/{entryId}")
    public KbFaqService.Entry get(@PathVariable String id, @PathVariable long entryId) {
        return faqService.get(id, entryId);
    }

    // ── 创建 / 更新 ─────────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-bases/{id}/faq/entry")
    public KbFaqService.Entry create(@PathVariable String id,
                                     @RequestBody FaqEntryPayload req) {
        KbFaqService.Entry entry = faqService.create(id, req.toInput());
        log.info("[FAQ API] POST /faq/entry kb={} → seq_id={}", id, entry.id());
        return entry;
    }

    @PutMapping("/api/v1/knowledge-bases/{id}/faq/entries/{entryId}")
    public KbFaqService.Entry update(@PathVariable String id, @PathVariable long entryId,
                                     @RequestBody FaqEntryPayload req) {
        return faqService.update(id, entryId, req.toInput());
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/faq/entries/{entryId}/similar-questions")
    public KbFaqService.Entry addSimilarQuestions(@PathVariable String id, @PathVariable long entryId,
                                                  @RequestBody AddSimilarQuestionsRequest req) {
        return faqService.addSimilarQuestions(id, entryId, req.questions());
    }

    // ── 批量 ────────────────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public UpsertResponse upsert(@PathVariable String id, @RequestBody BatchUpsertRequest req) {
        if (req.entries() == null || req.entries().isEmpty()) {
            throw new ValidationException("entries 不能为空");
        }
        List<KbFaqService.EntryInput> inputs = new ArrayList<>(req.entries().size());
        for (FaqEntryPayload p : req.entries()) {
            inputs.add(p.toInput());
        }
        KbFaqService.ImportResult r = faqService.upsertBatch(id, inputs, req.mode());
        log.info("[FAQ API] POST /faq/entries kb={} 模式={} 成功={} 失败={}",
            id, req.mode(), r.createdSeqIds().size(), r.failed().size());
        return new UpsertResponse("import-" + System.currentTimeMillis(),
            r.createdSeqIds().size(), r.failed().size(), r.createdSeqIds());
    }

    @DeleteMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public DeleteResponse delete(@PathVariable String id, @RequestBody DeleteRequest req) {
        List<Long> ids = req.entryIds() != null ? req.entryIds() : req.ids();
        int n = faqService.delete(id, ids);
        return new DeleteResponse(n);
    }

    /**
     * 批量改状态与标签。
     *
     * <p>源侧 PATCH 的 body 形状：{@code {ids: [...], is_enabled: bool, is_recommended: bool, tag_id: n}}
     * ⇒ 对 ids 里的每一条同时改这三个字段。
     */
    @PatchMapping("/api/v1/knowledge-bases/{id}/faq/entries")
    public PatchResponse patch(@PathVariable String id, @RequestBody PatchRequest req) {
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new ValidationException("ids 不能为空");
        }
        Map<Long, KbFaqService.FieldUpdate> byId = new LinkedHashMap<>();
        KbFaqService.FieldUpdate update =
            new KbFaqService.FieldUpdate(req.isEnabled(), req.isRecommended(), req.tagId());
        for (Long entryId : req.ids()) {
            byId.put(entryId, update);
        }
        int updated = faqService.updateFieldsBatch(id, byId, null, null);
        return new PatchResponse(updated);
    }

    /**
     * 批量改字段（源侧形状：{@code {by_id: {...}, by_tag: {...}, exclude_ids: [...]}}）。
     *
     * <p>⚠️ JSON 的 map key 只能是字符串 ⇒ 这里用 {@code Map<String, ...>} 接收再转 Long。
     */
    @PutMapping("/api/v1/knowledge-bases/{id}/faq/entries/fields")
    public PatchResponse updateFields(@PathVariable String id,
                                      @RequestBody FieldsBatchRequest req) {
        Map<Long, KbFaqService.FieldUpdate> byId = toFieldMap(req.byId());
        Map<Long, KbFaqService.FieldUpdate> byTag = toFieldMap(req.byTag());
        int updated = faqService.updateFieldsBatch(id, byId, byTag, req.excludeIds());
        return new PatchResponse(updated);
    }

    /**
     * 批量改标签（源侧形状：{@code {entry_id: tag_seq_id}}，null = 解除标签）。
     */
    @PutMapping("/api/v1/knowledge-bases/{id}/faq/entries/tags")
    public PatchResponse updateTags(@PathVariable String id,
                                    @RequestBody Map<String, Long> updates) {
        Map<Long, Long> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : updates.entrySet()) {
            try {
                long entryId = Long.parseLong(e.getKey().trim());
                parsed.put(entryId, e.getValue() == null ? 0L : e.getValue());
            } catch (NumberFormatException ex) {
                throw new ValidationException("entry_id 必须是整数: " + e.getKey());
            }
        }
        return new PatchResponse(faqService.updateTagsBatch(id, parsed));
    }

    private static Map<Long, KbFaqService.FieldUpdate> toFieldMap(
        Map<String, FieldUpdatePayload> raw) {
        Map<Long, KbFaqService.FieldUpdate> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, FieldUpdatePayload> e : raw.entrySet()) {
            try {
                long key = Long.parseLong(e.getKey().trim());
                FieldUpdatePayload v = e.getValue();
                out.put(key, v == null ? null
                    : new KbFaqService.FieldUpdate(v.isEnabled(), v.isRecommended(), v.tagId()));
            } catch (NumberFormatException ex) {
                throw new ValidationException("键必须是整数 ID: " + e.getKey());
            }
        }
        return out;
    }

    // ── 搜索 ────────────────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-bases/{id}/faq/search")
    public List<KbFaqService.Entry> search(@PathVariable String id,
                                           @RequestBody SearchRequest req) {
        List<Long> first = req.firstPriorityTagIds();
        List<Long> second = req.secondPriorityTagIds();
        return faqService.search(id, req.queryText(), req.matchCount(),
            Boolean.TRUE.equals(req.onlyRecommended()), first, second);
    }

    // ── 导出 ────────────────────────────────────────────────────────

    /**
     * 导出 CSV。
     *
     * <p>列与源侧一致：{@code id,tag_name,standard_question,similar_questions,answers,answer_strategy}
     * —— 多值字段用 {@code |} 分隔（CSV 里不好放数组，且用户会在表格里编辑）。
     */
    @GetMapping("/api/v1/knowledge-bases/{id}/faq/entries/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        List<KbFaqService.Entry> entries = faqService.exportAll(id);
        StringBuilder sb = new StringBuilder();
        sb.append("id,tag_name,standard_question,similar_questions,negative_questions,")
            .append("answers,answer_strategy\n");
        for (KbFaqService.Entry e : entries) {
            sb.append(e.id()).append(',')
                .append(csv(e.tagName())).append(',')
                .append(csv(e.standardQuestion())).append(',')
                .append(csv(String.join("|", e.similarQuestions()))).append(',')
                .append(csv(String.join("|", e.negativeQuestions()))).append(',')
                .append(csv(String.join("|", e.answers()))).append(',')
                .append(csv(e.answerStrategy())).append('\n');
        }
        byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        log.info("[FAQ API] GET /faq/entries/export kb={} 条数={}", id, entries.size());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"faq-export.csv\"")
            .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
            .body(body);
    }

    // ── 导入进度 / 结果展示 ─────────────────────────────────────────

    /**
     * 导入进度。
     *
     * <p>⚠️ 本仓批量导入是同步的 ⇒ 恒返回 completed/100。
     * 保留端点是为了前端契约兼容（⛔ 不返回 404）。
     */
    @GetMapping("/api/v1/faq/import/progress/{taskId}")
    public ImportProgress progress(@PathVariable String taskId) {
        return new ImportProgress(taskId, null, null, "completed", 100,
            0, 0, 0, 0, 0, "append", null, 0L);
    }

    /**
     * 更新「最后一次导入结果」的展示状态（已读/未读）。
     *
     * <p>存在 {@code kb_knowledge.last_faq_import_result}（容器 knowledge 上）。
     */
    @PutMapping("/api/v1/knowledge-bases/{id}/faq/import/last-result/display")
    public DisplayStatusResponse updateDisplay(@PathVariable String id,
                                               @RequestBody DisplayStatusRequest req) {
        boolean displayed = faqService.markImportResultDisplayed(id, req.displayed());
        return new DisplayStatusResponse(displayed);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private static List<Long> parseTagSeqIds(String raw) {
        List<Long> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException e) {
                throw new ValidationException("tag_ids 必须是整数列表: " + raw);
            }
        }
        return out;
    }

    /** CSV 字段转义：含逗号/引号/换行 ⇒ 包引号并把引号翻倍。 */
    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ── 请求 / 响应 DTO ─────────────────────────────────────────────

    /** 单条 FAQ 入参（对齐源侧 {@code types.FAQEntryPayload}）。 */
    public record FaqEntryPayload(
        @com.fasterxml.jackson.annotation.JsonProperty("id") Long id,
        @com.fasterxml.jackson.annotation.JsonProperty("standard_question") String standardQuestion,
        @com.fasterxml.jackson.annotation.JsonProperty("similar_questions") List<String> similarQuestions,
        @com.fasterxml.jackson.annotation.JsonProperty("negative_questions") List<String> negativeQuestions,
        @com.fasterxml.jackson.annotation.JsonProperty("answers") List<String> answers,
        @com.fasterxml.jackson.annotation.JsonProperty("answer_strategy") String answerStrategy,
        @com.fasterxml.jackson.annotation.JsonProperty("tag_id") Long tagId,
        @com.fasterxml.jackson.annotation.JsonProperty("tag_name") String tagName,
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") Boolean isEnabled,
        @com.fasterxml.jackson.annotation.JsonProperty("is_recommended") Boolean isRecommended
    ) {
        KbFaqService.EntryInput toInput() {
            return new KbFaqService.EntryInput(standardQuestion, similarQuestions,
                negativeQuestions, answers, answerStrategy, tagId, tagName,
                isEnabled, isRecommended);
        }
    }

    public record AddSimilarQuestionsRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("questions") List<String> questions
    ) {
    }

    public record BatchUpsertRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("entries") List<FaqEntryPayload> entries,
        @com.fasterxml.jackson.annotation.JsonProperty("mode") String mode,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_id") String knowledgeId,
        @com.fasterxml.jackson.annotation.JsonProperty("task_id") String taskId,
        @com.fasterxml.jackson.annotation.JsonProperty("dry_run") Boolean dryRun
    ) {
    }

    public record UpsertResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("task_id") String taskId,
        @com.fasterxml.jackson.annotation.JsonProperty("success_count") int successCount,
        @com.fasterxml.jackson.annotation.JsonProperty("failed_count") int failedCount,
        @com.fasterxml.jackson.annotation.JsonProperty("created_ids") List<Long> createdIds
    ) {
    }

    public record DeleteRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("entry_ids") List<Long> entryIds,
        @com.fasterxml.jackson.annotation.JsonProperty("ids") List<Long> ids
    ) {
    }

    public record DeleteResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("deleted") int deleted
    ) {
    }

    /** PATCH 批量改状态/标签的请求。 */
    public record PatchRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("ids") List<Long> ids,
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") Boolean isEnabled,
        @com.fasterxml.jackson.annotation.JsonProperty("is_recommended") Boolean isRecommended,
        @com.fasterxml.jackson.annotation.JsonProperty("tag_id") Long tagId
    ) {
    }

    public record FieldUpdatePayload(
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") Boolean isEnabled,
        @com.fasterxml.jackson.annotation.JsonProperty("is_recommended") Boolean isRecommended,
        @com.fasterxml.jackson.annotation.JsonProperty("tag_id") Long tagId
    ) {
    }

    /** PUT fields 的请求（key 是 ID 的字符串形式 —— JSON map 的键只能是字符串）。 */
    public record FieldsBatchRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("by_id") Map<String, FieldUpdatePayload> byId,
        @com.fasterxml.jackson.annotation.JsonProperty("by_tag") Map<String, FieldUpdatePayload> byTag,
        @com.fasterxml.jackson.annotation.JsonProperty("exclude_ids") List<Long> excludeIds
    ) {
    }

    public record PatchResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("updated") int updated
    ) {
    }

    public record SearchRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("query_text") String queryText,
        @com.fasterxml.jackson.annotation.JsonProperty("vector_threshold") Double vectorThreshold,
        @com.fasterxml.jackson.annotation.JsonProperty("match_count") Integer matchCount,
        @com.fasterxml.jackson.annotation.JsonProperty("first_priority_tag_ids") List<Long> firstPriorityTagIds,
        @com.fasterxml.jackson.annotation.JsonProperty("second_priority_tag_ids") List<Long> secondPriorityTagIds,
        @com.fasterxml.jackson.annotation.JsonProperty("only_recommended") Boolean onlyRecommended
    ) {
    }

    /** 列表响应（源侧 {success, data: PageResult}）。 */
    public record ListResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("data") List<KbFaqService.Entry> data,
        @com.fasterxml.jackson.annotation.JsonProperty("page") int page,
        @com.fasterxml.jackson.annotation.JsonProperty("page_size") int pageSize,
        @com.fasterxml.jackson.annotation.JsonProperty("total") int total
    ) {
    }

    public record ImportProgress(
        @com.fasterxml.jackson.annotation.JsonProperty("task_id") String taskId,
        @com.fasterxml.jackson.annotation.JsonProperty("kb_id") String kbId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_id") String knowledgeId,
        @com.fasterxml.jackson.annotation.JsonProperty("status") String status,
        @com.fasterxml.jackson.annotation.JsonProperty("progress") int progress,
        @com.fasterxml.jackson.annotation.JsonProperty("total") int total,
        @com.fasterxml.jackson.annotation.JsonProperty("processed") int processed,
        @com.fasterxml.jackson.annotation.JsonProperty("success_count") int successCount,
        @com.fasterxml.jackson.annotation.JsonProperty("failed_count") int failedCount,
        @com.fasterxml.jackson.annotation.JsonProperty("skipped_count") int skippedCount,
        @com.fasterxml.jackson.annotation.JsonProperty("import_mode") String importMode,
        @com.fasterxml.jackson.annotation.JsonProperty("imported_at") String importedAt,
        @com.fasterxml.jackson.annotation.JsonProperty("processing_time_ms") long processingTimeMs
    ) {
    }

    public record DisplayStatusRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("displayed") Boolean displayed
    ) {
    }

    public record DisplayStatusResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("displayed") boolean displayed
    ) {
    }
}
