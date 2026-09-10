package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.skill.SkillListingSentRegistry;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-10] 系统提示注入语义修正 + skill_listing dedup 单测。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>指引文本注入系统提示</b> — CC prompts.ts:352-400 getSessionSpecificGuidanceSection 注入
 *       systemPromptSection('session_guidance')（:491-494），非全量 catalog 清单；P1-10 前 Java 误把
 *       catalogText 注入 systemContext（差异项 X22 根源）。</li>
 *   <li><b>skill_listing 渲染为 user message</b> — messages.ts:4160-4170（2026-09-10 纠偏：旧注释引
 *       messages.ts:3728-3738，实为 plan-mode pair-planning 文本，与 skill_listing 无关）
 *       {@code wrapMessagesInSystemReminder([createUserMessage({content: `The following skills are
 *       available for use with the Skill tool:\n\n${attachment.content}`, isMeta:true})])}；位置 =
 *       首条用户消息之后（processTextPrompt.ts:97 {@code [userMessage, ...attachmentMessages]}），<b>非头部</b>。</li>
 *   <li><b>按 skill name 增量 dedup</b> — attachments.ts:2676 进程级 sentSkillNames（keyed by agentId，
 *       空串=主线程）、:2781-2809 get-or-create/suppress/newSkills/isInitial。2026-09-10 起 Java 侧由
 *       {@link SkillListingSentRegistry}（键 sessionId+agentKey，跨 run 存活）承载。</li>
 *   <li><b>resume suppress 与 /clear reset</b> — resume 且未初始化 → 抑制（:2791-2797）；/clear →
 *       重发整份（clear/caches.ts:79 {@code resetSentSkillNames()}）。</li>
 * </ol>
 */
class SystemPromptGuidanceTest {

    // [IMP-SP-08] ① SessionSpecificGuidance.build 三用例已删除 —— 旧类整类删除（DEL-SP-28），
    //   指引文本段由新 SessionGuidanceSection 承载（SessionGuidanceSectionTest 已覆盖 per-bullet
    //   门控矩阵，CC prompts.ts:352-400 等价）。

    // ── ② dedup：SkillListingSentRegistry.decide 按 skill name 增量（进程级，键 sessionId+agentKey）──

    @Test
    @DisplayName("dedup：首次全量 + isInitial=true，二次同集合空决策，新增 1 个仅返回该 name")
    void dedup_firstAll_thenEmpty_thenDelta() {
        SkillListingSentRegistry.reset();
        String session = "sess-dedup-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> names = List.of("commit", "review", "test");

        // 首次（全新会话首 run，!resume）→ 全量 + isInitial=true
        SkillListingSentRegistry.Decision first =
            SkillListingSentRegistry.decide(session, "", names, false);
        assertThat(first.names()).containsExactly("commit", "review", "test");
        assertThat(first.isInitial()).as("首注 isInitial").isTrue();

        // 二次同集合（resume 且已初始化）→ 无增量
        SkillListingSentRegistry.Decision second =
            SkillListingSentRegistry.decide(session, "", names, true);
        assertThat(second.names()).as("同集合二次不再注入").isEmpty();

        // 新增 1 个 → 仅返回新 name
        SkillListingSentRegistry.Decision delta = SkillListingSentRegistry.decide(
            session, "", List.of("commit", "review", "test", "new-skill"), true);
        assertThat(delta.names()).containsExactly("new-skill");
        assertThat(delta.isInitial()).as("已有 sent → 非首注").isFalse();
    }

    @Test
    @DisplayName("dedup：主线程 key=\"\" 与 subagent key 各自独立（attachments.ts:2699 agentId ?? ''）")
    void dedup_mainThreadAndSubagentIndependent() {
        SkillListingSentRegistry.reset();
        String session = "sess-key-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> names = List.of("commit");

        // 主线程 (key="") 首注全量
        SkillListingSentRegistry.Decision mainFirst =
            SkillListingSentRegistry.decide(session, "", names, false);
        assertThat(mainFirst.names()).containsExactly("commit");
        assertThat(mainFirst.isInitial()).isTrue();

        // 主线程二次（resume）→ 空
        SkillListingSentRegistry.Decision mainSecond =
            SkillListingSentRegistry.decide(session, "", names, true);
        assertThat(mainSecond.names()).as("主线程二次同集合必须 dedup 为空").isEmpty();

        // subagent 独立 key → 不受主线程影响，首注全量
        SkillListingSentRegistry.Decision sub =
            SkillListingSentRegistry.decide(session, "sub-1", names, false);
        assertThat(sub.names()).containsExactly("commit");
        assertThat(sub.isInitial()).as("subagent 各自独立 sent 集合").isTrue();
    }

