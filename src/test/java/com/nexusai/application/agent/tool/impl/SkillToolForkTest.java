package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [P0-1] SkillTool fork 模式对齐 CC 测试 · 对齐 CC SkillTool.ts:122-289 executeForkedSkill
 * (forkedAgent.ts:224 promptMessages = [createUserMessage({content: skillContent})]).
 *
 * <p>规则九 (验证意图): context=fork 技能必须<b>真实启动隔离子代理执行</b> — fork 子代理收到的
 * 用户消息 = 技能内容 (untagged, CC getPromptForCommand 输出), 而非用户 args; executor 缺失时
 * 必须显式失败 (CC fork 无 inline 降级); fork 执行含 tool 内容消息时向父 onProgress 上报
 * skill_progress. 若这三项不成立, fork 技能要么静默降级为 inline (生产恒 null 的旧 BUG), 要么
 * 子代理拿到错误指令 (用户 args) 无法按技能正文执行.
 *
 * <p>RED 依据: 实施前 (a) 传 args 非技能内容 (SkillToolImpl.java:340 自验); (b) executor=null
 * 走 inline fallback+warn (:351-354); (c) 无 onProgress 上报. 实施后转 GREEN.
 */
@DisplayName("[P0-1] SkillTool fork 模式对齐 CC executeForkedSkill")
class SkillToolForkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 技能正文标记 · 断言 fork 第一参数含技能内容而非 args. */
    private static final String BODY_MARKER = "FORK_SKILL_BODY_MARKER_cc_alignment";

    /**
     * 在 @TempDir 下创建 context=fork 技能目录 (skills/&lt;name&gt;/SKILL.md) · 对齐
     * SkillsLoader 目录布局 (skillsRoot/<skillName>/SKILL.md).
     */
    private SkillRegistry newForkRegistry(Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("fork-skill");
        Files.createDirectories(skillDir);
        String md = "---\n"
                + "name: fork-skill\n"
                + "description: fork 隔离执行技能\n"
                + "context: fork\n"
                + "agent: general-purpose\n"
                + "effort: high\n"
                + "---\n"
                + "# Fork Skill\n\n"
                + BODY_MARKER + "\n";
        Files.writeString(skillDir.resolve("SKILL.md"), md);
        return new SkillRegistry(tempDir.toString());
    }

    private ToolUseBlock forkBlock(String args) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "fork-skill");
        input.put("args", args);
        return new ToolUseBlock("tool-use-1", "Skill", input);
    }

    @Test
    @DisplayName("fork skill → executeForkedSkill 第一参数 = 技能内容 (untagged), 非用户 args (CC forkedAgent.ts:224)")
    void forkSkill_firstArgShouldBeSkillContentNotArgs(@TempDir Path tempDir) throws Exception {
        // WHY: CC fork 子代理收到 promptMessages = [createUserMessage({content: skillContent})]
        //   (forkedAgent.ts:224, SkillTool.ts:206) — skillContent 是 getPromptForCommand 输出
        //   (loadSkillsDir.ts:344-379, 无 command-name 标签). 旧实现传用户 args (SkillToolImpl.java:340)
        //   是 BUG: 子代理拿到指令而非技能正文, 无法按技能执行.
        SkillToolImpl tool = new SkillToolImpl(newForkRegistry(tempDir));

        SubagentExecutor executor = mock(SubagentExecutor.class);
        doAnswer(inv -> SubagentExecutor.SubagentResult.completed(
                "fork 完成", 0, 0L, "fork-agent-id"))
                .when(executor).executeForkedSkill(
                        anyString(), any(Command.class), anyString(),
                        any(ToolUseContext.class), any());
        tool.setSubagentExecutor(executor);

        tool.execute(forkBlock("user-args"), ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        // verify executeForkedSkill 第一参数 (prompt) = 技能内容 (含正文 marker + baseDir 前缀), 非 args
        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Command> cmdCaptor = org.mockito.ArgumentCaptor.forClass(Command.class);
        verify(executor).executeForkedSkill(
                promptCaptor.capture(), cmdCaptor.capture(), anyString(),
                any(ToolUseContext.class), any());
        assertThat(promptCaptor.getValue())
                .as("fork 第一参数必须是技能内容 (getPromptForCommand 输出), 而非用户 args")
                .contains(BODY_MARKER)
                .startsWith("Base directory for this skill:")
                // P0-4 CC 对齐: substituteArguments appendIfNoPlaceholder=true (loadSkillsDir.ts:351)
                //   → 技能正文无 $ARGUMENTS 占位符时追加 "ARGUMENTS: {args}" (argumentSubstitution.ts:140-141)。
                //   args 以扩展形式并入技能内容（CC fork skillContent 语义），不再作为独立裸消息传入。
                .contains("ARGUMENTS: user-args");
        assertThat(cmdCaptor.getValue().getEffort())
                .as("fork 的 effort 从 frontmatter 解析进 Command (CC SkillTool.ts:208-212 合并数据源)")
                .isEqualTo("high");
    }

    @Test
    @DisplayName("fork skill + executor=null → 抛 IllegalStateException (fail loud, CC fork 无 inline 降级)")
    void forkSkill_withoutExecutor_shouldFailLoud(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:622-632 context==='fork' 直接 executeForkedSkill, 无 inline 降级
        //   语义. 旧实现 executor=null 静默 fallback inline + warn (SkillToolImpl.java:351-354) —
        //   生产恒 null 时 fork 技能默默降级为 inline, 与 CC 语义漂移且用户无感知. 现改为显式
        //   IllegalStateException (fail loud, 规则十二).
        SkillToolImpl tool = new SkillToolImpl(newForkRegistry(tempDir));
        // 不注入 subagentExecutor (模拟 bean 缺失)

        assertThatThrownBy(() ->
                tool.execute(forkBlock("user-args"), ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fork mode 需要 SubagentExecutor bean");
    }

    @Test
    @DisplayName("fork 执行含 tool 内容消息 → onProgress 收到 skill_progress (CC SkillTool.ts:240-261)")
    void forkSkill_withToolContentMessage_shouldReportSkillProgress(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:240-261 — fork 子代理产出含 tool_use/tool_result 的消息时,
        //   onProgress({toolUseID: 'skill_'+parentMessage.id, data:{message, type:'skill_progress',
        //   prompt: skillContent, agentId}}). 若不上报, 父 Agent 无法在 fork 执行期间观测
        //   子代理工具活动 (skill 卡死无反馈).
        SkillToolImpl tool = new SkillToolImpl(newForkRegistry(tempDir));

        SubagentExecutor executor = mock(SubagentExecutor.class);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<SubagentMessage> sink =
                    inv.getArgument(4);
            if (sink != null) {
                // 模拟 fork 子代理产出含 tool 内容的 assistant 消息 (toSubagentMessage seam)
                // [P1-18] toolContent=true + agentId="fork-agent-id" 携带 — 对齐 CC SkillTool.ts:246-248
                //   (仅含 tool_use/tool_result 块上报) + :256 (onProgress.data.agentId 真实 fork agentId)
                sink.accept(new SubagentMessage.AssistantMessage(
                        "工具调用结果", AgentUsage.EMPTY, true, "fork-agent-id"));
            }
            return SubagentExecutor.SubagentResult.completed(
                    "fork 完成", 1, 12L, "fork-agent-id");
        }).when(executor).executeForkedSkill(
                anyString(), any(Command.class), anyString(),
                any(ToolUseContext.class), any());
        tool.setSubagentExecutor(executor);

        List<Tool.ToolProgress> progress = new ArrayList<>();
        var result = tool.execute(forkBlock("user-args"),
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                progress::add);

        assertThat(result)
                .as("fork 分支必须走真实 sub-agent 执行并返回 CC-style fork result")
                .isNotNull();
        assertThat(result.toString())
                .as("fork result 含 status='forked' (CC SkillTool.ts:278-283)")
                .contains("forked");

        assertThat(progress)
                .as("fork 执行含 tool 内容消息必须向父 onProgress 上报 skill_progress")
                .anySatisfy(p -> {
                    assertThat(p.toolUseId())
                            .as("toolUseId 格式 'skill_'+parentMessage.id (CC SkillTool.ts:251)")
                            .startsWith("skill_");
                    assertThat(p.data())
                            .as("data 必须为 SkillProgressData (CC onProgress.data 结构)")
                            .isInstanceOf(SkillToolImpl.SkillProgressData.class);
                    SkillToolImpl.SkillProgressData d = (SkillToolImpl.SkillProgressData) p.data();
                    assertThat(d.type()).isEqualTo("skill_progress");
                    assertThat(d.prompt())
                            .as("data.prompt = 技能内容 (CC SkillTool.ts:255)")
                            .contains(BODY_MARKER);
                    assertThat(d.agentId())
                            .as("[P1-18] data.agentId = 真实 fork 子代理 id (CC SkillTool.ts:256), 不再硬编码 null")
                            .isEqualTo("fork-agent-id");
                    assertThat(d.message())
                            .as("data.message = 归一化消息 (CC SkillTool.ts:253)")
                            .isInstanceOf(SubagentMessage.AssistantMessage.class);
                });
    }

    @Test
    @DisplayName("fork 纯文本消息 (无 tool 块) → 不向父 onProgress 上报 skill_progress (CC SkillTool.ts:246-248)")
    void forkSkill_textOnlyMessage_shouldNotReportSkillProgress(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:246-248 — onProgress 仅对 content 含 tool_use/tool_result 块的消息上报,
        //   纯文本 assistant/user 消息不上报. [P1-18] 旧启发式对全部 assistant/user 消息上报
        //   (SkillToolImpl.java:1112-1123) 是偏差: 纯文本消息刷屏 skill_progress 事件. 修后仅
        //   toolContent()==true 的消息上报.
        SkillToolImpl tool = new SkillToolImpl(newForkRegistry(tempDir));

        SubagentExecutor executor = mock(SubagentExecutor.class);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<SubagentMessage> sink =
                    inv.getArgument(4);
            if (sink != null) {
                // 模拟 fork 子代理产出纯文本 assistant 消息 (toolContent=false, toSubagentMessage 判定
                //   msg.toolCalls() 为空 → toolContent=false)
                sink.accept(new SubagentMessage.AssistantMessage(
                        "普通文本回复", AgentUsage.EMPTY, false, "fork-agent-id"));
            }
            return SubagentExecutor.SubagentResult.completed(
                    "fork 完成", 0, 0L, "fork-agent-id");
        }).when(executor).executeForkedSkill(
                anyString(), any(Command.class), anyString(),
                any(ToolUseContext.class), any());
        tool.setSubagentExecutor(executor);

        List<Tool.ToolProgress> progress = new ArrayList<>();
        tool.execute(forkBlock("user-args"),
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                progress::add);

        assertThat(progress)
                .as("[P1-18] 纯文本消息 (无 tool_use/tool_result 块) 不得上报 skill_progress (CC SkillTool.ts:246-248)")
                .isEmpty();
    }

    @Test
    @DisplayName("fork 完成后 finally 清理 fork 子 agent 的 invokedSkills (CC SkillTool.ts:287)")
    void forkSkill_finally_clearsForkAgentInvokedSkills(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:287 executeForkedSkill finally { clearInvokedSkillsForAgent(agentId) } —
        //   fork 子 agent 结束后必须释放其 invokedSkills 全文（每条含完整 skill content，防累积
        //   泄漏 + stale skill 注入）。若 finally 未接线：fork 子 agent 的 skill content 留在
        //   invokedSkills Map，随主会话压缩被重注入，污染 LLM 输入。
        SkillToolImpl tool = new SkillToolImpl(newForkRegistry(tempDir));

        UUID forkAgentId = UUID.randomUUID();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // 预置：fork 子 agent 条目（写侧落点）+ 主会话条目（必须保留，验证 clearForAgent 精确性）
        AgentState sessionState = new AgentState("test-system-prompt");
        sessionState.addInvokedSkill("skill-x", "/s-x/SKILL.md", "fork-content", forkAgentId);
        sessionState.addInvokedSkill("skill-main", "/s-main/SKILL.md", "main-content", null);

        SubagentExecutor executor = mock(SubagentExecutor.class);
        doAnswer(inv -> SubagentExecutor.SubagentResult.completed(
                "fork 完成", 0, 0L, forkAgentId.toString()))
                .when(executor).executeForkedSkill(
                        anyString(), any(Command.class), anyString(),
                        any(ToolUseContext.class), any());
        tool.setSubagentExecutor(executor);
        // 注入 session AgentState 访问器（与 P1-6 写入侧共用的 session-state 访问模式）
        tool.setSessionStateResolver(sid -> sid.equals(sessionId) ? sessionState : null);

        // ToolUseContext.of(agentId, sessionId) — 第二参才是 doExecute 读取的 sessionId
        tool.execute(forkBlock("user-args"), ToolUseContext.of(UUID.randomUUID(), sessionId));

        assertThat(sessionState.getInvokedSkillsForAgent(forkAgentId))
                .as("fork 子 agent 结束后其 invokedSkills 必须被 finally 清理 (CC SkillTool.ts:287)")
                .isEmpty();
        assertThat(sessionState.getInvokedSkillsForAgent(null))
                .as("主会话 skill 不受 fork 清理影响")
                .hasSize(1)
                .allSatisfy((k, info) -> assertThat(info.content()).isEqualTo("main-content"));
    }

    @Test
    @DisplayName("fork 异常路径 result=null → finally 清理安全 no-op 不 NPE")
    void forkSkill_finally_exceptionPathIsSafeNoop(@TempDir Path tempDir) throws Exception {
        // WHY: executeForkedSkill 抛异常时 result 恒 null，fork finally 无法捕获 fork agentId →
        //   必须安全跳过（异常路径清理由 SubagentExecutor.runSubagentQueryLoop finally 兜底）。
        //   若 fork finally 抛 NPE，会掩盖原始 fork 异常（CC executeForkedSkill finally 同样不抛）。
        SkillToolImpl tool = new SkillToolImpl(newForkRegistry(tempDir));

        SubagentExecutor executor = mock(SubagentExecutor.class);
        doAnswer(inv -> { throw new RuntimeException("fork 执行失败"); })
                .when(executor).executeForkedSkill(
                        anyString(), any(Command.class), anyString(),
                        any(ToolUseContext.class), any());
        tool.setSubagentExecutor(executor);
        // 注入访问器，确保异常路径也走 cleanupForkAgentInvokedSkills(result=null) 分支
        AgentState sessionState = new AgentState("test-system-prompt");
        sessionState.addInvokedSkill("skill-main", "/s-main/SKILL.md", "main-content", null);
        tool.setSessionStateResolver(sid -> sessionState);

        var result = tool.execute(forkBlock("user-args"),
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
                .as("fork 异常被外层 catch 转 ToolResult.error（fail loud 非静默降级）")
                .isTrue();
        assertThat(result.data())
                .as("原始异常消息透传进 error data")
                .isEqualTo("Skill execution failed: fork 执行失败");
        assertThat(sessionState.getInvokedSkillsForAgent(null))
                .as("异常路径 finally 安全 no-op，主会话条目不受影响")
                .hasSize(1);
    }
}
