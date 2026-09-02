package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SkillToolImpl;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 安全闸测试（P2-14 依赖项）· 对齐 CC loadSkillsDir.ts:371-396
 * {@code if (loadedFrom !== 'mcp') { ... executeShellCommandsInPrompt ... }}。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>MCP 技能是远程不可信内容</b>（CC :371-373 注释）——永不执行其 markdown body 中的内联
 *       shell 命令（!`…` / ```! … ```），${CLAUDE_SKILL_DIR} 对 MCP 技能也无意义。
 *       Java 等价：{@code cmd.getSource() != CommandSource.MCP}（McpToolPool.java:464/:466
 *       skill:// 资源命令 + JsonRpcMcpClient.java:256 MCP prompt 命令设 source=MCP 已实证）。</li>
 *   <li><b>差分对照证明是安全闸生效</b>——同一 fail-closed PromptShellExecutor + 同一
 *       "!`cmd`" 内容，仅 source 不同：MCP 跳过（无异常、内容原样），USER 走 shell 注入
 *       （fail-closed 权限拒绝 → 抛 MalformedCommandException）。若安全闸被删除，MCP 用例
 *       必 fail。</li>
 * </ol>
 */
class McpShellGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SHELL_CONTENT = "before !`echo should_not_run` after";

    /** 返回单个 Command 的注册表（避免 BundledSkills/loader 加载外部技能）。 */
    private static final class SingleCommandRegistry extends SkillRegistry {
        private final List<Command> cmds;

        SingleCommandRegistry(List<Command> cmds) {
            super(".claude/skills");
            this.cmds = cmds;
        }

        @Override
        public List<Command> getAllCommands() {
            return cmds;
        }
    }

    private static ToolUseBlock skillBlock(String skillName) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", skillName);
        return new ToolUseBlock(UUID.randomUUID().toString(), "Skill", input);
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT, List.of());
    }

    private static Command skill(String name, CommandSource source) {
        Command cmd = new Command();
        cmd.setName(name);
        cmd.setContent(SHELL_CONTENT);
        cmd.setSource(source);
        // P2-21: 安全闸改判 loadedFrom（CC loadSkillsDir.ts:374 loadedFrom !== 'mcp'）——MCP 命令
        //   loadedFrom=MCP（McpToolPool skill:// 生产），USER 命令 loadedFrom=SKILLS（SkillsLoader :467）
        cmd.setLoadedFrom(source == CommandSource.MCP
            ? CommandLoadedFrom.MCP
            : CommandLoadedFrom.SKILLS);
        return cmd;
    }

    /** 从 SkillToolImpl 执行结果提取注入对话的 newMessage 正文（buildSkillSystemPrompt 产物）。 */
    private static String newMessageContent(AgentToolResult<?> result) {
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("MCP 技能执行 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        assertThat(tr.newMessages()).isNotEmpty();
        return tr.newMessages().get(0).content();
    }

    @Test
    @DisplayName("MCP 技能跳过 shell 注入: 无异常 + 内容含 !`cmd` 原样（CC loadSkillsDir.ts:374-396）")
    void mcpSkill_skipsShellInjection() {
        SkillToolImpl tool = new SkillToolImpl(new SingleCommandRegistry(List.of(skill("mcpskill", CommandSource.MCP))));
        // fail-closed PromptShellExecutor: 若安全闸被删, shell 注入会执行 → 权限失败 → 抛异常 → 测试 fail
        tool.setPromptShellExecutor(new PromptShellExecutor());

        AgentToolResult<?> result = tool.execute(skillBlock("mcpskill"), ctx());
        String content = newMessageContent(result);
        // 内容未被 shell 执行/替换（MCP 不可信内容永不注入）
        assertThat(content).contains(SHELL_CONTENT);
        assertThat(content).contains("!`echo should_not_run`");
    }

    @Test
    @DisplayName("非 MCP 技能走 shell 注入: fail-closed 权限拒绝 → 抛 MalformedCommandException（对照）")
    void nonMcpSkill_shellInjectionRuns() {
        SkillToolImpl tool = new SkillToolImpl(new SingleCommandRegistry(List.of(skill("userskill", CommandSource.USER))));
        tool.setPromptShellExecutor(new PromptShellExecutor());   // fail-closed: 权限预检非 allow → throw

        assertThatThrownBy(() -> tool.execute(skillBlock("userskill"), ctx()))
            .isInstanceOf(MalformedCommandException.class)
            .hasMessageContaining("permission check failed");
    }

    @Test
    @DisplayName("MCP 技能且内容无可注入 shell 语法 → 正常执行无异常")
    void mcpSkill_noShellSyntax_success() {
        Command cmd = skill("mcpclean", CommandSource.MCP);
        cmd.setContent("plain mcp content");
        SkillToolImpl tool = new SkillToolImpl(new SingleCommandRegistry(List.of(cmd)));
        tool.setPromptShellExecutor(new PromptShellExecutor());

        AgentToolResult<?> result = tool.execute(skillBlock("mcpclean"), ctx());
        assertThat(newMessageContent(result)).contains("plain mcp content");
    }

    /**
     * [P2-14] 差分记录-runner 测试 · 对齐 CC loadSkillsDir.ts:371-396。
     *
     * <p><b>WHY</b>（CLAUDE.md 规则 9）:现 MCP 用例是 fail-closed 反证（权限拒绝 → 抛异常 →
     * 删闸必 fail），依赖"权限恰好拒绝"这一状态。本用例把权限预检器注入为<b>恒 allow</b>、
     * 执行器注入为<b>记录 runner</b>（等价权限全放行 + 执行器就绪场景）——安全闸必须在
     * <b>权限状态无关</b>下生效：MCP 内容即使权限与执行器都就绪也<b>绝不触发 shell 执行</b>
     * （runner 调用计数为 0）。这是比现 fail-closed 反证更强的安全不变量证明。
     *
     * <p><b>差分对照</b>:同一 runner/checker 配置下 USER 技能 runner 必被调用（证明配置本身有效,
     * 差异只来自 {@code CommandSource.MCP} 闸）。若安全闸被删 → MCP 内容触发注入 → runner
     * 被调用 → {@code assertThat(runnerInvoked).isFalse()} fail。
     */
    @Test
    @DisplayName("差分: 权限全放行 + 执行器就绪下 MCP 技能 runner 绝不调用（安全闸短路径最强不变量）")
    void mcpSkill_neverInvokesRunner_evenWithAllowAllPermissions() {
        // 记录 runner + 恒 allow 检查器（权限全放行场景）· 与 PromptShellExecutorTest fake 同构
        AtomicBoolean mcpRunnerInvoked = new AtomicBoolean(false);
        PromptShellExecutor mcpExecutor = new PromptShellExecutor();
        mcpExecutor.setPermissionChecker((tn, cmd, c, at) -> true);
        mcpExecutor.setCommandRunner(cmd -> {
            mcpRunnerInvoked.set(true);
            return ToolResult.success("id", "MCP-OUTPUT");
        });
        SkillToolImpl mcpTool = new SkillToolImpl(new SingleCommandRegistry(List.of(skill("mcpskill", CommandSource.MCP))));
        mcpTool.setPromptShellExecutor(mcpExecutor);

        AgentToolResult<?> mcpResult = mcpTool.execute(skillBlock("mcpskill"), ctx());
        String mcpContent = newMessageContent(mcpResult);
        // MCP 内容原样（未被执行/替换）→ 安全闸短路, 即使权限与执行器都就绪
        assertThat(mcpContent).contains(SHELL_CONTENT);
        assertThat(mcpContent).contains("!`echo should_not_run`");
        assertThat(mcpRunnerInvoked).isFalse();

        // USER 差分对照: 同配置下 runner 必被调用（证明注入配置本身有效, 差异只在 source）
        AtomicBoolean userRunnerInvoked = new AtomicBoolean(false);
        PromptShellExecutor userExecutor = new PromptShellExecutor();
        userExecutor.setPermissionChecker((tn, cmd, c, at) -> true);
        userExecutor.setCommandRunner(cmd -> {
            userRunnerInvoked.set(true);
            return ToolResult.success("id", "USER-OUTPUT");
        });
        SkillToolImpl userTool = new SkillToolImpl(new SingleCommandRegistry(List.of(skill("userskill", CommandSource.USER))));
        userTool.setPromptShellExecutor(userExecutor);

        AgentToolResult<?> userResult = userTool.execute(skillBlock("userskill"), ctx());
        assertThat(newMessageContent(userResult)).doesNotContain("!`echo should_not_run`"); // 已被 shell 输出替换
        assertThat(userRunnerInvoked).isTrue();
    }
}
