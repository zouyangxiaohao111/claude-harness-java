package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [D4 解法1-精简 · 2026-09-12 用户裁定] 保留段<b>读侧重挂</b>验收
 * （{@code BoundaryReader.applyPreservedSegmentRelink}）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：kept 段（部分压缩保留的老消息）在本仓的
 * 写侧已改为<b>零改写</b> —— 它留在 boundary <b>之前</b>的原始 seq 位置（对齐 CC
 * {@code recordTranscript} 按 uuid dedup 跳过、磁盘上原地不动）。因此「模型面能看到 kept」这件事
 * <b>100% 依赖读侧重挂</b>：读侧一旦不重挂，kept 会被 boundary 切片整段丢掉 ——
 * 模型静默失去保留段上下文，且不报错、不失败任何"正常"断言。本测试就是这道保护。
 *
 * <p><b>CC 对照（实际 TS 源码行为，非注释）</b>：
 * {@code utils/sessionStorage.ts:1876-1992 applyPreservedSegmentRelinks}（唯一调用点 {@code :3808}
 * = {@code loadTranscriptFile} 内，即<b>读侧</b>）做两件事：
 * <pre>
 *   head.parentUuid = anchorUuid                     // :1935-1940 保留段接到锚点之后
 *   anchor 的其它 children → parentUuid = tailUuid   // :1942-1948 锚点原有后续内容挪到保留段之后
 * </pre>
 * 本仓无 {@code parentUuid} 链，顺序权威是 {@code seq} → 等价实现 = 切片后把「boundary 之前、
 * 且在 {@code [headUuid..tailUuid]} 区间内」的行按原 seq 序拼到 {@code anchorUuid} 之后。
 *
 * <p><b>方向无关性（本测试的核心断言）</b>：CC 的 {@code anchorUuid} = 「新链里紧邻 keep[0] 之前那条」
 * （{@code compact.ts:371-380}）——up_to/SM 方向 = 最后一条 summary，from 方向 = boundary 本身
 * （{@code compact.ts:1112-1115}）。故同一套拼接逻辑天然产出两个方向各自的正确顺序，
 * 且与压缩当轮的内存数组（{@code CompactionResult.buildPartialPostCompactMessages} 的
 * direction-aware 重组，{@code REPL.tsx:4950-4951}）<b>逐项一致</b>——见
 * {@link #relinkedView_matchesInMemoryPartialOrder_forBothDirections()}。
 *
 * <p><b>RED teeth（变异验证，隔离副本实测）</b>：
 * <ul>
 *   <li>回退重挂（去掉 {@code sliced = applyPreservedSegmentRelink(...)} 一行）→
 *       up_to / from / 内存序等价 / snip 组合 四组断言全红（kept 从视图消失）；</li>
 *   <li>把写侧 {@code UPDATE seq} 加回去（kept 物理回到 boundary 之后）→
 *       本类退化为 no-op 仍绿，但 {@code MessageServiceAppendPostCompactTest} 的
 *       {@code never()).update} / dedup 断言红 —— 两侧互为对方变异验证的牙齿。</li>
 * </ul>
 */
@DisplayName("[D4 解法1-精简] preservedSegment 读侧重挂（kept 从 boundary 之前接回 anchor 之后）")
class BoundaryReaderPreservedSegmentRelinkTest {

    private static final String SESSION = "sess-relink";

