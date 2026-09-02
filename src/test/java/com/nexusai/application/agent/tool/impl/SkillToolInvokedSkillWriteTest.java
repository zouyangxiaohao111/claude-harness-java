package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * [P1-6] SkillTool 写入侧（addInvokedSkill）对齐 CC 测试 · 对齐 CC processSlashCommand.tsx:880-885
 * （本地 inline 写入：skillPath=`${source}:${name}` / content=渲染后全文 / agentId=当前 agent）。
 *
 * <p>规则九（验证意图）: 已调用 skill 的渲染后全文必须落进会话 AgentState.invokedSkills，且
 * 主会话条目 agentId=null —— 否则压缩后重注入闭环（CompactContext.afterCompact →
 * {@code createSkillAttachmentIfNeeded} → {@code getInvokedSkillsForAgent(state.agentId())}，
 * CompactContext.java:514）恒 null，skill 指引在压缩摘要后丢失。
 *
 * <p>RED 依据: 实施前 SkillToolImpl.doExecute 无任何 addInvokedSkill 实写（grep 实证仅 JavaDoc /
 * CLEANUP-1 清理引用），本测试断言 getInvokedSkillsForAgent(null) 为空 → 先红后绿。
 *
 * <p><b>写侧 key 语义（EVD-B 归因闸）</b>: 写侧 agentId 取 ctx.agentId() 当且仅当
 * {@code sessionStateResolver.apply(ctx.agentId()) != null} —— registry 按 agentUuid 命中已注册
 * 后台 AgentState（后台化主会话任务以 agentId=agentUuid 注册，LlmAgentLoop:1659）即归因该
 * agentId，使 /clear preservedAgentIds={task.agentId()}（conversation.ts:93-106 /
 * CommandController:370-373）能匹配保留；命中失败 → null（对齐 CC processSlashCommand.tsx:885
 * {@code getAgentContext()?.agentId ?? null} 兜底）。主会话 ctx.agentId() 为 ToolUseContext
 * compact ctor 兜底随机 UUID（ToolUseContext.java:274-277）→ registry 查询必空 → null（既有
 * 行为不变）；fork 子 agent 不注册 → null。本测试的 inline 用例传入随机 agentId 的 ctx，
 * 断言条目仍 key 在 null-agent 下，证明该闸门成立。
 */
