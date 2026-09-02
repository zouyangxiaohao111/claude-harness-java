package com.nexusai.model.session.dto;

import com.nexusai.application.agent.tool.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [reasoningDurationMs] ChatMessageDto record 兼容测试 · 净新增字段（非 CC 对齐）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: canonical 38→39 参追加 reasoningDurationMs 后，
 * 既有 38 参 canonical 调用方经 38 参兼容构造器兜底、5 个拷贝方法重建 canonical 时必须保留新字段
 * （否则链式 withXxx 后 reasoningDurationMs 丢失）。变异点：
 * <ul>
 *   <li>38 参兼容构造器误把推理耗时当 cwd → 类型/语义错 → 红</li>
 *   <li>withReasoningDurationMs 重建丢其它字段 → 拷贝只覆盖目标字段契约破坏 → 红</li>
 *   <li>withCwd/withUsage/withUsageCache/withMatchedRule/withSourceToolUseID 重建丢
 *       reasoningDurationMs → 链式调用后耗时消失 → 红</li>
 * </ul>
 */
@DisplayName("[reasoningDurationMs] ChatMessageDto record 兼容（39 参 canonical + 拷贝方法保字段）")
class ChatMessageDtoReasoningDurationTest {

    /** 38 参形状（旧 canonical：…snipMetadata, cwd）+ 全字段占位。 */
    private static ChatMessageDto base38() {
        return new ChatMessageDto(
            "id-1", "sess-1", Role.assistant, "author", "content", "reasoning",
            List.of(), FinishReason.stop, 10, 20, "刚刚", OffsetDateTime.now(),
            "tc-1", "am-1", "afb", List.of("block"), List.of("img1"),
            Map.of("so", "v"), true, true, "stui", "subtype",
            true, "apiErr", "err", "errDetails",
            30, 40, Map.of("cm", 1), Map.of("mm", 2), "lpu",
            true, true,
            AgentUsage.fromInputOutput(10, 20), "level", "matchedRule", Map.of("snip", "v"), "cwd");
    }

    @Test
    @DisplayName("38 参兼容构造器默认 reasoningDurationMs=null（旧 canonical 形状不传耗时）")
    void compatible38Ctor_defaultsReasoningDurationNull() {
        ChatMessageDto dto = base38();

        assertThat(dto.reasoningDurationMs())
            .as("38 参兼容构造器（旧 canonical 形状）必须默认 reasoningDurationMs=null（无 reasoning 容错）")
            .isNull();
        // 位置校验：cwd 必须落在第 38 参（旧形状末参），非被新字段顶替
        assertThat(dto.cwd()).isEqualTo("cwd");
    }

    @Test
    @DisplayName("withReasoningDurationMs 只覆盖该字段，其余全字段保留")
    void withReasoningDurationMs_onlyOverridesTargetField() {
        ChatMessageDto base = base38().withReasoningDurationMs(999L);

        assertThat(base.reasoningDurationMs()).isEqualTo(999L);
        // 其余关键字段不得被重建丢失（拷贝方法必须全字段保真）
        assertThat(base.id()).isEqualTo("id-1");
        assertThat(base.cwd()).isEqualTo("cwd");
        assertThat(base.usage()).isEqualTo(AgentUsage.fromInputOutput(10, 20));
        assertThat(base.level()).isEqualTo("level");
        assertThat(base.matchedRule()).isEqualTo("matchedRule");
        assertThat(base.snipMetadata()).isEqualTo(Map.of("snip", "v"));
        assertThat(base.compactMetadata()).isEqualTo(Map.of("cm", 1));
        assertThat(base.sourceToolUseID()).isEqualTo("stui");
    }

    @Test
    @DisplayName("withCwd 重建后 reasoningDurationMs 不被丢弃（链式 withReasoningDurationMs→withCwd）")
    void withCwd_preservesReasoningDurationMs() {
        ChatMessageDto base = base38().withReasoningDurationMs(500L);

        ChatMessageDto viaCwd = base.withCwd("other-cwd");

        assertThat(viaCwd.reasoningDurationMs())
            .as("withCwd 重建 canonical 必须保留 reasoningDurationMs（拷贝方法保字段契约）")
            .isEqualTo(500L);
        assertThat(viaCwd.cwd()).isEqualTo("other-cwd");
    }

    @Test
    @DisplayName("withUsage / withUsageCache / withMatchedRule / withSourceToolUseID 重建后 reasoningDurationMs 不被丢弃")
    void otherCopyMethods_preserveReasoningDurationMs() {
        ChatMessageDto base = base38().withReasoningDurationMs(500L);

        ChatMessageDto viaUsage = base.withUsage(AgentUsage.fromInputOutput(77, 88));
        assertThat(viaUsage.reasoningDurationMs()).as("withUsage 保留 reasoningDurationMs").isEqualTo(500L);
        assertThat(viaUsage.inputTokens()).isEqualTo(77);

        ChatMessageDto viaUsageCache = base.withUsageCache(111, 222);
        assertThat(viaUsageCache.reasoningDurationMs()).as("withUsageCache 保留 reasoningDurationMs").isEqualTo(500L);
        assertThat(viaUsageCache.inputCacheReadTokens()).isEqualTo(111);

        ChatMessageDto viaMatchedRule = base.withMatchedRule("rule-X");
        assertThat(viaMatchedRule.reasoningDurationMs()).as("withMatchedRule 保留 reasoningDurationMs").isEqualTo(500L);
        assertThat(viaMatchedRule.matchedRule()).isEqualTo("rule-X");

        ChatMessageDto viaSourceToolUseId = base.withSourceToolUseID("stui-2");
        assertThat(viaSourceToolUseId.reasoningDurationMs()).as("withSourceToolUseID 保留 reasoningDurationMs").isEqualTo(500L);
        assertThat(viaSourceToolUseId.sourceToolUseID()).isEqualTo("stui-2");
    }
}
