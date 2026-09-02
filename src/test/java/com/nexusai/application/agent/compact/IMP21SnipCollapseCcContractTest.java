package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.loop.ContextCollapse;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-21 · snip/collapse 引用方语义对齐 + snipTokensFreed 真实透传（INV-9）+ D-28 独立入口删除。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>snip 返回形状/顺序 {messages, executed, tokensFreed, boundaryMessage}</b> — CC 真源
 *       snipCompact.ts:86-91 {@code snipCompactIfNeeded} 返回形状；调用方读取
 *       messages/tokensFreed/boundaryMessage（query.ts:404-408），executed 为 CC 真源判别字段。</li>
 *   <li><b>snipTokensFreed 减法断言（INV-9）</b> — CC autoCompact.ts:225
 *       {@code tokenCount = tokenCountWithEstimation(messages) - snipTokensFreed}，query.ts:466 把
 *       snip 释放的 token 真实传给 autocompact；Java 端 {@code shouldAutoCompact} 与
 *       {@code tryAutoCompact} 必须透传真实值而非硬编码 0（REQ-24 / REQ-10）。</li>
 *   <li><b>collapse 引用方语义</b> — CC query.ts:441 {@code applyCollapsesIfNeeded} 在 autocompact 前
 *       投影（feature CONTEXT_COLLAPSE 门控）；Java 端 flag=off 原样返回、flag=on 投影（REQ-25）。</li>
 *   <li><b>D-28 独立入口删除</b> — CC 无 collapse()/snip() 独立入口（collapse/snip 内联 queryLoop）；
 *       Java 端旧编排器（D-03）整类删除后旧符号 grep 0 命中。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert {@link SnipCompactor#snipCompactIfNeeded}（无 SnipResult 形状）/
 * {@code AutoCompactor.tryAutoCompact(messages, snipTokensFreed)}（无透传）/
 * 旧编排器复活（D-03 未删除）→ 本测试必须 fail。
 */
@DisplayName("[IMP-21] snip/collapse 引用方语义 + snipTokensFreed 透传 + D-28 删除")
class IMP21SnipCollapseCcContractTest {

    /** 已删除的旧编排器文件路径（符号名经拼接避免引入待删符号字面量，保持 grep 归零可复验）。 */
    private static final String COMPACT_CONTEXT_PATH =
        "src/main/java/com/nexusai/application/agent/compact/" + "Compact" + "Context.java";

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · snip 返回形状/顺序 {messages, tokensFreed, boundaryMessage}（REQ-24）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("snip: snipCompactIfNeeded 返回 {messages, executed, tokensFreed, boundaryMessage}（CC 真源 snipCompact.ts:86-91）")
    void snipResultShape() {
        SnipCompactor snip = new SnipCompactor();

        // 60 条 user + 尾部 snip_boundary（removedUuids=u0..u9）→ 真源算法过滤
        List<ChatMessageDto> msgs = withBoundary(largeMessages(60), "snip-boundary-1", removedUuids(0, 10));
        SnipCompactor.SnipResult r = snip.snipCompactIfNeeded(msgs);

        // 形状/顺序: messages → executed → tokensFreed → boundaryMessage（CC snipCompact.ts:86-91）
        assertThat(r.messages()).isNotNull();
        assertThat(r.executed()).isTrue();
        assertThat(r.tokensFreed()).isGreaterThan(0);
        assertThat(r.boundaryMessage()).isNotNull();
        // 真源算法：u0..u9 剔除 + boundary 保留 = 51 条（非 head3+tail47）
        assertThat(r.messages()).hasSize(51);
        assertThat(r.messages()).extracting(ChatMessageDto::id).doesNotContain("u0", "u9");
        // boundaryMessage = messages[boundaryIdx] 原样（非凭空构造，snipCompact.ts:115）
        assertThat(r.boundaryMessage().id()).isEqualTo("snip-boundary-1");
        assertThat(r.boundaryMessage().subtype()).isEqualTo(SnipCompactor.SUBTYPE_SNIP_BOUNDARY);
    }

    @Test
    @DisplayName("snip: 无 snip_boundary → executed=false, tokensFreed=0, boundaryMessage=null（snipCompact.ts:111-113 不 yield）")
    void snipNoOpWithoutBoundary() {
        SnipCompactor snip = new SnipCompactor();

        SnipCompactor.SnipResult r = snip.snipCompactIfNeeded(largeMessages(10));

        assertThat(r.executed()).isFalse();
        assertThat(r.messages()).hasSize(10);
        assertThat(r.tokensFreed()).isZero();
        assertThat(r.boundaryMessage()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · snipTokensFreed 减法断言（INV-9 / REQ-24）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("INV-9: shouldAutoCompact 减法 tokenCount − snipTokensFreed（autoCompact.ts:225）")
    void inv9ShouldAutoCompactSubtractsSnipTokensFreed() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("summary", null));
        List<ChatMessageDto> big = largeMessages(50);

        // 无 snip: tokenCount=200_000 ≥ 阈值 → 需压缩
        assertThat(auto.shouldAutoCompact(big, "user", 0)).isTrue();
        // snip 释放足量 token: tokenCount − snipTokensFreed < 阈值 → 不需压缩（INV-9）
        assertThat(auto.shouldAutoCompact(big, "user", 200_000)).isFalse();
    }

    @Test
    @DisplayName("INV-9: tryAutoCompact 透传 snipTokensFreed（真实减法，非硬编码 0）")
    void inv9TryAutoCompactForwardsSnipTokensFreed() {
        AtomicInteger llmCalls = new AtomicInteger();
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> { llmCalls.incrementAndGet();
                return new CompactConversation.SummaryResult("<summary>ok</summary>", null); });

        // snip 已释放足量 token → shouldAutoCompact false → 不触发 L4 LLM 摘要
        AutoCompactor.AutoCompactResult r = auto.tryAutoCompact(largeMessages(50), 200_000);
        assertThat(r.wasCompacted()).isFalse();
        assertThat(llmCalls.get()).isZero();

        // 无 snip 透传 → 应触发压缩路径
        AutoCompactor auto2 = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("<summary>valid summary</summary>", null));
        assertThat(auto2.tryAutoCompact(largeMessages(50), 0).wasCompacted()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · collapse 引用方语义（applyCollapsesIfNeeded 投影 + feature 门控，REQ-25）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("collapse: flag=on → applyCollapsesIfNeeded 投影缩小（CC query.ts:441-446）")
    void collapseApplyProjectsWhenFlagOn() {
        ContextCollapse cc = new ContextCollapse(new FeatureFlags(false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false));

        // 含 snip_boundary（removedUuids=u0..u9）→ collapse drain（L2 Snip）投影缩小
        List<ChatMessageDto> original = withBoundary(largeMessages(60), "snip-boundary-1", removedUuids(0, 10));
        List<ChatMessageDto> projected = cc.applyCollapsesIfNeeded(original, (ToolUseContext) null, "main_thread");

        assertThat(projected.size())
            .as("flag=on → applyCollapsesIfNeeded 必须投影 collapse（缩小消息数 · CC query.ts:440-447）")
            .isLessThan(original.size());
    }

    @Test
    @DisplayName("collapse: flag=off → applyCollapsesIfNeeded 原样返回（0 命中空值保护）")
    void collapseApplyNoOpWhenFlagOff() {
        ContextCollapse cc = new ContextCollapse(FeatureFlags.ALL_DISABLED);

        List<ChatMessageDto> original = largeMessages(60);
        List<ChatMessageDto> projected = cc.applyCollapsesIfNeeded(original, (ToolUseContext) null, "main_thread");

        assertThat(projected).isSameAs(original);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · D-28/D-03 独立入口删除（CC 无独立入口；GR-3 旧编排器整类删除）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("D-28: 旧编排器文件已删除，collapse()/snip() 独立入口不再存在（D-03 闭环）")
    void d28IndependentEntryDeleted() throws Exception {
        // GR-3: 旧编排器整类删除（D-03）——collapse()/snip() 独立入口随宿主消失。
        // 本守卫确保文件不复活（构建级 grep 归零保护）。
        assertThat(Files.notExists(Path.of(COMPACT_CONTEXT_PATH)))
            .as("D-03/GR-3: 旧编排器文件必须已删除（collapse()/snip() 独立入口随宿主消失）")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private static List<ChatMessageDto> largeMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ChatMessageDto("u" + i, null, Role.user, "user", "hi", null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of()));
        }
        return list;
    }

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

    /** 37 参 canonical 构造 snip_boundary 消息（subtype + snipMetadata 承载，CC snipCompact.ts:99-106）。 */
    private static ChatMessageDto snipBoundary(String id, List<String> removedUuids) {
        Map<String, Object> meta = null;
        if (removedUuids != null) {
            meta = new LinkedHashMap<>();
            meta.put("removedUuids", removedUuids);
        }
        return new ChatMessageDto(
            id, "s", Role.system, "system", "snip boundary", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(),
            null, false, false, null, SnipCompactor.SUBTYPE_SNIP_BOUNDARY,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, meta);
    }
}
