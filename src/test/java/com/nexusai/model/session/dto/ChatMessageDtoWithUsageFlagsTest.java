package com.nexusai.model.session.dto;

import com.nexusai.application.agent.tool.AgentUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [SM/compact 对齐 CC · V70] {@code ChatMessageDto.withUsage(...)} 必须<b>透传</b> compact 可观察性标志。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：{@code withUsage} 是 record 拷贝方法
 * （镜像 CC spread {@code {...m, usage}}，语义 = 只覆盖 usage 相关字段，其它字段原样保留）。
 * 但重建 31 参 canonical 时曾把 {@code compactMetadata / microcompactMetadata / logicalParentUuid /
 * isCompactSummary / isVisibleInTranscriptOnly} 五处硬编码为 {@code null, null, null, false, false}
 * —— 一旦对 compact 摘要消息调用 withUsage，摘要身份（isCompactSummary=true）与
 * 「仅 transcript 可见」标记会被静默清零：
 * <ul>
 *   <li>{@code isCompactSummary=false} → 前端 TraceView.compactSummaryAfter 断裂（摘要详情不渲染）；</li>
 *   <li>{@code isVisibleInTranscriptOnly=false} → 摘要被当普通 user 消息渲染；</li>
 *   <li>{@code logicalParentUuid=null} → compact boundary 的 parent 链断（读侧无法回溯压缩前最后消息）。</li>
 * </ul>
 * 当前未爆的原因是 {@code MessageService.toDto} 只在 usage 非空时调 {@code withUsage}，而 compact
 * 摘要消息 token 恒 null → 提前 {@code return this}。这是「暂不触发」而非「不会触发」：任何给摘要行
 * 补 usage 的改动（provider 回填 / 重算 usage）都会立刻踩雷。
 *
 * <p><b>RED 条件</b>：把 withUsage 里那五处改回 {@code null, null, null, false, false} →
 * 本测试四个「保持」断言全红。
 */
@DisplayName("[V70] ChatMessageDto.withUsage 透传 compact 可观察性标志（isCompactSummary/isVisibleInTranscriptOnly/compactMetadata/logicalParentUuid）")
class ChatMessageDtoWithUsageFlagsTest {

    /** 构造一条「compact 摘要身份齐全」的 user 消息（31 参 canonical，末 5 参 = 本批被硬编码丢掉的字段）。 */
    private static ChatMessageDto compactSummary(String summaryId) {
        return new ChatMessageDto(
            summaryId, "sess-withusage", Role.user, "system",
            "这是 compact 摘要正文", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, "compact_summary",
            false, null, null, null,
            Map.of("preTokens", 12345),               // compactMetadata 非 null
            null,                                      // microcompactMetadata（对照组：null 应保持 null）
            "uuid-last-message-before-compact",        // logicalParentUuid 非 null
            true,                                      // isCompactSummary=true
            true);                                      // isVisibleInTranscriptOnly=true
    }

    @Test
    @DisplayName("withUsage 后 compactMetadata/logicalParentUuid/两标志全部保持（仅 usage 投影被覆盖）")
    void withUsage_preservesCompactObservabilityFlags() {
        ChatMessageDto dto = compactSummary("summary-1");

        ChatMessageDto out = dto.withUsage(AgentUsage.EMPTY);

        assertThat(out).as("withUsage 非 null usage → 返回新实例（非同一对象）").isNotSameAs(dto);
        assertThat(out.isCompactSummary())
            .as("isCompactSummary 必须保持 true —— 否则 TraceView.compactSummaryAfter 断裂")
            .isTrue();
        assertThat(out.isVisibleInTranscriptOnly())
            .as("isVisibleInTranscriptOnly 必须保持 true —— 否则摘要被当普通 user 消息")
            .isTrue();
        assertThat(out.compactMetadata())
            .as("compactMetadata 必须透传（boundary 元数据 round-trip，不得被清 null）")
            .isNotNull()
            .containsEntry("preTokens", 12345);
        assertThat(out.logicalParentUuid())
            .as("logicalParentUuid 必须透传（compact boundary parent 链）")
            .isEqualTo("uuid-last-message-before-compact");
        assertThat(out.microcompactMetadata())
            .as("microcompactMetadata 同样走透传通道（原值 null → 仍 null，未被硬编码覆盖成别的值）")
            .isNull();
        // usage 投影仍生效（覆盖语义未被破坏）
        assertThat(out.inputTokens()).as("usage.inputTokens 投影到 inputTokens 字段").isEqualTo(0);
        assertThat(out.outputTokens()).as("usage.outputTokens 投影到 outputTokens 字段").isEqualTo(0);
        // 其余 identify 字段不漂移
        assertThat(out.id()).isEqualTo("summary-1");
        assertThat(out.subtype()).isEqualTo("compact_summary");
        assertThat(out.content()).isEqualTo("这是 compact 摘要正文");
    }
}
