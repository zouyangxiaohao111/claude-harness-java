package com.nexusai.application.agent.loop;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H7-arch Phase 5 P4 C5] ContextCollapse 薄门面 CC 契约测试。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>flag=on + 413 → 先 drain（COLLAPSE_DRAIN_RETRY）再 reactive</b> — CC query.ts:1086-1117
 *       PTL 时先 recoverFromOverflow 排空暂存 collapse，committed &gt; 0 时 continue
 *       collapse_drain_retry；drain 失败才落到 reactive compact。测试验证 drain 在 loop 源码中
 *       出现在 reactive compact 之前，且 flag=on 时 drain 有实际效果。</li>
 *   <li><b>flag=off → 0 命中</b> — CC flag 关闭时 contextCollapse 模块为 null，
 *       所有调用点空值保护 → recoverFromOverflow 返回 committed=0（不 drain）。</li>
 * </ol>
 *
 * <p><b>[GR-3]</b> 旧编排器已删除：drain 逻辑（L2 Snip）现内联在本类，
 * 构造不再依赖外部压缩组件（{@code new ContextCollapse(featureFlags)}）。
 *
 * <p><b>RED teeth</b>: revert {@link ContextCollapse}（isContextCollapseEnabled 不读 flag /
 * recoverFromOverflow 不禁用短路）→ 本测试必须 fail。
 */