    // ════════════════════════════════════════════════════════════════════
    // 1. up_to / SM 方向：anchor = 最后一条 summary → [boundary, summary, kept...]
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("up_to/SM（后缀保留）：anchor=summary → 视图 = boundary → summary → kept(原 seq 序) → 后续")
    void upToDirection_keptSplicedAfterSummary() {
        // GIVEN: 物理序（seq）= 被摘要段(m1,m2) → kept(m3,m4) → boundary → summary → 新消息(n1)
        //   —— 这正是「写侧零改写」之后的真实 DB 形态（kept 留在 boundary 之前）
        ChatMessageDto summary = summary("sum-1");
        CompactBoundaryMessage annotated = annotate(
            summary.id(), // up_to/SM 方向 anchor = 最后一条 summary（compact.ts:1079-1080）
            List.of(msg("m3"), msg("m4")));
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            msg("m1"), msg("m2"), msg("m3"), msg("m4"),
            annotated.toChatMessageDto(), summary, msg("n1")));

        // WHEN
        List<ChatMessageDto> view = BoundaryReader.getMessagesAfterCompactBoundary(raw);

        // THEN: kept(m3,m4) 按原 seq 序接在 anchor(summary) 之后；被摘要段(m1,m2)仍不可见
        assertThat(ids(view))
            .as("up_to：boundary → summary → kept(m3,m4) → n1（kept 按原 seq 序，非按 id 字典序）")
            .containsExactly(annotated.toChatMessageDto().id(), "sum-1", "m3", "m4", "n1");
        assertThat(ids(view))
            .as("被摘要掉的前缀（m1/m2）不进模型视图（boundary 切片语义）")
            .doesNotContain("m1", "m2");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. from 方向：anchor = boundary 自身 → [boundary, kept..., summary]
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("from（前缀保留）：anchor=boundary → 视图 = boundary → kept(原 seq 序) → summary → 后续")
    void fromDirection_keptSplicedRightAfterBoundary() {
        // GIVEN: 物理序 = kept(m1,m2) → boundary → summary → n1（from 方向保留前缀）
        CompactBoundaryMessage boundary = newBoundary();
        String boundaryId = boundary.toChatMessageDto().id();
        // CC compact.ts:1112-1115：from 方向 anchorUuid = boundaryMarker.uuid()（保留段紧接 boundary）
        CompactBoundaryMessage annotated = CompactBoundaryMessage.annotateBoundaryWithPreservedSegment(
            boundary, boundaryId, List.of(msg("m1"), msg("m2")));
        ChatMessageDto summary = summary("sum-1");
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            msg("m1"), msg("m2"), annotated.toChatMessageDto(), summary, msg("n1")));

        // WHEN
        List<ChatMessageDto> view = BoundaryReader.getMessagesAfterCompactBoundary(raw);

        // THEN: kept 紧接 boundary（在 summary **之前**）—— 与 up_to 形态相反，这就是 anchor 方向差异的落点
        assertThat(ids(view))
            .as("from：boundary → kept(m1,m2) → summary → n1（keep 在 summary 之前，REPL.tsx:4950-4951）")
            .containsExactly(boundaryId, "m1", "m2", "sum-1", "n1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 与压缩当轮内存数组逐项一致（方向无关的强等价）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("方向无关等价：读侧重挂结果 == 压缩当轮内存数组（buildPartialPostCompactMessages 两方向）")
    void relinkedView_matchesInMemoryPartialOrder_forBothDirections() {
        // WHY（本测试最重要的一条）：若两者不一致，同一会话「压缩当轮」与「下一轮从 DB 恢复」会看到
        //   不同顺序的上下文 —— 模型行为在同一会话内漂移，且无任何报错。这条断言把「读写两侧
        //   对同一份 preservedSegment 的解释必须一致」钉死。
        assertDirectionEquivalence(CompactPrompt.Direction.UP_TO);
        assertDirectionEquivalence(CompactPrompt.Direction.FROM);
    }

    private void assertDirectionEquivalence(CompactPrompt.Direction direction) {
        boolean upTo = direction == CompactPrompt.Direction.UP_TO;
        List<ChatMessageDto> keep = List.of(msg("k1"), msg("k2"));
        ChatMessageDto summary = summary("sum-1");

        CompactBoundaryMessage boundary = newBoundary();
        // 生产 anchor 规则（compact.ts:1112-1115 复核）：up_to = 最后一条 summary / from = boundary 自身
        String anchorUuid = upTo ? summary.id() : boundary.toChatMessageDto().id();
        CompactBoundaryMessage annotated = CompactBoundaryMessage.annotateBoundaryWithPreservedSegment(
            boundary, anchorUuid, keep);

        // 物理序 = keep（写侧零改写 → 原地不动）→ boundary → summary
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            keep.get(0), keep.get(1), annotated.toChatMessageDto(), summary));

        CompactionResult result = new CompactionResult(
            annotated, List.of(summary), List.of(), List.of(), keep,
            null, 100, 20, 20, null);

        List<String> inMemory = ids(CompactionResult.buildPartialPostCompactMessages(result, direction));
        List<String> fromDb = ids(BoundaryReader.getMessagesAfterCompactBoundary(raw));

        assertThat(fromDb)
            .as("%s 方向：读侧重挂出的顺序必须与压缩当轮内存数组逐项一致（否则同一会话上下文顺序漂移）",
                upTo ? "up_to" : "from")
            .containsExactlyElementsOf(inMemory);
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 幂等：改造前的旧数据（kept 已被写侧搬到 boundary 之后）不重复插入
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("旧数据幂等：kept 物理在 boundary 之后（改造前写侧搬迁过）→ 不重复插入")
    void legacyShape_keptAlreadyAfterBoundary_noDoubleInsert() {
        // WHY：改造前写侧发 UPDATE seq 把 kept 搬到 boundary 之后 → 老会话的 DB 里 kept 在切片内。
        //   若重挂不看「区间是否在 boundary 之前」，同一批行会被插两次 → 模型上下文里 kept 重复出现。
        ChatMessageDto summary = summary("sum-1");
        CompactBoundaryMessage annotated = annotate(
            summary.id(), // up_to/SM 方向 anchor = 最后一条 summary（compact.ts:1079-1080）
            List.of(msg("m3"), msg("m4")));
        // 旧形态：m3/m4 排在 boundary **之后**
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            msg("m1"), annotated.toChatMessageDto(), summary, msg("m3"), msg("m4")));

        List<ChatMessageDto> view = BoundaryReader.getMessagesAfterCompactBoundary(raw);

        assertThat(ids(view))
            .as("旧数据：kept 已在切片内 → 原样返回，绝不重复插入（幂等）")
            .containsExactly(annotated.toChatMessageDto().id(), "sum-1", "m3", "m4");
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. 退化：无 seg / seg 坏值 → 行为与改前一致（不硬拼、不抛）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("退化①无 preservedSegment（全量压缩/老数据）→ 与改前逐项一致（不重挂）")
    void noPreservedSegment_behavesAsBefore() {
        ChatMessageDto boundary = newBoundary().toChatMessageDto();
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            msg("m1"), boundary, summary("sum-1"), msg("n1")));

        List<ChatMessageDto> view = BoundaryReader.getMessagesAfterCompactBoundary(raw);

        assertThat(ids(view))
            .as("无 seg → 纯切片：boundary → summary → n1（与改造前逐字节一致）")
            .containsExactly(boundary.id(), "sum-1", "n1");
    }

    @Test
    @DisplayName("退化②headUuid 解析不到（行被删/id 损坏）→ 不重挂、不抛、不丢其它消息")
    void brokenSegment_headMissing_noOp() {
        ChatMessageDto summary = summary("sum-1");
        // 接线图指向一条列表里不存在的 head
        CompactBoundaryMessage annotated = annotate(summary.id(), List.of(msg("ghost")));
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            msg("m3"), msg("m4"), annotated.toChatMessageDto(), summary));

        List<ChatMessageDto> view = BoundaryReader.getMessagesAfterCompactBoundary(raw);

        assertThat(ids(view))
            .as("坏接线图 → 放弃重挂（ERROR 留痕），切片语义不变；绝不抛异常打断读侧")
            .containsExactly(annotated.toChatMessageDto().id(), "sum-1");
    }

    @Test
    @DisplayName("退化③anchorUuid 不在切片内 → 不重挂、不抛")
    void brokenSegment_anchorMissing_noOp() {
        CompactBoundaryMessage annotated = annotate("ghost-anchor", List.of(msg("m1"), msg("m2")));
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            msg("m1"), msg("m2"), annotated.toChatMessageDto(), summary("sum-1")));

        List<ChatMessageDto> view = BoundaryReader.getMessagesAfterCompactBoundary(raw);

        assertThat(ids(view))
            .as("anchor 不在切片 → 放弃重挂（ERROR 留痕），不抛")
            .containsExactly(annotated.toChatMessageDto().id(), "sum-1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. 只有 seq 一个排序权威 + 纯派生（不改写入参）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("单一排序权威：重挂只按列表序(seq)拼接 —— 纯派生、幂等、不改写入参")
    void singleSortAuthority_pureDerivation() {
        // WHY：本项的红线是「绝不引入第二套排序权威」。三条可观测判据：
        //   ① 入参（全量列表）调用后逐项不变（未就地排序/改写）；
        //   ② 同一入参调用两次结果逐项相同（纯函数）；
        //   ③ kept 内部相对顺序 = 入参相对顺序（即 seq 序，不按 id/时间另排）。
        ChatMessageDto summary = summary("sum-1");
        CompactBoundaryMessage annotated = annotate(
            summary.id(), // up_to/SM 方向 anchor = 最后一条 summary（compact.ts:1079-1080）
            List.of(msg("zz"), msg("aa")));
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            msg("m1"), msg("zz"), msg("aa"), annotated.toChatMessageDto(), summary));
        List<String> rawBefore = ids(raw);

        List<String> first = ids(BoundaryReader.getMessagesAfterCompactBoundary(raw));
        List<String> second = ids(BoundaryReader.getMessagesAfterCompactBoundary(raw));

        assertThat(ids(raw)).as("① 读侧投影是纯派生：入参列表序不变（无就地排序/改写）")
            .containsExactlyElementsOf(rawBefore);
        assertThat(second).as("② 幂等：同入参两次结果一致").containsExactlyElementsOf(first);
        assertThat(first).as("③ kept 顺序 = 原 seq 序（zz 在 aa 前：按位置不按字典序）")
            .containsSubsequence("zz", "aa");
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. 与 snip 投影的组合顺序：先重挂、后按 removedUuids 剔除
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("与 snip 投影组合：先重挂再按 removedUuids 剔除（被 snip 的 kept 行仍不进模型面）")
    void relinkThenSnipProjection() {
        // WHY：重挂与 snip 投影都作用于同一个最终数组，顺序错了会「把已裁掉的消息又搬回来」。
        //   正确次序 = 先重挂（kept 回到切片内）→ 再 snip 投影（removedUuids 一并剔除）。
        CompactBoundaryMessage annotated = annotate("sum-1", List.of(msg("m3"), msg("m4")));
        // m3 之后被 snip 裁掉（snip_boundary 落在 compact boundary 之后 → 在切片内）
        ChatMessageDto snipBoundary = snipBoundary("snip-1", List.of("m3"));
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            msg("m1"), msg("m3"), msg("m4"), annotated.toChatMessageDto(), snipBoundary, summary("sum-1")));

        List<ChatMessageDto> view = BoundaryReader.getMessagesAfterCompactBoundary(raw);

        assertThat(ids(view))
            .as("先重挂（m3/m4 回到视图）→ 再 snip 投影（m3 被 removedUuids 剔除）")
            .containsExactly(annotated.toChatMessageDto().id(), "snip-1", "sum-1", "m4");
    }

    @Test
    @DisplayName("includeSnipped=true（UI 全量口径）同样重挂 —— 重挂属读侧装配，与 snip 门无关")
    void includeSnippedTrue_alsoRelinks() {
        ChatMessageDto summary = summary("sum-1");
        CompactBoundaryMessage annotated = annotate(summary.id(), List.of(msg("m3")));
        List<ChatMessageDto> raw = new ArrayList<>(List.of(
            msg("m1"), msg("m3"), annotated.toChatMessageDto(), summary));

        List<ChatMessageDto> view = BoundaryReader.getMessagesAfterCompactBoundary(raw, true);

        assertThat(ids(view))
            .as("CC 的 relink 发生在 loadTranscriptFile（读侧装配）→ 与 includeSnipped 无关")
            .containsExactly(annotated.toChatMessageDto().id(), "sum-1", "m3");
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** 新 compact boundary（生产取号路径 → id 与 record.uuid 同源）。 */
    private static CompactBoundaryMessage newBoundary() {
        return CompactBoundaryMessage.createCompactBoundaryMessage("manual", 100, null, null, 3);
    }

    /** 用真实生产注解路径打接线图（走 annotateBoundaryWithPreservedSegment → toCompactMetadataMap）。 */
    private static CompactBoundaryMessage annotate(String anchorUuid, List<ChatMessageDto> keep) {
        return CompactBoundaryMessage.annotateBoundaryWithPreservedSegment(
            newBoundary(), anchorUuid, keep);
    }

    private static List<String> ids(List<ChatMessageDto> messages) {
        return messages.stream().map(ChatMessageDto::id).toList();
    }

    private static ChatMessageDto msg(String id) {
        return new ChatMessageDto(id, SESSION, Role.assistant, "assistant",
            "content-" + id, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    private static ChatMessageDto summary(String id) {
        return new ChatMessageDto(id, SESSION, Role.user, "system",
            "摘要正文", null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** snip 边界（subtype='snip_boundary' + snipMetadata.removedUuids）。 */
    private static ChatMessageDto snipBoundary(String id, List<String> removedUuids) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("removedUuids", removedUuids);
        return new ChatMessageDto(
            id, SESSION, Role.system, "system", "snip boundary", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(),
            null, false, false, null, "snip_boundary",
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, meta);
    }
}
