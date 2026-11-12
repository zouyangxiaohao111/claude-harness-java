package com.nexusai.kb.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.kb.api.dto.KnowledgeSpansDto;
import com.nexusai.kb.app.ingest.span.KbSpanTreeBuilder;
import com.nexusai.kb.repo.entity.KbProcessingSpanRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace 批 2 · span 树装配 + {@code /spans} 端点契约。
 *
 * <p><b>⛔ 不用 {@code @SpringBootTest}</b>（会 Flyway 迁移用户真库）：
 * 这里只测 {@link KbSpanTreeBuilder} 的纯逻辑（直接构造行对象）+ 反射读
 * {@link KnowledgeController#spans} 的路径注解。
 *
 * <p><b>⭐ 为什么断言这么细</b>：时间线的错都是静默的 —— 少一段占位、顺序错位、
 * 键名写成 camelCase，界面只会表现为「空白 / 转圈」，没有任何异常可查。
 */
@DisplayName("[R2-A] knowledge/{id}/spans · 树装配与端点契约")
class KnowledgeSpansEndpointTest {

    private static final String KID = "kb-doc-spans-1";
    private static final int ATTEMPT = 2;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 12, 0, 0);

    // ── 1. 无任何 span ─────────────────────────────────────────────

    @Test
    @DisplayName("1. 无任何 span ⇒ 五个 stage 全是 pending 占位")
    void emptyRowsSynthesizeFivePendingStages() {
        KbSpanTreeBuilder.Built built = KbSpanTreeBuilder.build(
            KID, ATTEMPT, List.of(), "processing", null, null, false, NOW);

        assertThat(built.trace().kind()).isEqualTo("root");
        assertThat(built.trace().name()).isEqualTo("knowledge_processing");
        assertThat(built.trace().status()).isEqualTo("pending");
        assertThat(stageNames(built)).containsExactlyElementsOf(KbSpanTreeBuilder.ALL_STAGES);
        assertThat(built.trace().children()).allSatisfy(node -> {
            assertThat(node.kind()).isEqualTo("stage");
            assertThat(node.status()).isEqualTo("pending");
            assertThat(node.spanId())
                .as("占位不是真行：没有 span_id、没有 started_at")
                .isNull();
            assertThat(node.startedAt()).isNull();
        });
    }

    // ── 2. 只写了 docreader ────────────────────────────────────────

    @Test
    @DisplayName("2. 只写了 docreader ⇒ 其余四个 pending，顺序不变")
    void missingStagesKeepCanonicalOrder() {
        List<KbProcessingSpanRecord> rows = List.of(
            row(1, "s-doc", null, "docreader", "stage", "done"));

        KbSpanTreeBuilder.Built built = KbSpanTreeBuilder.build(
            KID, ATTEMPT, rows, "processing", null, null, false, NOW);

        assertThat(stageNames(built)).containsExactlyElementsOf(KbSpanTreeBuilder.ALL_STAGES);
        assertThat(stageStatuses(built))
            .containsExactly("done", "pending", "pending", "pending", "pending");
        assertThat(built.currentStage()).isEqualTo("docreader");
    }

    // ── 3. 根节点的五段顺序固定（即使只记录了中间一段）────────────

    @Test
    @DisplayName("3. 只记录了 embedding ⇒ root 的 children 仍是 docreader/chunking/embedding/... 的固定顺序")
    void realStageSitsAtCanonicalPosition() {
        List<KbProcessingSpanRecord> rows = List.of(
            row(1, "s-root", null, "knowledge_processing", "root", "running"),
            row(2, "s-emb", "s-root", "embedding", "stage", "running"));

        KbSpanTreeBuilder.Built built = KbSpanTreeBuilder.build(
            KID, ATTEMPT, rows, "processing", null, null, false, NOW);

        assertThat(stageNames(built)).containsExactlyElementsOf(KbSpanTreeBuilder.ALL_STAGES);
        assertThat(built.currentStage()).isEqualTo("embedding");
        // 真实那段必须是库里的节点（不是占位）：有空 span_id 且状态 running
        KnowledgeSpansDto.SpanNode embedding = stage(built, "embedding");
        assertThat(embedding.spanId()).isEqualTo("s-emb");
        assertThat(embedding.status()).isEqualTo("running");
    }

    // ── 4. 子 span 挂在正确 stage 下 ──────────────────────────────

    @Test
    @DisplayName("4. 子 span 挂在正确 stage 下（parent_span_id 指向该 stage）")
    void subSpanAttachesToItsStage() {
        List<KbProcessingSpanRecord> rows = List.of(
            row(1, "s-root", null, "knowledge_processing", "root", "running"),
            row(2, "s-chunk", "s-root", "chunking", "stage", "done"),
            row(3, "s-sub", "s-chunk", "postprocess.summary", "subspan", "done"));

        KbSpanTreeBuilder.Built built = KbSpanTreeBuilder.build(
            KID, ATTEMPT, rows, "processing", null, null, false, NOW);

        KnowledgeSpansDto.SpanNode chunking = stage(built, "chunking");
        assertThat(chunking.children()).extracting(KnowledgeSpansDto.SpanNode::spanId)
            .containsExactly("s-sub");
        assertThat(stage(built, "docreader").children()).isEmpty();
    }

    // ── 5. last_error 取最晚失败 ──────────────────────────────────

    @Test
    @DisplayName("5. last_error 取「结束时间最晚」的失败 span（不是行序最后一条）")
    void lastErrorPicksLatestFailure() {
        KbProcessingSpanRecord early = row(1, "s-a", null, "chunking", "stage", "failed");
        early.setErrorCode("EARLY");
        early.setErrorMessage("早先的失败");
        early.setFinishedAt("2026-11-12 10:00:00");
        KbProcessingSpanRecord late = row(2, "s-b", null, "embedding", "stage", "failed");
        late.setErrorCode("LATE");
        late.setErrorMessage("最后的失败");
        late.setFinishedAt("2026-11-12 11:00:00");
        // 行序把「早」放在后面，确保实现不是「取最后一条」
        List<KbProcessingSpanRecord> rows = new ArrayList<>(List.of(late, early));

        KbSpanTreeBuilder.Built built = KbSpanTreeBuilder.build(
            KID, ATTEMPT, rows, "failed", "knowledge 级错误文案", null, false, NOW);

        assertThat(built.lastError()).isNotNull();
        assertThat(built.lastError().name()).isEqualTo("embedding");
        assertThat(built.lastError().errorCode()).isEqualTo("LATE");
        assertThat(built.lastError().finishedAt()).isEqualTo("2026-11-12 11:00:00");
    }

    // ── 6. JSON 键名 snake_case ───────────────────────────────────

    @Test
    @DisplayName("6. 出站 JSON 键名是 snake_case（knowledge_id / parent_span_id / duration_ms）")
    void jsonKeysAreSnakeCase() throws Exception {
        List<KbProcessingSpanRecord> rows = List.of(
            row(1, "s-root", null, "knowledge_processing", "root", "running"),
            row(2, "s-doc", "s-root", "docreader", "stage", "done"));
        KbSpanTreeBuilder.Built built = KbSpanTreeBuilder.build(
            KID, ATTEMPT, rows, "processing", null, null, false, NOW);
        KnowledgeSpansDto dto = new KnowledgeSpansDto(
            KID, ATTEMPT, ATTEMPT, "processing", ATTEMPT,
            built.currentStage(), built.trace(), built.lastActivityAt(),
            built.stallState(), built.lastError());

        String json = new ObjectMapper().writeValueAsString(dto);

        assertThat(json).contains("\"knowledge_id\"");
        assertThat(json).contains("\"latest_attempt\"");
        assertThat(json).contains("\"parse_status\"");
        assertThat(json).contains("\"current_attempt\"");
        assertThat(json).contains("\"parent_span_id\"");
        assertThat(json).contains("\"duration_ms\"");
        assertThat(json).doesNotContain("knowledgeId").doesNotContain("parentSpanId");
    }

    // ── 7. /spans 与 /stages 同一方法 ─────────────────────────────

    @Test
    @DisplayName("7. /spans 与 /stages 两个路径打到同一个 handler（源站 routes 同款）")
    void bothPathsHitSameHandler() throws Exception {
        Method method = KnowledgeController.class.getMethod("spans", String.class, Integer.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertThat(mapping).as("必须标在 spans(...) 上").isNotNull();
        assertThat(mapping.value()).containsExactlyInAnyOrder(
            "/api/v1/knowledge/{id}/spans", "/api/v1/knowledge/{id}/stages");
    }

    // ── 8. input/output 解析失败退化成字符串 ──────────────────────

    @Test
    @DisplayName("8. input/output 是脏 JSON ⇒ 退化成原字符串，不抛异常（端点不会 500）")
    void dirtyJsonDegradesToString() {
        KbProcessingSpanRecord row = row(1, "s-chunk", null, "chunking", "stage", "done");
        row.setInput("{这不是 JSON");
        row.setOutput("{\"chunks\": 12}");

        KbSpanTreeBuilder.Built built = KbSpanTreeBuilder.build(
            KID, ATTEMPT, List.of(row), "processing", null, null, false, NOW);

        KnowledgeSpansDto.SpanNode chunking = stage(built, "chunking");
        assertThat(chunking.input())
            .as("解析失败 ⇒ 原字符串（前端按字符串渲染）")
            .isEqualTo("{这不是 JSON");
        assertThat(chunking.output())
            .as("合法 JSON ⇒ 解析成对象（前端 prettyJSON 直接 stringify）")
            .isInstanceOf(java.util.Map.class);
    }

    // ── 9. stall_state（20 分钟阈值，与源站/前端同口径）────────────

    @Test
    @DisplayName("9. 在处理中且 20 分钟无活动 ⇒ 有排队任务给 queued，否则 stalled")
    void stallStateUsesTwentyMinuteThreshold() {
        KbProcessingSpanRecord old = row(1, "s-doc", null, "docreader", "stage", "running");
        old.setStartedAt("2026-11-12 10:30:00");
        old.setUpdateTime("2026-11-12 11:00:00");   // 60 分钟前
        List<KbProcessingSpanRecord> rows = List.of(old);

        KbSpanTreeBuilder.Built queued = KbSpanTreeBuilder.build(
            KID, ATTEMPT, rows, "processing", null, null, true, NOW);
        assertThat(queued.stallState()).isEqualTo("queued");
        assertThat(queued.lastActivityAt()).isEqualTo("2026-11-12 11:00:00");

        KbSpanTreeBuilder.Built stalled = KbSpanTreeBuilder.build(
            KID, ATTEMPT, rows, "processing", null, null, false, NOW);
        assertThat(stalled.stallState()).isEqualTo("stalled");

        // 终态文档不给 stall 判定
        KbSpanTreeBuilder.Built completed = KbSpanTreeBuilder.build(
            KID, ATTEMPT, rows, "completed", null, null, false, NOW);
        assertThat(completed.stallState()).isNull();

        // 未超过 20 分钟也不给判定
        KbProcessingSpanRecord fresh = row(2, "s-doc2", null, "docreader", "stage", "running");
        fresh.setUpdateTime("2026-11-12 11:50:00");  // 10 分钟前
        KbSpanTreeBuilder.Built notYet = KbSpanTreeBuilder.build(
            KID, ATTEMPT, List.of(fresh), "processing", null, null, false, NOW);
        assertThat(notYet.stallState()).isNull();
    }

    // ── 10. 老文档（无 span 行）按 parse_status 推断 ───────────────

    @Test
    @DisplayName("10. 老文档：completed ⇒ 五段全 done；failed ⇒ 五段 pending + last_error 兜底")
    void historicalDocsInferStageStatusFromParseStatus() {
        KbSpanTreeBuilder.Built done = KbSpanTreeBuilder.build(
            KID, 0, List.of(), "completed", null, "2026-11-01 09:00:00", false, NOW);
        assertThat(done.trace().status()).isEqualTo("done");
        assertThat(stageStatuses(done)).containsOnly("done");
        assertThat(done.lastError()).isNull();

        KbSpanTreeBuilder.Built failed = KbSpanTreeBuilder.build(
            KID, 0, List.of(), "failed", "解析器崩溃", "2026-11-01 09:00:00", false, NOW);
        assertThat(failed.trace().status())
            .as("失败阶段未知 ⇒ 五段 pending（派单 §2.6-3），不凭空标 failed")
            .isEqualTo("pending");
        assertThat(stageStatuses(failed)).containsOnly("pending");
        assertThat(failed.lastError()).isNotNull();
        assertThat(failed.lastError().errorMessage()).isEqualTo("解析器崩溃");
    }

    // ── 11. 有真实失败 stage 时，缺失的下游阶段标 cancelled ─────────

    @Test
    @DisplayName("11. 真实 chunking 失败 ⇒ 缺失的 embedding/multimodal/postprocess 标 cancelled")
    void downstreamMissingStagesAreCancelledAfterRealFailure() {
        KbProcessingSpanRecord doc = row(1, "s-doc", null, "docreader", "stage", "done");
        KbProcessingSpanRecord chunk = row(2, "s-chunk", null, "chunking", "stage", "failed");
        chunk.setErrorCode("CHUNK_FAILED");
        chunk.setFinishedAt("2026-11-12 11:59:00");

        KbSpanTreeBuilder.Built built = KbSpanTreeBuilder.build(
            KID, ATTEMPT, List.of(doc, chunk), "processing", null, null, false, NOW);

        assertThat(stage(built, "docreader").status()).isEqualTo("done");
        assertThat(stage(built, "chunking").status()).isEqualTo("failed");
        assertThat(stage(built, "embedding").status()).isEqualTo("cancelled");
        assertThat(stage(built, "multimodal").status()).isEqualTo("cancelled");
        assertThat(stage(built, "postprocess").status()).isEqualTo("cancelled");
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    private static KbProcessingSpanRecord row(long id, String spanId, String parentId,
                                              String name, String kind, String status) {
        KbProcessingSpanRecord row = new KbProcessingSpanRecord();
        row.setId(id);
        row.setKnowledgeId(KID);
        row.setAttempt(ATTEMPT);
        row.setSpanId(spanId);
        row.setParentSpanId(parentId);
        row.setName(name);
        row.setKind(kind);
        row.setStatus(status);
        row.setStartedAt("2026-11-12 11:30:00");
        row.setUpdateTime("2026-11-12 11:30:00");
        return row;
    }

    private static List<String> stageNames(KbSpanTreeBuilder.Built built) {
        return built.trace().children().stream()
            .filter(node -> "stage".equals(node.kind()))
            .map(KnowledgeSpansDto.SpanNode::name)
            .toList();
    }

    private static List<String> stageStatuses(KbSpanTreeBuilder.Built built) {
        return built.trace().children().stream()
            .filter(node -> "stage".equals(node.kind()))
            .map(KnowledgeSpansDto.SpanNode::status)
            .toList();
    }

    private static KnowledgeSpansDto.SpanNode stage(KbSpanTreeBuilder.Built built, String name) {
        return built.trace().children().stream()
            .filter(node -> name.equals(node.name()) && "stage".equals(node.kind()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("root 下没有阶段 " + name));
    }
}