class ContextCollapseCcContractTest {

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    private List<ChatMessageDto> snipableMessages() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            msgs.add(new ChatMessageDto(
                "m" + i, "s", Role.user, "user", "content " + i, null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of()));
        }
        // 追加 snip_boundary（CC 真源判别 snipCompact.ts:96-109）；removedUuids=m0..m9 →
        // recoverFromOverflow L2 Snip drain 释放 >0 token（content "content N" 各 ~3 token × 10）
        msgs.add(snipBoundary("snip-boundary-1", removedUuids(0, 10)));
        return msgs;
    }

    private static List<String> removedUuids(int from, int count) {
        List<String> removed = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            removed.add("m" + i);
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
            null, false, false, null, "snip_boundary",
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, meta);
    }

    @Test
    @DisplayName("flag=on → isContextCollapseEnabled()=true，recoverFromOverflow 返回 committed>0（可 COLLAPSE_DRAIN_RETRY）")
    void flagOn_drainHasEffect() {
        ContextCollapse cc = new ContextCollapse(new FeatureFlags(false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false));

        assertThat(cc.isContextCollapseEnabled())
            .as("flag=on 时 isContextCollapseEnabled() 必须返回 true（CC query.ts:618）")
            .isTrue();

        ContextCollapse.DrainResult drain = cc.recoverFromOverflow(snipableMessages(), "main_thread");
        assertThat(drain.committed())
            .as("flag=on + 413 → drain 必须先排空暂存 collapse（committed>0 可 continue collapse_drain_retry · CC query.ts:1098）")
            .isGreaterThan(0);
    }

    @Test
    @DisplayName("flag=off → 0 命中：isContextCollapseEnabled()=false，recoverFromOverflow committed=0，applyCollapsesIfNeeded 原样返回")
    void flagOff_zeroHits() {
        ContextCollapse cc = new ContextCollapse(FeatureFlags.ALL_DISABLED);
        List<ChatMessageDto> original = snipableMessages();

        assertThat(cc.isContextCollapseEnabled())
            .as("flag=off 时 isContextCollapseEnabled() 必须返回 false（对齐 CC flag 关闭模块为 null）")
            .isFalse();

        ContextCollapse.DrainResult drain = cc.recoverFromOverflow(original, "main_thread");
        assertThat(drain.committed())
            .as("flag=off → 0 命中：recoverFromOverflow 必须返回 committed=0（不 drain）")
            .isZero();

        List<ChatMessageDto> projected = cc.applyCollapsesIfNeeded(original, null, "main_thread");
        assertThat(projected)
            .as("flag=off → applyCollapsesIfNeeded 必须原样返回（不投影）")
            .isSameAs(original);
    }

    @Test
    @DisplayName("flag=on → applyCollapsesIfNeeded 投影 collapse（返回缩小后的消息列表）")
    void flagOn_applyCollapsesProjects() {
        ContextCollapse cc = new ContextCollapse(new FeatureFlags(false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false));
        List<ChatMessageDto> original = snipableMessages();

        List<ChatMessageDto> projected = cc.applyCollapsesIfNeeded(original, null, "main_thread");

        assertThat(projected.size())
            .as("flag=on → applyCollapsesIfNeeded 必须投影 collapse（缩小消息数 · CC query.ts:440-447）")
            .isLessThan(original.size());
    }

    @Test
    @DisplayName("loop wiring: drain 分支 gated on contextCollapse flag + 在 reactive compact 之前")
    void loopDrainBeforeReactiveWithGate() throws Exception {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        // drain 分支空值保护（[V52 X1-1] DB-aware：isContextCollapseEnabled() 含 DB settings.context_collapse_enabled，
        //   null 回落 FeatureFlags.contextCollapse()，故不再直接引用 featureFlags()）
        assertThat(source)
            .as("LlmAgentLoop drain 分支必须检查 contextCollapse().isContextCollapseEnabled()（DB-aware · V52 X1-1）")
            .contains("ctx.contextCollapse().isContextCollapseEnabled()");
        assertThat(source)
            .as("LlmAgentLoop drain 分支必须检查 contextCollapse() != null（空值保护）")
            .contains("ctx.contextCollapse() != null");
        // isWithheldPromptTooLong withhold 调用点（CC query.ts:800-810）
        assertThat(source)
            .as("LlmAgentLoop 必须包含 isWithheldPromptTooLong 调用点（CC query.ts:800-810）")
            .contains("isWithheldPromptTooLong(");
        // applyCollapsesIfNeeded 调用点（autocompact 前投影 · CC query.ts:440-447）
        assertThat(source)
            .as("LlmAgentLoop 必须包含 applyCollapsesIfNeeded 调用点（CC query.ts:440-447）")
            .contains("applyCollapsesIfNeeded(");
        // drain 必须先于 reactive compact（CC 顺序 query.ts:1086-1117 在 1119-1166 之前）
        int drainIdx = source.indexOf("recoverFromOverflow(state.messages()");
        int reactiveIdx = source.indexOf("tryReactiveCompact(");
        assertThat(drainIdx)
            .as("LlmAgentLoop PTL 必须包含 collapse drain（recoverFromOverflow）")
            .isGreaterThan(0);
        assertThat(reactiveIdx)
            .as("LlmAgentLoop PTL 必须包含 reactive compact（tryReactiveCompact）")
            .isGreaterThan(0);
        assertThat(drainIdx)
            .as("collapse drain 必须先于 reactive compact（CC query.ts:1086-1117 → 1119）")
            .isLessThan(reactiveIdx);
    }

    @Test
    @DisplayName("C4: collapse 3 参 withhold 调用点必须传实际消息（lastAssistantMsg），非恒 null · CC query.ts:800-810")
    void loopWithholdCallSitePassesActualMessage() throws Exception {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        int callIdx = source.indexOf("isWithheldPromptTooLong(");
        assertThat(callIdx)
            .as("LlmAgentLoop 必须包含 isWithheldPromptTooLong 调用点（CC query.ts:800-810）")
            .isGreaterThan(0);
        String callSnippet = source.substring(callIdx, source.indexOf(";", callIdx));
        assertThat(callSnippet)
            .as("C4: 3 参调用点第一实参必须传实际消息（CC query.ts:802-805 message 实参），"
                + "不得恒 null + streamError 闭包谓词（旧实现 LlmAgentLoop:3979-3980）")
            .contains("lastAssistantMsg")
            .doesNotContain("null,");
    }
}
