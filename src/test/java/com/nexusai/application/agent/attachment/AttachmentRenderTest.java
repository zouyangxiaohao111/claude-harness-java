package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-6-READ-2] invoked_skills attachment 类型 + 渲染测试 ·
 * 对齐 CC utils/attachments.ts:646-652 union member {@code {type:'invoked_skills', skills:[{name,path,content}]}}
 * + utils/messages.ts:3644-3662 normalizeAttachmentForAPI case 'invoked_skills'.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: skill 内容被压缩摘要吞掉后必须重注入 LLM,
 * 否则模型丢失 skill 使用指引 (READ-1 生产者已落盘)。本测试验证 READ-2 消费者侧两条链路:
 * <ol>
 *   <li><b>工厂</b> {@link AttachmentMessageDto#invokedSkills} 产出 type='invoked_skills' attachment,
 *       skills 列表正确承载 {name, path, content} (CC compact.ts:1530-1533 生产者)</li>
 *   <li><b>渲染</b> {@link AgentLoopContext#maybeInjectHookAttachments} → renderHookAttachmentForLlm
 *       case "invoked_skills": per-skill {@code ### Skill: name\nPath: path\n\ncontent} join
 *       {@code \n\n---\n\n} + 前导 {@code The following skills were invoked...} + {@code <system-reminder>}
 *       包裹的 isMeta user message (CC messages.ts:3644-3662 + :3097 wrapInSystemReminder)</li>
 * </ol>
 *
 * <p>空 skills → 不注入 (CC :3645-3646 return [])；与既有 hook_* / todo_reminder 分支互不影响。
 */
@DisplayName("[P1-6-READ-2] invoked_skills attachment 类型 + 渲染")
class AttachmentRenderTest {

    /** 空 messagesForLlm（对齐 LlmAgentLoop 组装入口: state.messages() 为起点）. */
    private List<ChatMessageDto> baseMessages(AgentState state) {
        return new ArrayList<>(state.messages());
    }

    @Test
    @DisplayName("invokedSkills 工厂 → type='invoked_skills' + skills 列表正确承载 {name,path,content}")
    void invokedSkillsFactory_producesCorrectAttachment() {
        // WHY: READ-1 createSkillAttachmentIfNeeded 依赖工厂构建 attachment (compact.ts:1530-1533);
        //      工厂必须把 {name,path,content} 原样透传, 渲染层才拼得出 per-skill 文本.
        List<AttachmentMessageDto.SkillRef> refs = List.of(
            new AttachmentMessageDto.SkillRef("test-skill", "/skills/test.md", "Some guidance"),
            new AttachmentMessageDto.SkillRef("second", "/skills/second.md", "More guidance"));

        AttachmentMessageDto attachment = AttachmentMessageDto.invokedSkills(refs);

        assertThat(attachment.type()).isEqualTo("invoked_skills");
        assertThat(attachment.skills()).hasSize(2);
        assertThat(attachment.skills().get(0).name()).isEqualTo("test-skill");
        assertThat(attachment.skills().get(0).path()).isEqualTo("/skills/test.md");
        assertThat(attachment.skills().get(0).content()).isEqualTo("Some guidance");
        assertThat(attachment.skills().get(1).name()).isEqualTo("second");
        // content 供 UI/日志展示 (CC attachment 无 content 字段, LLM 渲染走 skills())
        assertThat(attachment.content()).isEqualTo("Invoked skills: 2");
        // 空列表工厂不抛异常 (渲染层空 → 不注入)
        AttachmentMessageDto empty = AttachmentMessageDto.invokedSkills(List.of());
        assertThat(empty.type()).isEqualTo("invoked_skills");
        assertThat(empty.skills()).isEmpty();
    }

    @Test
    @DisplayName("maybeInjectHookAttachments 渲染 invoked_skills: per-skill 格式 + \\n\\n---\\n\\n 分隔 + 前导 + <system-reminder> 包裹")
    void invokedSkills_renderedIntoSingleSystemReminderMetaMessage() {
        // WHY: CC messages.ts:3652-3661 把 skills 拼成单条 system-reminder user message 注入;
        //      Java 端等价物必须产出完全相同文本, 模型才能继续遵循 skill 指引.
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.invokedSkills(List.of(
            new AttachmentMessageDto.SkillRef("test-skill", "/skills/test.md", "Some guidance"),
            new AttachmentMessageDto.SkillRef("second", "/skills/second.md", "More guidance"))));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        String expected =
            "<system-reminder>\n"
            + "The following skills were invoked in this session. Continue to follow these guidelines:\n"
            + "\n"
            + "### Skill: test-skill\n"
            + "Path: /skills/test.md\n"
            + "\n"
            + "Some guidance\n"
            + "\n"
            + "---\n"
            + "\n"
            + "### Skill: second\n"
            + "Path: /skills/second.md\n"
            + "\n"
            + "More guidance\n"
            + "</system-reminder>";

        assertThat(injected)
            .as("invoked_skills 必须作为 user-role meta 消息注入 messagesForLlm（LLM 可见）")
            .anySatisfy(m -> {
                assertThat(m.role()).isEqualTo(Role.user);
                assertThat(m.content()).isEqualTo(expected);
            });
    }

    @Test
    @DisplayName("skills 空 → 不注入 (CC messages.ts:3645-3646 return [])")
    void invokedSkills_emptySkills_notInjected() {
        // WHY: CC 对空 skills 直接 return [], 不产出任何 LLM 消息; Java 端若注入会污染上下文.
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.invokedSkills(List.of()));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected).isEmpty();
    }

    @Test
    @DisplayName("[WF6 R1] plan_mode attachment 由 maybeInjectHookAttachments 跳过（专用 plan 路径渲染，防双发）")
    void planModeAttachment_skippedByHookInjection() {
        // WHY: maybeInjectPlanModeAttachments 每 tool 轮生产并渲染 plan_mode（含节流 + full/sparse 周期），
        //      同时 append 进 state.attachments() 作计数源；若 maybeInjectHookAttachments 再重渲染，
        //      会破坏 TURNS_BETWEEN_ATTACHMENTS=5 节流 + 双发。跳过守卫是 R1 防回归的关键。
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.planMode("full", false, "/plans/s.md", false));
        state.appendAttachment(AttachmentMessageDto.planModeReentry("/plans/s.md"));
        state.appendAttachment(AttachmentMessageDto.planModeExit("/plans/s.md", false));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected).as("plan_mode/plan_mode_reentry/plan_mode_exit 不得经 hook 路径渲染").isEmpty();
    }

    @Test
    @DisplayName("[WF6 R2] plan_file_reference 经 maybeInjectHookAttachments 渲染为 system-reminder（携带真实全文）")
    void planFileReference_renderedAsSystemReminder() {
        // WHY: 压缩重建链 populatePlanAttachment 经 typed 工厂把 plan_file_reference 写入
        //      state.attachments()，必须经 hook 路径渲染为 system-reminder 携带磁盘全文（CC
        //      compact.ts:545-548 + messages.ts:3636-3643）——这是 typed 工厂获得真实调用方后
        //      render case 'plan_file_reference' 复活（R2 收敛双轨）的对抗性证明。
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(AttachmentMessageDto.planFileReference(
            new AttachmentMessageDto.PlanRef("/plans/s.md", "full plan content")));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        assertThat(injected).hasSize(1);
        assertThat(injected.get(0).content())
            .contains("<system-reminder>")
            .contains("/plans/s.md")
            .contains("full plan content")
            .contains("A plan file exists from plan mode at:");
    }

    @Test
    @DisplayName("与既有分支互不影响: todo_reminder 仍不渲染, invoked_skills 正常渲染")
    void invokedSkills_doesNotAffectExistingNonRenderedTypes() {
        // WHY: 新增 case 不得改变既有 todo_reminder/max_turns_reached 等"不渲染"类型的行为
        //      (CC normalizeAttachmentForAPI 对它们返回 []); 同时 invoked_skills 独立注入.
        AgentState state = new AgentState("system-prompt");
        state.appendAttachment(new AttachmentMessageDto(
            null, "attachment", "todo_reminder", "todo reminder text", null, null, null));
        state.appendAttachment(AttachmentMessageDto.invokedSkills(List.of(
            new AttachmentMessageDto.SkillRef("test-skill", "/skills/test.md", "Some guidance"))));

        List<ChatMessageDto> injected =
            AgentLoopContext.maybeInjectHookAttachments(null, state, baseMessages(state));

        // 只有 invoked_skills 的渲染结果注入 (1 条); todo_reminder 不渲染 (CC return [])
        assertThat(injected).hasSize(1);
        assertThat(injected.get(0).content())
            .contains("<system-reminder>")
            .contains("### Skill: test-skill")
            .contains("The following skills were invoked in this session.")
            .doesNotContain("todo reminder text");
    }
}
