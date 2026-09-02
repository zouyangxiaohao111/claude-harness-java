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
 * IMP2-21 · snipReplay 两参变体覆盖（S7）+ snipCompact.ts 真源导出覆盖（2026-08-18 重写）。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>两参变体形状 {messages, executed}</b> — CC QueryEngine.ts:169-172
 *       {@code snipReplay?: (yieldedSystemMsg, store) => { messages; executed } | undefined}；
 *       QueryEngine.ts:909-913 消费：{@code if (snipResult.executed) { store = snipResult.messages } }——
 *       executed 是「store 是否被替换」的判别依据。</li>
 *   <li><b>重放接线</b> — CC QueryEngine.ts:1277-1283：snip boundary 消息 yield 时对 store 强制重跑
 *       {@code snipModule.snipCompactIfNeeded(store, { force: true })}（两参变体，{@code force:true}
 *       为第二参）；snipProjection.isSnipBoundaryMessage 为门控。</li>
 *   <li><b>与单参共享真源算法</b> — 两参变体与单参 {@code snipCompactIfNeeded(List)} 同一算法
 *       （snip_boundary + removedUuids），仅返回形状裁剪为 {messages, executed}。</li>
 *   <li><b>force 语义（CC 真源 snipCompact.ts:85）</b> — {@code _options?.force} CC <b>声明但从未使用</b>：
 *       无 size 门槛，boundary 存在即执行。force=true 不绕过任何门槛（无门槛）；force=false 在
 *       boundary 存在时同样执行。</li>
 *   <li><b>snipCompact.ts 真源导出覆盖</b> — SNIP_NUDGE_TEXT / isSnipMarkerMessage /
 *       isSnipRuntimeEnabled / shouldNudgeForSnips / estimateMessageTokens（经 tokensFreed 断言）/
 *       snipCompactIfNeeded 三条路径（无 boundary / 无 removedUuids 回退 / removedUuids 过滤）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert {@link SnipCompactor#snipCompactIfNeeded(List, boolean)}（无两参变体）/
 * {@code SnipCompactor.SnipReplayResult}（无 {messages, executed} 形状）/ 单参退回 head3+tail47 →
 * 本测试必须 fail（编译/断言）。
 */
@DisplayName("[IMP2-21] snipReplay 两参变体 + snipCompact.ts 真源导出（CC snipCompact.ts:83-165）")
class SnipReplayVariantCcTest {

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · 两参变体形状 {messages, executed}（CC QueryEngine.ts:169-172）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("snipReplay: 两参变体返回 {messages, executed}（CC QueryEngine.ts:169-172），force=true + boundary → executed=true")
    void replayVariantShapeAndExecuted() {
        SnipCompactor snip = new SnipCompactor();

        List<ChatMessageDto> store = withBoundary(largeMessages(60), "snip-boundary-1", removedUuids(0, 10));
        SnipCompactor.SnipReplayResult r = snip.snipCompactIfNeeded(store, true);

        // 形状/字段：messages → executed（CC QueryEngine.ts:169-172 读取顺序）
        assertThat(r.messages()).isNotNull();
        assertThat(r.executed()).isTrue();
        // 真源算法：removedUuids 中的消息被剔除（u0..u9），boundary 自身保留（snipCompact.ts:128-139）
        assertThat(ids(r.messages()))
            .as("removedUuids 中 u0..u9 必须被剔除")
            .doesNotContain("u0", "u9")
            .as("boundary 自身必须保留")
            .contains("snip-boundary-1")
            .hasSize(51);
    }

    @Test
    @DisplayName("snipReplay: force=true 结果与单参 snipCompactIfNeeded 逐元素一致（共享真源算法）")
    void replaySharesClearingSemanticsWithSingleArg() {
        SnipCompactor snip = new SnipCompactor();
        List<ChatMessageDto> store = withBoundary(largeMessages(60), "snip-boundary-1", removedUuids(0, 10));

        SnipCompactor.SnipResult single = snip.snipCompactIfNeeded(store);
        SnipCompactor.SnipReplayResult replay = snip.snipCompactIfNeeded(store, true);

        // 两参变体委托单参（同一算法），id 序列必须逐元素一致
        assertThat(replay.messages()).extracting(ChatMessageDto::id)
            .as("两参变体与单参必须共享同一真源算法（snipCompact.ts:83-147）")
            .isEqualTo(single.messages().stream().map(ChatMessageDto::id).toList());
        assertThat(replay.executed()).isEqualTo(single.executed());
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · force 语义（CC 真源 snipCompact.ts:85：声明但从未使用）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("snipReplay: 无 boundary + force=false → executed=false 且 store 原样（snipCompact.ts:111-113）")
    void replayNoBoundaryKeepsStore() {
        SnipCompactor snip = new SnipCompactor();
        List<ChatMessageDto> store = largeMessages(10);

        SnipCompactor.SnipReplayResult r = snip.snipCompactIfNeeded(store, false);

        assertThat(r.executed()).isFalse();
        assertThat(r.messages()).isSameAs(store);   // 未执行 → 调用方不替换 store（QueryEngine.ts:910-913）
    }

    @Test
    @DisplayName("snipReplay: 无 boundary + force=true → executed=false（force 从未使用，snipCompact.ts:85）")
    void replayNoBoundaryForceTrueStillKeepsStore() {
        SnipCompactor snip = new SnipCompactor();
        List<ChatMessageDto> store = largeMessages(10);

        SnipCompactor.SnipReplayResult r = snip.snipCompactIfNeeded(store, true);

        // CC 真源：_options?.force 声明但从未被读取（snipCompact.ts:85）→ 无 boundary 恒不执行
        assertThat(r.executed()).as("force=true 不绕过「无 boundary」（force 未使用，CC snipCompact.ts:85）").isFalse();
        assertThat(r.messages()).isSameAs(store);
    }

    @Test
    @DisplayName("snipReplay: 有 boundary + force=false → executed=true（无 size 门槛，boundary 存在即执行）")
    void replayWithBoundaryExecutesRegardlessOfForce() {
        SnipCompactor snip = new SnipCompactor();
        List<ChatMessageDto> store = withBoundary(largeMessages(10), "snip-boundary-1", removedUuids(0, 5));

        SnipCompactor.SnipReplayResult r = snip.snipCompactIfNeeded(store, false);

        // 真源无「size ≤ 门槛 不压缩」门槛：boundary 存在即执行（snipCompact.ts:83-147）
        assertThat(r.executed()).isTrue();
        assertThat(ids(r.messages()))
            .as("removedUuids 中 u0..u4 必须被剔除")
            .doesNotContain("u0", "u4")
            .as("boundary 自身必须保留")
            .contains("snip-boundary-1");
    }

    @Test
    @DisplayName("snipReplay: 空/过小 store（无 boundary）→ executed=false（QueryEngine.ts:910 不替换）")
    void replayEmptyOrTinyStoreNotExecuted() {
        SnipCompactor snip = new SnipCompactor();

        // 空 store
        SnipCompactor.SnipReplayResult empty = snip.snipCompactIfNeeded(List.of(), true);
        assertThat(empty.executed()).isFalse();
        assertThat(empty.messages()).isEmpty();

        // null store（防御）
        SnipCompactor.SnipReplayResult nul = snip.snipCompactIfNeeded(null, true);
        assertThat(nul.executed()).isFalse();
        assertThat(nul.messages()).isEmpty();

        // 过小 store（3 条、无 boundary）→ 不执行，store 原样
        List<ChatMessageDto> tiny = largeMessages(3);
        SnipCompactor.SnipReplayResult tinyR = snip.snipCompactIfNeeded(tiny, true);
        assertThat(tinyR.executed()).isFalse();
        assertThat(tinyR.messages()).isSameAs(tiny);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · 单参真源算法三条路径（snipCompact.ts:83-147）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("snip: 无 snip_boundary → executed=false, tokensFreed=0, boundaryMessage=null（snipCompact.ts:111-113）")
    void singleNoBoundaryNotExecuted() {
        SnipCompactor snip = new SnipCompactor();

        SnipCompactor.SnipResult r = snip.snipCompactIfNeeded(largeMessages(10));

        assertThat(r.executed()).isFalse();
        assertThat(r.messages()).hasSize(10);
        assertThat(r.tokensFreed()).isZero();
        assertThat(r.boundaryMessage()).isNull();
    }

    @Test
    @DisplayName("snip: boundary 存在但无 removedUuids → 保留 boundary+之后全部，tokensFreed=0（snipCompact.ts:118-126）")
    void singleBoundaryNoRemovedUuidsFallsBackToSuffix() {
        SnipCompactor snip = new SnipCompactor();

        // boundary 插入位置 10（无 removedUuids）→ kept = messages[10..] = boundary + u10..u19
        List<ChatMessageDto> msgs = largeMessages(20);
        msgs.add(10, snipBoundary("snip-boundary-1", null));
        SnipCompactor.SnipResult r = snip.snipCompactIfNeeded(msgs);

        assertThat(r.executed()).isTrue();
        assertThat(r.tokensFreed()).isZero();
        assertThat(r.boundaryMessage().id()).isEqualTo("snip-boundary-1");
        assertThat(ids(r.messages()))
            .as("无 removedUuids → 保留 boundary 本身 + 之后全部（u10..u19），之前消息丢弃")
            .containsExactly("snip-boundary-1", "u10", "u11", "u12", "u13", "u14", "u15", "u16", "u17", "u18", "u19");
    }

    @Test
    @DisplayName("snip: removedUuids 过滤 + tokensFreed = Σ estimateMessageTokens（snipCompact.ts:128-139）")
    void singleFilteredRemovesAndFreesTokens() {
        SnipCompactor snip = new SnipCompactor();

        List<ChatMessageDto> msgs = withBoundary(largeMessages(60), "snip-boundary-1", removedUuids(0, 10));
        SnipCompactor.SnipResult r = snip.snipCompactIfNeeded(msgs);

        assertThat(r.executed()).isTrue();
        assertThat(r.boundaryMessage().id()).isEqualTo("snip-boundary-1");
        assertThat(r.boundaryMessage().subtype()).isEqualTo(SnipCompactor.SUBTYPE_SNIP_BOUNDARY);
        assertThat(ids(r.messages()))
            .as("u0..u9 剔除 + boundary 保留 + u10..u59 保留 = 51 条")
            .hasSize(51)
            .doesNotContain("u0", "u9")
            .contains("snip-boundary-1", "u10", "u59");
        // 每条 "hi" content=2 字符 → ceil(2/4)=1 token；移除 10 条 → 10 tokens
        assertThat(r.tokensFreed()).isEqualTo(10);
    }

    @Test
    @DisplayName("snip: tokensFreed 按 content 长度精确估算（estimateMessageTokens，snipCompact.ts:35-58）")
    void singleTokensFreedMatchesEstimate() {
        SnipCompactor snip = new SnipCompactor();

        // 3 条 content 长度 400 → ceil(400/4)=100 tokens each；移除 3 条 → 300 tokens
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            msgs.add(singleMessage("v" + i, "x".repeat(400)));
        }
        msgs.add(snipBoundary("snip-boundary-2", List.of("v0", "v1", "v2")));
        SnipCompactor.SnipResult r = snip.snipCompactIfNeeded(msgs);

        assertThat(r.executed()).isTrue();
        assertThat(r.tokensFreed()).isEqualTo(3 * 100);
        assertThat(r.messages()).extracting(ChatMessageDto::id).containsExactly("snip-boundary-2");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · snipCompact.ts 真源导出（SNIP_NUDGE_TEXT / isSnipMarkerMessage /
    //         isSnipRuntimeEnabled / shouldNudgeForSnips / shouldSnip）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("snipCompact.ts 导出: SNIP_NUDGE_TEXT 非空（snipCompact.ts:17-18）")
    void exportSnipNudgeText() {
        assertThat(SnipCompactor.SNIP_NUDGE_TEXT)
            .as("SNIP_NUDGE_TEXT 必须与 CC 真源一致（snipCompact.ts:17-18）")
            .isNotBlank()
            .contains("/force-snip");
    }

    @Test
    @DisplayName("snipCompact.ts 导出: isSnipMarkerMessage 判别 type=system + subtype=snip_marker（snipCompact.ts:25-28）")
    void exportIsSnipMarkerMessage() {
        ChatMessageDto marker = snipBoundaryWithSubtype("marker-1", SnipCompactor.SUBTYPE_SNIP_MARKER, null);
        ChatMessageDto boundary = snipBoundary("boundary-1", null);
        ChatMessageDto user = singleMessage("u1", "hi");

        assertThat(SnipCompactor.isSnipMarkerMessage(marker)).isTrue();
        assertThat(SnipCompactor.isSnipMarkerMessage(boundary)).isFalse();
        assertThat(SnipCompactor.isSnipMarkerMessage(user)).isFalse();
        assertThat(SnipCompactor.isSnipMarkerMessage(null)).isFalse();
    }

    @Test
    @DisplayName("snipCompact.ts 导出: isSnipRuntimeEnabled 恒 true（snipCompact.ts:154-156，HISTORY_SNIP flag 下才加载）")
    void exportIsSnipRuntimeEnabled() {
        assertThat(SnipCompactor.isSnipRuntimeEnabled()).isTrue();
    }

    @Test
    @DisplayName("snipCompact.ts 导出: shouldNudgeForSnips 阈值 30（snipCompact.ts:163-165）")
    void exportShouldNudgeForSnips() {
        assertThat(SnipCompactor.shouldNudgeForSnips(largeMessages(30))).isTrue();
        assertThat(SnipCompactor.shouldNudgeForSnips(largeMessages(29))).isFalse();
        assertThat(SnipCompactor.shouldNudgeForSnips(null)).isFalse();
    }

    @Test
    @DisplayName("shouldSnip: 存在 snip_boundary → true；否则 false（CC 前置条件 snipCompact.ts:96-109）")
    void exportShouldSnip() {
        assertThat(SnipCompactor.shouldSnip(withBoundary(largeMessages(3), "b", removedUuids(0, 1)))).isTrue();
        assertThat(SnipCompactor.shouldSnip(largeMessages(3))).isFalse();
        assertThat(SnipCompactor.shouldSnip(null)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private static List<ChatMessageDto> withBoundary(List<ChatMessageDto> msgs, String boundaryId, List<String> removedUuids) {
        List<ChatMessageDto> list = new ArrayList<>(msgs);
        list.add(snipBoundary(boundaryId, removedUuids));
        return list;
    }

    private static List<String> removedUuids(int from, int count) {
        List<String> removed = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            removed.add("u" + i);
        }
        return removed;
    }

    /** 37 参 canonical 构造 snip_boundary / snip_marker 消息（subtype + snipMetadata 承载）。 */
    private static ChatMessageDto snipBoundaryWithSubtype(String id, String subtype, Map<String, Object> snipMetadata) {
        return new ChatMessageDto(
            id, "s", Role.system, "system", "snip " + subtype, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(),
            null, false, false, null, subtype,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, snipMetadata);
    }

    private static ChatMessageDto snipBoundary(String id, List<String> removedUuids) {
        Map<String, Object> meta = null;
        if (removedUuids != null) {
            meta = new LinkedHashMap<>();
            meta.put("removedUuids", removedUuids);
        }
        return snipBoundaryWithSubtype(id, SnipCompactor.SUBTYPE_SNIP_BOUNDARY, meta);
    }

    private static List<ChatMessageDto> largeMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(singleMessage("u" + i, "hi"));
        }
        return list;
    }

    private static ChatMessageDto singleMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static List<String> ids(List<ChatMessageDto> msgs) {
        return msgs.stream().map(ChatMessageDto::id).toList();
    }
}