    @Test
    @DisplayName("dedup：resume 且未初始化 → 抑制（全量标 sent + 空决策，attachments.ts:2791-2797）")
    void dedup_resumeUninitialized_suppresses() {
        SkillListingSentRegistry.reset();
        String session = "sess-suppress-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> names = List.of("commit");

        // resume 且未初始化（JVM 重启 / 会话首现本进程）→ 抑制：不注入，但全量标 sent
        SkillListingSentRegistry.Decision suppressed =
            SkillListingSentRegistry.decide(session, "", names, true);
        assertThat(suppressed.names()).as("resume 抑制 → 空决策").isEmpty();
        assertThat(SkillListingSentRegistry.isInitialized(session, ""))
            .as("抑制分支必须置 initialized（下一 run 才走增量）").isTrue();

        // 后续 resume → 因全量已标 sent → 仍空决策（不再注入）
        assertThat(SkillListingSentRegistry.decide(session, "", names, true).names()).isEmpty();
    }

    @Test
    @DisplayName("dedup：/clear（removeSession）→ 即便 resume=true 也重发整份（CC resetSentSkillNames）")
    void dedup_clearForcesFullReInject() {
        SkillListingSentRegistry.reset();
        String session = "sess-clear-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> names = List.of("commit", "review");

        // 首注后进入稳定态
        assertThat(SkillListingSentRegistry.decide(session, "", names, false).names()).hasSize(2);
        assertThat(SkillListingSentRegistry.decide(session, "", names, true).names()).isEmpty();

        // /clear → 下次 decide 强制整份（即使 resume=true）
        SkillListingSentRegistry.removeSession(session);
        SkillListingSentRegistry.Decision afterClear =
            SkillListingSentRegistry.decide(session, "", names, true);
        assertThat(afterClear.names()).containsExactly("commit", "review");
        assertThat(afterClear.isInitial()).as("clear 后重发 = isInitial").isTrue();
    }

    @Test
    @DisplayName("dedup：同 JVM 两会话互不串扰（sessionId 进键 · 非 CC 偏离）")
    void dedup_twoSessionsIsolated() {
        SkillListingSentRegistry.reset();
        List<String> names = List.of("commit");

        assertThat(SkillListingSentRegistry.decide("sess-A", "", names, false).names()).containsExactly("commit");
        // 会话 B 独立首注（不被 A 的 sent 串扰）
        assertThat(SkillListingSentRegistry.decide("sess-B", "", names, false).names())
            .as("会话 B 必须独立首注（带 sessionId 键）").containsExactly("commit");
        // 会话 A 二次 → 空
        assertThat(SkillListingSentRegistry.decide("sess-A", "", names, true).names()).isEmpty();
    }

    // ── ③ skill_listing 注入消息构造：skillListingMessage（真实消息，尾随用户消息；非头部）──

    @Test
    @DisplayName("skill_listing 消息构造：渲染 'The following skills are available...' 前缀 + isMeta + subtype")
    void skillListing_messageRendering() {
        ChatMessageDto msg = AgentLoopContext.skillListingMessage("sess-x", "- commit: 提交代码");

        assertThat(msg).as("skill_listing 必须渲染为一条 meta user message").isNotNull();
        assertThat(msg.content())
            .as("对齐 CC messages.ts:4160-4170 wrapMessagesInSystemReminder 前缀")
            .startsWith("<system-reminder>\nThe following skills are available for use with the Skill tool:\n\n")
            .contains("- commit: 提交代码")
            .endsWith("\n</system-reminder>");
        assertThat(msg.isMeta()).as("CC createUserMessage({content, isMeta:true})").isTrue();
        assertThat(msg.subtype()).as("落库/重放判别键").isEqualTo("skill_listing");
        assertThat(msg.author()).isEqualTo("attachment");
        assertThat(msg.sessionId()).as("真实消息落库必需 sessionId").isEqualTo("sess-x");

        // 空 / null listingText → null（对齐 CC :2720-2722 content 空 → return []）
        assertThat(AgentLoopContext.skillListingMessage("sess-x", "")).isNull();
        assertThat(AgentLoopContext.skillListingMessage("sess-x", null)).isNull();
    }

    @Test
    @DisplayName("skill_listing 不再经 maybeInjectHookAttachments 队尾重放（真实消息注入取代，防双份 + 防位置漂移）")
    void skillListing_notTailReplayedByHookAttachments() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendAttachment(AttachmentMessageDto.skillListing("- commit: 提交代码", 1, true));

        List<ChatMessageDto> messages = AgentLoopContext.maybeInjectHookAttachments(
            null, state, new ArrayList<>());

        assertThat(messages).as("skill_listing attachment 被 maybeInjectHookAttachments 跳过（不再队尾重放）")
            .isEmpty();
    }
}
