package com.nexusai.application.agent.compact;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NEW-GAP-4 · {@link StreamCompactSummary#stripReinjectedAttachments} 精确 subtype 判别单测
 * · 对齐 CC compact.ts:211-223（按 {@code m.attachment.type} 精确匹配）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>: 旧实现按 {@code content.contains("skill_discovery")} /
 * {@code content.contains("skill_listing")} 子串判别，存在两类缺陷：
 * <ul>
 *   <li><b>漏剥</b>：真实 skill_listing 附件 content 是技能清单文本，不含 {@code skill_listing}
 *       字面量 → 判别失效，重注入附件未被剥离；</li>
 *   <li><b>误剥</b>：content 偶然含 {@code skill_listing} 字面量的普通附件被误剥。</li>
 * </ul>
 * 修复后按 {@code ChatMessageDto.subtype}（映射 CC attachment.type）精确判别：subtype 为
 * skill_discovery / skill_listing 才剥离，其它类型 / subtype=null 不受影响。
 */
class StreamCompactSummaryStripReinjectedAttachmentsTest {

    @org.junit.jupiter.api.BeforeEach
    void enableSkillPrefetchGate() {
        // [merge 适配 2026-08-14] stripReinjectedAttachments 按 CC compact.ts:213
        //   feature('EXPERIMENTAL_SKILL_SEARCH') 门控（默认关 → no-op）；本测试验证剥离行为
        //   须开启 skillPrefetch 门，否则门关 no-op 剥离不生效（master 侧测试遗漏注入）。
        StreamCompactSummary.setFeatureFlags(
            new com.nexusai.application.agent.loop.FeatureFlags(true, false, true,
                false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false, false));
    }

    @org.junit.jupiter.api.AfterEach
    void resetGate() {
        StreamCompactSummary.setFeatureFlags(null);
    }

    @Test
    @DisplayName("NEW-GAP-4: subtype=skill_listing 的清单文本附件被剥离")
    void stripsSkillListingAttachment() {
        ChatMessageDto listing = attachment("skill_listing",
            "可用技能清单（skill 名称/路径/描述，不含字面量 skill_listing）");
        ChatMessageDto keep = userMessage("普通消息");
        List<ChatMessageDto> out =
            StreamCompactSummary.stripReinjectedAttachments(List.of(listing, keep));
        // WHY: 真实 skill_listing 附件 content 是清单文本，旧 content.contains 判别会漏剥
        assertThat(out).containsExactly(keep);
    }

    @Test
    @DisplayName("NEW-GAP-4: subtype=skill_discovery 附件被剥离")
    void stripsSkillDiscoveryAttachment() {
        ChatMessageDto discovery = attachment("skill_discovery", "发现可用技能");
        List<ChatMessageDto> out = StreamCompactSummary.stripReinjectedAttachments(List.of(discovery));
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("NEW-GAP-4: subtype=invoked_skills 等其它类型附件不被剥")
    void keepsOtherSubtypeAttachment() {
        ChatMessageDto invoked = attachment("invoked_skills", "{\"type\":\"invoked_skills\"}");
        List<ChatMessageDto> out = StreamCompactSummary.stripReinjectedAttachments(List.of(invoked));
        assertThat(out).containsExactly(invoked);
    }

    @Test
    @DisplayName("NEW-GAP-4: content 偶然含 skill_listing 字面量的普通附件（subtype=null）不被误剥")
    void keepsOrdinaryAttachmentWithLiteralInContent() {
        // WHY: 旧 content.contains 判别会把这种普通附件误剥；精确 subtype 判别下 subtype=null 不受影响
        ChatMessageDto ordinary = attachment(null, "文档内容里偶然提到 skill_listing 字样");
        List<ChatMessageDto> out = StreamCompactSummary.stripReinjectedAttachments(List.of(ordinary));
        assertThat(out).containsExactly(ordinary);
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具
    // ════════════════════════════════════════════════════════════════════

    /** attachment 消息（author='attachment' + subtype）· 21 参构造器末参 subtype。 */
    private static ChatMessageDto attachment(String subtype, String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "attachment",
            content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false,
            subtype);
    }

    /** 普通 user 消息。 */
    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user",
            content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }
}
