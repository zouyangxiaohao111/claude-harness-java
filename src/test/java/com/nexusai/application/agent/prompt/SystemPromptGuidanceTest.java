package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.model.command.Command;
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
 *   <li><b>skill_listing 渲染为 user message</b> — messages.ts:3728-3738
 *       {@code wrapMessagesInSystemReminder([createUserMessage({content: `The following skills are
 *       available for use with the Skill tool:\n\n${attachment.content}`, isMeta:true})])}。</li>
 *   <li><b>按 skill name 增量 dedup</b> — attachments.ts:2607 sentSkillNames（keyed by agentId，空串=主线程）、
 *       :2717-2730 newSkills filter + isInitial = sent.size===0。P1-10 前是 catalogText.hashCode() +
 *       enableSkillDedup=false 默认关（C-8/D-5 双实现漂移）。</li>
 *   <li><b>主线程 key="" 不绕过 dedup</b> — 旧 isSkillCatalogAlreadySent 对 agentKey==null 直接 return false
 *       （:497-499），主线程恒绕过 dedup 每轮重发。</li>
 * </ol>
 */
class SystemPromptGuidanceTest {

    private static Command cmd(String name) {
        Command c = new Command();
        c.setName(name);
        c.setType("prompt");
        return c;
    }

    // [IMP-SP-08] ① SessionSpecificGuidance.build 三用例已删除 —— 旧类整类删除（DEL-SP-28），
    //   指引文本段由新 SessionGuidanceSection 承载（SessionGuidanceSectionTest 已覆盖 per-bullet
    //   门控矩阵，CC prompts.ts:352-400 等价）。

    // ── ② dedup：computeSkillListingDelta 按 skill name 增量 ──

    @Test
    @DisplayName("dedup：首次全量 + isInitial=true，二次同集合空 delta，新增 1 个仅返回该 name")
    void dedup_firstAll_thenEmpty_thenDelta() {
        AgentLoopContext ctx = new AgentLoopContextFactory().forSession("/t", "s", "m");
        List<Command> commands = List.of(cmd("commit"), cmd("review"), cmd("test"));

        // 首次 → 全量 + isInitial=true
        AgentLoopContext.SkillListingDelta first = AgentLoopContext.computeSkillListingDelta(ctx, "main", commands);
        assertThat(first.newSkills()).extracting(Command::getName).containsExactly("commit", "review", "test");
        assertThat(first.isInitial()).as("sent.size===0 → 首注").isTrue();

        // 二次同集合 → 空 delta
        AgentLoopContext.SkillListingDelta second = AgentLoopContext.computeSkillListingDelta(ctx, "main", commands);
        assertThat(second.newSkills()).as("同集合二次不再注入").isEmpty();

        // 新增 1 个 → 仅返回新 name
        AgentLoopContext.SkillListingDelta delta = AgentLoopContext.computeSkillListingDelta(
            ctx, "main", List.of(cmd("commit"), cmd("review"), cmd("test"), cmd("new-skill")));
        assertThat(delta.newSkills()).extracting(Command::getName).containsExactly("new-skill");
        assertThat(delta.isInitial()).as("sent.size>0 → 非首注").isFalse();
    }

    @Test
    @DisplayName("dedup：主线程 key=\"\" 与 subagent key 各自独立（attachments.ts:2699 agentId ?? ''）")
    void dedup_mainThreadAndSubagentIndependent() {
        AgentLoopContext ctx = new AgentLoopContextFactory().forSession("/t", "s", "m");
        List<Command> commands = List.of(cmd("commit"));

        // 主线程 (key="") 首注全量
        AgentLoopContext.SkillListingDelta mainFirst =
            AgentLoopContext.computeSkillListingDelta(ctx, null, commands);
        assertThat(mainFirst.newSkills()).extracting(Command::getName).containsExactly("commit");
        assertThat(mainFirst.isInitial()).isTrue();

        // 主线程二次 → 空（不因 agentId=null 绕过 dedup · 修复 C-8 旧实现 :497-499）
        AgentLoopContext.SkillListingDelta mainSecond =
            AgentLoopContext.computeSkillListingDelta(ctx, null, commands);
        assertThat(mainSecond.newSkills()).as("主线程二次同集合必须 dedup 为空（旧实现 agentKey==null 绕过）").isEmpty();

        // subagent 独立 key → 不受主线程影响，首注全量
        AgentLoopContext.SkillListingDelta sub = AgentLoopContext.computeSkillListingDelta(ctx, "sub-1", commands);
        assertThat(sub.newSkills()).extracting(Command::getName).containsExactly("commit");
        assertThat(sub.isInitial()).as("subagent 各自 turn-0 全量").isTrue();
    }

    @Test
    @DisplayName("dedup：suppressNextSkillListing 命中 → 全量标已发送并返回空 delta（attachments.ts:2709-2715）")
    void dedup_suppressNext_returnsEmptyAndMarksAll() {
        AgentLoopContext ctx = new AgentLoopContextFactory().forSession("/t", "s", "m");
        List<Command> commands = List.of(cmd("commit"));
        ctx.sessionState().suppressNextSkillListing().set(true);

        AgentLoopContext.SkillListingDelta suppressed =
            AgentLoopContext.computeSkillListingDelta(ctx, "main", commands);
        assertThat(suppressed.newSkills()).as("resume 抑制 → 空 delta").isEmpty();
        assertThat(ctx.sessionState().suppressNextSkillListing().get())
            .as("suppressNext 一次性消费后自动 reset")
            .isFalse();

        // 后续真实调用 → 因全量已标 sent → 空 delta（不再注入）
        AgentLoopContext.SkillListingDelta after = AgentLoopContext.computeSkillListingDelta(ctx, "main", commands);
        assertThat(after.newSkills()).isEmpty();
    }

    // ── ③ skill_listing 渲染：renderHookAttachmentForLlm → user message ──

    @Test
    @DisplayName("skill_listing 渲染：经 maybeInjectHookAttachments 注入 'The following skills are available...' 前缀")
    void skillListing_rendersAsUserMessage() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendAttachment(AttachmentMessageDto.skillListing("- commit: 提交代码", 1, true));

        List<ChatMessageDto> messages = AgentLoopContext.maybeInjectHookAttachments(
            null, state, new ArrayList<>());

        assertThat(messages).as("skill_listing 必须渲染为一条 meta user message").hasSize(1);
        assertThat(messages.get(0).content())
            .as("对齐 CC messages.ts:3734 wrapInSystemReminder 前缀")
            .contains("The following skills are available for use with the Skill tool:")
            .contains("- commit: 提交代码");
    }
}