@DisplayName("[P1-6] SkillTool 写入侧 addInvokedSkill（压缩存活闭环的写入环节）")
class SkillToolInvokedSkillWriteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 技能正文标记 · 断言写入的 content 是渲染后全文（含 baseDir 头），而非元数据。 */
    private static final String BODY_MARKER = "INLINE_SKILL_BODY_MARKER_cc_alignment";

    /** 无 context 前导 → 走 inline 路径（CC processPromptSlashCommand）。 */
    private SkillRegistry newInlineRegistry(Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("inline-skill");
        Files.createDirectories(skillDir);
        String md = "---\n"
                + "name: inline-skill\n"
                + "description: inline 展开技能\n"
                + "---\n"
                + "# Inline Skill\n\n"
                + BODY_MARKER + "\n";
        Files.writeString(skillDir.resolve("SKILL.md"), md);
        return new SkillRegistry(tempDir.toString());
    }

    /** context: fork → 走 executeForkedSkill 分支（CC 'fork 不写'）。 */
    private SkillRegistry newForkRegistry(Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("fork-skill");
        Files.createDirectories(skillDir);
        String md = "---\n"
                + "name: fork-skill\n"
                + "description: fork 隔离执行技能\n"
                + "context: fork\n"
                + "agent: general-purpose\n"
                + "---\n"
                + "# Fork Skill\n\n"
                + BODY_MARKER + "\n";
        Files.writeString(skillDir.resolve("SKILL.md"), md);
        return new SkillRegistry(tempDir.toString());
    }

    private ToolUseBlock skillBlock(String skillName, String args) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", skillName);
        if (args != null) {
            input.put("args", args);
        }
        return new ToolUseBlock("tool-use-1", "Skill", input);
    }

    @Test
    @DisplayName("inline skill 调用 → 会话 invokedSkills 出现 null-agent 条目（name/path/渲染后全文/agentId=null）")
    void inlineSkill_writesInvokedSkillsForMainAgent(@TempDir Path tempDir) throws Exception {
        // WHY: CC processSlashCommand.tsx:883-885 — inline 技能渲染后全文写入 STATE.invokedSkills，
        //   主会话 agentId=null。若写入缺失：压缩后 createSkillAttachmentIfNeeded(state.agentId())
        //   读到空 Map → 恒 null → 模型丢失 skill 使用指引（闭环断）。
        SkillToolImpl tool = new SkillToolImpl(newInlineRegistry(tempDir));

        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState sessionState = new AgentState("test-system-prompt");
        tool.setSessionStateResolver(sid -> sid.equals(sessionId) ? sessionState : null);

        // 故意传随机 agentId 的 ctx —— registry 命中闸必 miss（resolver 只匹配 sessionId，
        // apply(随机agentId)=null）→ 写侧归因 null，随机 UUID 不污染写侧 key
        //（对齐 CC getAgentContext()?.agentId ?? null 主会话语义）。
        tool.execute(skillBlock("inline-skill", null), ToolUseContext.of(UUID.randomUUID(), sessionId));

        var mainSkills = sessionState.getInvokedSkillsForAgent(null);
        assertThat(mainSkills)
                .as("inline skill 调用后主会话(null-agent)必须出现 invokedSkills 条目 (CC processSlashCommand.tsx:885)")
                .hasSize(1);
        mainSkills.forEach((key, info) -> {
            assertThat(key).as("复合键 = `${agentId??''}:${skillName}`，主会话前缀空 (CC state.ts:1516)").isEqualTo(":inline-skill");
            assertThat(info.skillName()).as("skillName = 技能名 (CC state.ts:1503)").isEqualTo("inline-skill");
            assertThat(info.skillPath())
                    .as("skillPath = `${source}:${name}`，source 小写 'user' (CC processSlashCommand.tsx:883, CommandSource.USER)")
                    .isEqualTo("user:inline-skill");
            assertThat(info.content())
                    .as("content = 渲染后全文（getPromptForCommand 输出：baseDir 头 + 正文），非 metadata")
                    .startsWith("Base directory for this skill:")
                    .contains(BODY_MARKER);
            assertThat(info.agentId()).as("主会话条目 agentId=null（CC getAgentContext()?.agentId ?? null）").isNull();
        });
    }

    @Test
    @DisplayName("fork skill 调用 → 不写 invokedSkills（CC SkillTool.ts:622-632 fork 路由先于 processSlashCommand，'fork 不写'）")
    void forkSkill_doesNotWriteInvokedSkills(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:622-632 context==='fork' 直接 executeForkedSkill，processSlashCommand
        //   （含 addInvokedSkill 调用点，:761-764 注释）不执行 → fork 分支天然不写。
        //   若 fork 误写：fork 子 agent skill 全文进主 map，与 fork 短命清理语义冲突。
        SkillToolImpl tool = new SkillToolImpl(newForkRegistry(tempDir));

        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState sessionState = new AgentState("test-system-prompt");
        // 预置一条主会话旧条目 —— 验证 fork 不写不新增、且 fork finally 清理不影响主条目
        sessionState.addInvokedSkill("existing", "/existing/SKILL.md", "old", null);

        SubagentExecutor executor = mock(SubagentExecutor.class);
        doAnswer(inv -> SubagentExecutor.SubagentResult.completed(
                "fork 完成", 0, 0L, UUID.randomUUID().toString()))
                .when(executor).executeForkedSkill(
                        anyString(), any(Command.class), anyString(),
                        any(ToolUseContext.class), any());
        tool.setSubagentExecutor(executor);
        tool.setSessionStateResolver(sid -> sid.equals(sessionId) ? sessionState : null);

        tool.execute(skillBlock("fork-skill", null), ToolUseContext.of(UUID.randomUUID(), sessionId));

        assertThat(sessionState.getInvokedSkillsForAgent(null))
                .as("fork 分支不得写入主会话 invokedSkills (CC SkillTool.ts:622-632 + :761-764)")
                .hasSize(1)
                .allSatisfy((k, info) -> assertThat(info.skillName()).isEqualTo("existing"));
        assertThat(sessionState.getInvokedSkills()).hasSize(1);
    }

    @Test
    @DisplayName("sessionStateResolver 未注入 → inline 写入安全跳过不 NPE（null-safe 降级）")
    void inlineSkill_withoutResolver_isSafeNoop(@TempDir Path tempDir) throws Exception {
        // WHY: resolver 未接线（非 Spring 场景 / 生产 registry 缺失）时写入侧必须安全跳过
        //   （CC addInvokedSkill 无校验无抛错；CLAUDE.md 规则十二 显式失败不掩盖 —— debug 日志 skip）。
        //   若缺 resolver 抛 NPE：1 参 execute 旧路径 / 单测无注入场景直接崩溃，破坏调用链。
        SkillToolImpl tool = new SkillToolImpl(newInlineRegistry(tempDir));
        // 不注入 sessionStateResolver

        var result = tool.execute(skillBlock("inline-skill", null),
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
                .as("resolver 未注入不影响 inline skill 正常执行返回")
                .isFalse();
        assertThat(String.valueOf(result.data()))
                .as("inline skill 正常返回 success=true metadata")
                .contains("success");
    }

    @Test
    @DisplayName("[EVD-B] 后台 AgentState 按 agentUuid 注册命中 → skill 归因 agentUuid，/clear preserved 保留")
    void backgroundAgentRegistryHit_attributesSkillToAgentUuid_andPreservesOnClear(@TempDir Path tempDir) throws Exception {
        // WHY: SkillToolImpl:1346-1350 归属闸 —— skillAgentId=ctx.agentId() 当且仅当 registry 按 agentUuid
        //   命中已注册后台 AgentState，否则 null（CC processSlashCommand.tsx:885 + LocalMainSessionTask.ts:368
        //   runWithAgentContext{agentId:taskId}）。若正向分支断链：后台主会话 skill 落 null-agent 条目，
        //   /clear preservedAgentIds={agentUuid}（CommandController:370-373 / CC conversation.ts:93-106）
        //   匹配不到 → 后台 skill 被误清（state.ts:1551），后续继续查询时模型失忆。
        SkillToolImpl tool = new SkillToolImpl(newInlineRegistry(tempDir));

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);   // 主会话 sessionId（MainSessionBackgroundService:338）
        UUID agentUuid = UUID.randomUUID();     // 后台任务 agentId（MainSessionBackgroundService:341 agentUuid）
        AgentState sharedState = new AgentState("test-system-prompt");                    // 共享会话 AgentState（≈CC 全局 STATE）
        AgentState bgState = new AgentState("test-system-prompt", sessionUuid, agentUuid); // 后台 loop AgentState

        // 生产接线还原：LlmAgentLoop:1656-1659 双分支注册（主会话按 sessionId / 后台按 agentId）
        //   + ToolRegistrationConfig:349 resolver=sessionAgentStateRegistry::get
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        registry.register(sessionUuid, sharedState);
        registry.register(agentUuid, bgState);
        tool.setSessionStateResolver(registry::get);

        // 后台 loop 工具线程 ctx：agentId()=agentUuid（buildBaseToolUseContext 传 state.agentId()，LlmAgentLoop:4803）
        tool.execute(skillBlock("inline-skill", null), ToolUseContext.of(agentUuid, sessionUuid));

        // 断言 1：正向分支 —— 条目落共享会话 AgentState，key=agentUuid:skill，agentId=agentUuid（CC state.ts:1516）
        var bgSkills = sharedState.getInvokedSkillsForAgent(agentUuid);
        assertThat(bgSkills)
                .as("后台 skill 必须按 agentUuid 归因（CC processSlashCommand.tsx:885 + LocalMainSessionTask.ts:368），否则 /clear 误清")
                .hasSize(1);
        bgSkills.forEach((key, info) -> {
            assertThat(key).as("复合键 = `${agentId}:${skillName}`（CC state.ts:1516）").isEqualTo(agentUuid + ":inline-skill");
            assertThat(info.agentId()).as("条目 agentId=agentUuid（CC state.ts:1504）").isEqualTo(agentUuid);
            assertThat(info.skillName()).as("skillName = 技能名（CC state.ts:1503）").isEqualTo("inline-skill");
        });

        // 主会话对照：随机 agentId ctx → registry miss → 仍 null 归因；同 skillName 双条目并存不覆盖（复合键语义）
        tool.execute(skillBlock("inline-skill", null), ToolUseContext.of(UUID.randomUUID(), sessionUuid));
        assertThat(sharedState.getInvokedSkillsForAgent(null))
                .as("ctx.agentId() registry miss → null 归因（既有行为不变）")
                .hasSize(1);

        // 断言 2：/clear preserved={agentUuid}（对齐 CommandController:370-373 + CC conversation.ts:93-106 + state.ts:1543-1555）
        sharedState.clearInvokedSkills(java.util.Set.of(agentUuid));
        assertThat(sharedState.getInvokedSkillsForAgent(agentUuid))
                .as("后台 agentId∈preserved → 跨 /clear 存活（state.ts:1551 保留语义）")
                .hasSize(1);
        assertThat(sharedState.getInvokedSkillsForAgent(null))
                .as("null-agent 主会话条目恒被清（state.ts:1551）")
                .isEmpty();
    }
}
